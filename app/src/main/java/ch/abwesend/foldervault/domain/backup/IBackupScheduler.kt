package ch.abwesend.foldervault.domain.backup

import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.NetworkPolicy

interface IBackupScheduler {
    fun scheduleOneTime(configId: String)
    fun schedulePeriodicIfNeeded(
        configId: String,
        schedule: BackupSchedule,
        networkPolicy: NetworkPolicy,
        globalDefault: BackupSchedule = BackupSchedule.DAILY,
    )
    fun cancel(configId: String)
}
