package ch.abwesend.foldervault.domain.backup

/**
 * Which mechanism hosts a config's *scheduled* (periodic) backups. Exactly one is ever active for
 * a given config (see the execution-mode coordinator). The manual "back up now" routing is a
 * separate decision made by [StartManualBackupUseCase].
 */
enum class ScheduledExecutionMode {
    /**
     * An exact alarm fires a receiver that starts the foreground service. Opt-in and gated on the
     * `SCHEDULE_EXACT_ALARM` permission; runs the initial/large delta in one long window instead
     * of WorkManager's short chunks.
     */
    EXACT_ALARM,

    /** The always-available default: the WorkManager periodic worker with the §5.8 continuation. */
    WORKMANAGER_PERIODIC,
}
