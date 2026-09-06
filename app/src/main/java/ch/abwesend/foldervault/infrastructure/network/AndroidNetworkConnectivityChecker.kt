package ch.abwesend.foldervault.infrastructure.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import ch.abwesend.foldervault.domain.network.INetworkConnectivityChecker

class AndroidNetworkConnectivityChecker(private val context: Context) : INetworkConnectivityChecker {
    override fun isOnUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        // Deliberately NOT NET_CAPABILITY_VALIDATED, unlike NetworkStateMonitor: this answers the
        // UI's "would a Wi-Fi-only backup run on this connection?", and WorkManager's UNMETERED
        // constraint — which is what actually schedules that run — does not require validation.
        // Adding it here would warn the user about mobile data they are not on whenever Wi-Fi is
        // briefly unvalidated (just after association, during periodic revalidation), while
        // WorkManager would happily schedule on that very network.
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
