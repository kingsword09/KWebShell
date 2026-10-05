package io.github.kingsword09.kwebshell.example.html5

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

internal fun findCapabilityLabCdpPort(nextCandidate: () -> Int = { 0 }): Int {
    val ipv4 = InetAddress.getByName("127.0.0.1")
    val ipv6 = InetAddress.getByName("::1")
    val hasIpv6Loopback = NetworkInterface.getByInetAddress(ipv6) != null
    var lastFailure: IOException? = null
    repeat(32) {
        val candidate = nextCandidate()
        if (candidate != 0 && candidate !in 1024..65535) {
            throw CapabilityLabException("cdp-port-invalid", "A CDP port candidate must be zero or an unprivileged port.")
        }
        try {
            ServerSocket().use { first ->
                first.reuseAddress = false
                first.bind(InetSocketAddress(ipv4, candidate), 1)
                val port = first.localPort
                if (port !in 1024..65535) {
                    throw CapabilityLabException("cdp-port-invalid", "The OS allocated an invalid CDP port.")
                }
                if (hasIpv6Loopback) {
                    ServerSocket().use { second ->
                        second.reuseAddress = false
                        second.bind(InetSocketAddress(ipv6, port), 1)
                    }
                }
                return port
            }
        } catch (error: IOException) {
            lastFailure = error
        }
    }
    throw CapabilityLabException(
        "cdp-port-unavailable",
        "No exclusive CDP port was available on the configured loopback address families after 32 attempts.",
        lastFailure,
    )
}
