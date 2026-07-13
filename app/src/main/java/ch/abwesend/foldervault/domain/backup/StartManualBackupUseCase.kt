package ch.abwesend.foldervault.domain.backup

import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.NetworkPolicy

/**
 * Routes a user-initiated ("back up now" / auto-start after creation) backup run to the right
 * host: the foreground service while the initial sync is still incomplete (long run window, so
 * a large first upload finishes in one go), WorkManager once the backup is established (short
 * incremental runs don't need a service).
 *
 * Scheduled background runs never pass through here — they stay on WorkManager.
 */
class StartManualBackupUseCase(
    private val scheduler: IBackupScheduler,
    private val foregroundLauncher: IForegroundBackupLauncher,
) {
    /**
     * [networkPolicy] and [requiresCharging] are the *effective* values after the UI's override
     * prompts, not necessarily the config's own settings.
     */
    fun start(config: BackupConfig, networkPolicy: NetworkPolicy, requiresCharging: Boolean) {
        if (needsForegroundService(config.lastRunStatus, config.totalFilesDiscovered)) {
            foregroundLauncher.start(config.id, networkPolicy, requiresCharging)
        } else {
            scheduler.scheduleOneTime(config.id, networkPolicy, requiresCharging)
        }
    }

    companion object {
        /**
         * The initial sync is incomplete when the config never ran ([BackupRunStatus.IDLE]),
         * is known to be mid-sync, or still carries cross-run progress counters — the counters
         * only reset to zero when a run completes normally, so a non-zero
         * [totalFilesDiscovered] catches a sync that was interrupted by a CANCELLED or FAILED
         * run and would otherwise fall back into the short WorkManager windows.
         */
        fun needsForegroundService(lastRunStatus: BackupRunStatus, totalFilesDiscovered: Int): Boolean =
            lastRunStatus == BackupRunStatus.IDLE ||
                lastRunStatus == BackupRunStatus.INITIAL_SYNC_IN_PROGRESS ||
                totalFilesDiscovered > 0
    }
}
