package io.github.kingsword09.kwebshell.service.files

import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.services.KWebServiceGrant
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy
import io.github.kingsword09.kwebshell.services.policy.KWebGestureBinding
import io.github.kingsword09.kwebshell.services.policy.KWebInMemoryConsentStore
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyAudit
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import io.github.kingsword09.kwebshell.services.policy.KWebUserGestureRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmKWebFilesPageOwnerTest {
    @Test
    fun navigationReplacesProviderAndInvalidatesOldHandles() {
        val root = createTempDirectory("kweb-files-owner")
        val gestures = KWebUserGestureRegistry()
        val policy = KWebServicePolicyEngine(
            rendererGrants = KWebServicePermissionPolicy.exact(
                KWebFiles.DESCRIPTOR.operations.map { KWebServiceGrant(KWebFiles.DESCRIPTOR.id, it.id) }.toSet(),
            ),
            gestures = gestures,
            consentStore = KWebInMemoryConsentStore("owner-test"),
            osConsent = null,
            audit = KWebPolicyAudit(),
        )
        val owner = JvmKWebFilesPageOwner(
            engineId = "engine",
            profileId = "profile",
            pageId = "page",
            expectedOrigin = "https://app.example",
            configuration = JvmKWebFilesConfiguration(
                mapOf("documents" to JvmKWebWorkspace(root, KWebFileGrant.entries.toSet())),
            ),
            policyEngine = policy,
        )
        try {
            owner.onNavigationCommitted("https://app.example")
            gestures.mint(KWebGestureBinding("engine", "profile", "page", "https://app.example"))
            val response = kotlinx.coroutines.runBlocking {
                owner.bridgeDispatcher().dispatch(
                    """{"version":1,"method":"openWorkspace","payload":{"workspaceId":"documents","grants":["read","write","create","enumerate","watch","metadata","copy","move"]}}""",
                )
            }
            val handle = Json.parseToJsonElement(response).jsonObject["handle"]!!.jsonPrimitive.content
            owner.onNavigationStarted()
            owner.onNavigationCommitted("https://app.example")
            val error = assertFailsWith<KWebBridgeException> {
                kotlinx.coroutines.runBlocking {
                    owner.bridgeDispatcher().dispatch(
                        """{"version":1,"method":"metadata","payload":{"handle":"$handle"}}""",
                    )
                }
            }
            assertEquals(KWebFilesErrorCode.HANDLE_NOT_FOUND, error.code)
            owner.onNavigationStarted()
            owner.onNavigationCommitted("https://other.example")
            val wrongOrigin = assertFailsWith<KWebBridgeException> {
                kotlinx.coroutines.runBlocking {
                    owner.bridgeDispatcher().dispatch(
                        """{"version":1,"method":"metadata","payload":{"handle":"$handle"}}""",
                    )
                }
            }
            assertEquals("service.owner-closed", wrongOrigin.code)
            owner.close()
            assertEquals(true, owner.isClosed())
        } finally {
            owner.close()
            root.toFile().deleteRecursively()
        }
    }
}
