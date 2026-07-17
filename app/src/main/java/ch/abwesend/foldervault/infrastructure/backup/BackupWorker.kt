package ch.abwesend.foldervault.infrastructure.backup

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.abwesend.foldervault.domain.backup.ExecutionStrategySelector
import ch.abwesend.foldervault.domain.backup.IBackupScheduler
import ch.abwesend.foldervault.domain.backup.IFgsLaunchScheduler
import ch.abwesend.foldervault.domain.backup.ScheduledExecutionMode
import ch.abwesend.foldervault.domain.backup.StartManualBackupUseCase
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.BackupRunStatus
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.domain.util.injectAnywhere
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupMessageEntity
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.util.UUID

class BackupWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    private val backupRunner: BackupRunner by injectAnywhere()
    private val backupConfigDao: BackupConfigDao by injectAnywhere()
    private val backupMessageDao: BackupMessageDao by injectAnywhere()
    private val notificationManager: BackupNotificationManager by injectAnywhere()
    private val scheduler: IBackupScheduler by injectAnywhere()
    private val fgsLaunchScheduler: IFgsLaunchScheduler by injectAnywhere()
    private val settingsRepository: IAppSettingsRepository by injectAnywhere()
    private val foregroundRunState: ForegroundRunState by injectAnywhere()
    private val errorHandler = WorkerErrorHandler()

    companion object {
        const val KEY_CONFIG_ID = "configId"

        /**
         * Input-data flag marking a run as a charging-only fallback. When such a run hits its
         * time budget, its continuation must re-enqueue as a fallback (forced charging constraint,
         * dedicated unique name) rather than a plain one-time run whose config has
         * `requiresCharging = false`. See [BackupContinuationScheduler].
         */
        const val KEY_IS_CHARGING_FALLBACK = "isChargingFallback"

        /**
         * Input-data flag forcing this run to execute inline in the worker, never trampolining to
         * the foreground service. Set only by the budget-exhaustion degrade paths (the service /
         * alarm receiver could not enter the foreground): without it, the degraded run would
         * trampoline straight back to the service, fail to start again, and loop until the dataSync
         * time budget resets.
         */
        const val KEY_FORCE_INLINE = "forceInline"

        /**
         * NOTE: [WORK_NAME_PREFIX] is a strict prefix of the other two, so a config id that
         * happens to start with `one_time_` / `charging_fallback_` would alias another config's
         * one-time / fallback name. Purely theoretical with UUID config ids — and NOT worth
         * fixing by renaming: existing installs' scheduled work lives under these names, and a
         * rename would orphan it (never cancelled, never replaced).
         */
        const val WORK_NAME_PREFIX = "foldervault_backup_"
        const val ONE_TIME_WORK_NAME_PREFIX = "foldervault_backup_one_time_"
        const val CHARGING_FALLBACK_WORK_NAME_PREFIX = "foldervault_backup_charging_fallback_"
        private const val RUN_BUDGET_MINUTES = 8L
        private const val DEADLINE_BUFFER_SECONDS = 30L
        private const val MS_PER_MINUTE = 60_000L
        private const val MS_PER_SECOND = 1_000L
        private const val RUN_BUDGET_MS = RUN_BUDGET_MINUTES * MS_PER_MINUTE // 8 minutes (leave 2 min buffer)
        private const val DEADLINE_BUFFER_MS = DEADLINE_BUFFER_SECONDS * MS_PER_SECOND // stop 30s before deadline

        /** Unique-work name for the config's *periodic* schedule. */
        fun workName(configId: String) = "$WORK_NAME_PREFIX$configId"

        /**
         * Unique-work name for one-time runs ("back up now" + time-budget continuations). Kept
         * separate from [workName] so a one-time [ExistingWorkPolicy.REPLACE] enqueue can never
         * cancel the config's periodic schedule (WorkManager shares one unique-name namespace
         * across periodic and one-time work).
         */
        fun oneTimeWorkName(configId: String) = "$ONE_TIME_WORK_NAME_PREFIX$configId"

        /** Unique-work name for the charging-only fallback run. */
        fun chargingFallbackWorkName(configId: String) = "$CHARGING_FALLBACK_WORK_NAME_PREFIX$configId"
    }

    override suspend fun doWork(): Result {
        val configId = inputData.getString(KEY_CONFIG_ID)
        val isChargingFallback = inputData.getBoolean(KEY_IS_CHARGING_FALLBACK, false)
        val forceInline = inputData.getBoolean(KEY_FORCE_INLINE, false)
        val fallbackRunId = UUID.randomUUID().toString()

        return errorHandler.doWorkWithErrorHandling(
            workDescription = "FolderVault backup",
            onFatalError = { surfaceFatalError(configId, fallbackRunId) },
        ) {
            val id = configId ?: return@doWorkWithErrorHandling Result.failure()

            val config = backupConfigDao.getByIdOnce(id)
                ?: return@doWorkWithErrorHandling Result.success() // config deleted, nothing to do

            // A paused config must never run, no matter which path enqueued the work (periodic
            // schedule, continuation, or a charging fallback that slipped in while pausing
            // cancelled an in-flight run). Success, not retry — the work is intentionally inert
            // until the user resumes, which re-registers the schedule.
            if (config.isPaused) {
                logger.info("Backup config $id is paused; skipping this run")
                return@doWorkWithErrorHandling Result.success()
            }

            // The foreground service owns this config's run — either executing it or holding it
            // in its serial queue. A *queued* config does not hold BackupRunner's per-config lock
            // yet, so without this guard the worker would steal the run and crawl through it in
            // 8-minute background windows while the service was about to give it an hours-long
            // budget. Retry with backoff, like a concurrent run: by then the service has finished
            // (or degraded the run to WorkManager itself).
            if (foregroundRunState.isRunning(id)) {
                logger.info("Backup for $id is owned by the foreground service; retrying later")
                return@doWorkWithErrorHandling errorHandler.retryOrGiveUp(runAttemptCount)
            }

            // Opt-in "more reliable backups": a scheduled or continuation run that needs a long
            // window (an initial / large / interrupted sync) cannot start the foreground service
            // from this background worker, so hand it over via a one-shot exact-alarm trampoline
            // (only an exact-alarm callback is exempt from the background-FGS-start restriction).
            // Small established deltas — and installs without the opt-in or the exact-alarm
            // permission — keep running inline exactly as before.
            if (!forceInline &&
                shouldTrampolineToService(config.lastRunStatus, config.totalFilesDiscovered) &&
                fgsLaunchScheduler.scheduleImmediateLaunch(id, config.networkPolicy, config.requiresCharging)
            ) {
                logger.info("Trampolining config $id to the foreground service via a one-shot exact alarm")
                return@doWorkWithErrorHandling Result.success()
            }

            val deadline = Instant.now().plusMillis(RUN_BUDGET_MS - DEADLINE_BUFFER_MS)
            val result = backupRunner.runBackup(id, BackupRunControl(deadline))

            // A skipped run did nothing and has no run row — no notifications to derive from it.
            if (result is RunResult.Completed) {
                notificationManager.postProblemNotificationIfNeeded(
                    configId = id,
                    configName = config.displayName,
                    runId = result.runId,
                )

                // Cancelled runs never reach this point (the CancellationException propagates), and
                // non-terminal results (retry / continuation) map to null — so only a truly finished
                // run can produce a completion notification.
                BackupNotificationManager.completionOutcomeOf(result)?.let { outcome ->
                    notificationManager.postCompletionNotificationIfEnabled(
                        configId = id,
                        configName = config.displayName,
                        outcome = outcome,
                        filesUploaded = result.summary.filesUploaded,
                    )
                }
            }

            when (result) {
                is RunResult.Success -> {
                    if (result.summary.hitTimeBudget) {
                        // Made progress but ran out of time — re-enqueue for the next slot. A
                        // charging-fallback run re-enqueues as a fallback so it keeps its charging
                        // constraint and dedicated name; all others re-enqueue as one-time work.
                        logger.info("Run hit time budget with progress; re-enqueueing for config $id")
                        BackupContinuationScheduler.scheduleContinuation(
                            scheduler = scheduler,
                            configId = id,
                            networkPolicy = config.networkPolicy,
                            requiresCharging = config.requiresCharging,
                            isChargingFallback = isChargingFallback,
                        )
                        Result.success() // not retry() — we don't want backoff accumulation
                    } else {
                        notificationManager.clearResolvedThrottles(id)
                        Result.success()
                    }
                }
                is RunResult.AuthLost -> {
                    // Auth loss needs user re-consent, so give up after a low cap instead of
                    // hammering the auth stack for 20 backed-off attempts (BUG-9). A problem
                    // notification is already posted, so the user knows action is required.
                    logger.warning("Backup run for $id lost auth; retrying with WorkManager backoff")
                    errorHandler.retryOrGiveUp(runAttemptCount, WorkerErrorHandler.MAX_AUTH_RETRY_COUNT)
                }
                is RunResult.FatalError -> {
                    logger.error("Backup run for $id failed fatally", result.error)
                    Result.failure()
                }
                is RunResult.SkippedConcurrentRun -> {
                    // Manual + periodic overlap: another run of this config is executing right
                    // now. Retry with backoff instead of waiting — the in-flight run will have
                    // finished by then, and this worker gets a fresh deadline. Capped so a hung
                    // in-flight run cannot keep this worker retrying forever.
                    logger.info("Backup for $id is already running; retrying later")
                    errorHandler.retryOrGiveUp(runAttemptCount)
                }
            }
        }
    }

    /**
     * Whether this background run should be handed to the foreground service instead of running
     * inline: the user opted into exact-alarm backups, the permission is (still) granted (see
     * [ExecutionStrategySelector]), and the run needs a long window
     * ([StartManualBackupUseCase.needsForegroundService] — an initial / large / interrupted sync).
     */
    private suspend fun shouldTrampolineToService(
        lastRunStatus: BackupRunStatus,
        totalFilesDiscovered: Int,
    ): Boolean {
        val mode = ExecutionStrategySelector.scheduledMode(
            apiLevel = Build.VERSION.SDK_INT,
            exactAlarmUserEnabled = settingsRepository.settings.first().exactAlarmBackupsEnabled,
            canScheduleExactAlarms = fgsLaunchScheduler.isExactAlarmPermitted(),
        )
        return mode == ScheduledExecutionMode.EXACT_ALARM &&
            StartManualBackupUseCase.needsForegroundService(lastRunStatus, totalFilesDiscovered)
    }

    /**
     * Surfaces a worker-level fatal error (an exception that escaped the run pipeline) to the
     * user by inserting a coalesced GENERIC_ERROR message and triggering the throttled problem
     * notification. The exception itself is already logged + recorded in Crashlytics by
     * [WorkerErrorHandler]. With no configId we can only rely on that — there is no row to
     * attach the message to.
     */
    private suspend fun surfaceFatalError(configId: String?, runId: String) {
        if (configId == null) return
        val config = backupConfigDao.getByIdOnce(configId) ?: return

        backupMessageDao.coalesceInsert(
            BackupMessageEntity(
                backupConfigId = configId,
                runId = runId,
                timestamp = System.currentTimeMillis(),
                severity = MessageSeverity.ERROR,
                type = MessageType.GENERIC_ERROR,
                messageText = null,
                formatArgs = emptyList(),
                relativePath = null,
                readAt = null,
            )
        )

        notificationManager.postProblemNotificationIfNeeded(
            configId = configId,
            configName = config.displayName,
            runId = runId,
        )

        // The run died before the pipeline produced a summary, so no file count is available.
        notificationManager.postCompletionNotificationIfEnabled(
            configId = configId,
            configName = config.displayName,
            outcome = BackupRunOutcome.FAILURE,
            filesUploaded = null,
        )
    }
}
