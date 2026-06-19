package ch.abwesend.foldervault.infrastructure.backup

import android.content.Context
import android.net.Uri
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.dao.UploadedFileIndexDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.room.entity.BackupMessageEntity
import ch.abwesend.foldervault.infrastructure.room.entity.UploadedFileIndexEntity
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
        runId: String,
        folderCache: FolderPathCache,
    ): Int {
        val fileInfoList = collectFileInfos(config)
        // Collect into two lists first to avoid a producer/consumer deadlock: if we sent normal
        // and oversized tasks interleaved and the oversized channel (cap=8) filled up, the producer
        // would block before closing the normal channel, while the consumer was blocked waiting for
        // normal to close — deadlock. Collecting first lets us close normal before sending oversized.
        val (normalTasks, oversizedTasks) =
            buildUploadTaskLists(config, fileInfoList, fileSizeLimitBytes, runId, folderCache)
        for (task in normalTasks) normalChannel.send(task)
        normalChannel.close()
        for (task in oversizedTasks) oversizedChannel.send(task)
        log.debug("Analyzer finished for config ${config.id}: ${fileInfoList.size} files scanned")
        return fileInfoList.size
    }

    private suspend fun collectFileInfos(config: BackupConfigEntity): List<LocalFileInfo> =
        withContext(dispatchers.io) {
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

    private suspend fun buildUploadTaskLists(
        config: BackupConfigEntity,
        fileInfoList: List<LocalFileInfo>,
        fileSizeLimitBytes: Long,
        runId: String,
        folderCache: FolderPathCache,
    ): Pair<List<UploadTask>, List<UploadTask>> {
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

                ChangeDetector.Decision.CHANGED -> buildChangedTask(fileInfo, config, indexed, fileSizeLimitBytes)

                ChangeDetector.Decision.CHECK_CLOUD -> {
                    if (!unreliableMtimeWarned) {
                        unreliableMtimeWarned = true
                        emitUnreliableMtimeWarning(config, runId)
                    }
                    val cloudExists = checkCloudExists(config, fileInfo.relativePath, indexed?.remoteName, folderCache)
                    if (cloudExists) {
                        null
                    } else {
                        buildTask(
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
            }

            when (task?.tier) {
                UploadTier.NORMAL -> normalTasks.add(task)
                UploadTier.OVERSIZED -> oversizedTasks.add(task)
                null -> Unit
            }
        }

        return Pair(normalTasks, oversizedTasks)
    }

    private suspend fun checkCloudExists(
        config: BackupConfigEntity,
        relativePath: String,
        remoteName: String?,
        folderCache: FolderPathCache,
    ): Boolean {
        if (remoteName == null) return false
        val folderPath = relativePath.substringBeforeLast('/', "")
        val folderResult = folderCache.ensurePath(config.cloudSubFolderId, folderPath)
        if (folderResult is ErrorResult) return false
        val cloudFolderId = (folderResult as SuccessResult).value
        val children = cloudProvider.listChildren(cloudFolderId)
        return children is SuccessResult && children.value.any { it.name == remoteName }
    }

    private suspend fun emitUnreliableMtimeWarning(config: BackupConfigEntity, runId: String) {
        backupMessageDao.coalesceInsert(
            BackupMessageEntity(
                backupConfigId = config.id,
                runId = runId,
                timestamp = System.currentTimeMillis(),
                severity = MessageSeverity.WARNING,
                type = MessageType.UNRELIABLE_TIMESTAMPS,
                messageText = context.getString(MessageType.UNRELIABLE_TIMESTAMPS.labelResId),
                formatArgs = emptyList(),
                relativePath = null,
                readAt = null,
            )
        )
    }

    private fun buildChangedTask(
        fileInfo: LocalFileInfo,
        config: BackupConfigEntity,
        indexed: UploadedFileIndexEntity?,
        fileSizeLimitBytes: Long,
    ): UploadTask? {
        val mode = when (config.changedFilePolicy) {
            ChangedFilePolicy.DUPLICATE_WITH_TIMESTAMP -> UploadMode.CHANGED_DUPLICATE
            ChangedFilePolicy.OVERWRITE -> UploadMode.CHANGED_OVERWRITE
            ChangedFilePolicy.IGNORE -> null
        }
        return mode?.let {
            val prevCloudFileId = if (it == UploadMode.CHANGED_OVERWRITE) indexed?.cloudFileId else null
            buildTask(
                relativePath = fileInfo.relativePath,
                uri = fileInfo.uri,
                localSize = fileInfo.size,
                localMtime = fileInfo.mtime ?: 0L,
                mode = it,
                sizeLimitBytes = fileSizeLimitBytes,
                previousCloudFileId = prevCloudFileId,
            )
        }
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
