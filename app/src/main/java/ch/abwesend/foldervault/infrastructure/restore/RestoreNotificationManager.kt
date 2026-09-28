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

        /** `setProgress` scale for a byte-based run, whose progress is reported as a percentage. */
        private const val PERCENT_MAX = 100
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
     *
     * [progress] is `null` while the run has nothing to report yet — for a folder, while the source
     * tree is scanned and the password probed, which on a large backup is a minute or more. Both
     * progress shapes are rendered, and neither names a file: this notification can sit on a
     * lockscreen, and the folder variant has always shown counts only. [stopIntent] targets the
     * service itself, so this class stays independent of the service class.
     */
    fun buildProgressNotification(progress: RestoreProgress?, stopIntent: PendingIntent): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.restore_notification_title))
            .setContentText(contentTextFor(progress))
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, context.getString(R.string.backup_notification_stop_action), stopIntent)
        applyProgressBar(builder, progress)
        return builder.build()
    }

    private fun contentTextFor(progress: RestoreProgress?): String = when {
        progress == null -> context.getString(R.string.restore_notification_preparing_text)
        progress is RestoreProgress.Files && progress.total > 0 -> context.getString(
            R.string.restore_notification_progress_text,
            progress.processed,
            progress.total,
        )
        progress is RestoreProgress.Bytes ->
            progress.percent
                ?.let { context.getString(R.string.restore_notification_single_file_progress, it) }
                ?: context.getString(R.string.restore_single_decrypting)
        else -> context.getString(R.string.restore_notification_preparing_text)
    }

    /**
     * An ongoing restore is exactly the case a notification progress bar exists for. A run whose
     * extent is not known yet — no progress at all, or a source whose provider does not report a
     * size — gets the indeterminate bar rather than a bar stuck at zero.
     */
    private fun applyProgressBar(builder: NotificationCompat.Builder, progress: RestoreProgress?) {
        when {
            progress is RestoreProgress.Files && progress.total > 0 ->
                builder.setProgress(progress.total, progress.processed, false)
            progress is RestoreProgress.Bytes && progress.percent != null ->
                builder.setProgress(PERCENT_MAX, progress.percent ?: 0, false)
            else -> builder.setProgress(0, 0, true)
        }
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
