package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupRunDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.room.entity.BackupMessageEntity

/**
 * Decides whether to enqueue a charging-only fallback run for a config that just had a
 * cancelled backup. Called from [BackupRunner]'s cancellation path — extracted into its own
 * file so the streak-detection logic is testable without spinning up the full runner.
 *
 * The fallback fires only when [BackupConfigEntity.requiresCharging] is `false` AND the most
 * recent [CANCELLATION_STREAK_THRESHOLD] runs (including the one that just cancelled) were all
 * cancelled. Configs already pinned to charging need no additional fallback — their periodic
 * schedule already carries that constraint.
 */
internal object ChargingFallbackTrigger {

    /**
     * Number of consecutive cancellations that trigger a single charging-only fallback run.
     * Any success in between resets the streak.
     */
    const val CANCELLATION_STREAK_THRESHOLD = 3

    /**
     * @param runId the run that just cancelled, used to coalesce the info message so a single
     *   triggering run never produces more than one "fallback scheduled" row.
     * @param backupMessageDao sink for the audit message written when the fallback fires, so the
     *   mechanism is visible on the backup detail screen's message log.
     */
    suspend fun maybeSchedule(
        config: BackupConfigEntity,
        backupRunDao: BackupRunDao,
        scheduler: IBackupScheduler,
        backupMessageDao: BackupMessageDao,
        runId: String,
    ) {
        if (config.requiresCharging) return
        val recent = backupRunDao.getRecentStatuses(config.id, CANCELLATION_STREAK_THRESHOLD)
        val streakReached = recent.size >= CANCELLATION_STREAK_THRESHOLD &&
            recent.all { it == BackupRunStatus.CANCELLED }
        if (streakReached) {
            logger.info(
                "Cancellation streak of $CANCELLATION_STREAK_THRESHOLD reached for config ${config.id}; " +
                    "enqueueing charging-only fallback",
            )
            scheduler.scheduleChargingFallback(config.id, config.networkPolicy)
            backupMessageDao.coalesceInsert(
                BackupMessageEntity(
                    backupConfigId = config.id,
                    runId = runId,
                    timestamp = System.currentTimeMillis(),
                    severity = MessageSeverity.INFO,
                    type = MessageType.CHARGING_FALLBACK_SCHEDULED,
                    messageText = null,
                    formatArgs = emptyList(),
                    relativePath = null,
                    readAt = null,
                ),
            )
        }
    }
}
