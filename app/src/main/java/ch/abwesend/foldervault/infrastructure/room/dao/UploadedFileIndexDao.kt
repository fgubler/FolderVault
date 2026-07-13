package ch.abwesend.foldervault.infrastructure.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ch.abwesend.foldervault.infrastructure.room.entity.UploadedFileIndexEntity
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions")
interface UploadedFileIndexDao {

    @Query(
        """SELECT * FROM UploadedFileIndex
           WHERE backupConfigId = :backupConfigId AND isCurrentVersion = 1
           ORDER BY relativePath ASC"""
    )
    fun getCurrentVersions(backupConfigId: String): Flow<List<UploadedFileIndexEntity>>

    @Query(
        """SELECT * FROM UploadedFileIndex
           WHERE backupConfigId = :backupConfigId AND relativePath = :relativePath
             AND isCurrentVersion = 1
           LIMIT 1"""
    )
    suspend fun getCurrentVersion(backupConfigId: String, relativePath: String): UploadedFileIndexEntity?

    @Query(
        """SELECT * FROM UploadedFileIndex
           WHERE backupConfigId = :backupConfigId AND relativePath = :relativePath
           ORDER BY uploadedAt DESC"""
    )
    suspend fun getVersionHistory(backupConfigId: String, relativePath: String): List<UploadedFileIndexEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: UploadedFileIndexEntity): Long

    @Transaction
    suspend fun upsertCurrentVersion(entity: UploadedFileIndexEntity): Long {
        clearCurrentFlag(entity.backupConfigId, entity.relativePath)
        deleteSupersededBaselineRows(entity.backupConfigId, entity.relativePath)
        return insert(entity.copy(isCurrentVersion = true))
    }

    /**
     * Baseline rows (never-uploaded snapshot entries of a `syncLaterChangesOnly` config) are
     * only meaningful as the current version — once a real upload supersedes one, it is
     * deleted rather than kept as a dead history entry that retention would otherwise trip on.
     */
    @Query(
        """DELETE FROM UploadedFileIndex
           WHERE backupConfigId = :backupConfigId AND relativePath = :relativePath
             AND isBaseline = 1 AND isCurrentVersion = 0"""
    )
    suspend fun deleteSupersededBaselineRows(backupConfigId: String, relativePath: String)

    @Query("UPDATE UploadedFileIndex SET pendingDeletionCloudFileId = NULL WHERE id = :id")
    suspend fun clearPendingDeletion(id: Long)

    @Query("UPDATE UploadedFileIndex SET pendingDeletionCloudFileId = :cloudFileId WHERE id = :id")
    suspend fun markPendingDeletion(id: Long, cloudFileId: String)

    @Query(
        """SELECT * FROM UploadedFileIndex
           WHERE backupConfigId = :configId AND pendingDeletionCloudFileId IS NOT NULL"""
    )
    suspend fun getPendingDeletions(configId: String): List<UploadedFileIndexEntity>

    @Query(
        """UPDATE UploadedFileIndex SET isCurrentVersion = 0
           WHERE backupConfigId = :backupConfigId AND relativePath = :relativePath"""
    )
    suspend fun clearCurrentFlag(backupConfigId: String, relativePath: String)

    @Query(
        """DELETE FROM UploadedFileIndex
           WHERE backupConfigId = :backupConfigId AND relativePath = :relativePath
             AND isCurrentVersion = 0
             AND id NOT IN (
               SELECT id FROM UploadedFileIndex
               WHERE backupConfigId = :backupConfigId AND relativePath = :relativePath
                 AND isCurrentVersion = 0
               ORDER BY uploadedAt DESC
               LIMIT :keepCount
             )"""
    )
    suspend fun pruneOldVersions(backupConfigId: String, relativePath: String, keepCount: Int)

    @Query(
        """DELETE FROM UploadedFileIndex
           WHERE backupConfigId = :backupConfigId
             AND isCurrentVersion = 0
             AND uploadedAt < :cutoffEpochMs"""
    )
    suspend fun pruneVersionsOlderThan(backupConfigId: String, cutoffEpochMs: Long)

    @Query("DELETE FROM UploadedFileIndex WHERE backupConfigId = :backupConfigId")
    suspend fun deleteAllForConfig(backupConfigId: String)

    @Query(
        """SELECT * FROM UploadedFileIndex
           WHERE backupConfigId = :configId AND isCurrentVersion = 1
           ORDER BY relativePath ASC"""
    )
    suspend fun getCurrentVersionList(configId: String): List<UploadedFileIndexEntity>

    @Query(
        """SELECT DISTINCT relativePath FROM UploadedFileIndex
           WHERE backupConfigId = :configId"""
    )
    suspend fun getDistinctPaths(configId: String): List<String>

    @Query(
        """SELECT * FROM UploadedFileIndex
           WHERE backupConfigId = :configId
             AND isCurrentVersion = 0
             AND uploadedAt < :cutoffEpochMs"""
    )
    suspend fun getOldVersionsOlderThan(configId: String, cutoffEpochMs: Long): List<UploadedFileIndexEntity>

    @Query("DELETE FROM UploadedFileIndex WHERE id = :id")
    suspend fun deleteById(id: Long)
}
