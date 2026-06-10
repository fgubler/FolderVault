package ch.abwesend.foldervault.infrastructure.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class BackupScheduler(private val context: Context) {
    private val log get() = logger
    private val workManager get() = WorkManager.getInstance(context)

    companion object {
        private const val HOURS_PER_DAY = 24L
        private const val DAYS_PER_WEEK = 7L
        private const val DAYS_PER_MONTH = 30L
        private const val BACKOFF_INITIAL_DELAY_SECONDS = 30L
    }

    /**
     * Registers (or updates) the periodic work for [config].
     * Does nothing if the resolved schedule is [BackupSchedule.MANUAL_ONLY].
     * [globalDefaultSchedule] is the DataStore default to fall back to when the config uses USE_GLOBAL_DEFAULT.
     */
    fun schedulePeriodicIfNeeded(
        config: BackupConfigEntity,
        globalDefaultSchedule: BackupSchedule = BackupSchedule.DAILY,
    ) {
        val resolvedSchedule = if (config.schedule == BackupSchedule.USE_GLOBAL_DEFAULT) {
            globalDefaultSchedule
        } else {
            config.schedule
        }
        if (resolvedSchedule == BackupSchedule.MANUAL_ONLY) {
            cancel(config.id)
            return
        }

        try {
            val repeatIntervalHours = when (resolvedSchedule) {
                BackupSchedule.DAILY -> HOURS_PER_DAY
                BackupSchedule.WEEKLY -> DAYS_PER_WEEK * HOURS_PER_DAY
                BackupSchedule.MONTHLY -> DAYS_PER_MONTH * HOURS_PER_DAY
                BackupSchedule.MANUAL_ONLY, BackupSchedule.USE_GLOBAL_DEFAULT -> return
            }

            val request = PeriodicWorkRequestBuilder<BackupWorker>(
                repeatInterval = repeatIntervalHours,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
            )
                .setConstraints(buildConstraints(config.networkPolicy))
                .setInputData(workDataOf(BackupWorker.KEY_CONFIG_ID to config.id))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()

            workManager.enqueueUniquePeriodicWork(
                BackupWorker.workName(config.id),
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            log.info("Scheduled periodic backup for config ${config.id} every $repeatIntervalHours h")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to schedule periodic backup for config ${config.id}", e)
        }
    }

    /** Enqueues a one-time "back up now" run; replaces any already-queued one-time run. */
    fun scheduleOneTime(configId: String) {
        try {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(workDataOf(BackupWorker.KEY_CONFIG_ID to configId))
                .build()
            workManager.enqueueUniqueWork(
                BackupWorker.workName(configId),
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

    /** Enqueues an expedited one-time continuation for in-progress initial sync. */
    fun scheduleExpedited(configId: String) {
        try {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(BackupWorker.KEY_CONFIG_ID to configId))
                .build()
            workManager.enqueueUniqueWork(
                BackupWorker.workName(configId),
                ExistingWorkPolicy.KEEP, // don't interrupt an already-running one
                request,
            )
            log.info("Enqueued expedited continuation backup for config $configId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to enqueue expedited backup for config $configId", e)
        }
    }

    /** Cancels all work (periodic + one-time) for this config. Call before deleting a config. */
    fun cancel(configId: String) {
        try {
            workManager.cancelUniqueWork(BackupWorker.workName(configId))
            log.info("Cancelled all backup work for config $configId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to cancel backup work for config $configId", e)
        }
    }

    private fun buildConstraints(networkPolicy: NetworkPolicy) = Constraints.Builder()
        .setRequiredNetworkType(
            if (networkPolicy == NetworkPolicy.WIFI_ONLY) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            }
        )
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()
}
