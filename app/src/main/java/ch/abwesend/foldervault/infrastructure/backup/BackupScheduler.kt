package ch.abwesend.foldervault.infrastructure.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.NetworkPolicy
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
                .setConstraints(buildConstraints(networkPolicy))
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

    /** Enqueues a one-time "back up now" run; replaces any already-queued one-time run. */
    override fun scheduleOneTime(configId: String) {
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

    /** Cancels all work (periodic + one-time) for this config. Call before deleting a config. */
    override fun cancel(configId: String) {
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
