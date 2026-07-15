package ch.abwesend.foldervault.domain.backup

import ch.abwesend.foldervault.domain.model.BackupSchedule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

/**
 * Pins down the next-fire computation for the exact-alarm host: interval after the last run (or
 * creation for a never-run config), a soon-but-staggered time when overdue, and DST-independent
 * intervals (absolute-instant arithmetic never drifts).
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

    "never-run config anchors on createdAt" {
        val createdAt = Instant.parse("2026-07-15T08:00:00Z")
        val now = createdAt.plus(Duration.ofHours(1))
        NextTriggerCalculator.nextTriggerAt(
            interval = daily,
            lastRunAt = null,
            createdAt = createdAt.toEpochMilli(),
            now = now,
            configId = "cfg-1",
        ) shouldBe createdAt.plus(daily)
    }

    "a config that ran recently fires one interval after its last run" {
        val lastRun = Instant.parse("2026-07-15T02:00:00Z")
        val now = lastRun.plus(Duration.ofHours(3))
        NextTriggerCalculator.nextTriggerAt(
            interval = daily,
            lastRunAt = lastRun.toEpochMilli(),
            createdAt = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
            now = now,
            configId = "cfg-1",
        ) shouldBe lastRun.plus(daily)
    }

    "lastRunAt takes precedence over createdAt as the anchor" {
        val createdAt = Instant.parse("2026-01-01T00:00:00Z")
        val lastRun = Instant.parse("2026-07-14T09:30:00Z")
        val now = lastRun.plus(Duration.ofHours(1))
        NextTriggerCalculator.nextTriggerAt(
            interval = daily,
            lastRunAt = lastRun.toEpochMilli(),
            createdAt = createdAt.toEpochMilli(),
            now = now,
            configId = "cfg-1",
        ) shouldBe lastRun.plus(daily)
    }

    "a never-run config whose createdAt is already past due fires soon after now" {
        val createdAt = Instant.parse("2026-06-01T00:00:00Z")
        val now = Instant.parse("2026-07-15T12:00:00Z") // long past createdAt + 1 day
        NextTriggerCalculator.nextTriggerAt(
            interval = daily,
            lastRunAt = null,
            createdAt = createdAt.toEpochMilli(),
            now = now,
            configId = "cfg-fresh-overdue",
        ) shouldBe now.plus(NextTriggerCalculator.staggerFor("cfg-fresh-overdue"))
    }

    "an overdue config fires soon after now, offset by its deterministic stagger" {
        val lastRun = Instant.parse("2026-07-01T00:00:00Z")
        val now = Instant.parse("2026-07-15T12:00:00Z") // long past lastRun + 1 day
        NextTriggerCalculator.nextTriggerAt(
            interval = daily,
            lastRunAt = lastRun.toEpochMilli(),
            createdAt = 0L,
            now = now,
            configId = "cfg-overdue",
        ) shouldBe now.plus(NextTriggerCalculator.staggerFor("cfg-overdue"))
    }

    "the stagger is deterministic and bounded within the 0..8 minute window" {
        NextTriggerCalculator.staggerFor("cfg-1") shouldBe NextTriggerCalculator.staggerFor("cfg-1")
        listOf("a", "b", "c", "config-xyz", "", "another-one").forEach { id ->
            val stagger = NextTriggerCalculator.staggerFor(id)
            stagger shouldBeGreaterThanOrEqualTo Duration.ZERO
            stagger shouldBeLessThanOrEqualTo Duration.ofMinutes(8)
        }
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

    "intervals do not drift across a DST boundary (absolute-instant arithmetic)" {
        // Central-European spring-forward night (clocks jump 02:00 -> 03:00 local on 2026-03-29).
        // The next daily fire is exactly 24 h later on the absolute timeline regardless.
        val lastRun = Instant.parse("2026-03-28T23:30:00Z")
        val now = lastRun.plus(Duration.ofHours(1))
        val next = NextTriggerCalculator.nextTriggerAt(
            interval = daily,
            lastRunAt = lastRun.toEpochMilli(),
            createdAt = 0L,
            now = now,
            configId = "cfg-dst",
        )
        next shouldBe lastRun.plus(Duration.ofHours(24))
    }
})
