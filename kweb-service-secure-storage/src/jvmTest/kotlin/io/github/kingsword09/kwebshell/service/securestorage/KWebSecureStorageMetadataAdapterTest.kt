package io.github.kingsword09.kwebshell.service.securestorage

import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceGrant
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.policy.KWebGestureBinding
import io.github.kingsword09.kwebshell.services.policy.KWebInMemoryConsentStore
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyAudit
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import io.github.kingsword09.kwebshell.services.policy.KWebUserGestureRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The opt-in renderer metadata adapter: only an application-declared allowlist is
 * summarized, a secret byte is never returned, and a missing grant or gesture is
 * denied before anything is read.
 */
class KWebSecureStorageMetadataAdapterTest {
    private inner class RecordingStorage : KWebSecureStorage {
        override val descriptor: KWebServiceDescriptor = KWebSecureStorage.DESCRIPTOR
        override val lifecycle: StateFlow<KWebLifecycleState> =
            MutableStateFlow(KWebLifecycleState.OPEN).asStateFlow()

        val readIds = mutableListOf<KWebSecretId>()

        override suspend fun capabilities(): KWebSecureStorageCapabilities = KWebSecureStorageCapabilities(
            providerId = "secure-storage.test",
            backend = KWebSecretBackend.MACOS_KEYCHAIN,
            flags = setOf(
                KWebSecureStorageCapability.ACCESSIBILITY_POLICY,
                KWebSecureStorageCapability.USER_PRESENCE,
                KWebSecureStorageCapability.ROTATION,
            ),
            accessibilities = setOf(KWebSecretAccessibility.WHEN_UNLOCKED),
            maxValueBytes = KWEB_SECRET_MAX_VALUE_BYTES,
        )

        override suspend fun put(request: KWebSecretPutRequest): KWebSecretMetadata =
            KWebSecretMetadata(
                request.reference,
                KWebSecretBackend.MACOS_KEYCHAIN,
                request.accessibility,
                KWEB_SECRET_ENVELOPE_VERSION,
                1,
                request.value.size,
            )

        override suspend fun get(id: KWebSecretRef): KWebSecretValue {
            readIds += id.id
            return KWebSecretValue.of(ByteArray(12) { 7 })
        }

        override suspend fun delete(id: KWebSecretRef): Boolean = true

        override suspend fun exists(id: KWebSecretRef): Boolean = true

        override suspend fun rotate(id: KWebSecretRef): KWebSecretMetadata =
            KWebSecretMetadata(id, KWebSecretBackend.MACOS_KEYCHAIN, KWebSecretAccessibility.WHEN_UNLOCKED, 1, 2, 12)

        override suspend fun rotationStatus(id: KWebSecretRef): KWebSecretRotationStatus =
            KWebSecretRotationStatus.CURRENT

        override fun close() = Unit
    }

    private fun subject(mainFrame: Boolean = true) = KWebPolicySubject(
        engineId = "engine-1",
        profileId = "profile-1",
        pageId = "page-1",
        origin = "https://app.example",
        scope = KWebServiceScope.APPLICATION,
        isMainFrame = mainFrame,
        hostCall = false,
    )

    private fun engine(
        grants: Set<KWebServiceGrant>,
        gestures: KWebUserGestureRegistry = KWebUserGestureRegistry(),
    ) = KWebServicePolicyEngine(
        rendererGrants = KWebServicePermissionPolicy.exact(grants),
        gestures = gestures,
        consentStore = KWebInMemoryConsentStore("secure-storage-test"),
        osConsent = null,
        audit = KWebPolicyAudit(),
    )

    private fun grant(permission: String) = KWebServiceGrant(KWebSecureStorage.DESCRIPTOR.id, "read-metadata")
        .let { KWebServiceGrant(KWebSecureStorage.DESCRIPTOR.id, "read-metadata") }

    private fun declared() = listOf(
        KWebSecureStorageMetadataAdapter.Declared(KWebSecretRef(KWebSecretId("declared-one"))),
        KWebSecureStorageMetadataAdapter.Declared(
            KWebSecretRef(KWebSecretId("declared-profile"), KWebSecretScope.PROFILE, "profile-1"),
        ),
    )

    private fun dispatcher(
        storage: RecordingStorage,
        grants: Set<String>,
        gestures: KWebUserGestureRegistry = KWebUserGestureRegistry(),
        mainFrame: Boolean = true,
    ) = KWebSecureStorageMetadataAdapter.dispatcher(
        storage = storage,
        declared = declared(),
        policyEngine = engine(
            grants.map { KWebServiceGrant(KWebSecureStorage.DESCRIPTOR.id, "read-metadata") }.toSet(),
            gestures,
        ),
        subject = subject(mainFrame),
        operationPermission = "secure-storage.read-metadata",
        requiresUserGesture = false,
    )

    @Test
    fun onlyDeclaredIdentifiersAreSummarizedWithoutSecretBytes() = runBlocking {
        val storage = RecordingStorage()
        val response = dispatcher(storage, setOf("secure-storage.read-metadata")).dispatch(
            """{"version":1,"method":"readMetadata","payload":{"facts":["backend"],"secretIds":["declared-one","declared-profile"]}}""",
        )
        assertTrue(response.contains("\"backend\":\"macos_keychain\""), response)
        assertTrue(response.contains("\"id\":\"declared-one\""), response)
        assertTrue(response.contains("\"scope\":\"profile\""), response)
        // Only the sizes are exposed, never the bytes.
        assertTrue(response.contains("\"sizeBytes\":12"), response)
        assertTrue(!response.contains("7777777"), response)
        assertEquals(2, storage.readIds.size)
    }

    @Test
    fun anUndeclaredIdentifierIsDenied() = runBlocking {
        val storage = RecordingStorage()
        val denied = assertFailsWith<KWebBridgeException> {
            dispatcher(storage, setOf("secure-storage.read-metadata")).dispatch(
                """{"version":1,"method":"readMetadata","payload":{"facts":["backend"],"secretIds":["not-declared"]}}""",
            )
        }
        assertEquals(KWebSecureStorageErrorCode.SECRET_NOT_FOUND, denied.code)
        assertTrue(storage.readIds.isEmpty(), "an undeclared identifier reached the store")
    }

    @Test
    fun aMissingGrantOrAChildFrameIsDenied() = runBlocking {
        val storage = RecordingStorage()
        val missingGrant = assertFailsWith<KWebBridgeException> {
            dispatcher(storage, emptySet()).dispatch(
                """{"version":1,"method":"readMetadata","payload":{"facts":["backend"],"secretIds":["declared-one"]}}""",
            )
        }
        assertEquals("service.permission-denied", missingGrant.code)
        val childFrame = assertFailsWith<KWebBridgeException> {
            dispatcher(storage, setOf("secure-storage.read-metadata"), mainFrame = false).dispatch(
                """{"version":1,"method":"readMetadata","payload":{"facts":["backend"],"secretIds":["declared-one"]}}""",
            )
        }
        assertEquals("service.policy.denied-frame", childFrame.code)
        assertTrue(storage.readIds.isEmpty())
    }

    @Test
    fun aGestureIsRequiredWhenTheAdapterDeclaresOne() = runBlocking {
        val storage = RecordingStorage()
        val gestures = KWebUserGestureRegistry()
        val dispatcher = KWebSecureStorageMetadataAdapter.dispatcher(
            storage = storage,
            declared = declared(),
            policyEngine = engine(
                setOf(KWebServiceGrant(KWebSecureStorage.DESCRIPTOR.id, "read-metadata")),
                gestures,
            ),
            subject = subject(),
            operationPermission = "secure-storage.read-metadata",
            requiresUserGesture = true,
        )
        val noGesture = assertFailsWith<KWebBridgeException> {
            dispatcher.dispatch(
                """{"version":1,"method":"readMetadata","payload":{"facts":["backend"],"secretIds":["declared-one"]}}""",
            )
        }
        assertEquals("service.user-gesture-required", noGesture.code)
        gestures.mint(KWebGestureBinding("engine-1", "profile-1", "page-1", "https://app.example"))
        val granted = dispatcher.dispatch(
            """{"version":1,"method":"readMetadata","payload":{"facts":["backend"],"secretIds":["declared-one"]}}""",
        )
        assertTrue(granted.contains("\"id\":\"declared-one\""), granted)
    }
}
