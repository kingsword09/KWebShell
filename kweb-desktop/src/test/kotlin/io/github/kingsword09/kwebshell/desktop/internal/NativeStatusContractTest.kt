package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeStatusContractTest {
    @Test
    fun nativeStatusValuesAreUniqueAndRoundTrip() {
        val statuses = NativeStatus.entries
        assertEquals(statuses.size, statuses.map { it.value }.toSet().size)
        statuses.forEach { status ->
            assertEquals(status, NativeStatus.fromValue(status.value))
            assertTrue(status.id.isNotBlank())
        }
        assertEquals(66, NativeStatus.NETWORK_PROXY_INVALID.value)
        assertEquals(69, NativeStatus.NETWORK_POLICY_LIMIT_EXCEEDED.value)
        assertEquals(70, NativeStatus.PROFILE_CONTEXT_INITIALIZATION_FAILED.value)
        assertEquals(null, NativeStatus.fromValue(-1))
        assertEquals(null, NativeStatus.fromValue(Int.MAX_VALUE))
    }

    @Test
    fun resolvesOnlyTheThreeAdvertisedDesktopEngines() {
        assertEquals("kwebshell_engine.dll", nativeEngineLibraryFileName("Windows 11"))
        assertEquals("libkwebshell_engine.dylib", nativeEngineLibraryFileName("Mac OS X"))
        assertEquals("libkwebshell_engine.so", nativeEngineLibraryFileName("Linux"))
        val error = assertFailsWith<KWebConfigurationException> {
            nativeEngineLibraryFileName("FreeBSD")
        }
        assertEquals("native.platform.unsupported", error.code)
    }

    @Test
    fun mapsNativeNetworkValidationStatusesToStableContractErrors() {
        val cases = mapOf(
            NativeStatus.WRONG_THREAD to "network.operation-wrong-thread",
            NativeStatus.NETWORK_PROXY_INVALID to "network.proxy.invalid",
            NativeStatus.NETWORK_HEADER_FORBIDDEN to "network.header.forbidden",
            NativeStatus.NETWORK_REDIRECT_INVALID to "network.redirect.invalid",
            NativeStatus.NETWORK_POLICY_LIMIT_EXCEEDED to "network.policy.limit-exceeded",
            NativeStatus.NETWORK_RUNTIME_CAPABILITY_MISSING to
                "network.runtime-capability-missing",
            NativeStatus.PROFILE_CONTEXT_INITIALIZATION_FAILED to
                "profile.context-initialization-failed",
        )
        cases.forEach { (status, expectedCode) ->
            val error = profileNetworkStatusException("configure-network-policy", status.value)
            assertEquals(expectedCode, error.code)
        }
        val fallback = profileNetworkStatusException("configure-network-policy", NativeStatus.INVALID_ARGUMENT.value)
        assertEquals("native.abi.invalid-argument", fallback.code)
    }
}
