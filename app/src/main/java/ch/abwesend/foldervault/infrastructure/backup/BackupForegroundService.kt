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
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import ch.abwesend.foldervault.domain.result.rethrowCancellation
import ch.abwesend.foldervault.domain.util.injectAnywhere
import ch.abwesend.foldervault.infrastructure.network.NetworkStateMonitor
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupConfigEntity
import ch.abwesend.foldervault.infrastructure.room.entity.BackupMessageEntity
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
 * Two legitimate start paths only: from foreground UI via [ForegroundBackupLauncher], or from
 * [BackupAlarmReceiver]'s exact-alarm callback (the opt-in "more reliable backups" trampoline —
 * only an exact-alarm callback is exempt from Android 12+'s background-FGS-start restriction, the
 * reason the previous `setForeground`-inside-worker attempt was removed, see commit `be3b3bd`).
 * Never from a plain background context or a sticky restart, hence [android.app.Service.START_NOT_STICKY].
 * Should foreground promotion be refused anyway (e.g. the dataSync time budget is exhausted for a
 * background start), [enterForeground] degrades the run to WorkManager instead of crashing.
 */
class BackupForegroundService : Service() {
    private val log get() = logger

    private val backupRunner: BackupRunner by injectAnywhere()
    private val backupConfigDao: BackupConfigDao by injectAnywhere()
    private val backupMessageDao: BackupMessageDao by injectAnywhere()
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
     * Last notification posted for the active run. A second `startForegroundService` while a
     * run is active must call `startForeground` again — re-posting this instance keeps the
     * ongoing notification on the *active* run instead of flipping it to the incoming config.
     * Always kept non-null while a run is active (seeded synchronously by [launchRun]), so the
     * fallback build in [startRun] only ever runs for the genuinely-first start.
     */
    @Volatile
    private var latestProgressNotification: Notification? = null

    /**
     * Latest per-file progress of the active run, or `null` while idle. [postProgressNotification]
     * rebuilds the ongoing notification for the *active* run from this snapshot plus the live
     * [pendingRunCount] — synchronously, on demand, so neither a second `startForeground` nor a
     * newly queued run has to wait for the asynchronous [publishProgress] tick (which runs on the
     * default dispatcher and can be starved while the analyzer saturates it). Volatile: written
     * from the run coroutine, read from the main thread.
     */
    @Volatile
    private var activeProgress: ProgressSnapshot? = null

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

    /** Snapshot of the active run's progress, rebuilt into the ongoing notification on demand. */
    private data class ProgressSnapshot(
        val configId: String,
        val filesUploaded: Int,
        val totalDiscovered: Int,
        val indexing: Boolean,
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
        // coroutine yet and would otherwise stay marked as running forever. No notification
        // refresh here: the service is being torn down (its foreground notification already
        // removed on the paths that reach onDestroy), so a re-post would orphan a notification.
        drainQueue(refreshNotification = false)
        super.onDestroy()
    }

    private fun startRun(intent: Intent?) {
        val configId = intent?.getStringExtra(EXTRA_CONFIG_ID)
        val networkPolicy = intent?.getStringExtra(EXTRA_NETWORK_POLICY)
            ?.let { runCatching { NetworkPolicy.valueOf(it) }.getOrNull() }
            ?: NetworkPolicy.WIFI_ONLY
        val requiresCharging = intent?.getBooleanExtra(EXTRA_REQUIRES_CHARGING, false) ?: false

        if (!enterForeground(configId)) {
            // The OS refused foreground promotion — for a background (alarm-triggered) start this
            // is typically the dataSync 6h/24h budget being exhausted
            // (ForegroundServiceStartNotAllowedException). Hand the incoming run to WorkManager
            // rather than crash; any run already in progress keeps going (stopWhenIdle spares it).
            if (configId != null) {
                log.warning("Foreground backup could not enter the foreground for $configId — handing to WorkManager")
                // forceInline: a plain one-time run would trampoline straight back to this service
                // (opted-in + long-window) and fail to enter the foreground again, looping.
                scheduler.scheduleOneTime(configId, networkPolicy, requiresCharging, forceInline = true)
                emitBudgetReachedMessage(configId)
            }
            stopWhenIdle()
        } else if (configId == null) {
            log.error("Foreground backup started without a config id")
            stopWhenIdle()
        } else {
            enqueueOrLaunch(RunParameters(configId, networkPolicy, requiresCharging))
        }
    }

    /**
     * Enters the foreground with the active run's ongoing notification (or a fresh one for the very
     * first start). Started via `startForegroundService`, the service must become foreground
     * promptly on EVERY start — even when the intent turns out to be unusable — and while a run is
     * active its latest progress notification is re-posted, so the ongoing notification never flips
     * to the incoming config's deep link and counts.
     *
     * Returns `false` if the OS refused (e.g. `ForegroundServiceStartNotAllowedException` when the
     * dataSync time budget is exhausted for a background start), so the caller degrades gracefully
     * instead of letting the exception crash the service.
     */
    private fun enterForeground(configId: String?): Boolean = try {
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
        true
    } catch (e: Exception) {
        e.rethrowCancellation()
        log.error("Foreground backup could not enter the foreground for ${configId.orEmpty()}", e)
        false
    }

    /**
     * Records an informational message for [configId] that this opted-in run could not get the
     * foreground service's long window (the shared dataSync time budget was exhausted) and fell
     * back to WorkManager. Coalesced like the other run messages so a repeated fallback does not
     * flood the config. Fire-and-forget on [scope] — a failure here must never take down the
     * degrade path that already re-scheduled the run.
     *
     * Best-effort by design: when the degraded start finds the service idle it stops right after,
     * and [onDestroy] cancels [scope] — so this insert may be cut short before it commits. That is
     * an acceptable trade for an INFO breadcrumb: the run itself is already safely re-queued on
     * WorkManager (the guaranteed part), and the fallback is also logged. Keeping the service alive
     * to guarantee the message is not worth complicating this invariant-heavy control flow.
     */
    private fun emitBudgetReachedMessage(configId: String) {
        scope.launch {
            try {
                backupMessageDao.coalesceInsert(
                    BackupMessageEntity(
                        backupConfigId = configId,
                        runId = null,
                        timestamp = System.currentTimeMillis(),
                        severity = MessageSeverity.INFO,
                        type = MessageType.FGS_TIME_BUDGET_REACHED,
                        messageText = null,
                        formatArgs = emptyList(),
                        relativePath = null,
                        readAt = null,
                    )
                )
            } catch (e: Exception) {
                e.rethrowCancellation()
                log.error("Failed to record the extended-run-time fallback message for $configId", e)
            }
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
                // Surface the newly queued run in the *active* run's ongoing notification at
                // once, rather than waiting for the next publishProgress tick.
                postProgressNotification()
            }
        }
    }

    /** Starts [params] as the active run. Must be called while holding [runLock]. */
    private fun launchRun(params: RunParameters) {
        stopReason = null
        activeRun = params
        foregroundRunState.markRunning(params.configId)
        // Seed the ongoing notification synchronously for the new active run so a
        // near-simultaneous second start (which re-posts latestProgressNotification) can never
        // flip it to the incoming config — publishProgress only refines this asynchronously and
        // may be delayed. Counts start at 0 and are corrected on the first tick.
        activeProgress = ProgressSnapshot(
            configId = params.configId,
            filesUploaded = 0,
            totalDiscovered = 0,
            indexing = false,
        )
        postProgressNotification()
        runJob = scope.launch { executeRun(params) }
    }

    /**
     * Rebuilds and posts the ongoing progress notification for the active run from the latest
     * [activeProgress] snapshot and the live [pendingRunCount], caching it in
     * [latestProgressNotification]. A no-op while idle. Because it always targets the active
     * run's config, a second `startForeground` can never flip the ongoing notification to a
     * merely-queued incoming config.
     */
    private fun postProgressNotification() {
        val snapshot = activeProgress
        if (snapshot != null) {
            latestProgressNotification = notificationManager.updateProgressNotification(
                configId = snapshot.configId,
                filesUploaded = snapshot.filesUploaded,
                totalDiscovered = snapshot.totalDiscovered,
                queuedRuns = pendingRunCount.value,
                stopIntent = stopPendingIntent(),
                indexing = snapshot.indexing,
            )
        }
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
                activeProgress = null
                // Drop the finished run's cached notification too: a start already in flight
                // could otherwise re-post the previous session's notification (wrong config
                // deep-link and counts) via startForeground before launchRun overwrites it.
                latestProgressNotification = null
                stopService()
            }
        }
    }

    /**
     * Empties the pending queue, unmarking each config and passing it to [onDrained] —
     * dropped on a user stop, handed to WorkManager on an OS timeout.
     *
     * [refreshNotification] re-posts the active run's ongoing notification so the emptied queue
     * ("N waiting" → gone) shows at once instead of on the next [publishProgress] tick. Callers on
     * a live session (user stop, OS timeout) leave it `true`; [onDestroy] passes `false` — the
     * service's foreground notification is already removed by then, so a re-post would orphan one.
     */
    private fun drainQueue(refreshNotification: Boolean = true, onDrained: (RunParameters) -> Unit = {}) {
        synchronized(runLock) {
            pendingRuns.forEach { params ->
                foregroundRunState.markStopped(params.configId)
                onDrained(params)
            }
            pendingRuns.clear()
            pendingRunCount.value = 0
            if (refreshNotification) {
                postProgressNotification()
            }
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
        }.collect { (uploadedThisRun, discoveredThisRun, _) ->
            // pendingRunCount is kept in the combine above only to re-trigger this collector when
            // the queue changes; postProgressNotification reads its current value directly.
            activeProgress = ProgressSnapshot(
                configId = config.id,
                filesUploaded = config.filesUploadedTotal + uploadedThisRun,
                totalDiscovered = if (discoveredThisRun > 0) discoveredThisRun else config.totalFilesDiscovered,
                indexing = config.isBaselinePending,
            )
            postProgressNotification()
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
