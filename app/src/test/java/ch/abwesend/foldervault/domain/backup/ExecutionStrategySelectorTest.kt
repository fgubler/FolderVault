package ch.abwesend.foldervault.domain.backup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Pins down which mechanism hosts scheduled backups: the exact-alarm host is chosen only when the
 * user opted in AND the permission is effectively granted; every other case falls back to the
 * always-available WorkManager periodic path. On API < 31 no runtime permission exists, so the
 * opt-in flag alone decides.
 */
class ExecutionStrategySelectorTest : StringSpec({

    "opt-in + permission granted (API 31+) selects the exact-alarm host" {
        ExecutionStrategySelector.scheduledMode(
            apiLevel = 34,
            exactAlarmUserEnabled = true,
            canScheduleExactAlarms = true,
        ) shouldBe ScheduledExecutionMode.EXACT_ALARM
    }

    "opt-in but permission denied falls back to WorkManager" {
        ExecutionStrategySelector.scheduledMode(
            apiLevel = 34,
            exactAlarmUserEnabled = true,
            canScheduleExactAlarms = false,
        ) shouldBe ScheduledExecutionMode.WORKMANAGER_PERIODIC
    }

    "permission granted but opt-in off stays on WorkManager (the default)" {
        ExecutionStrategySelector.scheduledMode(
            apiLevel = 34,
            exactAlarmUserEnabled = false,
            canScheduleExactAlarms = true,
        ) shouldBe ScheduledExecutionMode.WORKMANAGER_PERIODIC
    }

    "neither opt-in nor permission stays on WorkManager" {
        ExecutionStrategySelector.scheduledMode(
            apiLevel = 34,
            exactAlarmUserEnabled = false,
            canScheduleExactAlarms = false,
        ) shouldBe ScheduledExecutionMode.WORKMANAGER_PERIODIC
    }

    "API < 31: opt-in alone selects the exact-alarm host regardless of the reported permission flag" {
        ExecutionStrategySelector.scheduledMode(
            apiLevel = 30,
            exactAlarmUserEnabled = true,
            canScheduleExactAlarms = false,
        ) shouldBe ScheduledExecutionMode.EXACT_ALARM
    }

    "API < 31 with opt-in off still stays on WorkManager" {
        ExecutionStrategySelector.scheduledMode(
            apiLevel = 26,
            exactAlarmUserEnabled = false,
            canScheduleExactAlarms = true,
        ) shouldBe ScheduledExecutionMode.WORKMANAGER_PERIODIC
    }

    "the API-31 boundary requires the permission (revoked -> WorkManager)" {
        ExecutionStrategySelector.scheduledMode(
            apiLevel = 31,
            exactAlarmUserEnabled = true,
            canScheduleExactAlarms = false,
        ) shouldBe ScheduledExecutionMode.WORKMANAGER_PERIODIC
        ExecutionStrategySelector.scheduledMode(
            apiLevel = 31,
            exactAlarmUserEnabled = true,
            canScheduleExactAlarms = true,
        ) shouldBe ScheduledExecutionMode.EXACT_ALARM
    }
})
