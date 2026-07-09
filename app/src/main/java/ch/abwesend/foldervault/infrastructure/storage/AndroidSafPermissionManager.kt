package ch.abwesend.foldervault.infrastructure.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.storage.ISafPermissionManager

/**
 * [ISafPermissionManager] backed by [android.content.ContentResolver.releasePersistableUriPermission].
 *
 * The matching grant is taken with [Intent.FLAG_GRANT_READ_URI_PERMISSION] when a folder is picked
 * (see `AddEditBackupScreen`), so the same read flag is released here.
 */
class AndroidSafPermissionManager(private val context: Context) : ISafPermissionManager {
    override fun releasePersistedPermission(treeUri: String) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // No grant to hand back: the URI was never persisted, or was already released. Not an
            // error — the goal (this app no longer holding the grant) is already satisfied.
            logger.info("No persisted SAF permission to release for the given folder: ${e.message}")
        }
    }
}
