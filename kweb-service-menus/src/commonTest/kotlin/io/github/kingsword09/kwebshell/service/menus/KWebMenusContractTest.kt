package io.github.kingsword09.kwebshell.service.menus

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class KWebMenusContractTest {
    private val digest = "0".repeat(64)

    @Test
    fun identifiersAreBoundedAndStable() {
        KWebMenuId("app.file.open")
        KWebMenuId("A")
        assertFailsWith<KWebConfigurationException> { KWebMenuId("1st") }
        assertFailsWith<KWebConfigurationException> { KWebMenuId("app file") }
        assertFailsWith<KWebConfigurationException> { KWebMenuId("a".repeat(129)) }
        assertFailsWith<KWebConfigurationException> { KWebMenuWindowId("window/one") }
        assertFailsWith<KWebConfigurationException> { KWebMenuPageToken("") }
        assertEquals(KWebMenuId("app.quit"), KWebMenuId("app.quit"))
    }

    @Test
    fun labelsMnemonicsAndIconsAreValidatedBeforeDispatch() {
        KWebMenuItem.Command(KWebMenuId("file.open"), "Open", mnemonic = 'O')
        assertFailsWith<KWebConfigurationException> { KWebMenuItem.Command(KWebMenuId("file.open"), "  ") }
        assertFailsWith<KWebConfigurationException> {
            KWebMenuItem.Command(KWebMenuId("file.open"), "Open", mnemonic = 'Z')
        }
        assertFailsWith<KWebConfigurationException> {
            KWebMenuItem.Command(KWebMenuId("file.open"), "Open", mnemonic = '1')
        }
        assertFailsWith<KWebConfigurationException> {
            KWebMenuItem.Command(KWebMenuId("file.open"), "a\u0000b")
        }
        assertFailsWith<KWebConfigurationException> {
            KWebMenuItem.Command(KWebMenuId("file.open"), "L".repeat(KWEB_MENU_MAX_LABEL_BYTES + 1))
        }
        assertFailsWith<KWebConfigurationException> {
            KWebMenuItem.Command(
                KWebMenuId("file.open"),
                "Open",
                icon = KWebMenuIcon("icons/open", "not-a-digest"),
            )
        }
        assertFailsWith<KWebConfigurationException> {
            KWebMenuItem.Command(KWebMenuId("file.open"), "Open", icon = KWebMenuIcon("../open", digest))
        }
    }

    @Test
    fun acceleratorsRequireDistinctBoundedModifiers() {
        KWebMenuAccelerator(listOf(KWebMenuModifier.PRIMARY), KWebMenuKey.O)
        KWebMenuAccelerator(listOf(KWebMenuModifier.PRIMARY, KWebMenuModifier.SHIFT), KWebMenuKey.O)
        assertFailsWith<KWebConfigurationException> { KWebMenuAccelerator(emptyList(), KWebMenuKey.O) }
        assertFailsWith<KWebConfigurationException> {
            KWebMenuAccelerator(listOf(KWebMenuModifier.PRIMARY, KWebMenuModifier.PRIMARY), KWebMenuKey.O)
        }
        assertFailsWith<KWebConfigurationException> {
            KWebMenuAccelerator(
                listOf(
                    KWebMenuModifier.PRIMARY,
                    KWebMenuModifier.SHIFT,
                    KWebMenuModifier.ALT,
                    KWebMenuModifier.CONTROL,
                ),
                KWebMenuKey.O,
            )
        }
    }

    @Test
    fun treesRejectDuplicateCommandsAcceleratorsAndMisplacedSeparators() {
        val open = KWebMenuItem.Command(KWebMenuId("file.open"), "Open")
        assertFailsWith<KWebConfigurationException> {
            KWebMenuTree(KWebMenuId("file"), 1, listOf(open, KWebMenuItem.Command(KWebMenuId("file.open"), "Open again")))
        }
        val accelerator = KWebMenuAccelerator(listOf(KWebMenuModifier.PRIMARY), KWebMenuKey.S)
        assertFailsWith<KWebConfigurationException> {
            KWebMenuTree(
                KWebMenuId("file"),
                1,
                listOf(
                    KWebMenuItem.Command(KWebMenuId("file.save"), "Save", accelerator = accelerator),
                    KWebMenuItem.Command(KWebMenuId("file.saveAs"), "Save as", accelerator = accelerator),
                ),
            )
        }
        assertFailsWith<KWebConfigurationException> {
            KWebMenuTree(KWebMenuId("file"), 1, listOf(KWebMenuItem.Separator(), open))
        }
        assertFailsWith<KWebConfigurationException> {
            KWebMenuTree(KWebMenuId("file"), 1, listOf(open, KWebMenuItem.Separator()))
        }
        assertFailsWith<KWebConfigurationException> {
            KWebMenuTree(KWebMenuId("file"), 1, listOf(open, KWebMenuItem.Separator(), KWebMenuItem.Separator(), open))
        }
        assertFailsWith<KWebConfigurationException> { KWebMenuTree(KWebMenuId("file"), 0, listOf(open)) }
        assertFailsWith<KWebConfigurationException> { KWebMenuTree(KWebMenuId("file"), 1, emptyList()) }
        assertFailsWith<KWebConfigurationException> {
            KWebMenuTree(KWebMenuId("file"), 1, listOf(KWebMenuItem.Separator()))
        }
    }

    @Test
    fun nestingAndNodeCeilingsAreBounded() {
        assertFailsWith<KWebConfigurationException> {
            KWebMenuItem.Command(KWebMenuId("file.open"), "Open", toggle = null, checked = true)
        }
        assertFailsWith<KWebConfigurationException> {
            KWebMenuItem.Submenu(KWebMenuId("file"), "File", emptyList())
        }
        var nested: KWebMenuItem = KWebMenuItem.Command(KWebMenuId("deep.leaf"), "Leaf")
        repeat(KWEB_MENU_MAX_DEPTH) {
            nested = KWebMenuItem.Submenu(KWebMenuId("menu$it"), "Level $it", listOf(nested))
        }
        val tooDeep = nested
        assertFailsWith<KWebConfigurationException> { KWebMenuTree(KWebMenuId("deep"), 1, listOf(tooDeep)) }

        val wide = (1..KWEB_MENU_MAX_NODES + 1).map { KWebMenuItem.Command(KWebMenuId("item.$it"), "Item $it") }
        assertFailsWith<KWebConfigurationException> { KWebMenuTree(KWebMenuId("wide"), 1, wide) }
        KWebMenuTree(KWebMenuId("wide"), 1, wide.take(KWEB_MENU_MAX_NODES))
    }

    @Test
    fun toggleItemsCarryCheckedStateAndKinds() {
        val radio = KWebMenuItem.Command(
            KWebMenuId("view.zoom.100"),
            "100%",
            toggle = KWebMenuToggleKind.RADIO,
            checked = true,
        )
        assertEquals(KWebMenuItemKind.RADIO, radio.kind)
        assertEquals(true, radio.checked)
        val check = KWebMenuItem.Command(
            KWebMenuId("view.sidebar"),
            "Sidebar",
            toggle = KWebMenuToggleKind.CHECKBOX,
            checked = false,
        )
        assertEquals(KWebMenuItemKind.CHECKBOX, check.kind)
        assertEquals(false, check.checked)
        val plain = KWebMenuItem.Command(KWebMenuId("file.open"), "Open")
        assertEquals(KWebMenuItemKind.COMMAND, plain.kind)
        assertEquals(true, plain.enabled)
        assertEquals(true, plain.visible)
    }

    @Test
    fun popupAnchorsAreBoundedAndSourced() {
        KWebMenuPosition(KWebMenuCoordinateSpace.WINDOW, 0, 0)
        KWebMenuPosition(KWebMenuCoordinateSpace.PAGE, KWEB_MENU_MAX_POSITION, KWEB_MENU_MIN_POSITION)
        assertFailsWith<KWebConfigurationException> {
            KWebMenuPosition(KWebMenuCoordinateSpace.PAGE, KWEB_MENU_MAX_POSITION + 1, 0)
        }
        assertFailsWith<KWebConfigurationException> {
            KWebMenuPosition(KWebMenuCoordinateSpace.WINDOW, 0, KWEB_MENU_MIN_POSITION - 1)
        }
        val request = KWebMenuPopupRequest(
            KWebMenuId("editor.context"),
            KWebMenuOwner.Window(KWebMenuWindowId("main-window")),
            KWebMenuPosition(KWebMenuCoordinateSpace.WINDOW, 12, 34),
        )
        assertEquals(KWebMenuPopupSource.HOST, request.source)
        assertEquals("window:main-window", request.owner.identifier)
        assertEquals("application", KWebMenuOwner.Application.identifier)
        assertEquals(
            "page:page-token@main-window",
            KWebMenuOwner.Page(KWebMenuPageToken("page-token"), KWebMenuWindowId("main-window")).identifier,
        )
    }

    @Test
    fun descriptorPublishesOneRendererOperationAndEveryTarget() {
        val descriptor = KWebMenus.DESCRIPTOR
        assertEquals("menus", descriptor.id)
        val rendererOperations = descriptor.operations.filter { it.rendererPermission != null }
        assertEquals(listOf("show-declared-popup"), rendererOperations.map { it.id })
        assertEquals(true, rendererOperations.single().requiresUserGesture)
        assertEquals("native.menus.show-declared-popup", rendererOperations.single().rendererPermission)
        assertEquals(3, descriptor.supportedTargets.size)
        assertTrue(descriptor.operations.any { it.id == "set-application-menu" && it.rendererPermission == null })
        assertTrue(descriptor.operations.any { it.id == "set-window-menu" && it.rendererPermission == null })
    }
}
