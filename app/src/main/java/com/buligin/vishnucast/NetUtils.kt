package com.buligin.vishnucast

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.net.InetSocketAddress

object NetUtils {

    // Возвращает локальный IPv4 с приоритетом wlan*/ap*/eth* и приватных сетей
    fun getLocalIpv4(ctx: Context): String? {
        // 1) Прямой перебор интерфейсов — приоритезируем по имени
        val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val sorted = ifaces.sortedBy { rankIface(it.name ?: "") }
        for (ni in sorted) {
            if (!ni.isUp || ni.isLoopback) continue
            val addr = ni.inetAddresses?.toList()?.firstOrNull { it is Inet4Address && isPrivateIpv4(it.hostAddress) }
            if (addr != null) return addr.hostAddress
        }

        // 2) Fallback через UDP-сокет к 8.8.8.8
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("8.8.8.8", 53), 800)
                val ip = (s.localAddress as? Inet4Address)?.hostAddress
                if (ip != null) return ip
            }
        } catch (_: Throwable) {}

        // 3) Попытка через WifiManager (иногда работает в AP-режиме)
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo?.ipAddress
            if (ip != null && ip != 0) {
                val s = String.format(
                    "%d.%d.%d.%d",
                    (ip and 0xff), (ip shr 8 and 0xff), (ip shr 16 and 0xff), (ip shr 24 and 0xff)
                )
                if (isPrivateIpv4(s)) return s
            }
        } catch (_: Throwable) {}

        // 4) Через ConnectivityManager (малоинформативно, но вдруг)
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(n) ?: return null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                val linkProps = cm.getLinkProperties(n)
                val la = linkProps?.linkAddresses?.firstOrNull { it.address is Inet4Address }
                if (la != null) return (la.address as Inet4Address).hostAddress
            }
        } catch (_: Throwable) {}

        return null
    }

    private fun isPrivateIpv4(addr: String?): Boolean {
        if (addr.isNullOrEmpty()) return false
        return addr.startsWith("10.") ||
                addr.startsWith("192.168.") ||
                addr.split(".").let { parts ->
                    if (parts.size != 4) false
                    else {
                        val p0 = parts[0].toIntOrNull() ?: return false
                        val p1 = parts[1].toIntOrNull() ?: return false
                        p0 == 172 && p1 in 16..31
                    }
                }
    }

    private fun rankIface(name: String): Int {
        val n = name.lowercase()
        return when {
            n.startsWith("ap")   -> 0
            n.startsWith("wlan") -> 1
            n.startsWith("eth")  -> 2
            else -> 10
        }
    }
}


    enum class NetKind { AP, WIFI, ETH, OTHER }

    fun detectNetKind(ctx: Context, ip: String?): NetKind {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(active)

        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return NetKind.ETH
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))     return NetKind.WIFI
        }

        if (ip != null) {
            try {
                val target = java.net.InetAddress.getByName(ip)
                val ifs = java.net.NetworkInterface.getNetworkInterfaces()
                while (ifs.hasMoreElements()) {
                    val ni = ifs.nextElement()
                    val addrs = ni.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val a = addrs.nextElement()
                        if (a.hostAddress == target.hostAddress) {
                            val n = ni.name.lowercase()
                            if (n.startsWith("ap")) return NetKind.AP
                            if (n.startsWith("wlan")) {
                                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                                return if (!wm.isWifiEnabled) NetKind.AP else NetKind.WIFI
                            }
                            if (n.startsWith("eth")) return NetKind.ETH
                            return NetKind.OTHER
                        }
                    }
                }
            } catch (_: Throwable) { /* no-op */ }
        }

        if (ip != null && isPrivateIpv4(ip) && active == null) return NetKind.AP
        return NetKind.OTHER
    }

