package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.infrastructure.room.dao.BackupRunDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity

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

    suspend fun maybeSchedule(
        config: BackupConfigEntity,
        backupRunDao: BackupRunDao,
        scheduler: IBackupScheduler,
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
        }
    }
}
