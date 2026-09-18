package com.cuppa.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * NetworkUtils — Dynamic network state and address resolution.
 *
 * Dynamically identifies the device's local network IPv4 address (Wi-Fi, Ethernet, or USB tethering),
 * formats IPP URIs, and resolves active network configuration for driverless sharing.
 */
object NetworkUtils {

    private const val TAG = "NetworkUtils"

    /**
     * Get the active local IPv4 address of the Android device on Wi-Fi or Ethernet.
     * Returns null if no active network connection with an IPv4 address is found.
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val candidates = mutableListOf<Pair<String, String>>()

            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        candidates.add(Pair(intf.name.lowercase(), host))
                    }
                }
            }

            // Prioritize standard Wi-Fi (wlan0), then Ethernet (eth0), then any valid IPv4
            val wlan = candidates.find { it.first.startsWith("wlan") }
            if (wlan != null) return wlan.second

            val eth = candidates.find { it.first.startsWith("eth") }
            if (eth != null) return eth.second

            val anySiteLocal = candidates.find {
                it.second.startsWith("192.168.") || it.second.startsWith("10.") || it.second.startsWith("172.")
            }
            if (anySiteLocal != null) return anySiteLocal.second

            return candidates.firstOrNull()?.second
        } catch (e: Exception) {
            CuppaLog.w(TAG, "Failed resolving local IPv4 address", e)
            return null
        }
    }

    /**
     * Format a complete IPP URI for a given printer name and active port.
     */
    fun getPrinterIppUri(printerName: String, port: Int): String {
        val ip = getLocalIpAddress() ?: "127.0.0.1"
        return "ipp://$ip:$port/printers/${PrinterNaming.resourceName(printerName)}"
    }

    /**
     * Every IP address currently assigned to this device (all interfaces, IPv4 and IPv6, without
     * zone suffixes). Used to recognize mDNS services that are really Cuppa itself.
     */
    fun getAllLocalAddresses(): Set<String> {
        val out = mutableSetOf<String>()
        try {
            for (intf in NetworkInterface.getNetworkInterfaces() ?: return out) {
                for (addr in intf.inetAddresses) {
                    addr.hostAddress?.substringBefore('%')?.let { out.add(it.lowercase()) }
                }
            }
        } catch (e: Exception) {
            CuppaLog.w(TAG, "Failed enumerating local addresses", e)
        }
        return out
    }

    /**
     * Format the base IPP endpoint URL.
     */
    fun getBaseIppUrl(port: Int): String {
        val ip = getLocalIpAddress() ?: "127.0.0.1"
        return "ipp://$ip:$port/printers/"
    }

    /**
     * Get human-readable connection summary for UI display.
     */
    fun getConnectionSummary(context: Context, port: Int): String {
        val ip = getLocalIpAddress()
        return if (ip != null) {
            "Active on $ip:$port"
        } else {
            "Localhost only (127.0.0.1:$port)"
        }
    }
}
