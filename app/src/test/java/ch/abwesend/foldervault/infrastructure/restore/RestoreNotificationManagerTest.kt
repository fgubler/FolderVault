package ch.abwesend.foldervault.infrastructure.restore

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import ch.abwesend.foldervault.domain.restore.RestoreProgress
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ongoing notification is the only handle a user has on a restore once they have left the app,
 * so it has to render *both* progress shapes: files for a whole-folder run, bytes for a single
 * file. Neither may name a file — this notification can sit on a lockscreen, and the folder variant
 * has always shown counts only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RestoreNotificationManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = RestoreNotificationManager(context)

    @Test
    fun `no progress yet reads as preparing, with an indeterminate bar`() {
        val notification = build(progress = null)

        assertEquals("Preparing the restore…", contentText(notification))
        assertTrue(isIndeterminate(notification), "an extent that is not known yet must not show a bar at zero")
    }

    @Test
    fun `a folder run shows the file counts`() {
        val notification = build(RestoreProgress.Files(total = 40, processed = 12, failed = 0, currentFileName = "a"))

        assertEquals("Restoring 12 / 40 files", contentText(notification))
        assertEquals(40, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(12, notification.extras.getInt(Notification.EXTRA_PROGRESS))
    }

    @Test
    fun `a folder run never names the file it is working on`() {
        val progress = RestoreProgress.Files(total = 2, processed = 1, failed = 0, currentFileName = "tax-return.pdf")

        val notification = build(progress)

        assertTrue(
            "tax-return.pdf" !in contentText(notification),
            "a lockscreen notification must not leak the file name",
        )
    }

    @Test
    fun `a single-file run shows the percentage of the source consumed`() {
        val notification = build(RestoreProgress.Bytes(processedBytes = 250, totalBytes = 1_000))

        assertEquals("Decrypting the file — 25%", contentText(notification))
        assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(25, notification.extras.getInt(Notification.EXTRA_PROGRESS))
    }

    @Test
    fun `a single-file run whose source size is unknown falls back to an indeterminate bar`() {
        // `length() == 0` means "unknown" as often as it means "empty", so there is no percentage
        // to show — but the run is still going, and a bar stuck at 0% would read as a hang.
        val notification = build(RestoreProgress.Bytes(processedBytes = 4_096, totalBytes = null))

        assertEquals("Decrypting the file…", contentText(notification))
        assertTrue(isIndeterminate(notification))
    }

    private fun build(progress: RestoreProgress?): Notification =
        manager.buildProgressNotification(progress, stopIntent = noOpPendingIntent())

    private fun contentText(notification: Notification): String =
        notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString()

    private fun isIndeterminate(notification: Notification): Boolean =
        notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)

    private fun noOpPendingIntent(): PendingIntent = PendingIntent.getService(
        context,
        0,
        Intent(context, RestoreForegroundService::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
