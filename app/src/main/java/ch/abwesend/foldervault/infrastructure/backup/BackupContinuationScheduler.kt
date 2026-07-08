package ch.abwesend.foldervault.infrastructure.backup

import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.model.NetworkPolicy

/**
 * Decides how a run that hit its time budget re-enqueues its continuation. Extracted from
 * [BackupWorker] so the "which scheduler call does a continuation make" decision is unit-testable
 * without spinning up the full worker.
 */
internal object BackupContinuationScheduler {

    /**
     * Re-enqueues the continuation of a run that made progress but ran out of time.
     *
     * A charging-only fallback run belongs to a config with `requiresCharging = false`, so routing
     * its continuation through [IBackupScheduler.scheduleOneTime] with [requiresCharging] would
     * drop the charging constraint AND move the run off the fallback unique name — exactly where
     * the fallback matters (large backlogs cancel repeatedly). Such a run therefore re-enqueues as
     * a fallback, which forces the charging constraint and keeps the dedicated name. All other
     * runs re-enqueue as a normal one-time run, preserving the config's own charging preference.
     *
     * Both paths pass `asContinuation = true` so the scheduler appends the continuation after the
     * still-running worker (which holds the target unique name) instead of replacing — and thereby
     * cancelling — it.
     *
     * [networkPolicy] is reused so a Wi-Fi-only backup never spills over onto mobile data on the
     * continuation run.
     */
    suspend fun scheduleContinuation(
        scheduler: IBackupScheduler,
        configId: String,
        networkPolicy: NetworkPolicy,
        requiresCharging: Boolean,
        isChargingFallback: Boolean,
    ) {
        if (isChargingFallback) {
            scheduler.scheduleChargingFallback(configId, networkPolicy, asContinuation = true)
        } else {
            scheduler.scheduleOneTime(configId, networkPolicy, requiresCharging, asContinuation = true)
        }
    }
}
