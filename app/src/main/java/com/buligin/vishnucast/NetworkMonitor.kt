package com.buligin.vishnucast

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.os.Build
import java.util.concurrent.atomic.AtomicReference

class NetworkMonitor(
    ctx: Context,
    private val onBestIpChanged: (String?) -> Unit
) {
    private val app = ctx.applicationContext
    private val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val lastIp = AtomicReference<String?>(null)

    private val cb = object : NetworkCallback() {
        override fun onAvailable(network: Network) = recompute()
        override fun onLost(network: Network) = recompute()
        override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) = recompute()
        override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = recompute()
    }

    private fun recompute() {
        val current = NetUtils.getLocalIpv4(app)
        val prev = lastIp.getAndSet(current)
        if (current != prev) {
            onBestIpChanged(current)
        }
    }

    fun start() {
        // Нужен «дефолтный» коллбек, чтобы реагировать на все изменения
        if (Build.VERSION.SDK_INT >= 24) {
            cm.registerDefaultNetworkCallback(cb)
        } else {
            // Fallback для старых — всё равно сработают onLost/onAvailable по активной сети
            cm.registerNetworkCallback(android.net.NetworkRequest.Builder().build(), cb)
        }
        recompute() // сразу дать актуальное значение
    }

    fun stop() {
        try { cm.unregisterNetworkCallback(cb) } catch (_: Throwable) {}
    }
}
