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
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
