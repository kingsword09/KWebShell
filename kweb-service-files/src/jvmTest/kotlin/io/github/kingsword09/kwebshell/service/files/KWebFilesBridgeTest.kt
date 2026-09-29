package io.github.kingsword09.kwebshell.service.files

import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.KWebServiceGrant
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.policy.KWebInMemoryConsentStore
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyAudit
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import io.github.kingsword09.kwebshell.services.policy.KWebUserGestureRegistry
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class KWebFilesBridgeTest {
    @Test
    fun hostMetadataUsesNamedGeneratedOperationWithoutPathDisclosure() = runBlocking {
        val root = createTempDirectory("kweb-files-bridge")
        try {
            val service = JvmKWebFiles.open(
                owner(),
                JvmKWebFilesConfiguration(mapOf("documents" to JvmKWebWorkspace(root, KWebFileGrant.entries.toSet()))),
            )
            val workspace = service.openWorkspace(KWebWorkspaceRequest("documents", KWebFileGrant.entries.toSet()))
            val file = service.openFile(KWebFileOpenRequest(
                KWebFileHandle(workspace.handle),
                "bridge.txt",
                KWebFileOpenMode.READ_WRITE,
                createIfMissing = true,
            ))
            service.writeFile(KWebFileHandle(file.handle), 0, "bridge".encodeToByteArray())
            val grants = KWebFiles.DESCRIPTOR.operations.mapTo(mutableSetOf()) {
                KWebServiceGrant(KWebFiles.DESCRIPTOR.id, it.id)
            }
            val policy = KWebServicePolicyEngine(
                rendererGrants = KWebServicePermissionPolicy.exact(grants),
                gestures = KWebUserGestureRegistry(),
                consentStore = KWebInMemoryConsentStore("files-bridge"),
                osConsent = null,
                audit = KWebPolicyAudit(),
            )
            val dispatcher = service.bridgeDispatcher(policy, owner().toPolicySubject(hostCall = true))
            val response = dispatcher.dispatch(
                """{"version":1,"method":"metadata","payload":{"handle":"${file.handle}"}}""",
            )
            assertContains(response, "bridge.txt")
            assertContains(response, "lastModifiedEpochMillis")
            assertFalse(response.contains(root.toString()))
            service.close()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun owner(): KWebFileOwnerScope = KWebFileOwnerScope(
        engineId = "engine",
        profileId = "profile",
        pageId = "page",
        origin = "https://app.example",
        navigationId = 1,
    )

    private fun KWebFileOwnerScope.toPolicySubject(hostCall: Boolean): KWebPolicySubject = KWebPolicySubject(
        engineId = engineId,
        profileId = profileId,
        pageId = pageId,
        origin = origin,
        scope = KWebServiceScope.PAGE,
        hostCall = hostCall,
    )
}
