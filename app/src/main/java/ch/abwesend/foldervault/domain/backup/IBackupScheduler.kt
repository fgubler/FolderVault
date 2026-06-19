package ch.abwesend.foldervault.domain.backup

import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import kotlinx.coroutines.flow.Flow

interface IBackupScheduler {
    fun scheduleOneTime(configId: String)
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
