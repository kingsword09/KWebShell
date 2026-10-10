package io.github.kingsword09.kwebshell.service.tray

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.service.menus.KWebMenuItem
import io.github.kingsword09.kwebshell.service.menus.KWebMenuId
import io.github.kingsword09.kwebshell.service.menus.KWebMenuTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebTraysContractTest {
    private val digest = "0".repeat(64)

    private fun icon(vararg scales: Int) = KWebTrayIcon(
        scales.map { KWebTrayIconVariant(it, "icons/tray", digest) },
    )

    @Test
    fun itemIdentifiersAndTooltipsAreBounded() {
        KWebTrayItemId("status.item")
        assertFailsWith<KWebConfigurationException> { KWebTrayItemId("1status") }
        assertFailsWith<KWebConfigurationException> { KWebTrayItemId("status item") }
        assertFailsWith<KWebConfigurationException> { KWebTrayItemId("a".repeat(129)) }
        KWebTrayItemSpec(KWebTrayItemId("main"), icon(1), tooltip = "KWebShell")
        assertFailsWith<KWebConfigurationException> {
            KWebTrayItemSpec(KWebTrayItemId("main"), icon(1), tooltip = "  ")
        }
        assertFailsWith<KWebConfigurationException> {
            KWebTrayItemSpec(KWebTrayItemId("main"), icon(1), tooltip = "t".repeat(KWEB_TRAY_MAX_TOOLTIP_BYTES + 1))
        }
        assertFailsWith<KWebConfigurationException> {
            KWebTrayItemSpec(KWebTrayItemId("main"), icon(1), tooltip = "bad\u0000tooltip")
        }
    }

    @Test
    fun iconVariantsRequireUniqueBoundedScalesAndVerifiedDigests() {
        assertEquals(2, icon(1, 2).variants.size)
        assertFailsWith<KWebConfigurationException> { KWebTrayIcon(emptyList()) }
        assertFailsWith<KWebConfigurationException> { icon(1, 1) }
        assertFailsWith<KWebConfigurationException> {
            KWebTrayIcon((1..9).map { KWebTrayIconVariant(it, "icons/tray", digest) })
        }
        assertFailsWith<KWebConfigurationException> { KWebTrayIconVariant(0, "icons/tray", digest) }
        assertFailsWith<KWebConfigurationException> { KWebTrayIconVariant(1, "icons/tray", "not-a-digest") }
        assertFailsWith<KWebConfigurationException> { KWebTrayIconVariant(1, "../tray", digest) }
    }

    @Test
    fun singleScaleProvidersSelectTheHighestVariantWithinThePlatformScale() {
        val variants = icon(1, 2, 4)
        assertEquals(4, variants.selectForPlatformScale(4).scale)
        assertEquals(2, variants.selectForPlatformScale(3).scale)
        assertEquals(1, variants.selectForPlatformScale(1).scale)
        assertEquals(1, variants.selectForPlatformScale(0).scale)
        // No variant fits a smaller platform scale: the smallest declared
        // variant is selected and the platform downscales it.
        assertEquals(3, KWebTrayIcon(listOf(KWebTrayIconVariant(3, "icons/tray", digest)))
            .selectForPlatformScale(1).scale)
    }

    @Test
    fun activationsAreDeclaredAndNeverEmpty() {
        val spec = KWebTrayItemSpec(
            KWebTrayItemId("main"),
            icon(1),
            activations = setOf(KWebTrayActivation.PRIMARY, KWebTrayActivation.DOUBLE),
        )
        assertEquals(2, spec.activations.size)
        assertFailsWith<KWebConfigurationException> {
            KWebTrayItemSpec(KWebTrayItemId("main"), icon(1), activations = emptySet())
        }
    }

    @Test
    fun boundsRejectInvalidGeometry() {
        KWebTrayBounds(0, 0, 24, 24)
        KWebTrayBounds(KWEB_TRAY_MAX_POSITION, KWEB_TRAY_MIN_POSITION, 1, 1)
        assertFailsWith<KWebConfigurationException> { KWebTrayBounds(0, 0, 0, 24) }
        assertFailsWith<KWebConfigurationException> { KWebTrayBounds(0, 0, 24, -1) }
        assertFailsWith<KWebConfigurationException> {
            KWebTrayBounds(KWEB_TRAY_MAX_POSITION + 1, 0, 24, 24)
        }
    }

    @Test
    fun itemSpecificationReusesTheMenuModelWithoutDeclaringOne() {
        val tree = KWebMenuTree(
            KWebMenuId("tray.menu"),
            1,
            listOf(KWebMenuItem.Command(KWebMenuId("tray.open"), "Open")),
        )
        val spec = KWebTrayItemSpec(KWebTrayItemId("main"), icon(1), menuId = tree.menuId)
        assertEquals(KWebMenuId("tray.menu"), spec.menuId)
        assertEquals(1, tree.items.size)
    }

    @Test
    fun descriptorPublishesOnlyHostOperationsAndEveryTarget() {
        val descriptor = KWebTrays.DESCRIPTOR
        assertEquals("tray", descriptor.id)
        assertTrue(descriptor.operations.all { it.rendererPermission == null })
        assertEquals(
            setOf("capabilities", "create", "update", "set-menu", "bounds", "close-item", "events"),
            descriptor.operations.map { it.id }.toSet(),
        )
        assertEquals(3, descriptor.supportedTargets.size)
        assertEquals(KWebLifecycleState.OPEN, KWebLifecycleState.OPEN)
    }
}
