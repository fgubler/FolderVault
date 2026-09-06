package ch.abwesend.foldervault.infrastructure.restore

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.restore.IRestoreRunCoordinator
import ch.abwesend.foldervault.domain.restore.RestoreRunState
import ch.abwesend.foldervault.domain.result.rethrowCancellation
import ch.abwesend.foldervault.domain.util.injectAnywhere
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hosts a whole-folder restore as a dataSync foreground service, so leaving the app cannot have
 * the run killed half-way through writing decrypted files.
 *
 * Deliberately *not* a second run kind inside `BackupForegroundService`. That service is built
 * around backup configs — a per-config `BackupRunner` lock, `ForegroundRunState` keyed by config
 * id, `BackupRunControl` time budgets, a multi-config queue and WorkManager continuation handover.
 * A restore has none of those, and crucially no background continuation: it depends on
 * session-scoped picked tree uris and a password held only in memory, so a worker could never
 * resume it. Sharing the class would mean branching on run kind in the app's most safety-critical
 * service for no shared logic.
 *
 * What the two services *do* share is Android 15's cumulative dataSync time budget, so a
 * `startForeground` here can legitimately be refused when backups have used it up. The start is
 * therefore guarded and the run handed straight back to the caller (see [IRestoreRunCoordinator]),
 * which then executes it in the ViewModel scope and warns the user to keep the app open.
 *
 * Started only from the visible restore screen — a user-initiated start is exempt from Android
 * 12+'s background-FGS restriction, so unlike the backup path no exact-alarm trampoline is needed.
 * [START_NOT_STICKY] because a restarted service would have no staged request to run.
 */
class RestoreForegroundService : Service() {
    private val log get() = logger

    private val coordinator: IRestoreRunCoordinator by injectAnywhere()
    private val notificationManager: RestoreNotificationManager by injectAnywhere()
    private val dispatchers: IDispatchers by injectAnywhere()

    /** Lazy because [dispatchers] is only injectable once the service instance exists. */
    private val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + dispatchers.default) }

    private var runJob: Job? = null

    companion object {
        const val ACTION_STOP = "ch.abwesend.foldervault.action.STOP_RESTORE"
        private const val STOP_REQUEST_CODE = 2101

        /** How long [onTimeout] waits for the cooperative stop before tearing the service down. */
        private const val TIMEOUT_DRAIN_MS = 4_000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            log.info("User stopped the restore from its notification")
            coordinator.requestStop()
        } else {
            startRun()
        }
        return START_NOT_STICKY
    }

    /**
     * Claims the staged run, promotes to the foreground, and executes it.
     *
     * Claiming *before* promoting matters: if the promotion is refused the claim is handed straight
     * back, so the screen's fallback picks the run up instead of it being lost. Continuing here
     * without foreground status is not an option — a background service is killable at any moment,
     * which is the very thing this service exists to prevent.
     */
    private fun startRun() {
        val claimed = coordinator.tryClaim()
        when {
            !claimed -> {
                // Nothing staged, or the screen's fallback already took the run.
                log.info("No restore to take over — stopping the restore service again")
                stopSelf()
            }
            !enterForeground() -> {
                coordinator.releaseClaim()
                stopSelf()
            }
            else -> {
                coordinator.markHostedInForegroundService()
                observeProgress()
                runJob = scope.launch {
                    try {
                        coordinator.runClaimed()
                    } finally {
                        stopSelf()
                    }
                }
            }
        }
    }

    /**
     * Calls `startForeground` with the initial progress notification.
     *
     * @return `false` when the OS refused the promotion — most plausibly
     *   `ForegroundServiceStartNotAllowedException` because the dataSync budget shared with
     *   `BackupForegroundService` is exhausted. The caller then hands the claim back.
     */
    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            RestoreNotificationManager.PROGRESS_NOTIFICATION_ID,
            notificationManager.buildProgressNotification(progress = null, stopIntent = stopPendingIntent()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        true
    } catch (e: Exception) {
        // `startForeground` never suspends, so this only ever sees a real refusal — but the
        // project-wide rule (and CancellationRethrowArchitectureTest) is that no broad catch may
        // swallow a cancellation.
        e.rethrowCancellation()
        log.warning("Could not promote the restore service to the foreground — restoring in-app instead", e)
        false
    }

    /** Mirrors the coordinator's progress into the ongoing notification until the run ends. */
    private fun observeProgress() {
        scope.launch {
            coordinator.state
                .takeWhile { it is RestoreRunState.Running }
                .collect { state ->
                    val progress = (state as? RestoreRunState.Running)?.progress
                    notificationManager.updateProgressNotification(progress, stopPendingIntent())
                }
        }
    }

    /**
     * Android 15+ dataSync time limit. The budget is *cumulative across the app's dataSync
     * services*, so this service shares it with `BackupForegroundService` and can hit the wall
     * even on a short restore that follows a long backup day. When it does, the platform calls
     * this and the service must stop itself within seconds — otherwise the app is killed with
     * `ForegroundServiceDidNotStopInTimeException`.
     *
     * Stopping goes through the cooperative [IRestoreRunCoordinator.requestStop] so the run ends
     * at the next file boundary and still reports its partial counts as
     * [ch.abwesend.foldervault.domain.restore.RestoreResult.Cancelled]. A run that cannot drain in
     * [TIMEOUT_DRAIN_MS] is abandoned — the service must go regardless, and unlike a backup there
     * is no continuation to schedule: a restore depends on session-scoped picked tree uris and an
     * in-memory password, so no worker could resume it. The user restarts it from the screen.
     *
     * Must be the two-argument overload: the dataSync time-limit path calls only
     * `onTimeout(startId, fgsType)`; the one-argument [Service.onTimeout] is invoked solely for
     * `shortService` timeouts and its two-argument default implementation is empty.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        log.warning("Foreground restore hit the OS dataSync time limit — stopping the run")
        coordinator.requestStop()
        scope.launch {
            runJob?.let { withTimeoutOrNull(TIMEOUT_DRAIN_MS) { it.join() } }
            stopService()
        }
    }

    /** Drops the foreground notification and ends the service. */
    private fun stopService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, RestoreForegroundService::class.java).apply { action = ACTION_STOP }
        return PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onDestroy() {
        runJob = null
        scope.cancel()
        super.onDestroy()
    }
}
