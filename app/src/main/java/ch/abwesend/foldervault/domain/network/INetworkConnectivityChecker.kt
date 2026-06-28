package ch.abwesend.foldervault.domain.network

/**
 * Provides a synchronous snapshot of the device's current network state. Used by UI to decide
 * whether a manual backup that requires Wi-Fi should warn the user before running on mobile data.
 */
interface INetworkConnectivityChecker {
    /**
     * Returns `true` if the device is currently connected to an unmetered network (typically
     * Wi-Fi). Mirrors the criterion used by WorkManager's [androidx.work.NetworkType.UNMETERED]
     * constraint, so a `true` result means the same connection that a Wi-Fi-only backup would
     * be scheduled on.
     */
    fun isOnUnmeteredNetwork(): Boolean
}
