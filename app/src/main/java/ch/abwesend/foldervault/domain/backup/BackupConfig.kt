package ch.abwesend.foldervault.domain.backup

import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.ChangedFilePolicy
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.model.RetentionPolicy

data class BackupConfig(
    val id: String,
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
    val encryptionSaltBase64: String?,
    val retentionPolicy: RetentionPolicy,
    val networkPolicy: NetworkPolicy,
    val requiresCharging: Boolean,
    /**
     * When true, files that already existed in the source folder at the first run are never
     * uploaded — only files added or modified afterwards are synced. Immutable after creation.
     */
    val syncLaterChangesOnly: Boolean = false,
    /**
     * When the baseline snapshot of pre-existing files completed; null while it is still
     * pending (or always, if [syncLaterChangesOnly] is false).
     */
    val baselineCompletedAt: Long? = null,
    val createdAt: Long,
    val lastRunAt: Long?,
    val lastRunStatus: BackupRunStatus,
    val filesUploaded: Int,
    val filesSkipped: Int,
    val filesFailed: Int,
    val bytesUploaded: Long,
    val totalFilesDiscovered: Int,
    val filesUploadedTotal: Int,
    val lastRunCompletedNormally: Boolean,
    val isPaused: Boolean,
)
