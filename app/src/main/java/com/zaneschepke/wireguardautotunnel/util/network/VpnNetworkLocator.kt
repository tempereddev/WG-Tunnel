package com.zaneschepke.wireguardautotunnel.util.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.delay

/**
 * Finds the Android [Network] object that corresponds to an active VPN tunnel. Requests routed
 * through that Network go out the tunnel, so external APIs see the tunnel's exit IP rather than
 * the underlying cellular/Wi-Fi IP.
 */
class VpnNetworkLocator(private val context: Context) {

    /**
     * Polls connectivity manager for a VPN network up to [timeoutMillis]. Returns null if the VPN
     * network never appears (tunnel failed, kernel mode, or non-VPN backend). Safe to call from
     * any thread.
     */
    suspend fun awaitVpnNetwork(
        timeoutMillis: Long = 5_000,
        pollIntervalMillis: Long = 250,
    ): Network? {
        val cm =
            context.getSystemService(ConnectivityManager::class.java) ?: return null
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val net = findVpn(cm)
            if (net != null) return net
            delay(pollIntervalMillis)
        }
        return null
    }

    /** Non-suspending snapshot lookup. */
    fun findVpnNow(): Network? {
        val cm =
            context.getSystemService(ConnectivityManager::class.java) ?: return null
        return findVpn(cm)
    }

    private fun findVpn(cm: ConnectivityManager): Network? {
        val candidates =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.allNetworks else return null
        return candidates.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
