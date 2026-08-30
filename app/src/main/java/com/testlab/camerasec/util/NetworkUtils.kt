package com.testlab.camerasec.util

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Resolves the device's current local-network (LAN) IPv4 address so it can be
 * displayed to the user and used to bind the local test server.
 *
 * This deliberately only looks at interfaces already active on the device
 * (Wi-Fi in the normal case) — it does not perform any discovery, scanning,
 * or STUN/relay trick to find a public-facing address. If the device isn't
 * on a network, it returns null and the UI reports "Not connected".
 */
object NetworkUtils {

    fun getLocalIpAddress(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (netInterface in interfaces) {
                if (!netInterface.isUp || netInterface.isLoopback) continue
                val addresses = Collections.list(netInterface.inetAddresses)
                for (addr in addresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        // Prefer typical private LAN ranges (Wi-Fi / hotspot).
                        if (host.startsWith("192.168.") ||
                            host.startsWith("10.") ||
                            host.startsWith("172.")
                        ) {
                            return host
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
