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
    QUOTA_EXCEEDED(notifies = true, R.string.msg_quota_exceeded),
    UNRELIABLE_TIMESTAMPS(notifies = false, R.string.msg_unreliable_timestamps),
    RATE_LIMITED(notifies = false, R.string.msg_rate_limited),
    CHARGING_FALLBACK_SCHEDULED(notifies = false, R.string.msg_charging_fallback_scheduled),
    GENERIC_INFO(notifies = false, R.string.msg_generic_info),
    GENERIC_WARNING(notifies = false, R.string.msg_generic_warning),
    GENERIC_ERROR(notifies = true, R.string.msg_generic_error),
}
