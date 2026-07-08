package ch.abwesend.foldervault.infrastructure.backup

import android.content.Context
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.backup.CloudManifest
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.backup.ManifestEntry
import ch.abwesend.foldervault.domain.cloud.CloudAuthException
import ch.abwesend.foldervault.domain.cloud.CloudAuthResult
import ch.abwesend.foldervault.domain.cloud.ICloudAuthorizer
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.crypto.IEncryptionRepository
import ch.abwesend.foldervault.domain.crypto.IFvc1Cipher
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.result.BinaryResult
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.domain.result.ifError
import ch.abwesend.foldervault.domain.result.runCatchingAsResult
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupRunDao
import ch.abwesend.foldervault.infrastructure.room.dao.UploadedFileIndexDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.room.entity.BackupMessageEntity
import ch.abwesend.foldervault.infrastructure.room.entity.BackupRunEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

sealed class RunResult {
    abstract val runId: String
    abstract val summary: RunSummary
    data class Success(override val summary: RunSummary, override val runId: String) : RunResult()
    data class AuthLost(override val summary: RunSummary, override val runId: String) : RunResult()
    data class FatalError(
        val error: Exception,
        override val summary: RunSummary,
        override val runId: String,
    ) : RunResult()
}

class BackupRunner(
    private val context: Context,
    private val authorizer: ICloudAuthorizer,
    private val cipher: IFvc1Cipher,
    private val encryptionRepository: IEncryptionRepository,
    private val backupConfigDao: BackupConfigDao,
    private val uploadedFileIndexDao: UploadedFileIndexDao,
    private val backupMessageDao: BackupMessageDao,
    private val backupRunDao: BackupRunDao,
    private val settingsRepository: IAppSettingsRepository,
    private val dispatchers: IDispatchers,
    private val scheduler: IBackupScheduler,
) {
    private val log get() = logger

    /**
     * Per-config locks that serialize runs of the same backup. Since one-time and periodic work
     * now live under distinct WorkManager unique-work names, WorkManager no longer prevents a
     * manual "back up now" from executing concurrently with a periodic run of the same config.
     * Both workers run in this app process against the same [BackupRunner] singleton, so a
     * process-wide [Mutex] per configId restores the original "never run the same backup twice at
     * once" guarantee. A second run waits for the first to finish rather than racing it — matching
     * the pipeline's serial-upload design. Collisions are rare (a manual tap during a scheduled
     * run), so the brief wait is acceptable.
     */
    private val perConfigLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Runs a backup for [configId], serialized per config: only one run per config executes at a
     * time (see [perConfigLocks]). The lock is released on normal completion, cancellation, and
     * error alike ([withLock] is inline, so its `finally` unlocks even when the body throws).
     */
    suspend fun runBackup(configId: String, deadline: Instant? = null): RunResult {
        val lock = perConfigLocks.computeIfAbsent(configId) { Mutex() }
        return lock.withLock { runBackupExclusive(configId, deadline) }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod")
    private suspend fun runBackupExclusive(configId: String, deadline: Instant?): RunResult {
        val runId = UUID.randomUUID().toString()
        val config = backupConfigDao.getByIdOnce(configId)
            ?: return RunResult.FatalError(
                IllegalStateException("Backup config $configId not found"),
                RunSummary(),
                runId,
            )

        val summary = RunSummary()
        val startedAt = System.currentTimeMillis()
        backupRunDao.insert(
            BackupRunEntity(
                backupConfigId = config.id,
                runId = runId,
                startedAt = startedAt,
                completedAt = null,
                status = BackupRunStatus.RUNNING,
                filesUploaded = 0,
                filesSkipped = 0,
                filesFailed = 0,
                bytesUploaded = 0L,
            )
        )

        // Clean up stale staging dirs from previous interrupted runs.
        // Cleanup failures must not abort the run — they only mean cache disk fills up slowly.
        val stagingRoot = File(context.cacheDir, "encrypt-staging")
        val stagingManager = StagingDirManager(stagingRoot)
        runCatchingAsResult { stagingManager.cleanupOldDirs() }
            .ifError { log.warning("Failed to clean up old staging dirs for run $runId", it) }
        val stagingDir = stagingManager.createRunDir(runId)

        // Authorize — silent only (no UI interaction from a worker), targeting the account this
        // config was created with so multi-account setups don't cross-upload.
        val authResult = authorizer.authorize(config.cloudAccountIdentifier)
        if (authResult !is CloudAuthResult.Authorized) {
            summary.authLost = true
            commitRunStats(config.id, runId, BackupRunStatus.FAILED, summary, completedNormally = false)
            return RunResult.AuthLost(summary, runId)
        }
        val cloudProvider: ICloudStorageProvider = authResult.data

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
            cloudProvider = cloudProvider,
        )

        val analyzer = FileSystemAnalyzer(
            context = context,
            uploadedFileIndexDao = uploadedFileIndexDao,
            backupMessageDao = backupMessageDao,
            cloudProvider = cloudProvider,
            dispatchers = dispatchers,
        )

        try {
            // Derive the per-run encryption key once (PBKDF2 is too slow to run per-file)
            val encryptionKey = deriveEncryptionKey(config, summary)
            if (encryptionKey == null) {
                backupMessageDao.coalesceInsert(
                    BackupMessageEntity(
                        backupConfigId = config.id,
                        runId = runId,
                        timestamp = System.currentTimeMillis(),
                        severity = MessageSeverity.ERROR,
                        type = MessageType.ENCRYPTION_FAILED,
                        messageText = context.getString(R.string.msg_encryption_failed),
                        formatArgs = emptyList(),
                        relativePath = null,
                        readAt = null,
                    )
                )
                commitRunStats(config.id, runId, BackupRunStatus.FAILED, summary, false)
                return RunResult.FatalError(IllegalStateException("Failed to decrypt backup password"), summary, runId)
            }
            val (derivedKey, backupSalt) = encryptionKey

            runPipeline(
                config, analyzer, uploader, fileSizeLimitBytes,
                runId, stagingDir, folderCache, derivedKey, backupSalt, summary, deadline,
            )
            val retention = RetentionManager(uploadedFileIndexDao, cloudProvider)
            if (!summary.authLost && !summary.quotaExceeded && !summary.hitTimeBudget) {
                retention.applyRetention(config)
            }
            // Reap orphaned cloud files left behind by transient deleteFile failures during
            // CHANGED_OVERWRITE uploads. Independent of retention policy — runs whenever the
            // cloud connection is still usable.
            if (!summary.authLost) retention.reapPendingDeletions(config.id)
            if (!summary.authLost) writeManifest(configId, cloudProvider)
        } catch (e: CancellationException) {
            // Worker cancellation (timeout, constraints lost, user action) is expected — honor
            // structured concurrency and propagate, so it isn't recorded as a Crashlytics fatal.
            // The DB writes must run in a NonCancellable scope: this coroutine is already
            // cancelled, so plain suspending DAO calls would themselves throw CancellationException
            // and the RUNNING row would stay stuck forever.
            log.warning("BackupRunner cancelled for config $configId")
            withContext(NonCancellable) {
                commitRunStats(
                    configId = config.id,
                    runId = runId,
                    status = BackupRunStatus.CANCELLED,
                    summary = summary,
                    completedNormally = false,
                )
                ChargingFallbackTrigger.maybeSchedule(config, backupRunDao, scheduler, backupMessageDao, runId)
            }
            throw e
        } catch (e: Exception) {
            log.error("BackupRunner encountered a fatal error for config $configId", e)
            commitRunStats(
                configId = config.id,
                runId = runId,
                status = BackupRunStatus.FAILED,
                summary = summary,
                completedNormally = false,
            )
            return RunResult.FatalError(e, summary, runId)
        } finally {
            MessageRetentionManager(backupMessageDao).prune(config.id)
        }

        val status = when {
            summary.authLost -> BackupRunStatus.FAILED
            summary.hitTimeBudget -> BackupRunStatus.INITIAL_SYNC_IN_PROGRESS
            summary.quotaExceeded && summary.filesUploaded == 0 -> BackupRunStatus.FAILED
            summary.quotaExceeded || summary.filesFailed > 0 -> BackupRunStatus.COMPLETED_WITH_WARNINGS
            else -> BackupRunStatus.UP_TO_DATE
        }
        val cleanRun = !summary.authLost && !summary.quotaExceeded && !summary.hitTimeBudget
        if (cleanRun && config.lastRunStatus == BackupRunStatus.INITIAL_SYNC_IN_PROGRESS) {
            backupMessageDao.coalesceInsert(
                BackupMessageEntity(
                    backupConfigId = config.id,
                    runId = runId,
                    timestamp = System.currentTimeMillis(),
                    severity = MessageSeverity.INFO,
                    type = MessageType.INITIAL_SYNC_COMPLETE,
                    messageText = context.getString(MessageType.INITIAL_SYNC_COMPLETE.labelResId),
                    formatArgs = emptyList(),
                    relativePath = null,
                    readAt = null,
                )
            )
        }
        commitRunStats(
            configId = config.id,
            runId = runId,
            status = status,
            summary = summary,
            completedNormally = !summary.authLost && !summary.quotaExceeded && !summary.hitTimeBudget,
        )

        return if (summary.authLost) RunResult.AuthLost(summary, runId) else RunResult.Success(summary, runId)
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
        val bytes = json.toByteArray(Charsets.UTF_8)
        val writeResult = writeRootMetadataWithReAuth(
            cloudProvider,
            config.cloudSubFolderId,
            CloudManifest.CLOUD_FILE_NAME,
            bytes,
            config.cloudAccountIdentifier,
        )
        if (writeResult !is SuccessResult) {
            log.warning("Failed to write cloud manifest for config $configId")
        }
    }

    /**
     * Writes root metadata, silently re-authorizing once on [CloudAuthException].
     *
     * Long-running pipelines can outlive the access token. The provider's retry policy only
     * covers transient/rate-limit errors, not auth — so on auth failure we re-authorize via
     * [authorizer] and re-issue the write through the refreshed provider, matching the
     * pattern in `BackupUploader.handleAuthError`.
     */
    private suspend fun writeRootMetadataWithReAuth(
        cloudProvider: ICloudStorageProvider,
        rootFolderId: String,
        name: String,
        bytes: ByteArray,
        accountIdentifier: String,
    ): BinaryResult<Unit, Exception> {
        val first = cloudProvider.writeRootMetadata(rootFolderId, name, bytes)
        val authExpired = first is ErrorResult && first.error is CloudAuthException
        return if (authExpired) {
            val reAuth = authorizer.authorize(accountIdentifier)
            if (reAuth is CloudAuthResult.Authorized) {
                reAuth.data.writeRootMetadata(rootFolderId, name, bytes)
            } else {
                first
            }
        } else {
            first
        }
    }

    @Suppress("LongParameterList")
    private suspend fun runPipeline(
        config: BackupConfigEntity,
        analyzer: FileSystemAnalyzer,
        uploader: BackupUploader,
        fileSizeLimitBytes: Long,
        runId: String,
        stagingDir: File,
        folderCache: FolderPathCache,
        derivedKey: SecretKey?,
        backupSalt: ByteArray?,
        summary: RunSummary,
        deadline: Instant? = null,
    ) {
        val normalChannel = Channel<UploadTask>(capacity = 64)
        val oversizedChannel = Channel<UploadTask>(capacity = 8)
        var filesDiscovered = 0
        coroutineScope {
            // Producer: walk the file system and enqueue tasks
            launch {
                try {
                    filesDiscovered = analyzer.analyze(
                        config, normalChannel, oversizedChannel, fileSizeLimitBytes, runId, folderCache,
                    )
                } finally {
                    normalChannel.close()
                    oversizedChannel.close()
                }
            }
            // Consumer: drain normal-tier first, then oversized.
            // Both channels are always fully drained (even when stopped) to prevent the
            // producer from blocking on a full channel and hanging the coroutineScope.
            launch {
                uploader.processChannel(
                    config, normalChannel, runId, stagingDir, folderCache,
                    derivedKey, backupSalt, summary, deadline,
                )
                uploader.processChannel(
                    config, oversizedChannel, runId, stagingDir, folderCache,
                    derivedKey, backupSalt, summary, deadline,
                )
            }
        }
        summary.totalFilesDiscovered = filesDiscovered
    }

    /**
     * Returns (derivedKey, salt) when encryption is enabled and the password can be decrypted;
     * (null, null) when encryption is disabled; or null if decryption failed (signals error).
     */
    private suspend fun deriveEncryptionKey(
        config: BackupConfigEntity,
        summary: RunSummary,
    ): Pair<SecretKey?, ByteArray?>? {
        if (!config.encryptionEnabled || config.encryptedPasswordBlob == null) return Pair(null, null)
        val decryptResult = encryptionRepository.decryptPassword(config.encryptedPasswordBlob)
        if (decryptResult !is SuccessResult) {
            summary.filesFailed++ // flag as failed so stats are non-zero
            return null
        }
        val password = decryptResult.value
        val salt = config.encryptionParams?.let {
            Base64.getDecoder().decode(it.salt)
        } ?: cipher.generateBackupSalt()
        return Pair(cipher.deriveKey(password, salt), salt)
    }

    private suspend fun commitRunStats(
        configId: String,
        runId: String,
        status: BackupRunStatus,
        summary: RunSummary,
        completedNormally: Boolean,
    ) {
        val now = System.currentTimeMillis()
        backupConfigDao.updateRunStats(
            id = configId,
            lastRunAt = now,
            lastRunStatus = status,
            filesUploaded = summary.filesUploaded,
            filesSkipped = summary.filesSkipped,
            filesFailed = summary.filesFailed,
            bytesUploaded = summary.bytesUploaded,
            lastRunCompletedNormally = completedNormally,
        )
        backupRunDao.markComplete(
            runId = runId,
            completedAt = now,
            status = status,
            filesUploaded = summary.filesUploaded,
            filesSkipped = summary.filesSkipped,
            filesFailed = summary.filesFailed,
            bytesUploaded = summary.bytesUploaded,
        )
        backupRunDao.pruneOld(configId)
        when {
            summary.hitTimeBudget -> {
                val prev = backupConfigDao.getByIdOnce(configId)
                val prevTotal = prev?.filesUploadedTotal ?: 0
                backupConfigDao.updateCrossRunProgress(
                    id = configId,
                    totalFilesDiscovered = summary.totalFilesDiscovered,
                    filesUploadedTotal = prevTotal + summary.filesUploaded,
                )
            }
            completedNormally -> backupConfigDao.updateCrossRunProgress(
                id = configId,
                totalFilesDiscovered = 0,
                filesUploadedTotal = 0,
            )
            // Failed/quota run without hitTimeBudget — leave cross-run counters unchanged.
        }
    }
}
