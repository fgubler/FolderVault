package ch.abwesend.foldervault.domain.backup

import ch.abwesend.foldervault.domain.model.BackupSchedule
import java.time.Duration
import java.time.Instant
import kotlin.math.absoluteValue

/**
 * Computes when a config's next scheduled backup should fire, for the exact-alarm host.
 *
 * Interval-based: the next fire time is one full interval after the last run — or after the
 * creation time, if it never ran. All arithmetic is on absolute instants ([Duration] is exact
 * elapsed time), so an interval never drifts with DST: a "daily" backup is always 24 h after the
 * previous one, even across a spring-forward/fall-back boundary.
 *
 * An overdue config (its computed time already passed — e.g. the device was switched off) fires
 * shortly after [now][nextTriggerAt], offset by a small deterministic per-config stagger so that
 * several overdue configs do not all wake the device at the same instant.
 */
object NextTriggerCalculator {
    private const val DAYS_PER_WEEK = 7L
    private const val DAYS_PER_MONTH = 30L

    /** Number of distinct stagger slots; a config maps to one of them by its id hash. */
    private const val STAGGER_BUCKETS = 5
    private val STAGGER_STEP: Duration = Duration.ofMinutes(2)

    /** The fixed interval of a *resolved* periodic schedule; `null` for non-periodic schedules. */
    fun periodicInterval(schedule: BackupSchedule): Duration? = when (schedule) {
        BackupSchedule.DAILY -> Duration.ofDays(1)
        BackupSchedule.WEEKLY -> Duration.ofDays(DAYS_PER_WEEK)
        BackupSchedule.MONTHLY -> Duration.ofDays(DAYS_PER_MONTH)
        BackupSchedule.MANUAL_ONLY, BackupSchedule.USE_GLOBAL_DEFAULT -> null
    }

    /**
     * @param interval the resolved periodic interval (see [periodicInterval]).
     * @param lastRunAt epoch-millis of the last run, or `null` if the config never ran.
     * @param createdAt epoch-millis the config was created — the anchor for a never-run config.
     * @param now the reference instant to compare against.
     * @param configId used only to derive the deterministic overdue stagger.
     */
    fun nextTriggerAt(
        interval: Duration,
        lastRunAt: Long?,
        createdAt: Long,
        now: Instant,
        configId: String,
    ): Instant {
        val anchor = Instant.ofEpochMilli(lastRunAt ?: createdAt)
        val scheduled = anchor.plus(interval)
        return if (scheduled.isAfter(now)) {
            scheduled
        } else {
            now.plus(staggerFor(configId))
        }
    }

    /** Deterministic 0/2/4/6/8-minute offset derived from [configId], spreading overdue configs. */
    fun staggerFor(configId: String): Duration =
        STAGGER_STEP.multipliedBy(((configId.hashCode() % STAGGER_BUCKETS).absoluteValue).toLong())

    /**
     * Whether the config is overdue by more than one full interval past its expected next trigger —
     * the signal the watchdog uses to enqueue a fallback run for a schedule WorkManager has failed
     * to fire (OEM battery-killer, Doze deferral). The expected trigger is one interval after the
     * anchor ([lastRunAt] ?: [createdAt]); requiring a *further* full interval of slack before the
     * watchdog steps in keeps it from racing WorkManager's own periodic firing on a healthy device.
     *
     * @param interval the resolved periodic interval (see [periodicInterval]).
     * @param lastRunAt epoch-millis of the last run, or `null` if the config never ran.
     * @param createdAt epoch-millis the config was created — the anchor for a never-run config.
     * @param now the reference instant to compare against.
     */
    fun isOverdue(interval: Duration, lastRunAt: Long?, createdAt: Long, now: Instant): Boolean {
        val anchor = Instant.ofEpochMilli(lastRunAt ?: createdAt)
        val overdueThreshold = anchor.plus(interval).plus(interval)
        return now.isAfter(overdueThreshold)
    }
}
