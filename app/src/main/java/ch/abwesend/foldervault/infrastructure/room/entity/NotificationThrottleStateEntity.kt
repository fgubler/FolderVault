package ch.abwesend.foldervault.infrastructure.room.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import ch.abwesend.foldervault.domain.model.MessageType

@Entity(
    tableName = "NotificationThrottleState",
    primaryKeys = ["backupConfigId", "messageType"],
    foreignKeys = [
        ForeignKey(
            entity = BackupConfigEntity::class,
            parentColumns = ["id"],
            childColumns = ["backupConfigId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("backupConfigId")],
)
data class NotificationThrottleStateEntity(
    val backupConfigId: String,
    val messageType: MessageType,
    val lastNotifiedAt: Long,
    val lastRunId: String?,
)
