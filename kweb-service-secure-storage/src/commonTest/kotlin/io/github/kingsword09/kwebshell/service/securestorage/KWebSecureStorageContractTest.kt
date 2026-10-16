package io.github.kingsword09.kwebshell.service.securestorage

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KWebSecureStorageContractTest {
    private fun reference(
        id: String = "api-token",
        scope: KWebSecretScope = KWebSecretScope.APPLICATION,
        owner: String? = null,
    ) = KWebSecretRef(KWebSecretId(id), scope, owner)

    @Test
    fun identifiersAreBoundedAndStable() {
        assertEquals("api-token", KWebSecretId("api-token").value)
        KWebSecretId("oauth.refresh_1")
        assertFailsWith<KWebConfigurationException> { KWebSecretId("1token") }
        assertFailsWith<KWebConfigurationException> { KWebSecretId("api token") }
        assertFailsWith<KWebConfigurationException> { KWebSecretId("a".repeat(129)) }
        assertFailsWith<KWebConfigurationException> { KWebSecretId("") }
    }

    @Test
    fun scopeOwnershipIsExclusive() {
        reference(scope = KWebSecretScope.APPLICATION)
        reference(scope = KWebSecretScope.PROFILE, owner = "profile-1")
        val applicationWithOwner = assertFailsWith<KWebConfigurationException> {
            reference(scope = KWebSecretScope.APPLICATION, owner = "profile-1")
        }
        assertEquals(KWebSecureStorageErrorCode.SCOPE_INVALID, applicationWithOwner.code)
        val profileWithoutOwner = assertFailsWith<KWebConfigurationException> {
            reference(scope = KWebSecretScope.PROFILE)
        }
        assertEquals(KWebSecureStorageErrorCode.SCOPE_INVALID, profileWithoutOwner.code)
        assertFailsWith<KWebConfigurationException> {
            reference(scope = KWebSecretScope.PROFILE, owner = "   ")
        }
        assertFailsWith<KWebConfigurationException> {
            reference(scope = KWebSecretScope.PROFILE, owner = "p".repeat(KWEB_SECRET_MAX_OWNER_LENGTH + 1))
        }
        assertFailsWith<KWebConfigurationException> {
            reference(scope = KWebSecretScope.PROFILE, owner = "bad\u0000owner")
        }
    }

    @Test
    fun valuesAreBounded() {
        KWebSecretPutRequest(reference(), ByteArray(KWEB_SECRET_MAX_VALUE_BYTES) { 1 })
        KWebSecretPutRequest(reference(), byteArrayOf(1))
        val empty = assertFailsWith<KWebConfigurationException> {
            KWebSecretPutRequest(reference(), ByteArray(0))
        }
        assertEquals(KWebSecureStorageErrorCode.VALUE_INVALID, empty.code)
        val tooLarge = assertFailsWith<KWebConfigurationException> {
            KWebSecretPutRequest(reference(), ByteArray(KWEB_SECRET_MAX_VALUE_BYTES + 1))
        }
        assertEquals(KWebSecureStorageErrorCode.VALUE_INVALID, tooLarge.code)
    }

    @Test
    fun requestsCompareByValueContent() {
        assertEquals(
            KWebSecretPutRequest(reference(), byteArrayOf(1, 2, 3)),
            KWebSecretPutRequest(reference(), byteArrayOf(1, 2, 3)),
        )
        assertFalse(
            KWebSecretPutRequest(reference(), byteArrayOf(1, 2, 3)) ==
                KWebSecretPutRequest(reference(), byteArrayOf(1, 2, 4)),
        )
    }

    @Test
    fun secretValuesZeroOnCloseAndNeverRevealBytes() {
        val value = KWebSecretValue.of(byteArrayOf(9, 8, 7))
        assertEquals(3, value.size)
        assertTrue(value.copyBytes().contentEquals(byteArrayOf(9, 8, 7)))
        assertTrue(!value.toString().contains("9"), value.toString())
        assertTrue(!value.toString().contains("8"), value.toString())
        value.close()
        assertEquals(0, value.size)
        val closed = assertFailsWith<KWebConfigurationException> { value.copyBytes() }
        assertEquals(KWebSecureStorageErrorCode.VALUE_CLOSED, closed.code)
        // Closing twice is a no-op rather than a failure.
        value.close()
    }

    @Test
    fun capabilityReportAnswersDeclaredQueries() {
        val capabilities = KWebSecureStorageCapabilities(
            providerId = "secure-storage.macos.keychain",
            backend = KWebSecretBackend.MACOS_KEYCHAIN,
            flags = setOf(
                KWebSecureStorageCapability.ACCESSIBILITY_POLICY,
                KWebSecureStorageCapability.USER_PRESENCE,
                KWebSecureStorageCapability.ROTATION,
                KWebSecureStorageCapability.PROFILE_SCOPE,
            ),
            accessibilities = setOf(KWebSecretAccessibility.WHEN_UNLOCKED),
            maxValueBytes = KWEB_SECRET_MAX_VALUE_BYTES,
        )
        assertTrue(capabilities.supports(KWebSecureStorageCapability.USER_PRESENCE))
        assertTrue(capabilities.supports(KWebSecretAccessibility.WHEN_UNLOCKED))
        assertFalse(capabilities.supports(KWebSecretAccessibility.AFTER_FIRST_UNLOCK))
    }

    @Test
    fun descriptorPublishesHostOperationsOnly() {
        val descriptor = KWebSecureStorage.DESCRIPTOR
        assertEquals("secure-storage", descriptor.id)
        assertEquals("1.0.0", descriptor.version.toString())
        assertEquals(3, descriptor.supportedTargets.size)
        // A secret is never renderer-reachable through the service descriptor.
        assertTrue(descriptor.operations.all { it.rendererPermission == null })
        assertEquals(
            setOf("capabilities", "put", "get", "delete", "exists", "rotate", "rotation-status"),
            descriptor.operations.map { it.id }.toSet(),
        )
        assertEquals(KWebLifecycleState.OPEN, KWebLifecycleState.OPEN)
    }
}
