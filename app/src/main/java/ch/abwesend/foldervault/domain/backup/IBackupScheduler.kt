package ch.abwesend.foldervault.domain.backup

import ch.abwesend.foldervault.domain.model.BackupSchedule
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import kotlinx.coroutines.flow.Flow

interface IBackupScheduler {
    /**
     * Enqueues a one-time backup run for [configId]. [networkPolicy] is applied as a
     * WorkManager constraint for this run only — pass [NetworkPolicy.ANY] to allow the
     * user to override a config's Wi-Fi-only setting for a single manual run.
     * When [requiresCharging] is true, WorkManager holds the run until the device is charging.
     */
    fun scheduleOneTime(configId: String, networkPolicy: NetworkPolicy, requiresCharging: Boolean)

    fun schedulePeriodicIfNeeded(
        configId: String,
        schedule: BackupSchedule,
        networkPolicy: NetworkPolicy,
        requiresCharging: Boolean,
        globalDefault: BackupSchedule = BackupSchedule.DAILY,
    )

    /**
     * Enqueues a one-off charging-only run under a dedicated unique-work name that never
     * displaces or collides with the config's periodic / one-time schedule. Called when a
     * config with `requiresCharging = false` has been cancelled several times in a row so
     * that at least one attempt runs uninterrupted — while the periodic schedule keeps
     * firing on its normal (non-charging) cadence.
     *
     * Uses [androidx.work.ExistingWorkPolicy.KEEP], so repeated invocations while an earlier
     * fallback is still pending are no-ops.
     */
    fun scheduleChargingFallback(configId: String, networkPolicy: NetworkPolicy)

    fun cancel(configId: String)

    /**
     * Emits `true` while a backup for [configId] is enqueued or actively running, `false` otherwise.
     * Backed by WorkManager — survives process death and reflects retries.
     */
    fun observeIsRunning(configId: String): Flow<Boolean>
}
