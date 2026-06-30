package ch.abwesend.foldervault.domain.backup

import ch.abwesend.foldervault.domain.model.BackupRunStatus

/**
 * One historical backup run. [completedAt] is null while the run is still in progress or if it
 * was terminated mid-flight (e.g. WorkManager cancellation, process death).
 */
data class BackupRun(
    val id: Long,
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
