package ch.abwesend.foldervault.infrastructure.backup

import android.content.Context
import ch.abwesend.foldervault.domain.backup.CloudManifest
import ch.abwesend.foldervault.domain.backup.ManifestEntry
import ch.abwesend.foldervault.domain.cloud.CloudAuthResult
import ch.abwesend.foldervault.domain.cloud.ICloudAuthorizer
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.crypto.IEncryptionRepository
import ch.abwesend.foldervault.domain.crypto.IFvc1Cipher
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.dao.UploadedFileIndexDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID

sealed class RunResult {
    data class Success(val summary: RunSummary) : RunResult()
    data class AuthLost(val summary: RunSummary) : RunResult()
    data class FatalError(val error: Exception, val summary: RunSummary) : RunResult()
}

class BackupRunner(
    private val context: Context,
    private val authorizer: ICloudAuthorizer,
    private val cipher: IFvc1Cipher,
    private val encryptionRepository: IEncryptionRepository,
    private val backupConfigDao: BackupConfigDao,
    private val uploadedFileIndexDao: UploadedFileIndexDao,
    private val backupMessageDao: BackupMessageDao,
    private val settingsRepository: IAppSettingsRepository,
    private val dispatchers: IDispatchers,
) {
    private val log get() = logger

    suspend fun runBackup(configId: String): RunResult {
        val config = backupConfigDao.getByIdOnce(configId)
            ?: return RunResult.FatalError(
                IllegalStateException("Backup config $configId not found"),
                RunSummary(),
            )

        val summary = RunSummary()
        val runId = UUID.randomUUID().toString()

        // Clean up stale staging dirs from previous interrupted runs
        val stagingRoot = File(context.cacheDir, "encrypt-staging")
        val stagingManager = StagingDirManager(stagingRoot)
        stagingManager.cleanupOldDirs()
        val stagingDir = stagingManager.createRunDir(runId)

        // Authorize — silent only (no UI interaction from a worker)
        val authResult = authorizer.authorize()
        if (authResult !is CloudAuthResult.Authorized) {
            summary.authLost = true
            return RunResult.AuthLost(summary)
        }
        val cloudProvider: ICloudStorageProvider = authResult.data

        // Derive the per-run encryption key once (PBKDF2 is too slow to run per-file)
        val encryptionKey = deriveEncryptionKey(config, summary) ?: return RunResult.FatalError(
            Exception("Failed to decrypt backup password"),
            summary,
        )
        val (derivedKey, backupSalt) = encryptionKey

        val appSettings = settingsRepository.settings.first()
        val fileSizeLimitBytes = appSettings.defaultFileSizeLimitBytes

        val folderCache = FolderPathCache(cloudProvider)
        val uploader = BackupUploader(
            context = context,
            cipher = cipher,
            authorizer = authorizer,
            uploadedFileIndexDao = uploadedFileIndexDao,
            backupMessageDao = backupMessageDao,
            dispatchers = dispatchers,
        )
        uploader.cloudProvider = cloudProvider

        val analyzer = FileSystemAnalyzer(
            context = context,
            uploadedFileIndexDao = uploadedFileIndexDao,
            backupMessageDao = backupMessageDao,
            cloudProvider = cloudProvider,
            dispatchers = dispatchers,
        )

        try {
            runPipeline(config, analyzer, uploader, cloudProvider, fileSizeLimitBytes, runId, stagingDir, folderCache, derivedKey, backupSalt, summary)
            if (!summary.authLost && !summary.quotaExceeded) {
                RetentionManager(uploadedFileIndexDao, cloudProvider).applyRetention(config)
            }
            if (!summary.authLost) writeManifest(configId, cloudProvider)
        } catch (e: Exception) {
            log.error("BackupRunner encountered a fatal error for config $configId", e)
            commitRunStats(configId = config.id, status = BackupRunStatus.FAILED, summary = summary, completedNormally = false)
            return RunResult.FatalError(e, summary)
        }

        val status = when {
            summary.authLost -> BackupRunStatus.FAILED
            summary.quotaExceeded && summary.filesUploaded == 0 -> BackupRunStatus.FAILED
            summary.quotaExceeded || summary.filesFailed > 0 -> BackupRunStatus.COMPLETED_WITH_WARNINGS
            else -> BackupRunStatus.UP_TO_DATE
        }
        commitRunStats(
            configId = config.id,
            status = status,
            summary = summary,
            completedNormally = !summary.authLost && !summary.quotaExceeded,
        )

        return if (summary.authLost) RunResult.AuthLost(summary) else RunResult.Success(summary)
    }

    private suspend fun writeManifest(configId: String, cloudProvider: ICloudStorageProvider) {
        val config = backupConfigDao.getByIdOnce(configId) ?: return
        val currentVersions = uploadedFileIndexDao.getCurrentVersionList(configId)
        val entries = currentVersions.map { entry ->
            ManifestEntry(
                relativePath = entry.relativePath,
                mtime = entry.localLastModified,
                size = entry.localSize,
                cloudFileId = entry.cloudFileId,
                remoteName = entry.remoteName,
            )
        }
        val manifest = CloudManifest(
            generatedAt = Instant.now().toString(),
            files = entries,
        )
        val json = Json.encodeToString(manifest)
        val writeResult = cloudProvider.writeRootMetadata(
            config.cloudRootFolderId,
            CloudManifest.CLOUD_FILE_NAME,
            json.toByteArray(Charsets.UTF_8),
        )
        if (writeResult !is SuccessResult) {
            log.warning("Failed to write cloud manifest for config $configId")
        }
    }

    @Suppress("LongParameterList")
    private suspend fun runPipeline(
        config: BackupConfigEntity,
        analyzer: FileSystemAnalyzer,
        uploader: BackupUploader,
        cloudProvider: ICloudStorageProvider,
        fileSizeLimitBytes: Long,
        runId: String,
        stagingDir: File,
        folderCache: FolderPathCache,
        derivedKey: javax.crypto.SecretKey?,
        backupSalt: ByteArray?,
        summary: RunSummary,
    ) {
        val normalChannel = Channel<UploadTask>(capacity = 64)
        val oversizedChannel = Channel<UploadTask>(capacity = 8)
        coroutineScope {
            // Producer: walk the file system and enqueue tasks
            launch {
                try {
                    analyzer.analyze(config, normalChannel, oversizedChannel, fileSizeLimitBytes)
                } finally {
                    normalChannel.close()
                    oversizedChannel.close()
                }
            }
            // Consumer: drain normal-tier first, then oversized.
            // Both channels are always fully drained (even when stopped) to prevent the
            // producer from blocking on a full channel and hanging the coroutineScope.
            launch {
                uploader.processChannel(config, normalChannel, runId, stagingDir, folderCache, derivedKey, backupSalt, summary)
                uploader.processChannel(config, oversizedChannel, runId, stagingDir, folderCache, derivedKey, backupSalt, summary)
            }
        }
    }

    /**
     * Returns (derivedKey, salt) when encryption is enabled and the password can be decrypted;
     * (null, null) when encryption is disabled; or null if decryption failed (signals error).
     */
    private suspend fun deriveEncryptionKey(
        config: BackupConfigEntity,
        summary: RunSummary,
    ): Pair<javax.crypto.SecretKey?, ByteArray?>? {
        if (!config.encryptionEnabled || config.encryptedPasswordBlob == null) return Pair(null, null)
        val decryptResult = encryptionRepository.decryptPassword(config.encryptedPasswordBlob)
        if (decryptResult !is SuccessResult) {
            summary.filesFailed++ // flag as failed so stats are non-zero
            return null
        }
        val password = decryptResult.value
        val salt = config.encryptionParams?.let {
            java.util.Base64.getDecoder().decode(it.salt)
        } ?: cipher.generateBackupSalt()
        return Pair(cipher.deriveKey(password, salt), salt)
    }

    private suspend fun commitRunStats(
        configId: String,
        status: BackupRunStatus,
        summary: RunSummary,
        completedNormally: Boolean,
    ) {
        backupConfigDao.updateRunStats(
            id = configId,
            lastRunAt = System.currentTimeMillis(),
            lastRunStatus = status,
            filesUploaded = summary.filesUploaded,
            filesSkipped = summary.filesSkipped,
            filesFailed = summary.filesFailed,
            bytesUploaded = summary.bytesUploaded,
            lastRunCompletedNormally = completedNormally,
        )
    }
}
