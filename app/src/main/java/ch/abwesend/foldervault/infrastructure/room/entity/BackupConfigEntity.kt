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
    /**
     * When true, WorkManager only fires this backup's periodic + one-time work while the
     * device is charging. When false, the scheduler still schedules a one-off charging-only
     * fallback after several consecutive cancellations — see
     * [ch.abwesend.foldervault.infrastructure.backup.BackupScheduler].
     */
    val requiresCharging: Boolean = false,
    /**
     * When true, files that already existed in the source folder at the time of the first run
     * are never uploaded: that run records them as baseline rows in UploadedFileIndex instead
     * (see [UploadedFileIndexEntity.isBaseline]); only files added or modified afterwards are
     * synced. Immutable after creation. Note the baseline captures the folder state at the
     * *first run*, not the creation instant — new configs auto-start immediately, so the gap
     * is seconds.
     */
    val syncLaterChangesOnly: Boolean = false,
    /**
     * When the baseline snapshot finished a complete tree walk; NULL while the baseline is
     * still pending (an interrupted baseline resumes on the next run). Baseline mode is active
     * iff [syncLaterChangesOnly] is true and this is NULL. Always NULL when
     * [syncLaterChangesOnly] is false.
     */
    val baselineCompletedAt: Long? = null,
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
