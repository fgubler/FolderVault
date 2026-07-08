package ch.abwesend.foldervault.infrastructure.system

import android.content.Context
import android.os.BatteryManager
import ch.abwesend.foldervault.domain.system.IChargingStateChecker

/**
 * [IChargingStateChecker] backed by [BatteryManager.isCharging], which reports whether the device
 * is currently drawing power from any source (AC, USB or wireless). Mirrors the criterion behind
 * WorkManager's `setRequiresCharging` constraint, so a `true` result means the same "is charging"
 * state that a charging-only backup would be scheduled on.
 */
class AndroidChargingStateChecker(private val context: Context) : IChargingStateChecker {
    override fun isCharging(): Boolean {
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        return batteryManager?.isCharging ?: false
    }
}
