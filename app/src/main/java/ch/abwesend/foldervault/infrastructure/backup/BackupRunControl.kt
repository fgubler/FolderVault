package ch.abwesend.foldervault.infrastructure.backup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Cooperative run control handed into a backup run by its host (worker or foreground service).
 *
 * Generalizes the former `deadline` parameter of `BackupRunner.runBackup`: the uploader polls
 * [shouldStop] at each committed-file boundary, so a stop — whether from the [deadline], a user
 * action, or a mid-run constraint violation via [requestStop] — always takes the clean
 * `hitTimeBudget` path (cross-run counters persisted, status `INITIAL_SYNC_IN_PROGRESS`,
 * continuation scheduling) instead of a `CancellationException` that would mark the run
 * CANCELLED and feed the charging-fallback streak.
 *
 * Also carries live in-run progress for the foreground service's notification — per-file
 * progress is intentionally not persisted to the run row (one DB write per uploaded file for a
 * UI nicety), so an in-process flow is the only live source.
 */
class BackupRunControl(private val deadline: Instant? = null) {
    @Volatile
    private var stopRequested = false

    private val mutableFilesUploadedThisRun = MutableStateFlow(0)

    /** Number of files uploaded so far in this run; updated after each committed file. */
    val filesUploadedThisRun: StateFlow<Int> = mutableFilesUploadedThisRun.asStateFlow()

    /** Requests a cooperative stop at the next committed-file boundary. Idempotent. */
    fun requestStop() {
        stopRequested = true
    }

    /** True once a stop was requested or the [deadline] has passed. */
    fun shouldStop(): Boolean = stopRequested || (deadline != null && Instant.now().isAfter(deadline))

    /** Reports the current per-run uploaded-file count (from `RunSummary.filesUploaded`). */
    fun reportFileUploaded(count: Int) {
        mutableFilesUploadedThisRun.value = count
    }
}
