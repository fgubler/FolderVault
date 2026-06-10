package ch.abwesend.foldervault.infrastructure.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.infrastructure.room.entity.BackupMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupMessageDao {

    @Query(
        """SELECT * FROM BackupMessage
           WHERE backupConfigId = :backupConfigId AND dismissed = 0
           ORDER BY timestamp DESC"""
    )
    fun getUndismissed(backupConfigId: String): Flow<List<BackupMessageEntity>>

    @Query(
        """SELECT * FROM BackupMessage
           WHERE backupConfigId = :backupConfigId AND readAt IS NULL AND dismissed = 0
           ORDER BY timestamp DESC"""
    )
    fun getUnread(backupConfigId: String): Flow<List<BackupMessageEntity>>

    @Query(
        """SELECT * FROM BackupMessage
           WHERE backupConfigId IS NULL AND dismissed = 0
           ORDER BY timestamp DESC"""
    )
    fun getGlobalUndismissed(): Flow<List<BackupMessageEntity>>

    @Query(
        """SELECT COUNT(*) FROM BackupMessage
           WHERE backupConfigId = :backupConfigId AND readAt IS NULL AND dismissed = 0"""
    )
    fun getUnreadCount(backupConfigId: String): Flow<Int>

    @Query(
        """SELECT COUNT(*) FROM BackupMessage
           WHERE backupConfigId = :backupConfigId
             AND severity IN (:severities)
             AND readAt IS NULL AND dismissed = 0"""
    )
    fun getUnreadCountBySeverity(backupConfigId: String, severities: List<MessageSeverity>): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BackupMessageEntity): Long

    @Query("UPDATE BackupMessage SET readAt = :readAt WHERE id IN (:ids)")
    suspend fun markRead(ids: List<Long>, readAt: Long)

    @Query("UPDATE BackupMessage SET dismissed = 1 WHERE id IN (:ids)")
    suspend fun dismiss(ids: List<Long>)

    @Query("UPDATE BackupMessage SET dismissed = 1 WHERE backupConfigId = :backupConfigId")
    suspend fun dismissAllForConfig(backupConfigId: String)

    @Query("DELETE FROM BackupMessage WHERE backupConfigId = :backupConfigId")
    suspend fun deleteAllForConfig(backupConfigId: String)

    @Query("DELETE FROM BackupMessage WHERE timestamp < :cutoffEpochMs AND dismissed = 1")
    suspend fun deleteOldDismissed(cutoffEpochMs: Long)

    @Query("SELECT COUNT(*) FROM BackupMessage WHERE backupConfigId = :configId AND type = :type AND dismissed = 0")
    suspend fun getCountForType(configId: String, type: MessageType): Int
}
