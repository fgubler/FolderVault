package ch.abwesend.foldervault.infrastructure.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.infrastructure.room.entity.BackupRunEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupRunDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: BackupRunEntity): Long

    @Query(
        """UPDATE BackupRun
           SET completedAt = :completedAt,
               status = :status,
               filesUploaded = :filesUploaded,
               filesSkipped = :filesSkipped,
               filesFailed = :filesFailed,
               bytesUploaded = :bytesUploaded
           WHERE runId = :runId"""
    )
    suspend fun markComplete(
        runId: String,
        completedAt: Long,
        status: BackupRunStatus,
        filesUploaded: Int,
        filesSkipped: Int,
        filesFailed: Int,
        bytesUploaded: Long,
    )

    @Query(
        """SELECT * FROM BackupRun
           WHERE backupConfigId = :backupConfigId
           ORDER BY startedAt DESC
           LIMIT :limit"""
    )
    fun observeByConfig(backupConfigId: String, limit: Int = DEFAULT_LIMIT): Flow<List<BackupRunEntity>>

    /**
     * Deletes all rows for [configId] except the [keep] most recent (by startedAt).
     * Called after each run completes to bound history growth.
     */
    @Query(
        """DELETE FROM BackupRun
           WHERE backupConfigId = :configId
             AND id NOT IN (
                 SELECT id FROM BackupRun
                 WHERE backupConfigId = :configId
                 ORDER BY startedAt DESC
                 LIMIT :keep
             )"""
    )
    suspend fun pruneOld(configId: String, keep: Int = DEFAULT_LIMIT)

    @Query("SELECT * FROM BackupRun WHERE runId = :runId LIMIT 1")
    suspend fun findByRunId(runId: String): BackupRunEntity?

    /**
     * Returns the [limit] most-recent statuses for [configId] (newest first). Used by the
     * charging-only fallback trigger to decide whether the last N runs have all been cancelled.
     */
    @Query(
        """SELECT status FROM BackupRun
           WHERE backupConfigId = :configId
           ORDER BY startedAt DESC
           LIMIT :limit"""
    )
    suspend fun getRecentStatuses(configId: String, limit: Int): List<BackupRunStatus>

    /**
     * Marks any RUNNING row that started before [staleBefore] as CANCELLED with
     * [completedAt] = [now]. Used at app startup to flip rows left behind by process
     * death — see [STALE_GRACE_WINDOW_MS]. Returns the number of rows updated.
     */
    @Query(
        """UPDATE BackupRun
           SET status = 'CANCELLED',
               completedAt = :now
           WHERE status = 'RUNNING'
             AND startedAt < :staleBefore"""
    )
    suspend fun markStaleRunningAsCancelled(staleBefore: Long, now: Long): Int

    companion object {
        const val DEFAULT_LIMIT: Int = 100

        /**
         * Grace window before a still-RUNNING row is considered abandoned. A legitimate
         * backup that hits the WorkManager budget completes via the deadline path long
         * before this; anything older has almost certainly died with the host process.
         */
        const val STALE_GRACE_WINDOW_MS: Long = 24L * 60L * 60L * 1000L
    }
}
