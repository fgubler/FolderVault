package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.domain.result.ErrorResult
import ch.abwesend.foldervault.domain.result.SuccessResult
import ch.abwesend.foldervault.infrastructure.room.dao.UploadedFileIndexDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L

class RetentionManager(
    private val uploadedFileIndexDao: UploadedFileIndexDao,
    private val cloudProvider: ICloudStorageProvider,
) {
    private val log get() = logger

    suspend fun applyRetention(config: BackupConfigEntity) {
        when (val policy = config.retentionPolicy) {
            is RetentionPolicy.KeepAll -> return
            is RetentionPolicy.KeepLastN -> applyKeepLastN(config.id, policy.count)
            is RetentionPolicy.KeepNewerThan -> applyKeepNewerThan(config.id, policy.days)
        }
    }

    private suspend fun applyKeepLastN(configId: String, keepCount: Int) {
        val effective = keepCount.coerceAtLeast(1)
        log.debug("Applying KeepLastN($effective) retention for config $configId")
        val paths = uploadedFileIndexDao.getDistinctPaths(configId)
        for (path in paths) {
            // Get all versions for this path, newest first
            val history = uploadedFileIndexDao.getVersionHistory(configId, path)
            // Keep the newest effective count; delete everything beyond that
            val toDelete = history.drop(effective)
            for (entry in toDelete) {
                deleteCloudAndIndex(entry.cloudFileId, entry.id)
            }
        }
    }

    private suspend fun applyKeepNewerThan(configId: String, days: Int) {
        log.debug("Applying KeepNewerThan($days days) retention for config $configId")
        val cutoff = System.currentTimeMillis() - days.toLong() * MILLIS_PER_DAY
        val oldVersions = uploadedFileIndexDao.getOldVersionsOlderThan(configId, cutoff)
        for (entry in oldVersions) {
            deleteCloudAndIndex(entry.cloudFileId, entry.id)
        }
    }

    /**
     * Retries the cloud-delete for any row that committed a CHANGED_OVERWRITE upload but
     * failed to delete the predecessor object. Idempotent: Drive returning 404 for an
     * already-gone file is treated as a success and the marker is cleared.
     */
    suspend fun reapPendingDeletions(configId: String) {
        val pending = uploadedFileIndexDao.getPendingDeletions(configId)
        if (pending.isEmpty()) return
        log.debug("Reaping ${pending.size} pending cloud deletion(s) for config $configId")
        for (entry in pending) {
            val cloudFileId = entry.pendingDeletionCloudFileId ?: continue
            val deleteResult = cloudProvider.deleteFile(cloudFileId)
            if (deleteResult is SuccessResult) {
                uploadedFileIndexDao.clearPendingDeletion(entry.id)
            } else {
                log.warning(
                    "Reap: failed to delete $cloudFileId (row ${entry.id}) — will retry next run: " +
                        "${(deleteResult as ErrorResult).error}"
                )
            }
        }
    }

    private suspend fun deleteCloudAndIndex(cloudFileId: String, indexId: Long) {
        val deleteResult = cloudProvider.deleteFile(cloudFileId)
        if (deleteResult is ErrorResult) {
            log.warning(
                "Retention: failed to delete cloud file $cloudFileId" +
                    " — removing index entry anyway: ${deleteResult.error}"
            )
        }
        uploadedFileIndexDao.deleteById(indexId)
    }
}
