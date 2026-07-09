package ch.abwesend.foldervault.domain.storage

import ch.abwesend.foldervault.domain.backup.IBackupConfigRepository
import kotlinx.coroutines.flow.first

/**
 * Releases a folder's persisted SAF read grant once no backup config references it anymore.
 *
 * Called when a config is deleted or has its source folder replaced. The grant is only handed back
 * when no *other* config still points at the same tree URI, because several configs may
 * legitimately share one folder (or a common ancestor picked twice) and releasing an in-use grant
 * would break those backups. See [ISafPermissionManager] for why leaked grants matter.
 */
class ReleaseSafPermissionIfUnusedUseCase(
    private val configRepo: IBackupConfigRepository,
    private val safPermissionManager: ISafPermissionManager,
) {
    /**
     * @param treeUri the tree URI whose grant might be releasable (blank is a no-op).
     * @param excludingConfigId the config being deleted/edited — excluded from the "still in use"
     *   check so its own (about-to-be-removed or already-changed) reference does not keep the grant
     *   alive.
     */
    suspend operator fun invoke(treeUri: String, excludingConfigId: String?) {
        if (treeUri.isNotBlank()) {
            val stillInUse = configRepo.getAll().first().any { config ->
                config.id != excludingConfigId && config.sourceTreeUri == treeUri
            }
            if (!stillInUse) {
                safPermissionManager.releasePersistedPermission(treeUri)
            }
        }
    }
}
