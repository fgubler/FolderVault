package ch.abwesend.foldervault.domain.model

enum class BackupRunStatus {
    IDLE,
    RUNNING,
    INITIAL_SYNC_IN_PROGRESS,
    UP_TO_DATE,
    COMPLETED_WITH_WARNINGS,
    FAILED,
}
