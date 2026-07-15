package ch.abwesend.foldervault.infrastructure.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.backup.NextTriggerCalculator
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.domain.util.injectAnywhere
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.room.entity.BackupMessageEntity
import kotlinx.coroutines.flow.first
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * App-global backstop for the periodic schedule. WorkManager remains the single scheduler, but an
 * OEM battery-killer or an aggressive Doze can defer a config's periodic run for far longer than
 * its interval. This worker runs on its own daily cadence, finds every non-paused config that is
 * overdue by more than a full extra interval ([NextTriggerCalculator.isOverdue]), and enqueues an
 * ordinary one-time catch-up run for it plus a [MessageType.WATCHDOG_TRIGGERED_RUN] breadcrumb.
 *
 * It never starts the foreground service — a background worker cannot, and a plain one-time run is
 * all that is needed to unstick the schedule (that run may itself trampoline to the service if the
 * config opted in and the delta is large). Registered once from `FolderVaultApp` via
 * [IBackupScheduler.ensureWatchdogScheduled].
 */
class BackupWatchdogWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    private val backupConfigDao: BackupConfigDao by injectAnywhere()
    private val backupMessageDao: BackupMessageDao by injectAnywhere()
    private val scheduler: IBackupScheduler by injectAnywhere()
    private val settingsRepository: IAppSettingsRepository by injectAnywhere()

    companion object {
        /** Unique-work name for the single app-global watchdog schedule. */
        const val WORK_NAME = "foldervault_backup_watchdog"
    }

    override suspend fun doWork(): Result = try {
        val globalDefault = settingsRepository.settings.first().defaultSchedule
        val now = Instant.now()
        val overdue = backupConfigDao.getAll().first()
            .filterNot { it.isPaused }
            .filter { isConfigOverdue(it, globalDefault, now) }

        overdue.forEach { triggerFallback(it) }
        logger.info("Backup watchdog ran; enqueued ${overdue.size} overdue catch-up run(s)")
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A transient database/settings read failure — retry with WorkManager backoff rather than
        // silently skipping a whole day's check.
        logger.warning("Backup watchdog failed to run its overdue check; will retry", e)
        Result.retry()
    }

    /**
     * Whether [config] should be caught up by the watchdog: it has a resolved periodic interval
     * (manual-only configs never trigger) and is past the overdue threshold for that interval.
     */
    private fun isConfigOverdue(config: BackupConfigEntity, globalDefault: BackupSchedule, now: Instant): Boolean {
        val resolved = if (config.schedule == BackupSchedule.USE_GLOBAL_DEFAULT) globalDefault else config.schedule
        val interval = NextTriggerCalculator.periodicInterval(resolved)
        return interval != null &&
            NextTriggerCalculator.isOverdue(interval, config.lastRunAt, config.createdAt, now)
    }

    /** Enqueues the catch-up run for an overdue config and records the (deduped) breadcrumb. */
    private suspend fun triggerFallback(config: BackupConfigEntity) {
        logger.info("Watchdog found config ${config.id} overdue; enqueueing a catch-up run")
        scheduler.scheduleOneTime(config.id, config.networkPolicy, config.requiresCharging)
        emitWatchdogMessage(config.id)
    }

    /**
     * Records a single [MessageType.WATCHDOG_TRIGGERED_RUN] per config. Guarded against repetition:
     * while a config stays overdue (e.g. its catch-up run keeps being killed too) the daily watchdog
     * would otherwise stack an identical breadcrumb every day. A null runId means [coalesceInsert]
     * cannot coalesce, so the presence check does the throttling instead.
     */
    private suspend fun emitWatchdogMessage(configId: String) {
        val alreadyPresent = backupMessageDao.getCountForType(configId, MessageType.WATCHDOG_TRIGGERED_RUN) > 0
        if (!alreadyPresent) {
            backupMessageDao.coalesceInsert(
                BackupMessageEntity(
                    backupConfigId = configId,
                    runId = null,
                    timestamp = System.currentTimeMillis(),
                    severity = MessageSeverity.WARNING,
                    type = MessageType.WATCHDOG_TRIGGERED_RUN,
                    messageText = null,
                    formatArgs = emptyList(),
                    relativePath = null,
                    readAt = null,
                ),
            )
        }
    }
}
