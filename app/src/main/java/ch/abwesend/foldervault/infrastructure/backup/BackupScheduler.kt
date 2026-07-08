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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class BackupScheduler(private val context: Context) : IBackupScheduler {
    private val log get() = logger
    private val workManager get() = WorkManager.getInstance(context)

    companion object {
        private const val HOURS_PER_DAY = 24L
        private const val DAYS_PER_WEEK = 7L
        private const val DAYS_PER_MONTH = 30L
        private const val BACKOFF_INITIAL_DELAY_SECONDS = 30L
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
            cancel(configId)
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
     */
    override fun scheduleOneTime(configId: String, networkPolicy: NetworkPolicy, requiresCharging: Boolean) {
        try {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(buildConstraints(networkPolicy, requiresCharging))
                .setInputData(workDataOf(BackupWorker.KEY_CONFIG_ID to configId))
                .build()
            workManager.enqueueUniqueWork(
                BackupWorker.oneTimeWorkName(configId),
                ExistingWorkPolicy.REPLACE,
                request,
            )
            log.info("Enqueued one-time backup for config $configId")
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
     * [ExistingWorkPolicy.KEEP] means we no-op if a fallback for this config is still pending —
     * except when [replaceExisting] is true (a continuation from within the running fallback
     * worker), which must use [ExistingWorkPolicy.REPLACE] or KEEP would swallow it.
     */
    override fun scheduleChargingFallback(configId: String, networkPolicy: NetworkPolicy, replaceExisting: Boolean) {
        try {
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
                if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
            log.info("Enqueued charging-only fallback backup for config $configId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to enqueue charging-only fallback for config $configId", e)
        }
    }

    /**
     * Cancels all work (periodic + one-time + charging-only fallback) for this config.
     * Call before deleting a config.
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

    override fun observeIsRunning(configId: String): Flow<Boolean> {
        val periodic = workManager.getWorkInfosForUniqueWorkFlow(BackupWorker.workName(configId))
        val oneTime = workManager.getWorkInfosForUniqueWorkFlow(BackupWorker.oneTimeWorkName(configId))
        val fallback = workManager.getWorkInfosForUniqueWorkFlow(BackupWorker.chargingFallbackWorkName(configId))
        return combine(periodic, oneTime, fallback) { periodicInfos, oneTimeInfos, fallbackInfos ->
            (periodicInfos + oneTimeInfos + fallbackInfos).any { it.state == WorkInfo.State.RUNNING }
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
