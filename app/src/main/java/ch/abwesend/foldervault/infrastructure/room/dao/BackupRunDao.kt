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

    companion object {
        const val DEFAULT_LIMIT: Int = 100
    }
}
