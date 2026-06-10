package ch.abwesend.foldervault.infrastructure.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.util.injectAnywhere
import ch.abwesend.foldervault.infrastructure.room.dao.BackupConfigDao
import java.time.Instant

class BackupWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    private val backupRunner: BackupRunner by injectAnywhere()
    private val backupConfigDao: BackupConfigDao by injectAnywhere()
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
        val configName = configId?.let { backupConfigDao.getByIdOnce(it)?.displayName } ?: ""
        setForeground(notificationManager.createForegroundInfo(configName, 0, 0))

        return errorHandler.doWorkWithErrorHandling(
            workDescription = "FolderVault backup",
            onFatalError = { /* error is already logged by WorkerErrorHandler */ },
        ) {
            val id = inputData.getString(KEY_CONFIG_ID)
                ?: return@doWorkWithErrorHandling Result.failure()

            val config = backupConfigDao.getByIdOnce(id)
                ?: return@doWorkWithErrorHandling Result.success() // config deleted, nothing to do

            val deadline = Instant.now().plusMillis(RUN_BUDGET_MS - DEADLINE_BUFFER_MS)
            val result = backupRunner.runBackup(id, deadline)

            notificationManager.postProblemNotificationIfNeeded(
                configId = id,
                configName = config.displayName,
                runId = id, // simplified — in v1.1, runId from RunSummary/RunResult
            )

            when (result) {
                is RunResult.Success -> {
                    if (result.summary.hitTimeBudget) {
                        // Made progress but ran out of time — re-schedule as expedited one-time
                        logger.info("Run hit time budget with progress; re-enqueueing for config $id")
                        BackupScheduler(applicationContext).scheduleExpedited(id)
                        Result.success() // not retry() — we don't want backoff accumulation
                    } else {
                        Result.success()
                    }
                }
                is RunResult.AuthLost -> {
                    logger.warning("Backup run for $id lost auth; will retry on next periodic")
                    Result.failure()
                }
                is RunResult.FatalError -> {
                    logger.error("Backup run for $id failed fatally", result.error)
                    Result.failure()
                }
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        // Best-effort — may be called before any config is loaded
        val configId = inputData.getString(KEY_CONFIG_ID)
        val configName = configId?.let { backupConfigDao.getByIdOnce(it)?.displayName } ?: ""
        return notificationManager.createForegroundInfo(configName, 0, 0)
    }
}
