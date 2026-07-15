package ch.abwesend.foldervault.infrastructure.backup

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the exact-alarm trampoline's landing point: [BackupAlarmReceiver] starts
 * [BackupForegroundService] and forwards the run's config id and effective policy from the alarm
 * intent. A start with no config id is ignored rather than starting a useless service.
 *
 * The budget-exhaustion degrade path (start throws → WorkManager fallback) can't be simulated
 * without shadowing the framework's foreground-service check and is left to manual verification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupAlarmReceiverTest {

    @Test
    fun `starts the foreground service with the forwarded config id and effective policy`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent().apply {
            putExtra(BackupForegroundService.EXTRA_CONFIG_ID, "cfg-1")
            putExtra(BackupForegroundService.EXTRA_NETWORK_POLICY, NetworkPolicy.ANY.name)
            putExtra(BackupForegroundService.EXTRA_REQUIRES_CHARGING, true)
        }

        BackupAlarmReceiver().onReceive(app, intent)

        val started = shadowOf(app).nextStartedService
        assertEquals(BackupForegroundService::class.java.name, started.component?.className)
        assertEquals("cfg-1", started.getStringExtra(BackupForegroundService.EXTRA_CONFIG_ID))
        assertEquals(NetworkPolicy.ANY.name, started.getStringExtra(BackupForegroundService.EXTRA_NETWORK_POLICY))
        assertTrue(started.getBooleanExtra(BackupForegroundService.EXTRA_REQUIRES_CHARGING, false))
    }

    @Test
    fun `a start with no config id does not start the service`() {
        val app = ApplicationProvider.getApplicationContext<Application>()

        BackupAlarmReceiver().onReceive(app, Intent())

        assertNull(shadowOf(app).nextStartedService)
    }
}
