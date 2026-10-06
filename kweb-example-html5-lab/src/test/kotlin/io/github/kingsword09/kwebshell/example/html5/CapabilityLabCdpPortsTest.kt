package io.github.kingsword09.kwebshell.example.html5

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CapabilityLabCdpPortsTest {
    @Test
    fun selectedPortCanBindBothLoopbacksExclusively() {
        val port = findCapabilityLabCdpPort()
        ServerSocket().use { first ->
            first.reuseAddress = false
            first.bind(InetSocketAddress("127.0.0.1", port))
            ServerSocket().use { second ->
                second.reuseAddress = false
                second.bind(InetSocketAddress("::1", port))
            }
        }
    }

    @Test
    fun ipv6OnlyListenerCannotBeSelectedAsAnIpv4FreePort() {
        ServerSocket(0, 1, InetAddress.getByName("::1")).use { occupied ->
            var candidates = 0
            val port = findCapabilityLabCdpPort { if (candidates++ == 0) occupied.localPort else 0 }
            assertNotEquals(occupied.localPort, port)
            assertTrue(candidates >= 2)
        }
    }

    @Test
    fun exhaustedCandidatesFailWithoutLeakingIpv4Sockets() {
        ServerSocket(0, 1, InetAddress.getByName("::1")).use { occupied ->
            val failure = assertFailsWith<CapabilityLabException> { findCapabilityLabCdpPort { occupied.localPort } }
            assertEquals("cdp-port-unavailable", failure.code)
            ServerSocket().use { ipv4 ->
                ipv4.reuseAddress = false
                ipv4.bind(InetSocketAddress("127.0.0.1", occupied.localPort))
            }
        }
    }

    @Test
    fun invalidCandidateIsTyped() {
        val failure = assertFailsWith<CapabilityLabException> { findCapabilityLabCdpPort { 80 } }
        assertEquals("cdp-port-invalid", failure.code)
    }
}
