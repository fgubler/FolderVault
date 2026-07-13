package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.domain.cloud.CloudNotFoundException
import ch.abwesend.foldervault.domain.cloud.ICloudStorageProvider
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.RetentionPolicy
import ch.abwesend.foldervault.domain.result.BinaryResult
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
            // Get all versions for this path, newest first. Baseline rows own no cloud object,
            // so they neither count against N (which counts *cloud* copies) nor get
            // cloud-deleted — stray superseded ones are dropped locally (defense in depth; the
            // index upsert normally removes them already).
            val (baselineRows, cloudRows) = uploadedFileIndexDao.getVersionHistory(configId, path)
                .partition { it.isBaseline }
            for (entry in baselineRows.filterNot { it.isCurrentVersion }) {
                uploadedFileIndexDao.deleteById(entry.id)
            }
            // Keep the newest effective count; delete everything beyond that
            val toDelete = cloudRows.drop(effective)
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
            // Baseline rows have no cloud object to delete — drop the index row directly.
            if (entry.isBaseline) {
                uploadedFileIndexDao.deleteById(entry.id)
            } else {
                deleteCloudAndIndex(entry.cloudFileId, entry.id)
            }
        }
    }

    /**
     * Retries the cloud-delete for any row carrying a [pendingDeletionCloudFileId] marker.
     * Two producers share this mechanism, distinguished by whether the marked object is the
     * row's own [cloudFileId]:
     * - **Overwrite predecessor** (marker != own `cloudFileId`): a CHANGED_OVERWRITE upload that
     *   failed to delete its predecessor object. The row is still the current version, so only
     *   the marker is cleared once the object is gone.
     * - **Retention** (marker == own `cloudFileId`): [deleteCloudAndIndex] could not delete the
     *   row's own object, so it kept the row instead of orphaning the file. Once the object is
     *   gone the whole row is dropped.
     *
     * Idempotent: Drive returning 404 for an already-gone file is treated as a success (see
     * [isGone]).
     */
    suspend fun reapPendingDeletions(configId: String) {
        val pending = uploadedFileIndexDao.getPendingDeletions(configId)
        if (pending.isEmpty()) return
        log.debug("Reaping ${pending.size} pending cloud deletion(s) for config $configId")
        for (entry in pending) {
            val cloudFileId = entry.pendingDeletionCloudFileId ?: continue
            val deleteResult = cloudProvider.deleteFile(cloudFileId)
            if (isGone(deleteResult)) {
                if (cloudFileId == entry.cloudFileId) {
                    uploadedFileIndexDao.deleteById(entry.id)
                } else {
                    uploadedFileIndexDao.clearPendingDeletion(entry.id)
                }
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
        if (isGone(deleteResult)) {
            uploadedFileIndexDao.deleteById(indexId)
        } else {
            // Do NOT drop the row: without it, nothing records that the cloud object exists and it
            // is orphaned in Drive forever. Instead mark the row's own object pending so the
            // end-of-run reaper retries the delete and only then removes the row.
            log.warning(
                "Retention: failed to delete cloud file $cloudFileId" +
                    " — keeping index entry and marking for reap: ${(deleteResult as ErrorResult).error}"
            )
            uploadedFileIndexDao.markPendingDeletion(indexId, cloudFileId)
        }
    }

    /**
     * True when the cloud file is confirmed gone: either the delete succeeded, or Drive
     * reported a 404 ([CloudNotFoundException]) for an already-removed file. Treating 404 as
     * success keeps deletions idempotent so a file removed out-of-band (manually in Drive or by
     * a race) does not leave a marker that is re-fetched and re-"deleted" on every run.
     */
    private fun isGone(deleteResult: BinaryResult<Unit, Exception>): Boolean =
        deleteResult is SuccessResult ||
            (deleteResult is ErrorResult && deleteResult.error is CloudNotFoundException)
}
