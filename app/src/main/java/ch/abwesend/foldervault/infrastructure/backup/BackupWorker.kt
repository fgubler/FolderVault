package ch.abwesend.foldervault.infrastructure.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.MessageSeverity
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.util.injectAnywhere
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.entity.BackupMessageEntity
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
    private val errorHandler = WorkerErrorHandler()

    companion object {
        const val KEY_CONFIG_ID = "configId"
        const val WORK_NAME_PREFIX = "foldervault_backup_"
        private const val RUN_BUDGET_MINUTES = 8L
        private const val DEADLINE_BUFFER_SECONDS = 30L
        private const val MS_PER_MINUTE = 60_000L
        private const val MS_PER_SECOND = 1_000L
        private const val RUN_BUDGET_MS = RUN_BUDGET_MINUTES * MS_PER_MINUTE // 8 minutes (leave 2 min buffer)
        private const val DEADLINE_BUFFER_MS = DEADLINE_BUFFER_SECONDS * MS_PER_SECOND // stop 30s before deadline

        fun workName(configId: String) = "$WORK_NAME_PREFIX$configId"
    }

    override suspend fun doWork(): Result {
        val configId = inputData.getString(KEY_CONFIG_ID)
        val fallbackRunId = UUID.randomUUID().toString()

        return errorHandler.doWorkWithErrorHandling(
            workDescription = "FolderVault backup",
            onFatalError = { surfaceFatalError(configId, fallbackRunId) },
        ) {
            val id = configId ?: return@doWorkWithErrorHandling Result.failure()

            val config = backupConfigDao.getByIdOnce(id)
                ?: return@doWorkWithErrorHandling Result.success() // config deleted, nothing to do

            val deadline = Instant.now().plusMillis(RUN_BUDGET_MS - DEADLINE_BUFFER_MS)
            val result = backupRunner.runBackup(id, deadline)

            notificationManager.postProblemNotificationIfNeeded(
                configId = id,
                configName = config.displayName,
                runId = result.runId,
            )

            when (result) {
                is RunResult.Success -> {
                    if (result.summary.hitTimeBudget) {
                        // Made progress but ran out of time — re-enqueue for the next slot
                        logger.info("Run hit time budget with progress; re-enqueueing for config $id")
                        BackupScheduler(applicationContext).scheduleOneTime(id)
                        Result.success() // not retry() — we don't want backoff accumulation
                    } else {
                        notificationManager.clearResolvedThrottles(id)
                        Result.success()
                    }
                }
                is RunResult.AuthLost -> {
                    logger.warning("Backup run for $id lost auth; retrying with WorkManager backoff")
                    Result.retry()
                }
                is RunResult.FatalError -> {
                    logger.error("Backup run for $id failed fatally", result.error)
                    Result.failure()
                }
            }
        }
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
    }
}
