package ch.abwesend.foldervault.infrastructure.room.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType

@Entity(
    tableName = "BackupMessage",
    foreignKeys = [
        ForeignKey(
            entity = BackupConfigEntity::class,
            parentColumns = ["id"],
            childColumns = ["backupConfigId"],
            onDelete = ForeignKey.CASCADE,
            // Null FK values are not constrained by SQLite FKs — rows with null backupConfigId
            // are app-global messages and survive config deletion.
        ),
    ],
    indices = [
        Index("backupConfigId"),
        Index(value = ["backupConfigId", "timestamp"]),
        Index(value = ["backupConfigId", "dismissed"]),
    ],
)
data class BackupMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val backupConfigId: String?,
    val runId: String?,
    val timestamp: Long,
    val severity: MessageSeverity,
    val type: MessageType,
    val messageText: String?,
    val formatArgs: List<String>,
    val relativePath: String?,
    val count: Int = 1,
    val readAt: Long?,
    val dismissed: Boolean = false,
)
