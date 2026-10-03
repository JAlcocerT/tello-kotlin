package tech.jalcocer.tello.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher

class TelloWifiConnector(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun request(onAvailable: (Network) -> Unit, onUnavailable: (String) -> Unit) {
        release()
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher("TELLO-", PatternMatcher.PATTERN_PREFIX))
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val next = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onAvailable(network)
            override fun onUnavailable() = onUnavailable("No TELLO Wi-Fi network was selected")
            override fun onLost(network: Network) = onUnavailable("TELLO Wi-Fi connection was lost")
        }
        callback = next
        connectivity.requestNetwork(request, next, NETWORK_REQUEST_TIMEOUT_MS)
    }

    fun release() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
    }

    companion object {
        private const val NETWORK_REQUEST_TIMEOUT_MS = 30_000
    }
}
