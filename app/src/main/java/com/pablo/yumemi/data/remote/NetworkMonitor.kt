package com.pablo.yumemi.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches the default network and bumps [availableTick] whenever connectivity (re)appears: a network
 * becomes available, or the default network gains validated internet or a VPN transport (Tailscale
 * often comes up a few seconds after the radio does). Collectors skip the value present at subscription (`drop(1)`) and react to changes.
 */
class NetworkMonitor(context: Context) {

    private val _availableTick = MutableStateFlow(0L)
    val availableTick: StateFlow<Long> = _availableTick.asStateFlow()

    private fun bump() {
        _availableTick.value = _availableTick.value + 1
    }

    init {
        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private var validated = false
                private var vpn = false

                override fun onAvailable(network: Network) {
                    bump()
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val nowValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    val nowVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    if ((nowValidated && !validated) || (nowVpn && !vpn)) bump()
                    validated = nowValidated
                    vpn = nowVpn
                }

                override fun onLost(network: Network) {
                    validated = false
                    vpn = false
                }
            })
        } catch (e: Exception) {
            // Without the monitor the app only loses the early retry on network changes.
        }
    }
}
