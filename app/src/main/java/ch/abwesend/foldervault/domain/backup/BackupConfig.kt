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
