package ch.abwesend.foldervault.infrastructure.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import ch.abwesend.foldervault.R
import ch.abwesend.foldervault.domain.logging.logger
import ch.abwesend.foldervault.domain.model.MessageType
import ch.abwesend.foldervault.domain.settings.IAppSettingsRepository
import ch.abwesend.foldervault.infrastructure.room.dao.BackupMessageDao
import ch.abwesend.foldervault.infrastructure.room.dao.NotificationThrottleStateDao
import ch.abwesend.foldervault.infrastructure.room.entity.NotificationThrottleStateEntity
import kotlinx.coroutines.flow.first

private fun MessageType.notifPhraseResId(): Int? = when (this) {
    MessageType.AUTH_LOST -> R.string.notif_problem_auth_lost
    MessageType.FOLDER_UNREADABLE -> R.string.notif_problem_folder_unreadable
    MessageType.UPLOAD_FAILED -> R.string.notif_problem_upload_failed
    MessageType.ENCRYPTION_FAILED -> R.string.notif_problem_encryption_failed
    MessageType.QUOTA_EXCEEDED -> R.string.notif_problem_quota_exceeded
    MessageType.GENERIC_ERROR -> R.string.notif_problem_generic_error
    else -> null
}

/** Terminal outcome of a backup run, as shown in the completion notification. */
enum class BackupRunOutcome { SUCCESS, FAILURE }

class BackupNotificationManager(
    private val context: Context,
    private val notificationThrottleStateDao: NotificationThrottleStateDao,
    private val backupMessageDao: BackupMessageDao,
    private val settingsRepository: IAppSettingsRepository,
) {
    private val log get() = logger

    companion object {
        const val STATUS_CHANNEL_ID = "foldervault_backup_status"
        const val PROBLEMS_CHANNEL_ID = "foldervault_backup_problems"
        const val COMPLETIONS_CHANNEL_ID = "foldervault_backup_completions"

        /** ID of the foreground service's ongoing progress notification (spec §7.6 / §8.3). */
        const val PROGRESS_NOTIFICATION_ID = 1001
        const val THROTTLE_WINDOW_MS = 24 * 60 * 60 * 1000L
        private const val DEEP_LINK_SCHEME = "foldervault"
        private const val DEEP_LINK_HOST = "backup"
        private const val DEEP_LINK_PATH_PREFIX = "detail"
        private const val NOTIF_ID_MASK = 0x0FFFFFFF
        private const val NOTIF_ID_PREFIX = 0x10000000
        private const val COMPLETION_NOTIF_ID_PREFIX = 0x20000000

        /** Pure: returns true when the given throttle state does NOT suppress a new notification. */
        internal fun shouldNotify(state: NotificationThrottleStateEntity?, nowMs: Long): Boolean =
            state == null || (nowMs - state.lastNotifiedAt) >= THROTTLE_WINDOW_MS

        /** Isolates problem notification IDs from the progress ID range via a high-bit prefix. */
        internal fun problemId(configId: String): Int =
            (configId.hashCode() and NOTIF_ID_MASK) or NOTIF_ID_PREFIX

        /** Isolates completion notification IDs from the problem and progress ID ranges. */
        internal fun completionId(configId: String): Int =
            (configId.hashCode() and NOTIF_ID_MASK) or COMPLETION_NOTIF_ID_PREFIX

        /**
         * Pure: maps a run result to the outcome shown in the completion notification, or `null`
         * when the run is not terminal and must stay silent — an auth-lost run is retried by
         * WorkManager, and a time-budget run is re-enqueued as a continuation of the same backup.
         * (Cancelled runs never produce a [RunResult] at all, so they are silent by construction.)
         */
        internal fun completionOutcomeOf(result: RunResult): BackupRunOutcome? = when (result) {
            is RunResult.Success ->
                if (result.summary.hitTimeBudget) null else BackupRunOutcome.SUCCESS
            is RunResult.AuthLost -> null
            is RunResult.FatalError -> BackupRunOutcome.FAILURE
            is RunResult.SkippedConcurrentRun -> null // nothing ran, nothing to announce
        }

        private val NOTIFYING_TYPES = MessageType.entries.filter { it.notifies }
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

        val completionsChannel = NotificationChannel(
            COMPLETIONS_CHANNEL_ID,
            context.getString(R.string.notification_channel_completions_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notification_channel_completions_description) }

        nm.createNotificationChannel(statusChannel)
        nm.createNotificationChannel(problemsChannel)
        nm.createNotificationChannel(completionsChannel)
    }

    /**
     * Builds the ongoing (silent, LOW-importance) progress notification the foreground service
     * runs under. Shows "Uploading N / M files" once the total is known, an indeterminate text
     * before that; a positive [queuedRuns] appends how many further backups wait in the
     * service's queue. When [indexing] is set (a "only sync changes from now on" config's
     * baseline pass) the text reads "checking existing files" instead — the pass records metadata
     * and uploads nothing, so an upload count would misleadingly sit at zero. [stopIntent] is
     * provided by the service (it targets the service itself), so this class stays independent of
     * the service class.
     */
    fun buildProgressNotification(
        configId: String,
        filesUploaded: Int,
        totalDiscovered: Int,
        queuedRuns: Int,
        stopIntent: PendingIntent,
        indexing: Boolean = false,
    ): Notification {
        val progressText = when {
            indexing -> context.getString(R.string.backup_notification_indexing_text)
            totalDiscovered > 0 ->
                context.getString(R.string.backup_notification_progress_text_with_count, filesUploaded, totalDiscovered)
            else -> context.getString(R.string.backup_notification_progress_text)
        }
        val text = if (queuedRuns > 0) {
            val queuedText = context.resources
                .getQuantityString(R.plurals.backup_notification_progress_queued, queuedRuns, queuedRuns)
            // Join the two already-resolved display strings with a plain separator rather than a
            // second format string: feeding a getString() result into getString(fmt, …) trips
            // Lint's StringFormatInvalid (a false positive — String.format never re-parses % in
            // its arguments), so we concatenate instead.
            progressText + context.getString(R.string.backup_notification_progress_separator) + queuedText
        } else {
            progressText
        }
        return NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.backup_notification_progress_title))
            .setContentText(text)
            .setContentIntent(detailScreenPendingIntent(configId, PROGRESS_NOTIFICATION_ID))
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, context.getString(R.string.backup_notification_stop_action), stopIntent)
            .build()
    }

    /**
     * Re-posts the progress notification with updated counts. The initial one is handed to
     * `startForeground` by the service itself; this refreshes it while the run proceeds. The
     * posted notification is returned so the service can re-post the current state verbatim
     * when a later start forces another `startForeground` call.
     */
    fun updateProgressNotification(
        configId: String,
        filesUploaded: Int,
        totalDiscovered: Int,
        queuedRuns: Int,
        stopIntent: PendingIntent,
        indexing: Boolean = false,
    ): Notification {
        val notification =
            buildProgressNotification(configId, filesUploaded, totalDiscovered, queuedRuns, stopIntent, indexing)
        notifySafely(PROGRESS_NOTIFICATION_ID, notification, "backup progress")
        return notification
    }

    suspend fun postProblemNotificationIfNeeded(
        configId: String,
        configName: String,
        runId: String,
    ) {
        val notifyingTypes = NOTIFYING_TYPES
        if (notifyingTypes.isEmpty()) return

        val now = System.currentTimeMillis()
        val pendingTypes = mutableListOf<MessageType>()

        for (type in notifyingTypes) {
            val state = notificationThrottleStateDao.getState(configId, type)
            if (!shouldNotify(state, now)) continue

            val count = backupMessageDao.getCountForType(configId, type)
            if (count > 0) {
                pendingTypes.add(type)
            }
        }

        if (pendingTypes.isEmpty()) return

        val notifId = problemId(configId)
        val pendingIntent = detailScreenPendingIntent(configId, notifId)

        val problemPhrases = pendingTypes
            .mapNotNull { it.notifPhraseResId() }
            .joinToString(", ") { context.getString(it) }
        val notification = NotificationCompat.Builder(context, PROBLEMS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.backup_notification_problems_title))
            .setContentText(
                context.getString(R.string.backup_notification_problems_text, configName, problemPhrases)
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notifySafely(notifId, notification, "backup problem")

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

    /**
     * Posts the per-run completion notification (success or failure) if the user has enabled it
     * in the settings. Callers are expected to invoke this only for terminal runs — see
     * [completionOutcomeOf]. [filesUploaded] may be `null` when a run died before the pipeline
     * could produce a summary; the notification then omits the file count.
     */
    suspend fun postCompletionNotificationIfEnabled(
        configId: String,
        configName: String,
        outcome: BackupRunOutcome,
        filesUploaded: Int?,
    ) {
        if (!settingsRepository.settings.first().notifyOnBackupCompletion) return

        val notifId = completionId(configId)
        val titleResId = when (outcome) {
            BackupRunOutcome.SUCCESS -> R.string.backup_notification_completion_success_title
            BackupRunOutcome.FAILURE -> R.string.backup_notification_completion_failure_title
        }
        val text = if (filesUploaded != null) {
            context.resources.getQuantityString(
                R.plurals.backup_notification_completion_text,
                filesUploaded,
                configName,
                filesUploaded,
            )
        } else {
            context.getString(R.string.backup_notification_completion_text_no_count, configName)
        }

        val notification = NotificationCompat.Builder(context, COMPLETIONS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(titleResId))
            .setContentText(text)
            .setContentIntent(detailScreenPendingIntent(configId, notifId))
            .setAutoCancel(true)
            .build()

        notifySafely(notifId, notification, "backup completion")
    }

    /** Builds the pending intent deep-linking into the detail screen of the given backup config. */
    private fun detailScreenPendingIntent(configId: String, notifId: Int): PendingIntent {
        val deepLinkUri = Uri.parse("$DEEP_LINK_SCHEME://$DEEP_LINK_HOST/$DEEP_LINK_PATH_PREFIX/$configId")
        val deepLinkIntent = Intent(Intent.ACTION_VIEW, deepLinkUri).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            notifId,
            deepLinkIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Posts the notification, swallowing the [SecurityException] thrown when the user has revoked
     * the notification permission — a missing notification must never fail the backup itself.
     */
    private fun notifySafely(notifId: Int, notification: Notification, what: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(notifId, notification)
        } catch (e: SecurityException) {
            log.warning("Cannot post $what notification (permission denied): ${e.message}")
        }
    }

    /**
     * After a clean successful run, clear throttle entries for types that no longer have any
     * active (undismissed) messages — so a future recurrence of the same condition will notify again.
     */
    suspend fun clearResolvedThrottles(configId: String) {
        val throttled = notificationThrottleStateDao.getAllForConfig(configId)
        for (state in throttled) {
            val count = backupMessageDao.getCountForType(configId, state.messageType)
            if (count == 0) {
                notificationThrottleStateDao.deleteForConfigAndType(configId, state.messageType)
            }
        }
    }

    suspend fun clearThrottleForConfig(configId: String) {
        notificationThrottleStateDao.deleteAllForConfig(configId)
    }
}
