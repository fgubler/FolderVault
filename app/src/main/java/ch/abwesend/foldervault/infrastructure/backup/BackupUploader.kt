package ch.abwesend.foldervault.infrastructure.backup

import android.content.Context
import ch.abwesend.foldervault.domain.cloud.CloudAuthException
import ch.abwesend.foldervault.domain.cloud.CloudAuthResult
import ch.abwesend.foldervault.domain.cloud.CloudFile
import ch.abwesend.foldervault.domain.cloud.CloudQuotaExceededException
import ch.abwesend.foldervault.domain.cloud.ICloudAuthorizer
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.cloud.UploadContent
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.crypto.IFvc1Cipher
import ch.abwesend.foldervault.domain.logging.FileNameRedactor
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.result.BinaryResult
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.domain.result.rethrowCancellation
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.dao.UploadedFileIndexDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.room.entity.BackupMessageEntity
import ch.abwesend.foldervault.infrastructure.room.entity.UploadedFileIndexEntity
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.crypto.SecretKey

class BackupUploader(
    private val context: Context,
    private val cipher: IFvc1Cipher,
    private val authorizer: ICloudAuthorizer,
    private val uploadedFileIndexDao: UploadedFileIndexDao,
    private val backupMessageDao: BackupMessageDao,
    private val dispatchers: IDispatchers,
    cloudProvider: ICloudStorageProvider,
) {
    private val log get() = logger
    private var cloudProvider: ICloudStorageProvider = cloudProvider

    @Suppress("LongParameterList")
    suspend fun processChannel(
        config: BackupConfigEntity,
        channel: ReceiveChannel<UploadTask>,
        runId: String,
        stagingDir: File,
        folderCache: FolderPathCache,
        derivedKey: SecretKey?,
        backupSalt: ByteArray?,
        summary: RunSummary,
        control: BackupRunControl? = null,
    ) = withContext(dispatchers.io) {
        // Always drain the channel even when stopped — a break without draining would leave the
        // producer blocked on send(), hanging the coroutineScope indefinitely.
        for (task in channel) {
            val shouldSkip = summary.authLost || summary.quotaExceeded || summary.hitTimeBudget
            if (shouldSkip && task.tier == UploadTier.OVERSIZED) {
                summary.oversizedDeferred++
            } else if (!shouldSkip) {
                uploadOne(
                    config = config,
                    task = task,
                    runId = runId,
                    stagingDir = stagingDir,
                    folderCache = folderCache,
                    derivedKey = derivedKey,
                    backupSalt = backupSalt,
                    summary = summary,
                )
                control?.reportFileUploaded(summary.filesUploaded)
                if (control?.shouldStop() == true) {
                    summary.hitTimeBudget = true
                }
            }
        }
        if (summary.hitTimeBudget && summary.oversizedDeferred > 0) {
            emitMessage(config, runId, MessageSeverity.INFO, MessageType.FILE_TOO_LARGE)
        }
    }

    private suspend fun uploadOne(
        config: BackupConfigEntity,
        task: UploadTask,
        runId: String,
        stagingDir: File,
        folderCache: FolderPathCache,
        derivedKey: SecretKey?,
        backupSalt: ByteArray?,
        summary: RunSummary,
    ) {
        val folderPath = task.relativePath.substringBeforeLast('/', "")
        val fileName = task.relativePath.substringAfterLast('/')
        val remoteName = RemoteNameBuilder.buildName(fileName, task.mode, config.encryptionEnabled)

        val folderResult = folderCache.ensurePath(config.cloudSubFolderId, folderPath)
        if (folderResult is ErrorResult) {
            log.warning(
                "Could not ensure remote folder for ${FileNameRedactor.redactPath(task.relativePath)}: " +
                    "${folderResult.error}"
            )
            summary.filesFailed++
            return
        }
        val parentFolderId = (folderResult as SuccessResult).value

        // Only encrypted backups need a staged temp copy: encryption rewrites the bytes and the
        // ciphertext must be re-readable on each upload retry. Unencrypted backups stream the source
        // file straight to the upload — no plaintext copy is ever written to cache (see SEC-1).
        val staging = if (config.encryptionEnabled && derivedKey != null && backupSalt != null) {
            // hash + nanoTime avoids name collisions in the staging dir
            val file = File(stagingDir, "${task.relativePath.hashCode().toUInt()}_${System.nanoTime()}.tmp")
            EncryptedStaging(derivedKey, backupSalt, file)
        } else {
            null
        }
        try {
            val uploadContent = if (staging != null) {
                val prepared = encryptToStaging(task, staging)
                if (!prepared) {
                    summary.filesFailed++
                    emitMessage(config, runId, MessageSeverity.WARNING, MessageType.UPLOAD_FAILED)
                    return
                }
                UploadContent(
                    inputStreamProvider = { FileInputStream(staging.file) },
                    length = staging.file.length(),
                )
            } else {
                // The provider is re-invoked per upload retry, so re-opening the source is safe.
                // A null stream (source vanished) makes the provider throw → the upload fails and is
                // counted like any other upload failure, matching the staged path's behaviour.
                UploadContent(
                    inputStreamProvider = {
                        context.contentResolver.openInputStream(task.documentUri)
                            ?: error("Could not open source file for upload")
                    },
                    length = task.localSize,
                )
            }

            // Exclude the previous version (CHANGED_OVERWRITE) so the upload's retry-time
            // idempotency probe in GoogleDriveRepository doesn't mistake it for the just-uploaded
            // duplicate it's hunting for.
            val excludeIds = setOfNotNull(task.previousCloudFileId)
            val uploadResult = tryUpload(
                config = config,
                parentFolderId = parentFolderId,
                remoteName = remoteName,
                mimeType = "application/octet-stream",
                content = uploadContent,
                excludeIds = excludeIds,
                runId = runId,
                summary = summary,
            ) ?: return // auth-lost or quota — summary flags set; caller will skip subsequent tasks

            if (uploadResult is ErrorResult) {
                log.warning(
                    "Upload failed for ${FileNameRedactor.redactPath(task.relativePath)}: ${uploadResult.error}"
                )
                summary.filesFailed++
                emitMessage(config, runId, MessageSeverity.WARNING, MessageType.UPLOAD_FAILED)
                return
            }
            val cloudFile = (uploadResult as SuccessResult).value
            commitSuccess(config, task, cloudFile, remoteName, summary)
        } finally {
            staging?.file?.delete()
        }
    }

    private suspend fun commitSuccess(
        config: BackupConfigEntity,
        task: UploadTask,
        cloudFile: CloudFile,
        remoteName: String,
        summary: RunSummary,
    ) {
        val pendingDeletion = if (task.mode == UploadMode.CHANGED_OVERWRITE) task.previousCloudFileId else null
        // Persist the pending deletion *on the new row* before attempting the cloud delete, so
        // the cleanup obligation survives a transient delete failure. The end-of-run reaper
        // retries any row still marked pending.
        val rowId = uploadedFileIndexDao.upsertCurrentVersion(
            UploadedFileIndexEntity(
                backupConfigId = config.id,
                relativePath = task.relativePath,
                localLastModified = task.localMtime,
                localSize = task.localSize,
                cloudFileId = cloudFile.id,
                remoteName = remoteName,
                uploadedAt = System.currentTimeMillis(),
                isCurrentVersion = true,
                pendingDeletionCloudFileId = pendingDeletion,
            )
        )
        summary.filesUploaded++
        summary.bytesUploaded += task.localSize
        if (task.tier == UploadTier.OVERSIZED) summary.oversizedUploaded++
        if (pendingDeletion != null) {
            val deleteResult = cloudProvider.deleteFile(pendingDeletion)
            if (deleteResult is SuccessResult) {
                uploadedFileIndexDao.clearPendingDeletion(rowId)
            } else {
                log.warning(
                    "Failed to delete old cloud file $pendingDeletion — marked for end-of-run reap: " +
                        "${(deleteResult as ErrorResult).error}"
                )
            }
        }
    }

    /**
     * Encrypts the source file's content into [staging]'s temp file, ready for upload.
     * Returns true on success, false if the source file could not be opened.
     */
    private fun encryptToStaging(task: UploadTask, staging: EncryptedStaging): Boolean {
        val inputStream = context.contentResolver.openInputStream(task.documentUri) ?: return false
        return try {
            inputStream.use { ins ->
                FileOutputStream(staging.file).use { out ->
                    cipher.encryptFile(staging.derivedKey, staging.backupSalt, ins, out)
                }
            }
            true
        } catch (e: Exception) {
            e.rethrowCancellation()
            log.warning("Failed to encrypt local file for ${FileNameRedactor.redactPath(task.relativePath)}", e)
            false
        }
    }

    /**
     * Attempts an upload with silent re-auth on first [CloudAuthException].
     * Returns null to signal "stop the channel" (auth permanently lost or quota exceeded).
     */
    @Suppress("LongParameterList")
    private suspend fun tryUpload(
        config: BackupConfigEntity,
        parentFolderId: String,
        remoteName: String,
        mimeType: String,
        content: UploadContent,
        excludeIds: Set<String>,
        runId: String,
        summary: RunSummary,
    ): BinaryResult<CloudFile, Exception>? {
        val result = cloudProvider.uploadFile(parentFolderId, remoteName, mimeType, content, excludeIds)
        if (result is SuccessResult) {
            summary.consecutiveQuotaCount = 0
            return result
        }

        val error = (result as ErrorResult).error
        return when {
            error is CloudAuthException -> handleAuthError(
                config,
                parentFolderId,
                remoteName,
                mimeType,
                content,
                excludeIds,
                runId,
                summary,
            )
            error is CloudQuotaExceededException -> {
                summary.consecutiveQuotaCount++
                if (summary.consecutiveQuotaCount >= 2) {
                    summary.quotaExceeded = true
                    null
                } else {
                    emitMessage(config, runId, MessageSeverity.WARNING, MessageType.QUOTA_EXCEEDED)
                    result
                }
            }
            else -> result
        }
    }

    /** Re-auth once and retry the upload. Returns null if auth cannot be recovered. */
    @Suppress("LongParameterList")
    private suspend fun handleAuthError(
        config: BackupConfigEntity,
        parentFolderId: String,
        remoteName: String,
        mimeType: String,
        content: UploadContent,
        excludeIds: Set<String>,
        runId: String,
        summary: RunSummary,
    ): BinaryResult<CloudFile, Exception>? {
        val reAuthResult = authorizer.authorize(config.cloudAccountIdentifier)
        if (reAuthResult !is CloudAuthResult.Authorized) {
            summary.authLost = true
            emitMessage(config, runId, MessageSeverity.ERROR, MessageType.AUTH_LOST)
            return null
        }
        cloudProvider = reAuthResult.data
        val retryResult = reAuthResult.data.uploadFile(parentFolderId, remoteName, mimeType, content, excludeIds)
        if (retryResult is ErrorResult && retryResult.error is CloudAuthException) {
            summary.authLost = true
            emitMessage(config, runId, MessageSeverity.ERROR, MessageType.AUTH_LOST)
            return null
        }
        return retryResult
    }

    private suspend fun emitMessage(
        config: BackupConfigEntity,
        runId: String?,
        severity: MessageSeverity,
        type: MessageType,
    ) {
        backupMessageDao.coalesceInsert(
            BackupMessageEntity(
                backupConfigId = config.id,
                runId = runId,
                timestamp = System.currentTimeMillis(),
                severity = severity,
                type = type,
                messageText = resolveMessageText(type),
                formatArgs = emptyList(),
                relativePath = null,
                readAt = null,
            )
        )
    }

    private fun resolveMessageText(type: MessageType): String = context.getString(type.labelResId)
}

/** Material for encrypting one file into a cache staging copy before upload (encrypted backups only). */
private class EncryptedStaging(val derivedKey: SecretKey, val backupSalt: ByteArray, val file: File)
