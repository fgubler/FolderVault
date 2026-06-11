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
}
