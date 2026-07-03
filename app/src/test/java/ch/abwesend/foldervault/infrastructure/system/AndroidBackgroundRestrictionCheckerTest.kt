package ch.abwesend.foldervault.infrastructure.system

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidBackgroundRestrictionCheckerTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val checker = AndroidBackgroundRestrictionChecker(context)

    @Test
    fun batteryOptimizationIsReportedAsActiveByDefault() {
        assertFalse(checker.isIgnoringBatteryOptimizations())
    }

    @Test
    fun batteryOptimizationExemptionIsDetected() {
        val powerManager = context.getSystemService(PowerManager::class.java)
        shadowOf(powerManager).setIgnoringBatteryOptimizations(context.packageName, true)

        assertTrue(checker.isIgnoringBatteryOptimizations())
    }

    @Test
    fun backgroundDataIsNotRestrictedWhenDataSaverIsOff() {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivityManager)
            .setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED)

        assertFalse(checker.isBackgroundDataRestricted())
    }

    @Test
    fun backgroundDataIsNotRestrictedWhenAppIsWhitelisted() {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivityManager)
            .setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED)

        assertFalse(checker.isBackgroundDataRestricted())
    }

    @Test
    fun backgroundDataIsRestrictedWhenDataSaverBlocksTheApp() {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivityManager)
            .setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)

        assertTrue(checker.isBackgroundDataRestricted())
    }
}
