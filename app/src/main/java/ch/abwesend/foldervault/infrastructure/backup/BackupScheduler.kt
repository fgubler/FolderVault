package ch.abwesend.foldervault.infrastructure.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class BackupScheduler(
    private val context: Context,
    private val foregroundRunState: ForegroundRunState,
) : IBackupScheduler {
    private val log get() = logger
    private val workManager get() = WorkManager.getInstance(context)

    companion object {
        private const val HOURS_PER_DAY = 24L
        private const val DAYS_PER_WEEK = 7L
        private const val DAYS_PER_MONTH = 30L
        private const val BACKOFF_INITIAL_DELAY_SECONDS = 30L

        /**
         * Delay before a newly-scheduled periodic backup's first run — always one day, regardless
         * of the [BackupSchedule] cadence. Its only job is to lose the config-creation race with
         * the foreground-service auto-start (which fires within ~1 s), so the initial upload runs
         * on the service rather than an immediately-firing background worker; the exact value just
         * has to comfortably clear that window.
         */
        private const val FIRST_RUN_DELAY_HOURS = 24L
    }

    override fun schedulePeriodicIfNeeded(
        configId: String,
        schedule: BackupSchedule,
        networkPolicy: NetworkPolicy,
        requiresCharging: Boolean,
        globalDefault: BackupSchedule,
    ) {
        val resolved = if (schedule == BackupSchedule.USE_GLOBAL_DEFAULT) globalDefault else schedule
        if (resolved == BackupSchedule.MANUAL_ONLY) {
            cancelPeriodic(configId)
            return
        }
        try {
            val hours = when (resolved) {
                BackupSchedule.DAILY -> HOURS_PER_DAY
                BackupSchedule.WEEKLY -> DAYS_PER_WEEK * HOURS_PER_DAY
                BackupSchedule.MONTHLY -> DAYS_PER_MONTH * HOURS_PER_DAY
                BackupSchedule.MANUAL_ONLY, BackupSchedule.USE_GLOBAL_DEFAULT -> return
            }
            val request = PeriodicWorkRequestBuilder<BackupWorker>(hours, TimeUnit.HOURS)
                .setConstraints(buildConstraints(networkPolicy, requiresCharging))
                .setInputData(workDataOf(BackupWorker.KEY_CONFIG_ID to configId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
                // Defer the first occurrence by a fixed day (see FIRST_RUN_DELAY_HOURS). Without a
                // delay WorkManager runs a freshly-enqueued periodic request almost immediately, so
                // a config created with a periodic schedule fires a background run the instant it is
                // saved — that run grabs BackupRunner's per-config lock before the foreground-service
                // auto-start (routed via StartManualBackupUseCase) can, leaving the large initial
                // upload crawling across WorkManager's short windows instead of the service's
                // hours-long budget. Deferring hands the initial sync to the foreground service; the
                // periodic cadence takes over afterward. ExistingPeriodicWorkPolicy.UPDATE preserves
                // the already-scheduled run time on app restart, so the delay only ever applies to
                // the very first enqueue.
                .setInitialDelay(FIRST_RUN_DELAY_HOURS, TimeUnit.HOURS)
                .build()
            workManager.enqueueUniquePeriodicWork(
                BackupWorker.workName(configId),
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to schedule periodic backup for config $configId", e)
        }
    }

    /**
     * Enqueues a one-time "back up now" run; replaces any already-queued one-time run.
     * Uses a dedicated unique-work name ([BackupWorker.oneTimeWorkName]) so the
     * [ExistingWorkPolicy.REPLACE] here can never cancel the config's periodic schedule
     * (which lives under [BackupWorker.workName] in the same WorkManager unique-name namespace).
     *
     * A time-budget continuation ([asContinuation]) uses [ExistingWorkPolicy.APPEND_OR_REPLACE]
     * instead: it is enqueued from *within* the still-running worker that holds this unique name,
     * and REPLACE would cancel that worker before it could report its own result.
     */
    override fun scheduleOneTime(
        configId: String,
        networkPolicy: NetworkPolicy,
        requiresCharging: Boolean,
        asContinuation: Boolean,
    ) {
        try {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(buildConstraints(networkPolicy, requiresCharging))
                .setInputData(workDataOf(BackupWorker.KEY_CONFIG_ID to configId))
                .build()
            workManager.enqueueUniqueWork(
                BackupWorker.oneTimeWorkName(configId),
                if (asContinuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
                request,
            )
            log.info("Enqueued one-time backup for config $configId (asContinuation=$asContinuation)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to enqueue one-time backup for config $configId", e)
        }
    }

    /**
     * Charging-only fallback: forces [Constraints.setRequiresCharging] and uses a dedicated
     * unique-work name so it never displaces the config's periodic / one-time runs. The
     * [BackupWorker.KEY_IS_CHARGING_FALLBACK] flag lets a continuation of this run re-enqueue
     * itself as a fallback (keeping the charging constraint) rather than a plain one-time run.
     *
     * A plain trigger pre-checks for pending fallback work and no-ops (returning `false`) when
     * one exists; the enqueue itself still uses [ExistingWorkPolicy.KEEP] so a lost race between
     * two concurrent triggers stays harmless. A continuation ([asContinuation]) skips the
     * pre-check and uses [ExistingWorkPolicy.APPEND_OR_REPLACE]: it is enqueued from *within*
     * the still-running fallback worker, which holds the unique name — KEEP would swallow it,
     * REPLACE would cancel the calling worker itself.
     */
    override suspend fun scheduleChargingFallback(
        configId: String,
        networkPolicy: NetworkPolicy,
        asContinuation: Boolean,
    ): Boolean = try {
        if (!asContinuation && hasPendingChargingFallback(configId)) {
            log.info("Charging-only fallback for config $configId already pending — nothing to enqueue")
            false
        } else {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(buildConstraints(networkPolicy, requiresCharging = true))
                .setInputData(
                    workDataOf(
                        BackupWorker.KEY_CONFIG_ID to configId,
                        BackupWorker.KEY_IS_CHARGING_FALLBACK to true,
                    )
                )
                .build()
            workManager.enqueueUniqueWork(
                BackupWorker.chargingFallbackWorkName(configId),
                if (asContinuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
            log.info("Enqueued charging-only fallback backup for config $configId")
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("Failed to enqueue charging-only fallback for config $configId", e)
        false
    }

    /** Whether unfinished (enqueued, running, or blocked) fallback work exists for [configId]. */
    private suspend fun hasPendingChargingFallback(configId: String): Boolean =
        workManager.getWorkInfosForUniqueWorkFlow(BackupWorker.chargingFallbackWorkName(configId))
            .first()
            .any { !it.state.isFinished }

    /**
     * Cancels only the config's pending *one-time* slot (manual runs and time-budget
     * continuations share it); the periodic schedule and the charging-only fallback keep
     * their own unique names and stay untouched.
     */
    override fun cancelOneTime(configId: String) {
        try {
            workManager.cancelUniqueWork(BackupWorker.oneTimeWorkName(configId))
            log.info("Cancelled one-time backup work for config $configId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to cancel one-time backup work for config $configId", e)
        }
    }

    /**
     * Cancels all work (periodic + one-time + charging-only fallback) for this config.
     * Call before deleting or when pausing a config — NOT when a schedule merely resolves to
     * manual-only (that must leave pending one-time / fallback work alone, see [cancelPeriodic]).
     */
    override fun cancel(configId: String) {
        try {
            workManager.cancelUniqueWork(BackupWorker.workName(configId))
            workManager.cancelUniqueWork(BackupWorker.oneTimeWorkName(configId))
            workManager.cancelUniqueWork(BackupWorker.chargingFallbackWorkName(configId))
            log.info("Cancelled all backup work for config $configId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to cancel backup work for config $configId", e)
        }
    }

    /**
     * Cancels only the config's *periodic* schedule, leaving pending one-time runs, time-budget
     * continuations, and charging-only fallbacks untouched. Used when the resolved schedule is
     * manual-only: [schedulePeriodicIfNeeded] manages the periodic slot exclusively, and is also
     * called blindly for every config on app start — a full [cancel] there would silently destroy
     * pending manual work.
     */
    private fun cancelPeriodic(configId: String) {
        try {
            workManager.cancelUniqueWork(BackupWorker.workName(configId))
            log.info("Cancelled periodic backup work for config $configId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to cancel periodic backup work for config $configId", e)
        }
    }

    /** Cancels every piece of scheduled backup work — all work this app enqueues is backup work. */
    override fun cancelAll() {
        try {
            workManager.cancelAllWork()
            log.info("Cancelled all scheduled backup work")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to cancel all scheduled backup work", e)
        }
    }

    /**
     * True while any host is executing a run for this config — a WorkManager worker (periodic,
     * one-time, or charging fallback) or the foreground service ([ForegroundRunState]).
     */
    override fun observeIsRunning(configId: String): Flow<Boolean> {
        val periodic = workManager.getWorkInfosForUniqueWorkFlow(BackupWorker.workName(configId))
        val oneTime = workManager.getWorkInfosForUniqueWorkFlow(BackupWorker.oneTimeWorkName(configId))
        val fallback = workManager.getWorkInfosForUniqueWorkFlow(BackupWorker.chargingFallbackWorkName(configId))
        val foreground = foregroundRunState.observeIsRunning(configId)
        return combine(periodic, oneTime, fallback, foreground) { periodicInfos, oneTimeInfos, fallbackInfos, fg ->
            fg || (periodicInfos + oneTimeInfos + fallbackInfos).any { it.state == WorkInfo.State.RUNNING }
        }.distinctUntilChanged()
    }

    private fun buildConstraints(networkPolicy: NetworkPolicy, requiresCharging: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(
            if (networkPolicy == NetworkPolicy.WIFI_ONLY) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            }
        )
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .setRequiresCharging(requiresCharging)
        .build()
}
