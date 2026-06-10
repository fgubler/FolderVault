package ch.abwesend.foldervault.infrastructure.room.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.infrastructure.room.entity.NotificationThrottleStateEntity

@Dao
interface NotificationThrottleStateDao {

    @Query(
        """SELECT * FROM NotificationThrottleState
           WHERE backupConfigId = :backupConfigId AND messageType = :messageType"""
    )
    suspend fun getState(backupConfigId: String, messageType: MessageType): NotificationThrottleStateEntity?

    @Query("SELECT * FROM NotificationThrottleState WHERE backupConfigId = :backupConfigId")
    suspend fun getAllForConfig(backupConfigId: String): List<NotificationThrottleStateEntity>

    @Upsert
    suspend fun upsert(entity: NotificationThrottleStateEntity)

    @Query(
        """DELETE FROM NotificationThrottleState
           WHERE backupConfigId = :configId AND messageType = :type"""
    )
    suspend fun deleteForConfigAndType(configId: String, type: MessageType)

    @Query("DELETE FROM NotificationThrottleState WHERE backupConfigId = :backupConfigId")
    suspend fun deleteAllForConfig(backupConfigId: String)
}
