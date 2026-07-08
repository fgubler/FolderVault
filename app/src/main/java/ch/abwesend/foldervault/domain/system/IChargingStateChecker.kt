package ch.abwesend.foldervault.domain.system

/**
 * Provides a synchronous snapshot of whether the device is currently charging. Used by the UI to
 * decide whether a manual backup that is pinned to "only while charging" should warn the user
 * before it silently waits for power.
 *
 * This is a UI-only hint: the actual run-time gate stays with WorkManager's charging constraint,
 * never with this checker.
 */
interface IChargingStateChecker {
    /** Returns `true` if the device is currently plugged in / charging. */
    fun isCharging(): Boolean
}
