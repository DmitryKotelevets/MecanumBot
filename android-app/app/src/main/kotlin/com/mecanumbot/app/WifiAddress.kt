package com.mecanumbot.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

/** The phone's IPv4 address on Wi-Fi, or null without Wi-Fi (spec §6). */
class WifiAddress(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _address = MutableStateFlow<String?>(null)
    val address: StateFlow<String?> = _address.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            _address.value = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
        }

        override fun onLost(network: Network) {
            _address.value = null
        }
    }

    fun start() {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(request, callback)
    }

    fun stop() {
        cm.unregisterNetworkCallback(callback)
    }
}
