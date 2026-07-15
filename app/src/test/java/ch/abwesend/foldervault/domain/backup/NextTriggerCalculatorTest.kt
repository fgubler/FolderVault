package ch.abwesend.foldervault.domain.backup

import ch.abwesend.foldervault.domain.model.BackupSchedule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

/**
 * Pins down the watchdog's interval arithmetic: the per-cadence periodic interval and the
 * overdue detection (anchored on the last run, or creation for a never-run config, with a
 * one-interval grace) — with DST-independent intervals (absolute-instant arithmetic never drifts).
 */
class NextTriggerCalculatorTest : StringSpec({

    val daily = Duration.ofDays(1)

    "periodic interval maps each cadence; non-periodic schedules have none" {
        NextTriggerCalculator.periodicInterval(BackupSchedule.DAILY) shouldBe Duration.ofDays(1)
        NextTriggerCalculator.periodicInterval(BackupSchedule.WEEKLY) shouldBe Duration.ofDays(7)
        NextTriggerCalculator.periodicInterval(BackupSchedule.MONTHLY) shouldBe Duration.ofDays(30)
        NextTriggerCalculator.periodicInterval(BackupSchedule.MANUAL_ONLY) shouldBe null
        NextTriggerCalculator.periodicInterval(BackupSchedule.USE_GLOBAL_DEFAULT) shouldBe null
    }

    "isOverdue is false within the one-interval grace past the expected trigger" {
        val lastRun = Instant.parse("2026-07-14T00:00:00Z")
        // Expected trigger is lastRun + 1 day; the watchdog only fires after a *further* full day.
        // 1.5 days after the last run is past the expected trigger but still inside the grace window.
        val now = lastRun.plus(Duration.ofHours(36))
        NextTriggerCalculator.isOverdue(
            interval = daily,
            lastRunAt = lastRun.toEpochMilli(),
            createdAt = 0L,
            now = now,
        ) shouldBe false
    }

    "isOverdue is true once more than one full interval past the expected trigger has elapsed" {
        val lastRun = Instant.parse("2026-07-14T00:00:00Z")
        val now = lastRun.plus(Duration.ofHours(49)) // > lastRun + 2 days
        NextTriggerCalculator.isOverdue(
            interval = daily,
            lastRunAt = lastRun.toEpochMilli(),
            createdAt = 0L,
            now = now,
        ) shouldBe true
    }

    "isOverdue anchors on createdAt for a never-run config" {
        val createdAt = Instant.parse("2026-07-10T00:00:00Z")
        val now = createdAt.plus(Duration.ofDays(3)) // well past createdAt + 2 intervals
        NextTriggerCalculator.isOverdue(
            interval = daily,
            lastRunAt = null,
            createdAt = createdAt.toEpochMilli(),
            now = now,
        ) shouldBe true
    }

    "isOverdue is false exactly at the two-interval boundary (strictly-after threshold)" {
        val lastRun = Instant.parse("2026-07-14T00:00:00Z")
        val now = lastRun.plus(daily).plus(daily)
        NextTriggerCalculator.isOverdue(
            interval = daily,
            lastRunAt = lastRun.toEpochMilli(),
            createdAt = 0L,
            now = now,
        ) shouldBe false
    }

    "overdue threshold does not drift across a DST boundary (absolute-instant arithmetic)" {
        // Central-European spring-forward night (clocks jump 02:00 -> 03:00 local on 2026-03-29).
        // The two-interval overdue threshold is exactly 48 h on the absolute timeline regardless:
        // just before it the config is not yet overdue, just after it is.
        val lastRun = Instant.parse("2026-03-28T23:30:00Z")
        NextTriggerCalculator.isOverdue(
            interval = daily,
            lastRunAt = lastRun.toEpochMilli(),
            createdAt = 0L,
            now = lastRun.plus(Duration.ofHours(48)).minusSeconds(1),
        ) shouldBe false
        NextTriggerCalculator.isOverdue(
            interval = daily,
            lastRunAt = lastRun.toEpochMilli(),
            createdAt = 0L,
            now = lastRun.plus(Duration.ofHours(48)).plusSeconds(1),
        ) shouldBe true
    }
})
