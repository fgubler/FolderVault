package ch.abwesend.foldervault.infrastructure.room.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupConfigDao {

    @Query("SELECT * FROM BackupConfig ORDER BY displayName ASC")
    fun getAll(): Flow<List<BackupConfigEntity>>

    @Query("SELECT * FROM BackupConfig WHERE id = :id")
    fun getById(id: String): Flow<BackupConfigEntity?>

    @Query("SELECT * FROM BackupConfig WHERE id = :id")
    suspend fun getByIdOnce(id: String): BackupConfigEntity?

    @Upsert
    suspend fun upsert(entity: BackupConfigEntity)

    @Delete
    suspend fun delete(entity: BackupConfigEntity)

    @Query("DELETE FROM BackupConfig WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        """UPDATE BackupConfig SET
            lastRunAt = :lastRunAt,
            lastRunStatus = :lastRunStatus,
            filesUploaded = :filesUploaded,
            filesSkipped = :filesSkipped,
            filesFailed = :filesFailed,
            bytesUploaded = :bytesUploaded,
            lastRunCompletedNormally = :lastRunCompletedNormally
           WHERE id = :id"""
    )
    suspend fun updateRunStats(
        id: String,
        lastRunAt: Long,
        lastRunStatus: BackupRunStatus,
        filesUploaded: Int,
        filesSkipped: Int,
        filesFailed: Int,
        bytesUploaded: Long,
        lastRunCompletedNormally: Boolean,
    )

    @Query(
        """UPDATE BackupConfig SET
            totalFilesDiscovered = :totalFilesDiscovered,
            filesUploadedTotal = :filesUploadedTotal
           WHERE id = :id"""
    )
    suspend fun updateCrossRunProgress(
        id: String,
        totalFilesDiscovered: Int,
        filesUploadedTotal: Int,
    )

    @Query("UPDATE BackupConfig SET isPaused = :paused WHERE id = :id")
    suspend fun updatePaused(id: String, paused: Boolean)
}
