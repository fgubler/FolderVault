package ch.abwesend.foldervault.domain.backup

import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import kotlinx.coroutines.flow.Flow

interface IBackupScheduler {
    /**
     * Enqueues a one-time backup run for [configId]. [networkPolicy] is applied as a
     * WorkManager constraint for this run only — pass [NetworkPolicy.ANY] to allow the
     * user to override a config's Wi-Fi-only setting for a single manual run.
     */
    fun scheduleOneTime(configId: String, networkPolicy: NetworkPolicy)
    fun schedulePeriodicIfNeeded(
        configId: String,
        schedule: BackupSchedule,
        networkPolicy: NetworkPolicy,
        globalDefault: BackupSchedule = BackupSchedule.DAILY,
    )
    fun cancel(configId: String)

    /**
     * Emits `true` while a backup for [configId] is enqueued or actively running, `false` otherwise.
     * Backed by WorkManager — survives process death and reflects retries.
     */
    fun observeIsRunning(configId: String): Flow<Boolean>
}
