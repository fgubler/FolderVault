package ch.abwesend.foldervault.infrastructure.backup

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

/**
 * Runs backups (typically the large *initial* upload) as a dataSync foreground service.
 *
 * WorkManager runs are bounded to short windows (~10 min), so an initial sync of thousands of
 * files crawls across many runs and cancellations (spec §5.8). This service runs the same
 * pipeline ([BackupRunner]) with an hours-long budget under an ongoing progress notification.
 * WorkManager remains the background path: whenever this run stops with work remaining —
 * time budget, network-policy violation, OS timeout — a one-time continuation is enqueued (see
 * [ForegroundHandoverPolicy]), so the sync keeps crawling in the background.
 *
 * Runs stay strictly serial (one file at a time — spec convention), but the service accepts
 * starts for *other* configs while busy: they queue in arrival order and run back-to-back in
 * the same service session, each with its own [BackupRunControl] budget. Queued configs are
 * marked in [ForegroundRunState] so the UI shows them as running, and they degrade to
 * WorkManager one-time runs when the OS dataSync time limit ends the session first.
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
    private val networkStateMonitor: NetworkStateMonitor by injectAnywhere()
    private val dispatchers: IDispatchers by injectAnywhere()

    /** Lazy because [dispatchers] is only injectable once the service instance exists. */
    private val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + dispatchers.default) }

    /** Guards [activeRun], [runJob] and [pendingRuns] across the main thread and [scope]. */
    private val runLock = Any()

    /**
     * Parameters of the currently executing run; `null` while idle. Writes are guarded by
     * [runLock]; volatile so [onTimeout] can read it outside the lock.
     */
    @Volatile
    private var activeRun: RunParameters? = null

    private var runJob: Job? = null

    /** Runs accepted while another one was active, in arrival order. Guarded by [runLock]. */
    private val pendingRuns = ArrayDeque<RunParameters>()

    /** Mirrors [pendingRuns]'s size so [publishProgress] can show the queued count live. */
    private val pendingRunCount = MutableStateFlow(0)

    @Volatile
    private var control: BackupRunControl? = null

    @Volatile
    private var stopReason: ForegroundStopReason? = null

    /**
     * Last notification posted by [publishProgress]. A second `startForegroundService` while a
     * run is active must call `startForeground` again — re-posting this instance keeps the
     * ongoing notification on the *active* run instead of flipping it to the incoming config.
     */
    @Volatile
    private var latestProgressNotification: Notification? = null

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
            // The user stopped the whole session: queued runs are dropped, not handed over —
            // their periodic schedules remain untouched.
            drainQueue()
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
     * continuation is scheduled here, since the normal result handling never runs — subject to
     * [ForegroundHandoverPolicy], so a run the user had already stopped is not resurrected in
     * the background. Queued runs cannot start in an out-of-budget session, so they are handed
     * to WorkManager instead.
     *
     * Must be the two-argument overload: the dataSync time-limit path calls only
     * `onTimeout(startId, fgsType)` — the one-argument [Service.onTimeout] is invoked solely
     * for `shortService` timeouts and its two-argument default implementation is empty.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        log.warning("Foreground backup hit the OS dataSync time limit")
        if (stopReason == null) {
            stopReason = ForegroundStopReason.OS_TIMEOUT
        }
        drainQueue { params ->
            scheduler.scheduleOneTime(params.configId, params.networkPolicy, params.requiresCharging)
        }
        control?.requestStop()
        scope.launch {
            val job = runJob
            // Captured before the drain: once the run's own finally advances the (already
            // drained) queue, activeRun is nulled — reading it after a failed drain would race
            // that cleanup and could lose the continuation.
            val params = activeRun
            if (job == null) {
                // No run to drain — an OS timeout on an idle service just ends it.
                stopService()
            } else {
                // `true` on completion vs `null` on timeout, so a join finishing exactly at
                // the boundary is not mistaken for a failed drain (which would double-schedule
                // the continuation handleResult already enqueued).
                val drained = withTimeoutOrNull(TIMEOUT_DRAIN_MS) {
                    job.join()
                    true
                }
                if (drained == null) {
                    job.cancel()
                    if (params != null && ForegroundHandoverPolicy.shouldScheduleContinuation(stopReason)) {
                        scheduler.scheduleOneTime(params.configId, params.networkPolicy, params.requiresCharging)
                    }
                    ServiceCompat.stopForeground(this@BackupForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        // The active run's own finally unmarks it even when cancelled; queued runs have no
        // coroutine yet and would otherwise stay marked as running forever.
        drainQueue()
        super.onDestroy()
    }

    private fun startRun(intent: Intent?) {
        val configId = intent?.getStringExtra(EXTRA_CONFIG_ID)
        val networkPolicy = intent?.getStringExtra(EXTRA_NETWORK_POLICY)
            ?.let { runCatching { NetworkPolicy.valueOf(it) }.getOrNull() }
            ?: NetworkPolicy.WIFI_ONLY
        val requiresCharging = intent?.getBooleanExtra(EXTRA_REQUIRES_CHARGING, false) ?: false

        // Started via startForegroundService — must become foreground promptly on EVERY start,
        // even when the intent turns out to be unusable. While a run is active its latest
        // progress notification is re-posted, so the ongoing notification never flips to the
        // incoming config's deep link and counts.
        ServiceCompat.startForeground(
            this,
            BackupNotificationManager.PROGRESS_NOTIFICATION_ID,
            latestProgressNotification ?: notificationManager.buildProgressNotification(
                configId = configId.orEmpty(),
                filesUploaded = 0,
                totalDiscovered = 0,
                queuedRuns = 0,
                stopIntent = stopPendingIntent(),
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        if (configId == null) {
            log.error("Foreground backup started without a config id")
            stopWhenIdle()
        } else {
            enqueueOrLaunch(RunParameters(configId, networkPolicy, requiresCharging))
        }
    }

    /**
     * Launches [params] immediately when the service is idle; otherwise appends it to the
     * queue — unless a run for the same config is already active or queued, which makes the
     * start a duplicate to ignore ("Back up now" tapped twice, or an auto-start racing a
     * manual one).
     */
    private fun enqueueOrLaunch(params: RunParameters) {
        synchronized(runLock) {
            val duplicate = activeRun?.configId == params.configId ||
                pendingRuns.any { it.configId == params.configId }
            if (activeRun == null) {
                launchRun(params)
            } else if (duplicate) {
                log.info("Foreground backup for ${params.configId} already active or queued — ignoring start")
            } else {
                log.info("Foreground backup busy — queueing run for config ${params.configId}")
                pendingRuns.addLast(params)
                pendingRunCount.value = pendingRuns.size
                // Marked right away so the UI reflects the accepted tap instead of looking
                // like it was dropped; the service owns this run from now on.
                foregroundRunState.markRunning(params.configId)
            }
        }
    }

    /** Starts [params] as the active run. Must be called while holding [runLock]. */
    private fun launchRun(params: RunParameters) {
        stopReason = null
        activeRun = params
        foregroundRunState.markRunning(params.configId)
        runJob = scope.launch { executeRun(params) }
    }

    /**
     * Hands the active slot to the next queued run, or stops the service when nothing is left.
     * Never launches on a cancelled [scope] (service being destroyed) — a coroutine that can
     * no longer run would leave its config marked as running forever.
     */
    private fun advanceQueue() {
        synchronized(runLock) {
            val next = if (scope.isActive) pendingRuns.removeFirstOrNull() else null
            if (next != null) {
                pendingRunCount.value = pendingRuns.size
                launchRun(next)
            } else {
                activeRun = null
                stopService()
            }
        }
    }

    /**
     * Empties the pending queue, unmarking each config and passing it to [onDrained] —
     * dropped on a user stop, handed to WorkManager on an OS timeout.
     */
    private fun drainQueue(onDrained: (RunParameters) -> Unit = {}) {
        synchronized(runLock) {
            pendingRuns.forEach { params ->
                foregroundRunState.markStopped(params.configId)
                onDrained(params)
            }
            pendingRuns.clear()
            pendingRunCount.value = 0
        }
    }

    /** Stops the service unless a run is active or queued — a bad start must not kill a live run. */
    private fun stopWhenIdle() {
        synchronized(runLock) {
            if (activeRun == null && pendingRuns.isEmpty()) {
                stopService()
            }
        }
    }

    private suspend fun executeRun(params: RunParameters) {
        try {
            val config = backupConfigDao.getByIdOnce(params.configId)
            if (config == null || config.isPaused) {
                log.info("Foreground backup for ${params.configId} skipped (config missing or paused)")
            } else {
                runWithConfig(config, params.networkPolicy, params.requiresCharging)
            }
        } catch (e: Exception) {
            // BackupRunner already converts pipeline errors to FatalError results; anything
            // arriving here escaped that (or failed in the config read or result handling) and
            // must not crash the app through the scope's unhandled-exception handler.
            e.rethrowCancellation()
            log.error("Foreground backup for ${params.configId} died unexpectedly", e)
        } finally {
            foregroundRunState.markStopped(params.configId)
            advanceQueue()
        }
    }

    private suspend fun runWithConfig(
        config: BackupConfigEntity,
        networkPolicy: NetworkPolicy,
        requiresCharging: Boolean,
    ) {
        val configId = config.id

        // Any queued one-time run would duplicate this one the moment its constraints are met.
        // Only the one-time slot: the periodic schedule must survive, and a pending charging
        // fallback firing against a drained queue is a cheap no-op.
        scheduler.cancelOneTime(configId)

        val runControl = BackupRunControl(deadline = Instant.now().plus(RUN_BUDGET))
        control = runControl
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
        } finally {
            control = null
        }
    }

    /** Stops the run at the next file boundary when the network stops satisfying the policy. */
    private suspend fun watchNetworkPolicy(networkPolicy: NetworkPolicy, runControl: BackupRunControl) {
        networkStateMonitor.observeSatisfies(networkPolicy).collectLatest { satisfied ->
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
     * the StateFlows conflate intermediate values, so this samples the latest counts.
     *
     * The discovered total comes from the run control's live flow: the config row's persisted
     * `totalFilesDiscovered` is only written at run end, so on the very first run it stays 0
     * throughout — the live value is what makes "N / M files" appear at all. The stale
     * persisted value (from the previous run) is only the fallback until this run's scan
     * completes. The queued-run count is included so a backup accepted while another is
     * running stays visible to the user.
     */
    private suspend fun publishProgress(config: BackupConfigEntity, runControl: BackupRunControl) {
        combine(
            runControl.filesUploadedThisRun,
            runControl.filesDiscovered,
            pendingRunCount,
        ) { uploaded, discovered, queued ->
            Triple(uploaded, discovered, queued)
        }.collect { (uploadedThisRun, discoveredThisRun, queuedRuns) ->
            latestProgressNotification = notificationManager.updateProgressNotification(
                configId = config.id,
                filesUploaded = config.filesUploadedTotal + uploadedThisRun,
                totalDiscovered = if (discoveredThisRun > 0) discoveredThisRun else config.totalFilesDiscovered,
                queuedRuns = queuedRuns,
                stopIntent = stopPendingIntent(),
                indexing = config.isBaselinePending,
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
