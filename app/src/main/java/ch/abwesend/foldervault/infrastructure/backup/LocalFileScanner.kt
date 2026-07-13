package ch.abwesend.foldervault.infrastructure.backup

import android.content.Context
import android.net.Uri
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.storage.ScopedStorageHelper
import kotlinx.coroutines.withContext

/**
 * Metadata of one file found in the source tree during the SAF walk.
 * [mtime] is null when the provider reports no usable lastModified (0L).
 */
internal data class LocalFileInfo(
    val relativePath: String,
    val uri: Uri,
    val size: Long,
    val mtime: Long?,
)

/**
 * Seam over the SAF source-tree walk so that both the upload analyzer and the baseline
 * recorder share one traversal implementation, and consumers are testable with fakes.
 */
internal interface ILocalFileScanner {

    /**
     * Walks the config's source tree and returns the metadata of every file found. Sets
     * [RunSummary.sourceFolderInaccessible] when the tree cannot be accessed at all
     * (deleted, or its persisted SAF permission was revoked).
     */
    suspend fun scan(config: BackupConfigEntity, summary: RunSummary): List<LocalFileInfo>
}

internal class LocalFileScanner(
    private val context: Context,
    private val dispatchers: IDispatchers,
) : ILocalFileScanner {
    private val log get() = logger

    override suspend fun scan(config: BackupConfigEntity, summary: RunSummary): List<LocalFileInfo> =
        withContext(dispatchers.io) {
            val results = mutableListOf<LocalFileInfo>()
            val treeUri = Uri.parse(config.sourceTreeUri)
            val accessible = ScopedStorageHelper.walkTree(context, treeUri) { relativePath, file ->
                results.add(
                    LocalFileInfo(
                        relativePath = relativePath,
                        uri = file.uri,
                        size = file.length(),
                        mtime = file.lastModified().takeIf { it != 0L },
                    )
                )
            }
            if (!accessible) {
                log.warning("Source folder for config ${config.id} is inaccessible (deleted or permission revoked)")
                summary.sourceFolderInaccessible = true
            }
            results
        }
}
