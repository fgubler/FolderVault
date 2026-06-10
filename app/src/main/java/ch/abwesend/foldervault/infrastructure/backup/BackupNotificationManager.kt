package ch.abwesend.foldervault.infrastructure.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.dao.NotificationThrottleStateDao
import ch.abwesend.foldervault.infrastructure.room.entity.NotificationThrottleStateEntity

class BackupNotificationManager(
    private val context: Context,
    private val notificationThrottleStateDao: NotificationThrottleStateDao,
    private val backupMessageDao: BackupMessageDao,
) {
    private val log get() = logger

    companion object {
        const val STATUS_CHANNEL_ID = "foldervault_backup_status"
        const val PROBLEMS_CHANNEL_ID = "foldervault_backup_problems"
        const val PROGRESS_NOTIFICATION_ID = 1001
        private const val THROTTLE_WINDOW_MS = 24 * 60 * 60 * 1000L
    }

    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val statusChannel = NotificationChannel(
            STATUS_CHANNEL_ID,
            context.getString(R.string.notification_channel_status_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.notification_channel_status_description) }

        val problemsChannel = NotificationChannel(
            PROBLEMS_CHANNEL_ID,
            context.getString(R.string.notification_channel_problems_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notification_channel_problems_description) }

        nm.createNotificationChannel(statusChannel)
        nm.createNotificationChannel(problemsChannel)
    }

    @Suppress("UnusedParameter")
    fun createForegroundInfo(configName: String, filesUploaded: Int, totalDiscovered: Int): ForegroundInfo {
        val text = if (totalDiscovered > 0) {
            context.getString(R.string.backup_notification_progress_text_with_count, filesUploaded, totalDiscovered)
        } else {
            context.getString(R.string.backup_notification_progress_text)
        }

        val notification = NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.backup_notification_progress_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification)
    }

    suspend fun postProblemNotificationIfNeeded(
        configId: String,
        configName: String,
        runId: String,
    ) {
        val notifyingTypes = MessageType.entries.filter { it.notifies }
        if (notifyingTypes.isEmpty()) return

        val now = System.currentTimeMillis()
        val pendingTypes = mutableListOf<MessageType>()

        for (type in notifyingTypes) {
            val state = notificationThrottleStateDao.getState(configId, type)
            val alreadyThrottled = state != null && (now - state.lastNotifiedAt) < THROTTLE_WINDOW_MS
            if (alreadyThrottled) continue

            val count = backupMessageDao.getCountForType(configId, type)
            if (count > 0) {
                pendingTypes.add(type)
            }
        }

        if (pendingTypes.isEmpty()) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = configId.hashCode()

        val notification = NotificationCompat.Builder(context, PROBLEMS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.backup_notification_problems_title))
            .setContentText(context.getString(R.string.backup_notification_problems_text, configName))
            .setAutoCancel(true)
            .build()

        try {
            nm.notify(notifId, notification)
        } catch (e: SecurityException) {
            log.warning("Cannot post backup problem notification (permission denied): ${e.message}")
        }

        for (type in pendingTypes) {
            notificationThrottleStateDao.upsert(
                NotificationThrottleStateEntity(
                    backupConfigId = configId,
                    messageType = type,
                    lastNotifiedAt = now,
                    lastRunId = runId,
                )
            )
        }
    }

    suspend fun clearThrottleForConfig(configId: String) {
        notificationThrottleStateDao.deleteAllForConfig(configId)
    }
}
