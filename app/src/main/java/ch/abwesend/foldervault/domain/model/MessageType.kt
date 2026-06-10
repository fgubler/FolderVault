package ch.abwesend.foldervault.domain.model

enum class MessageType(val notifies: Boolean) {
    AUTH_LOST(notifies = true),
    FOLDER_UNREADABLE(notifies = true),
    FILE_TOO_LARGE(notifies = false),
    UPLOAD_FAILED(notifies = true),
    ENCRYPTION_FAILED(notifies = true),
    INITIAL_SYNC_COMPLETE(notifies = false),
    QUOTA_EXCEEDED(notifies = true),
    UNRELIABLE_TIMESTAMPS(notifies = false),
    RATE_LIMITED(notifies = false),
    GENERIC_INFO(notifies = false),
    GENERIC_WARNING(notifies = false),
    GENERIC_ERROR(notifies = true),
}
