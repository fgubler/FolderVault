package ch.abwesend.foldervault.domain.model

import androidx.annotation.StringRes
import ch.abwesend.foldervault.R

enum class BackupRunStatus(@StringRes val labelResId: Int) {
    IDLE(R.string.status_idle),
    RUNNING(R.string.status_running),
    INITIAL_SYNC_IN_PROGRESS(R.string.status_initial_sync_in_progress),
    UP_TO_DATE(R.string.status_up_to_date),
    COMPLETED_WITH_WARNINGS(R.string.status_completed_with_warnings),
    FAILED(R.string.status_failed),

    /**
     * The run could not reach the network and is queued for another attempt on WorkManager's
     * backoff. Deliberately not [FAILED]: nothing is wrong with the backup or the files, and the
     * user is not notified until the retries are exhausted — a status of "failed" would contradict
     * that silence from the very first attempt, and a device offline overnight would fill the run
     * history with failures for a backup that simply had no connection.
     */
    WAITING_FOR_NETWORK(R.string.status_waiting_for_network),
    CANCELLED(R.string.status_cancelled),
}
