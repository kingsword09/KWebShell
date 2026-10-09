package io.github.kingsword09.kwebshell.service.menus

import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.service.menus.generated.ShowDeclaredPopupRequest
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The renderer surface of the menus service: one declared-menu popup operation
 * that is exact-origin, main-frame, grant, and gesture checked, and that can
 * never carry a menu template.
 */
class KWebMenusBridgeTest {
    private val pageToken = KWebMenuPageToken("page-token")

    private class RecordingMenus : KWebMenus {
        override val descriptor: KWebServiceDescriptor = KWebMenus.DESCRIPTOR
        override val lifecycle: StateFlow<KWebLifecycleState> = MutableStateFlow(KWebLifecycleState.OPEN)
        override val events: Flow<KWebMenuEvent> = emptyFlow()
        val popups = mutableListOf<KWebMenuPopupRequest>()

        override suspend fun capabilities(): KWebMenuCapabilities = KWebMenuCapabilities(
            providerId = "test",
            flags = setOf(KWebMenuCapability.PAGE_MENU, KWebMenuCapability.POPUP_POSITIONING),
            nativeRoles = emptySet(),
        )

        override suspend fun setApplicationMenu(tree: KWebMenuTree?): KWebMenuTreeResult =
            throw UnsupportedOperationException("not used")

        override suspend fun setWindowMenu(windowId: KWebMenuWindowId, tree: KWebMenuTree?): KWebMenuTreeResult =
            throw UnsupportedOperationException("not used")

        override suspend fun declarePageMenu(
            pageToken: KWebMenuPageToken,
            tree: KWebMenuTree,
        ): KWebMenuTreeResult = throw UnsupportedOperationException("not used")

        override suspend fun clearPageMenu(pageToken: KWebMenuPageToken, menuId: KWebMenuId) {
            throw UnsupportedOperationException("not used")
        }

        override suspend fun showPopup(request: KWebMenuPopupRequest): KWebMenuPopupOutcome {
            popups += request
            return KWebMenuPopupOutcome.Dismissed(
                popupId = KWebMenuPopupId("popup-1"),
                owner = request.owner,
                menuId = request.menuId,
                treeVersion = 7,
                reason = KWebMenuDismissReason.USER_DISMISSED,
            )
        }

        override fun close() = Unit
    }

    private fun subject(hostCall: Boolean = false, mainFrame: Boolean = true) = KWebPolicySubject(
        engineId = "engine-1",
        profileId = "profile-1",
        pageId = "page-1",
        origin = "https://app.example",
        scope = KWebServiceScope.APPLICATION,
        isMainFrame = mainFrame,
        hostCall = hostCall,
    )

    private fun engine(
        grants: Set<KWebServiceGrant>,
        gestures: KWebUserGestureRegistry = KWebUserGestureRegistry(),
        audit: KWebPolicyAudit = KWebPolicyAudit(),
    ) = KWebServicePolicyEngine(
        rendererGrants = KWebServicePermissionPolicy.exact(grants),
        gestures = gestures,
        consentStore = KWebInMemoryConsentStore("menus-test"),
        osConsent = null,
        audit = audit,
    )

    private fun grant() = KWebServiceGrant(
        KWebMenus.DESCRIPTOR.id,
        KWebMenus.DESCRIPTOR.operations.single { it.id == "show-declared-popup" }.id,
    )

    @Test
    fun aGrantedGestureBoundRendererRequestPresentsTheDeclaredPageMenuOnly() = runBlocking {
        val menus = RecordingMenus()
        val gestures = KWebUserGestureRegistry()
        val dispatcher = menus.bridgeDispatcher(
            engine(grants = setOf(grant()), gestures = gestures),
            subject(),
            pageToken,
        )
        gestures.mint(
            KWebGestureBinding(
                engineId = "engine-1",
                profileId = "profile-1",
                pageId = "page-1",
                origin = "https://app.example",
            ),
        )
        val response = dispatcher.dispatch(
            """{"version":1,"method":"showDeclaredPopup","payload":{"menuId":"editor.context","x":12,"y":34}}""",
        )
        assertEquals(1, menus.popups.size)
        val request = menus.popups.single()
        assertEquals(KWebMenuId("editor.context"), request.menuId)
        assertEquals(KWebMenuOwner.Page(pageToken), request.owner)
        assertEquals(KWebMenuPopupSource.RENDERER, request.source)
        assertEquals(KWebMenuPosition(KWebMenuCoordinateSpace.PAGE, 12, 34), request.position)
        assertEquals(true, response.contains("\"popupId\":\"popup-1\""))
        assertEquals(true, response.contains("\"treeVersion\":7"))
    }

    @Test
    fun aMissingGrantOrChildFrameIsDeniedBeforeDispatch() = runBlocking {
        val menus = RecordingMenus()
        val missingGrant = assertFailsWith<KWebBridgeException> {
            menus.bridgeDispatcher(engine(grants = emptySet()), subject(), pageToken)
                .dispatch(popupEnvelope())
        }
        assertEquals("service.permission-denied", missingGrant.code)
        val childFrame = assertFailsWith<KWebBridgeException> {
            menus.bridgeDispatcher(
                engine(grants = setOf(grant())),
                subject(mainFrame = false),
                pageToken,
            ).dispatch(popupEnvelope())
        }
        assertEquals("service.policy.denied-frame", childFrame.code)
        assertEquals(0, menus.popups.size)
    }

    @Test
    fun aMissingGestureIsRejectedAndAMalformedRequestNeverReachesTheService() = runBlocking {
        val menus = RecordingMenus()
        val gestureDenied = assertFailsWith<KWebBridgeException> {
            menus.bridgeDispatcher(engine(grants = setOf(grant())), subject(), pageToken)
                .dispatch(popupEnvelope())
        }
        assertEquals("service.user-gesture-required", gestureDenied.code)
        val malformed = assertFailsWith<KWebBridgeException> {
            val gestures = KWebUserGestureRegistry()
            val dispatcher = menus.bridgeDispatcher(
                engine(grants = setOf(grant()), gestures = gestures),
                subject(),
                pageToken,
            )
            gestures.mint(
                KWebGestureBinding("engine-1", "profile-1", "page-1", "https://app.example"),
            )
            dispatcher.dispatch(
                """{"version":1,"method":"showDeclaredPopup","payload":{"menuId":"not a menu","x":1,"y":2}}""",
            )
        }
        assertEquals(KWebMenuErrorCode.COMMAND_ID_INVALID, malformed.code)
        assertEquals(0, menus.popups.size)
    }

    private fun popupEnvelope(): String =
        """{"version":1,"method":"showDeclaredPopup","payload":{"menuId":"editor.context","x":1,"y":2}}"""
}
