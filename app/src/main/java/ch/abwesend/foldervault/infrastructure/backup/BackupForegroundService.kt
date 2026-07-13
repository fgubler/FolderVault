package ch.abwesend.foldervault.infrastructure.backup

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.work.WorkManager
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.coroutine.IDispatchers
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.result.rethrowCancellation
import ch.abwesend.foldervault.domain.util.injectAnywhere
import ch.abwesend.foldervault.infrastructure.network.NetworkStateMonitor
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

/**
 * Runs a single backup (typically the large *initial* upload) as a dataSync foreground service.
 *
 * WorkManager runs are bounded to short windows (~10 min), so an initial sync of thousands of
 * files crawls across many runs and cancellations (spec §5.8). This service runs the same
 * pipeline ([BackupRunner]) with an hours-long budget under an ongoing progress notification.
 * WorkManager remains the background path: whenever this run stops with work remaining —
 * time budget, network-policy violation, OS timeout — a one-time continuation is enqueued (see
 * [ForegroundHandoverPolicy]), so the sync keeps crawling in the background.
 *
 * Must only be started from foreground UI via [ForegroundBackupLauncher] (Android 12+ forbids
 * background FGS starts — the reason the previous `setForeground`-inside-worker attempt was
 * removed, see commit `be3b3bd`). [android.app.Service.START_NOT_STICKY] for the same reason:
 * a sticky restart would be a background start.
 */
class BackupForegroundService : Service() {
    private val log get() = logger

    private val backupRunner: BackupRunner by injectAnywhere()
    private val backupConfigDao: BackupConfigDao by injectAnywhere()
    private val notificationManager: BackupNotificationManager by injectAnywhere()
    private val scheduler: IBackupScheduler by injectAnywhere()
    private val foregroundRunState: ForegroundRunState by injectAnywhere()
    private val dispatchers: IDispatchers by injectAnywhere()

    /** Lazy because [dispatchers] is only injectable once the service instance exists. */
    private val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + dispatchers.default) }
    private var runJob: Job? = null

    @Volatile
    private var control: BackupRunControl? = null

    @Volatile
    private var stopReason: ForegroundStopReason? = null

    /** Effective run parameters, kept for continuation scheduling from [onTimeout]. */
    @Volatile
    private var runParameters: RunParameters? = null

    companion object {
        const val EXTRA_CONFIG_ID = "configId"
        const val EXTRA_NETWORK_POLICY = "networkPolicy"
        const val EXTRA_REQUIRES_CHARGING = "requiresCharging"
        const val ACTION_STOP = "ch.abwesend.foldervault.action.STOP_FOREGROUND_BACKUP"

        /**
         * Stays safely below Android 15's 6-hour dataSync cap, so the run normally ends through
         * the cooperative time-budget path; [onTimeout] is only the hard safety net.
         */
        private val RUN_BUDGET: Duration = Duration.ofHours(5).plusMinutes(30)

        /**
         * Grace period before a network-policy violation stops the run: a network *switch*
         * (Wi-Fi to mobile) reports a transient loss before the new network's capabilities
         * arrive, which must not abort the run.
         */
        private const val NETWORK_VIOLATION_GRACE_MS = 5_000L

        /** Minimum interval between progress-notification updates. */
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L

        /** How long [onTimeout] waits for the cooperative stop before hard-cancelling. */
        private const val TIMEOUT_DRAIN_MS = 4_000L
    }

    private data class RunParameters(
        val configId: String,
        val networkPolicy: NetworkPolicy,
        val requiresCharging: Boolean,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            log.info("Foreground backup: stop requested by user")
            stopReason = ForegroundStopReason.USER_REQUESTED
            val activeControl = control
            if (activeControl != null) {
                activeControl.requestStop()
            } else {
                stopSelf()
            }
        } else {
            startRun(intent)
        }
        return START_NOT_STICKY
    }

    /**
     * OS-enforced dataSync time limit (Android 15+). Requests a cooperative stop and gives the
     * run a few seconds to finish its in-flight file; a run that cannot stop in time is
     * hard-cancelled ([BackupRunner]'s cancellation path marks it CANCELLED) and its
     * continuation is scheduled here, since the normal result handling never runs.
     */
    override fun onTimeout(startId: Int) {
        log.warning("Foreground backup hit the OS dataSync time limit")
        if (stopReason == null) {
            stopReason = ForegroundStopReason.OS_TIMEOUT
        }
        control?.requestStop()
        scope.launch {
            val drained = withTimeoutOrNull(TIMEOUT_DRAIN_MS) { runJob?.join() }
            if (drained == null) {
                runJob?.cancel()
                runParameters?.let { params ->
                    scheduler.scheduleOneTime(params.configId, params.networkPolicy, params.requiresCharging)
                }
                ServiceCompat.stopForeground(this@BackupForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startRun(intent: Intent?) {
        val configId = intent?.getStringExtra(EXTRA_CONFIG_ID)
        val networkPolicy = intent?.getStringExtra(EXTRA_NETWORK_POLICY)
            ?.let { runCatching { NetworkPolicy.valueOf(it) }.getOrNull() }
            ?: NetworkPolicy.WIFI_ONLY
        val requiresCharging = intent?.getBooleanExtra(EXTRA_REQUIRES_CHARGING, false) ?: false

        // Started via startForegroundService — must become foreground promptly, even when the
        // intent turns out to be unusable and the service stops right again.
        ServiceCompat.startForeground(
            this,
            BackupNotificationManager.PROGRESS_NOTIFICATION_ID,
            notificationManager.buildProgressNotification(
                configId = configId.orEmpty(),
                filesUploaded = 0,
                totalDiscovered = 0,
                stopIntent = stopPendingIntent(),
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        if (configId == null) {
            log.error("Foreground backup started without a config id — stopping")
            stopService()
        } else if (runJob?.isActive == true) {
            log.info("Foreground backup already running — ignoring start for config $configId")
        } else {
            stopReason = null
            runParameters = RunParameters(configId, networkPolicy, requiresCharging)
            runJob = scope.launch { executeRun(configId, networkPolicy, requiresCharging) }
        }
    }

    private suspend fun executeRun(configId: String, networkPolicy: NetworkPolicy, requiresCharging: Boolean) {
        val config = backupConfigDao.getByIdOnce(configId)
        if (config == null || config.isPaused) {
            log.info("Foreground backup for $configId skipped (config missing or paused)")
            stopService()
        } else {
            runWithConfig(config, networkPolicy, requiresCharging)
        }
    }

    private suspend fun runWithConfig(
        config: BackupConfigEntity,
        networkPolicy: NetworkPolicy,
        requiresCharging: Boolean,
    ) {
        val configId = config.id

        // Any queued one-time run would duplicate this one the moment its constraints are met.
        // Only the one-time name: the periodic schedule must survive, and a pending charging
        // fallback firing against a drained queue is a cheap no-op.
        WorkManager.getInstance(this).cancelUniqueWork(BackupWorker.oneTimeWorkName(configId))

        val runControl = BackupRunControl(deadline = Instant.now().plus(RUN_BUDGET))
        control = runControl
        foregroundRunState.markRunning(configId)
        try {
            val result = coroutineScope {
                val watchers = launch {
                    launch { watchNetworkPolicy(networkPolicy, runControl) }
                    launch { publishProgress(config, runControl) }
                }
                try {
                    backupRunner.runBackup(configId, runControl)
                } finally {
                    watchers.cancel()
                }
            }
            handleResult(config, result, networkPolicy, requiresCharging)
        } catch (e: Exception) {
            // BackupRunner already converts pipeline errors to FatalError results; anything
            // arriving here escaped that (or failed in result handling) and must not crash the
            // app through the scope's unhandled-exception handler.
            e.rethrowCancellation()
            log.error("Foreground backup for $configId died unexpectedly", e)
        } finally {
            foregroundRunState.markStopped(configId)
            control = null
            stopService()
        }
    }

    /** Stops the run at the next file boundary when the network stops satisfying the policy. */
    private suspend fun watchNetworkPolicy(networkPolicy: NetworkPolicy, runControl: BackupRunControl) {
        NetworkStateMonitor(this).observeSatisfies(networkPolicy).collectLatest { satisfied ->
            if (!satisfied) {
                delay(NETWORK_VIOLATION_GRACE_MS)
                log.info("Foreground backup: network no longer satisfies $networkPolicy — stopping cleanly")
                if (stopReason == null) {
                    stopReason = ForegroundStopReason.NETWORK_POLICY_VIOLATED
                }
                runControl.requestStop()
            }
        }
    }

    /**
     * Mirrors per-file progress into the ongoing notification, rate-limited by the delay —
     * the StateFlow conflates intermediate values, so this samples the latest count.
     */
    private suspend fun publishProgress(config: BackupConfigEntity, runControl: BackupRunControl) {
        runControl.filesUploadedThisRun.collect { uploadedThisRun ->
            notificationManager.updateProgressNotification(
                configId = config.id,
                filesUploaded = config.filesUploadedTotal + uploadedThisRun,
                totalDiscovered = config.totalFilesDiscovered,
                stopIntent = stopPendingIntent(),
            )
            delay(PROGRESS_UPDATE_INTERVAL_MS)
        }
    }

    /**
     * Terminal handling, mirroring [BackupWorker.doWork]'s notification block — minus the
     * retry logic (there is no WorkManager attempt counter here: an auth-lost or failed run
     * simply ends, and the problem notification tells the user).
     */
    private suspend fun handleResult(
        config: BackupConfigEntity,
        result: RunResult,
        networkPolicy: NetworkPolicy,
        requiresCharging: Boolean,
    ) {
        if (result is RunResult.Completed) {
            notificationManager.postProblemNotificationIfNeeded(
                configId = config.id,
                configName = config.displayName,
                runId = result.runId,
            )
            BackupNotificationManager.completionOutcomeOf(result)?.let { outcome ->
                notificationManager.postCompletionNotificationIfEnabled(
                    configId = config.id,
                    configName = config.displayName,
                    outcome = outcome,
                    filesUploaded = result.summary.filesUploaded,
                )
            }
        }

        when (result) {
            is RunResult.Success -> {
                if (result.summary.hitTimeBudget) {
                    if (ForegroundHandoverPolicy.shouldScheduleContinuation(stopReason)) {
                        log.info("Foreground backup stopped with work remaining ($stopReason) — handing over")
                        scheduler.scheduleOneTime(config.id, networkPolicy, requiresCharging)
                    } else {
                        log.info("Foreground backup stopped by user — no continuation scheduled")
                    }
                } else {
                    notificationManager.clearResolvedThrottles(config.id)
                }
            }
            is RunResult.AuthLost ->
                log.warning("Foreground backup for ${config.id} lost auth — user re-consent required")
            is RunResult.FatalError ->
                log.error("Foreground backup for ${config.id} failed fatally", result.error)
            is RunResult.SkippedConcurrentRun ->
                log.info("Foreground backup for ${config.id} skipped — another run is already executing")
        }
    }

    private fun stopService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, BackupForegroundService::class.java).apply { action = ACTION_STOP }
        return PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
