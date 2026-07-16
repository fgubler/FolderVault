package ch.abwesend.foldervault.domain.backup

import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import kotlinx.coroutines.flow.Flow

interface IBackupScheduler {
    /**
     * Enqueues a one-time backup run for [configId]. [networkPolicy] is applied as a
     * WorkManager constraint for this run only — pass [NetworkPolicy.ANY] to allow the
     * user to override a config's Wi-Fi-only setting for a single manual run.
     * When [requiresCharging] is true, WorkManager holds the run until the device is charging.
     *
     * A plain invocation replaces any already-queued one-time run
     * ([androidx.work.ExistingWorkPolicy.REPLACE]). A time-budget continuation
     * ([asContinuation]) instead uses [androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE],
     * because the enqueueing worker may be either kind: a continuation enqueued from a
     * still-running *one-time* worker holds this very unique name, so REPLACE would cancel
     * that worker mid-completion; one enqueued from a *periodic* worker (which holds the
     * separate periodic name) simply appends to whatever pending one-time chain exists, or
     * starts a fresh one. APPEND_OR_REPLACE queues the continuation to run after the current
     * run finishes in both cases.
     *
     * [forceInline] makes the enqueued worker run the backup inline instead of trampolining to the
     * foreground service. It is set only by the budget-exhaustion degrade paths (the service /
     * alarm receiver could not enter the foreground); otherwise the degraded run would trampoline
     * straight back and loop.
     */
    fun scheduleOneTime(
        configId: String,
        networkPolicy: NetworkPolicy,
        requiresCharging: Boolean,
        asContinuation: Boolean = false,
        forceInline: Boolean = false,
    )

    /**
     * (Re-)registers the config's *periodic* schedule, or — when the resolved schedule is
     * `MANUAL_ONLY` — cancels the periodic slot. Only the periodic unique-work name is touched:
     * pending one-time runs, time-budget continuations, and charging-only fallbacks are left
     * alone, so this method is safe to call blindly for every config (e.g. as an app-start
     * safety net). Full cleanup on pause / delete goes through [cancel].
     *
     * A freshly enqueued schedule defers its first occurrence by a short fixed delay so the
     * config-creation auto-start wins the per-config run lock for the initial upload; the delay
     * is small enough that re-enqueueing after a pause still runs the overdue backup promptly.
     */
    fun schedulePeriodicIfNeeded(
        configId: String,
        schedule: BackupSchedule,
        networkPolicy: NetworkPolicy,
        requiresCharging: Boolean,
        globalDefault: BackupSchedule = BackupSchedule.DAILY,
    )

    /**
     * Enqueues a one-off charging-only run under a dedicated unique-work name that never
     * displaces or collides with the config's periodic / one-time schedule. Called when a
     * config with `requiresCharging = false` has been cancelled several times in a row so
     * that at least one attempt runs uninterrupted — while the periodic schedule keeps
     * firing on its normal (non-charging) cadence.
     *
     * A plain trigger no-ops when a fallback for this config is already pending
     * ([androidx.work.ExistingWorkPolicy.KEEP], plus a pre-check so the no-op is reported to
     * the caller). A time-budget continuation ([asContinuation]) instead uses
     * [androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE]: it is enqueued from *within* the
     * still-running fallback worker, which holds the unique name — so KEEP would silently
     * swallow the continuation, and REPLACE would cancel the calling worker itself.
     * APPEND_OR_REPLACE queues the continuation to run after the current run finishes.
     *
     * @return `true` if a new fallback run was enqueued, `false` if one was already pending
     *   (or the enqueue failed) — so callers can avoid reporting a "fallback scheduled" that
     *   was actually a no-op.
     */
    suspend fun scheduleChargingFallback(
        configId: String,
        networkPolicy: NetworkPolicy,
        asContinuation: Boolean = false,
    ): Boolean

    /**
     * Cancels only the config's pending *one-time* run (or continuation), leaving the periodic
     * schedule and any charging-only fallback untouched. Used by the foreground service when it
     * takes over a run: a one-time run left in the queue would duplicate the foreground run the
     * moment its constraints are met.
     */
    fun cancelOneTime(configId: String)

    /**
     * Cancels *all* scheduled work for [configId]: the periodic schedule, any pending one-time
     * run or continuation, and any pending charging-only fallback. For pause / delete — not for
     * switching a schedule to manual-only (see [schedulePeriodicIfNeeded]).
     */
    fun cancel(configId: String)

    /**
     * Cancels every piece of scheduled backup work for all configs. Used when the local
     * database is reset: the configs the scheduled work belongs to no longer exist.
     */
    fun cancelAll()

    /**
     * (Re-)registers the single app-global watchdog: a cheap periodic worker that catches configs
     * whose normal periodic schedule WorkManager has failed to fire (OEM battery-killer, Doze) and
     * enqueues a one-time catch-up run for them. Idempotent — uses
     * [androidx.work.ExistingPeriodicWorkPolicy.KEEP] so repeated calls (every app start) never
     * reset the running cadence. The watchdog itself never starts a foreground service; a
     * background worker cannot, and it only needs to enqueue ordinary WorkManager runs.
     */
    fun ensureWatchdogScheduled()

    /**
     * Emits `true` while a backup for [configId] is enqueued or actively running, `false` otherwise.
     * Covers both hosts: WorkManager runs (survives process death, reflects retries) and
     * foreground-service runs of the initial upload.
     */
    fun observeIsRunning(configId: String): Flow<Boolean>
}
