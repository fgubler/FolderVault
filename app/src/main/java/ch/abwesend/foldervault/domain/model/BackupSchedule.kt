package ch.abwesend.foldervault.domain.model

enum class BackupSchedule {
    USE_GLOBAL_DEFAULT,
    MANUAL_ONLY,
    DAILY,
    WEEKLY,
    MONTHLY,
}
