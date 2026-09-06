package ch.abwesend.foldervault.infrastructure.restore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.restore.RestoreProgress

/**
 * Notification channel and ongoing progress notification for the restore foreground service.
 *
 * Separate from `BackupNotificationManager` on purpose: that class is wired to backup configs
 * (deep links into the backup detail screen, per-config problem/completion throttling), none of
 * which a restore has. A restore only ever needs one ongoing progress notification.
 */
class RestoreNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "foldervault_restore_status"
        const val PROGRESS_NOTIFICATION_ID = 1101
    }

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_restore_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.notification_channel_restore_description) }
        manager.createNotificationChannel(channel)
    }

    /**
     * Builds the ongoing (silent, LOW-importance) notification the restore service runs under.
     * [progress] is `null` while the source tree is still being scanned and the password verified,
     * which on a large backup is a minute or more of no per-file progress. [stopIntent] targets
     * the service itself, so this class stays independent of the service class.
     */
    fun buildProgressNotification(progress: RestoreProgress?, stopIntent: PendingIntent): Notification {
        val text = if (progress == null || progress.total == 0) {
            context.getString(R.string.restore_notification_preparing_text)
        } else {
            context.getString(R.string.restore_notification_progress_text, progress.processed, progress.total)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.restore_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, context.getString(R.string.backup_notification_stop_action), stopIntent)
            .build()
    }

    /**
     * Re-posts the progress notification while the run proceeds. A missing POST_NOTIFICATIONS
     * grant must never fail the restore itself, so the `SecurityException` is swallowed — the run
     * keeps going, just without a visible progress notification.
     */
    fun updateProgressNotification(progress: RestoreProgress?, stopIntent: PendingIntent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(PROGRESS_NOTIFICATION_ID, buildProgressNotification(progress, stopIntent))
        } catch (e: SecurityException) {
            logger.warning("Cannot post restore progress notification (permission denied): ${e.message}")
        }
    }
}
