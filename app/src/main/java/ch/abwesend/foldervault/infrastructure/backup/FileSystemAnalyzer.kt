package ch.abwesend.foldervault.infrastructure.backup

import android.content.Context
import android.net.Uri
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.dao.UploadedFileIndexDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.room.entity.BackupMessageEntity
import ch.abwesend.foldervault.infrastructure.storage.ScopedStorageHelper
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withContext

private data class LocalFileInfo(
    val relativePath: String,
    val uri: Uri,
    val size: Long,
    val mtime: Long?,
)

class FileSystemAnalyzer(
    private val context: Context,
    private val uploadedFileIndexDao: UploadedFileIndexDao,
    private val backupMessageDao: BackupMessageDao,
    private val cloudProvider: ICloudStorageProvider,
    private val dispatchers: IDispatchers,
) {
    private val log get() = logger

    /**
     * Walks the source tree and sends upload tasks to the appropriate tier channel.
     * Closes neither channel — the caller is responsible for closing both after this returns.
     */
    suspend fun analyze(
        config: BackupConfigEntity,
        normalChannel: SendChannel<UploadTask>,
        oversizedChannel: SendChannel<UploadTask>,
        fileSizeLimitBytes: Long,
    ) {
        // Phase 1: collect all file infos synchronously on IO (DocumentFile operations are blocking)
        val fileInfoList = withContext(dispatchers.io) {
            val results = mutableListOf<LocalFileInfo>()
            val treeUri = Uri.parse(config.sourceTreeUri)
            ScopedStorageHelper.walkTree(context, treeUri) { relativePath, file ->
                results.add(
                    LocalFileInfo(
                        relativePath = relativePath,
                        uri = file.uri,
                        size = file.length(),
                        mtime = file.lastModified().takeIf { it != 0L },
                    )
                )
            }
            results
        }

        // Phase 2: build upload tasks (change-detection + cloud-existence checks)
        // Collect into two lists first to avoid a producer/consumer deadlock: if we sent normal
        // and oversized tasks interleaved and the oversized channel (cap=8) filled up, the producer
        // would block before closing the normal channel, while the consumer was blocked waiting for
        // normal to close — deadlock. Collecting first lets us close normal before sending oversized.
        val normalTasks = mutableListOf<UploadTask>()
        val oversizedTasks = mutableListOf<UploadTask>()
        var unreliableMtimeWarned = false

        for (fileInfo in fileInfoList) {
            val indexed = uploadedFileIndexDao.getCurrentVersion(config.id, fileInfo.relativePath)
            val decision = ChangeDetector.decide(fileInfo.mtime, fileInfo.size, indexed)

            val task: UploadTask? = when (decision) {
                ChangeDetector.Decision.UNCHANGED -> null

                ChangeDetector.Decision.NEW -> buildTask(
                    relativePath = fileInfo.relativePath,
                    uri = fileInfo.uri,
                    localSize = fileInfo.size,
                    localMtime = fileInfo.mtime ?: 0L,
                    mode = UploadMode.NEW,
                    sizeLimitBytes = fileSizeLimitBytes,
                    previousCloudFileId = null,
                )

                ChangeDetector.Decision.CHANGED -> {
                    val mode = when (config.changedFilePolicy) {
                        ChangedFilePolicy.DUPLICATE_WITH_TIMESTAMP -> UploadMode.CHANGED_DUPLICATE
                        ChangedFilePolicy.OVERWRITE -> UploadMode.CHANGED_OVERWRITE
                        ChangedFilePolicy.IGNORE -> null
                    }
                    mode?.let {
                        buildTask(
                            relativePath = fileInfo.relativePath,
                            uri = fileInfo.uri,
                            localSize = fileInfo.size,
                            localMtime = fileInfo.mtime ?: 0L,
                            mode = it,
                            sizeLimitBytes = fileSizeLimitBytes,
                            previousCloudFileId = if (it == UploadMode.CHANGED_OVERWRITE) indexed?.cloudFileId else null,
                        )
                    }
                }

                ChangeDetector.Decision.CHECK_CLOUD -> {
                    if (!unreliableMtimeWarned) {
                        unreliableMtimeWarned = true
                        emitUnreliableMtimeWarning(config)
                    }
                    val cloudExists = checkCloudExists(config, fileInfo.relativePath, indexed?.remoteName)
                    if (cloudExists) null
                    else buildTask(
                        relativePath = fileInfo.relativePath,
                        uri = fileInfo.uri,
                        localSize = fileInfo.size,
                        localMtime = fileInfo.mtime ?: 0L,
                        mode = UploadMode.NEW,
                        sizeLimitBytes = fileSizeLimitBytes,
                        previousCloudFileId = null,
                    )
                }
            }

            when (task?.tier) {
                UploadTier.NORMAL -> normalTasks.add(task)
                UploadTier.OVERSIZED -> oversizedTasks.add(task)
                null -> Unit
            }
        }

        // Phase 3: send all normal tasks then close normal channel, then send oversized tasks.
        // Closing normal before sending to oversized prevents the deadlock described above.
        for (task in normalTasks) normalChannel.send(task)
        normalChannel.close()
        for (task in oversizedTasks) oversizedChannel.send(task)

        log.debug("Analyzer finished for config ${config.id}: ${fileInfoList.size} files scanned")
    }

    private suspend fun checkCloudExists(
        config: BackupConfigEntity,
        relativePath: String,
        remoteName: String?,
    ): Boolean {
        if (remoteName == null) return false
        val folderPath = relativePath.substringBeforeLast('/', "")
        val cloudFolderId = resolveCloudFolderForPath(config, folderPath) ?: return false
        val children = cloudProvider.listChildren(cloudFolderId)
        return children is SuccessResult && children.value.any { it.name == remoteName }
    }

    private suspend fun resolveCloudFolderForPath(config: BackupConfigEntity, folderPath: String): String? {
        if (folderPath.isEmpty()) return config.cloudRootFolderId
        val segments = folderPath.split('/')
        var currentId = config.cloudRootFolderId
        for (segment in segments) {
            val result = cloudProvider.getOrCreateChildFolder(currentId, segment)
            if (result !is SuccessResult) return null
            currentId = result.value.id
        }
        return currentId
    }

    private suspend fun emitUnreliableMtimeWarning(config: BackupConfigEntity) {
        backupMessageDao.insert(
            BackupMessageEntity(
                backupConfigId = config.id,
                runId = null,
                timestamp = System.currentTimeMillis(),
                severity = MessageSeverity.WARNING,
                type = MessageType.UNRELIABLE_TIMESTAMPS,
                messageText = null,
                formatArgs = emptyList(),
                relativePath = null,
                readAt = null,
            )
        )
    }

    private fun buildTask(
        relativePath: String,
        uri: Uri,
        localSize: Long,
        localMtime: Long,
        mode: UploadMode,
        sizeLimitBytes: Long,
        previousCloudFileId: String?,
    ): UploadTask {
        val tier = if (localSize <= sizeLimitBytes) UploadTier.NORMAL else UploadTier.OVERSIZED
        return UploadTask(
            relativePath = relativePath,
            documentUri = uri,
            localSize = localSize,
            localMtime = localMtime,
            mode = mode,
            tier = tier,
            previousCloudFileId = previousCloudFileId,
        )
    }
}
