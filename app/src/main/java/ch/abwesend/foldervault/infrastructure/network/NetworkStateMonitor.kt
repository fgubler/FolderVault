package ch.abwesend.foldervault.infrastructure.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import ch.abwesend.foldervault.domain.model.NetworkPolicy
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Observes whether the device's default network currently satisfies a backup's [NetworkPolicy].
 * Counterpart of [AndroidNetworkConnectivityChecker]'s one-shot snapshot, for consumers that
 * need to react to mid-run changes (the foreground service stops cleanly when a Wi-Fi-only
 * backup loses its unmetered network).
 */
class NetworkStateMonitor(private val context: Context) {

    /**
     * Emits `true`/`false` as the default network starts/stops satisfying [policy], starting
     * with the current state. Note that a network *switch* (Wi-Fi to mobile) emits a transient
     * `false` between `onLost` and the new network's capabilities — collectors should apply a
     * short grace period before treating a `false` as a real violation.
     *
     * When no [ConnectivityManager] is available the flow emits a single `true`: an unobservable
     * network must never veto a run the user explicitly started.
     */
    fun observeSatisfies(policy: NetworkPolicy): Flow<Boolean> = callbackFlow {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        if (connectivityManager == null) {
            trySend(true)
            awaitClose { }
        } else {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    trySend(capabilities.satisfies(policy))
                }

                override fun onLost(network: Network) {
                    trySend(false)
                }
            }
            trySend(currentlySatisfies(connectivityManager, policy))
            connectivityManager.registerDefaultNetworkCallback(callback)
            awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    private fun currentlySatisfies(connectivityManager: ConnectivityManager, policy: NetworkPolicy): Boolean {
        val capabilities = connectivityManager.activeNetwork
            ?.let { connectivityManager.getNetworkCapabilities(it) }
        return capabilities?.satisfies(policy) ?: false
    }

    /** Mirrors the criteria of WorkManager's CONNECTED / UNMETERED constraints. */
    private fun NetworkCapabilities.satisfies(policy: NetworkPolicy): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (policy == NetworkPolicy.ANY || hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
}
