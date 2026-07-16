package ch.abwesend.foldervault.domain.model

import androidx.annotation.StringRes
import ch.abwesend.foldervault.R

enum class MessageType(val notifies: Boolean, @StringRes val labelResId: Int) {
    AUTH_LOST(notifies = true, R.string.msg_auth_lost),
    FOLDER_UNREADABLE(notifies = true, R.string.msg_folder_unreadable),
    FILE_TOO_LARGE(notifies = false, R.string.msg_file_too_large),
    UPLOAD_FAILED(notifies = true, R.string.msg_upload_failed),
    ENCRYPTION_FAILED(notifies = true, R.string.msg_encryption_failed),
    INITIAL_SYNC_COMPLETE(notifies = false, R.string.msg_initial_sync_complete),
    BASELINE_RECORDED(notifies = false, R.string.msg_baseline_recorded),
    QUOTA_EXCEEDED(notifies = true, R.string.msg_quota_exceeded),
    UNRELIABLE_TIMESTAMPS(notifies = false, R.string.msg_unreliable_timestamps),
    RATE_LIMITED(notifies = false, R.string.msg_rate_limited),
    CHARGING_FALLBACK_SCHEDULED(notifies = false, R.string.msg_charging_fallback_scheduled),

    /**
     * A background run that opted into extended run time could not enter the foreground service
     * (typically the app's shared dataSync time budget was exhausted), so it fell back to the
     * standard WorkManager path. Informational — the backup still runs, just in shorter windows.
     */
    FGS_TIME_BUDGET_REACHED(notifies = false, R.string.msg_fgs_time_budget_reached),

    /**
     * The daily watchdog found a schedule WorkManager had failed to fire for more than a full extra
     * interval and enqueued a catch-up run itself. Non-notifying: the run is already on its way, so
     * this is an after-the-fact breadcrumb explaining why a backup started off its normal cadence.
     */
    WATCHDOG_TRIGGERED_RUN(notifies = false, R.string.msg_watchdog_triggered_run),
    GENERIC_INFO(notifies = false, R.string.msg_generic_info),
    GENERIC_WARNING(notifies = false, R.string.msg_generic_warning),
    GENERIC_ERROR(notifies = true, R.string.msg_generic_error),
}
