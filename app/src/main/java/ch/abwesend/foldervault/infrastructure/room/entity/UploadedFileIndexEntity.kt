package ch.abwesend.foldervault.infrastructure.room.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Partial unique index on `(backupConfigId, relativePath) WHERE isCurrentVersion = 1`
 * is created via `RoomDatabase.Callback.onCreate` — Room does not support partial indexes natively.
 */
@Entity(
    tableName = "UploadedFileIndex",
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
        Index(value = ["backupConfigId", "relativePath", "uploadedAt"], unique = true),
    ],
)
data class UploadedFileIndexEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val backupConfigId: String,
    val relativePath: String,
    val localLastModified: Long,
    val localSize: Long,
    val cloudFileId: String,
    val remoteName: String,
    val uploadedAt: Long,
    val isCurrentVersion: Boolean,

    /**
     * Non-null when this row "owns" the deletion of a superseded cloud file
     * (CHANGED_OVERWRITE). Cleared once the cloud delete confirms; an end-of-run
     * reaper retries any rows still marked pending.
     */
    val pendingDeletionCloudFileId: String? = null,

    /**
     * True for rows recorded by the baseline snapshot of a "only sync changes from now on"
     * config ([BackupConfigEntity.syncLaterChangesOnly]): the file existed at the first run
     * and was deliberately never uploaded. Baseline rows carry empty [cloudFileId] /
     * [remoteName] sentinels and [uploadedAt] means "baselined at". They must never reach the
     * cloud manifest or retention cloud-deletes, and they are only ever current versions —
     * a real upload of the same path replaces the baseline row.
     */
    val isBaseline: Boolean = false,
)
