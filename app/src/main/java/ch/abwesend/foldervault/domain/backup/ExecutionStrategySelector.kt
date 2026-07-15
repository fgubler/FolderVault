package ch.abwesend.foldervault.domain.backup

/**
 * Pure decision of which mechanism hosts a config's *scheduled* backups (see
 * [ScheduledExecutionMode]).
 *
 * WorkManager is the always-available default. The exact-alarm host is an opt-in enhancement that
 * additionally requires the OS `SCHEDULE_EXACT_ALARM` permission. On API < 31 exact alarms need no
 * runtime permission, so the platform wrapper reports [canScheduleExactAlarms] as `true` there and
 * the opt-in flag alone decides.
 */
object ExecutionStrategySelector {
    /** First API level on which exact alarms require the user-granted `SCHEDULE_EXACT_ALARM`. */
    private const val FIRST_API_WITH_EXACT_ALARM_PERMISSION = 31

    /**
     * @param apiLevel `Build.VERSION.SDK_INT` of the running device.
     * @param exactAlarmUserEnabled whether the user opted into exact-alarm backups in settings.
     * @param canScheduleExactAlarms `AlarmManager.canScheduleExactAlarms()` on API 31+; the caller
     *   passes `true` on older devices, where exact alarms need no permission.
     */
    fun scheduledMode(
        apiLevel: Int,
        exactAlarmUserEnabled: Boolean,
        canScheduleExactAlarms: Boolean,
    ): ScheduledExecutionMode {
        val exactAlarmPermitted =
            apiLevel < FIRST_API_WITH_EXACT_ALARM_PERMISSION || canScheduleExactAlarms
        return if (exactAlarmUserEnabled && exactAlarmPermitted) {
            ScheduledExecutionMode.EXACT_ALARM
        } else {
            ScheduledExecutionMode.WORKMANAGER_PERIODIC
        }
    }
}
