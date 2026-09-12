package io.github.kingsword09.kwebshell.services.consent

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.services.policy.KWebConsentRequest
import io.github.kingsword09.kwebshell.services.policy.KWebConsentStatus
import io.github.kingsword09.kwebshell.services.policy.KWebOsConsentProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Exercises the real OS consent facility for the current target and retains
 * whatever status the OS reports. The test never flips OS state and never
 * automates around a system denial; it asserts only that the provider surfaces
 * a real, deterministic OS decision.
 */
class KWebOsConsentProvidersTest {
    private fun currentTarget(): KWebTarget {
        val operatingSystem = System.getProperty("os.name").lowercase().let {
            when {
                it.startsWith("windows") -> "windows"
                it.startsWith("mac") -> "macos"
                else -> "linux"
            }
        }
        val architecture = when (System.getProperty("os.arch").lowercase()) {
            "x86_64", "amd64" -> "x64"
            else -> "arm64"
        }
        return KWebTarget.parse("$operatingSystem-$architecture")
    }

    private fun providerFor(target: KWebTarget): KWebOsConsentProvider = when (target.operatingSystem.id) {
        "windows" -> WindowsCapabilityAccessConsentProvider("webcam")
        "macos" -> MacOsTccConsentProvider("accessibility")
        else -> LinuxPortalPermissionStoreConsentProvider("kwebshell-test")
    }

    @Test
    fun realOsFacilityReportsADeterministicStatus() = runBlocking {
        val provider = providerFor(currentTarget())
        val request = KWebConsentRequest("test-service", "op", "https://app.example", provider.facility)
        val first = provider.status(request)
        val second = provider.status(request)
        assertTrue(first in KWebConsentStatus.entries, "The provider must surface a real OS status.")
        assertEquals(first, second, "The same OS facility must report the same status twice in a row.")
    }

    @Test
    fun invalidFacilityNamesFailTyped() {
        assertFailsWith<KWebConfigurationException> { WindowsCapabilityAccessConsentProvider("bad\\name") }
        assertFailsWith<KWebConfigurationException> { LinuxPortalPermissionStoreConsentProvider("bad table") }
    }
}

class KWebFileConsentStoreTest {
    private fun tempFile(): Path = Files.createTempFile("kweb-consent", ".json")

    @Test
    fun decisionsPersistAcrossInstancesAndRevokeRemovesThem() {
        val file = tempFile()
        val first = KWebFileConsentStore("profile-alpha", file)
        val request = KWebConsentRequest("test-service", "op", "https://app.example", "facility")
        first.record(request, granted = true, decidedBy = "user")
        assertEquals(true, first.decision(request)?.granted)
        first.close()

        val second = KWebFileConsentStore("profile-alpha", file)
        assertEquals(true, second.decision(request)?.granted)
        second.revoke(request, decidedBy = "user")
        assertEquals(null, second.decision(request))
        second.close()

        val third = KWebFileConsentStore("profile-alpha", file)
        assertEquals(null, third.decision(request))
    }

    @Test
    fun scopeMismatchIsRejected() {
        val file = tempFile()
        val store = KWebFileConsentStore("profile-alpha", file)
        store.record(
            KWebConsentRequest("test-service", "op", "https://app.example", "facility"),
            granted = true,
            decidedBy = "user",
        )
        store.close()
        val failure = assertFailsWith<KWebConfigurationException> {
            KWebFileConsentStore("profile-beta", file)
        }
        assertEquals("service.consent.store-scope-mismatch", failure.code)
    }
}
