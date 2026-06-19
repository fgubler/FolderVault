package ch.abwesend.foldervault.infrastructure.room.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy

@Entity(tableName = "BackupConfig")
data class BackupConfigEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val sourceTreeUri: String,
    val cloudProvider: String,
    val cloudSubFolderId: String,
    val cloudSubFolderName: String,
    val cloudAccountIdentifier: String,
    val schedule: BackupSchedule,
    val changedFilePolicy: ChangedFilePolicy,
    val encryptionEnabled: Boolean,
    val encryptedPasswordBlob: String?,
    /**
     * Fast-path cache of per-backup encryption params (authoritative source is the FVC1 file header).
     * Non-null iff [encryptionEnabled] is true.
     */
    @Embedded(prefix = "enc_") val encryptionParams: EncryptionParams?,
    val retentionPolicy: RetentionPolicy,
    val networkPolicy: NetworkPolicy,
    val createdAt: Long,
    val lastRunAt: Long?,
    val lastRunStatus: BackupRunStatus,
    // Counters for last run only
    val filesUploaded: Int,
    val filesSkipped: Int,
    val filesFailed: Int,
    val bytesUploaded: Long,
    // Cross-run progress tracking (§7.6)
    val totalFilesDiscovered: Int,
    val filesUploadedTotal: Int,
    val lastRunCompletedNormally: Boolean,
    val isPaused: Boolean = false,
)
