package ch.abwesend.foldervault.infrastructure.room.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import ch.abwesend.foldervault.domain.model.BackupRunStatus

/**
 * One row per backup-run attempt — captures when it started and ended, how it finished, and
 * how many files were affected. Rows with [completedAt] = null represent runs that are still
 * in progress or were terminated mid-flight (e.g. WorkManager cancellation).
 */
@Entity(
    tableName = "BackupRun",
    foreignKeys = [
        ForeignKey(
            entity = BackupConfigEntity::class,
            parentColumns = ["id"],
            childColumns = ["backupConfigId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("backupConfigId"),
        Index(value = ["backupConfigId", "startedAt"]),
        Index(value = ["runId"], unique = true),
    ],
)
data class BackupRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val backupConfigId: String,
    val runId: String,
    val startedAt: Long,
    val completedAt: Long?,
    val status: BackupRunStatus,
    val filesUploaded: Int,
    val filesSkipped: Int,
    val filesFailed: Int,
    val bytesUploaded: Long,
)
