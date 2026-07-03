package ch.abwesend.foldervault.infrastructure.system

import android.content.Context
import android.net.ConnectivityManager
import android.os.PowerManager
import ch.abwesend.foldervault.domain.system.IBackgroundRestrictionChecker

/**
 * Android implementation of [IBackgroundRestrictionChecker], backed by [PowerManager] (battery
 * optimization / Doze exemption) and [ConnectivityManager] (Data Saver).
 */
class AndroidBackgroundRestrictionChecker(private val context: Context) : IBackgroundRestrictionChecker {

    override fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }

    override fun isBackgroundDataRestricted(): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        return connectivityManager?.restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }
}
