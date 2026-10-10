package io.github.kingsword09.kwebshell.electron.migration

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KWebElectronTrayMigrationTest {
    private fun scan(source: String): List<KWebElectronInventoryFinding> {
        val root = Files.createTempDirectory("kweb-electron-tray-migration")
        try {
            root.resolve("tray.ts").writeText(source)
            return KWebElectronInventoryScanner().scan(root).findings
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun trayRowIsAHostOnlyRewriteBackedByKWebTrays() {
        val row = KWebElectronCapabilityMatrix.find("tray")
        assertEquals(KWebElectronMappingStatus.REWRITE, row?.status)
        assertEquals("KWebTrays", row?.kweb)
        // A tray is host-only: the migration kit introduces no renderer adapter kind
        // and the generated facade never exposes a tray construction surface.
        assertTrue(KWebElectronAdapterKind.entries.none { it.name.contains("TRAY") })
        val fixture = KWebElectronMigrationJson.decode(Files.readString(java.nio.file.Path.of(
            "kweb-electron-migration/src/jvmTest/resources/migration-fixture/migration-manifest.json",
        )))
        val sources = KWebElectronPreloadGenerator().generate(fixture)
        // A tray is host-only: the generated renderer facade never constructs,
        // mutates, or observes a tray item.
        listOf(sources.typescript, sources.declarations, sources.javascript).forEach { generated ->
            assertTrue(!generated.contains("Tray"), generated)
            assertTrue(!generated.contains("tray"), generated)
        }
        // The host checklist declares the service the application installs; that
        // is a host requirement, not a renderer surface.
        assertTrue(sources.checklist.contains("- [ ] Install `tray` (version `1.0.0`)"), sources.checklist)
    }

    @Test
    fun knownTrayConstructionAndMethodsAreTypedRewriteBlockers() {
        val findings = scan(
            """
            import { Tray } from "electron";
            const tray = new Tray(icon);
            tray.setImage(icon);
            tray.setToolTip("Tooltip");
            tray.on("click", () => toggle());
            tray.popUpContextMenu(menu, { x: 1, y: 2 });
            tray.destroy();
            """.trimIndent(),
        )
        assertTrue(findings.any { it.matrixId == "tray" && it.detail == "electron.Tray" && it.status == KWebElectronMappingStatus.REWRITE && it.blocking })
        listOf(
            "electron.Tray.instance.setImage",
            "electron.Tray.instance.setToolTip",
            "electron.Tray.instance.on",
            "electron.Tray.instance.popUpContextMenu",
            "electron.Tray.instance.destroy",
        ).forEach { operation ->
            assertTrue(findings.any { it.matrixId == "tray" && it.detail == operation && it.blocking }, findings.toString())
        }
        assertTrue(findings.none { it.matrixId == "tray" && !it.blocking }, findings.toString())
    }

    @Test
    fun unknownTrayEventsAndClosedOverMenuCallbacksRemainExplicitBlockers() {
        val findings = scan(
            """
            import { Menu, Tray } from "electron";
            const tray = new Tray(icon);
            tray.on("totally-custom", () => custom());
            tray.on(eventName, () => dynamic());
            tray.setContextMenu(Menu.buildFromTemplate([{ label: "Quit", click: () => quit() }]));
            """.trimIndent(),
        )
        // Unknown and dynamic event names are recorded distinctly from a declared event.
        assertTrue(findings.count { it.matrixId == "tray" && it.detail == "electron.Tray.instance.on-unknown" && it.blocking } == 2, findings.toString())
        assertTrue(findings.none { it.matrixId == "tray" && it.detail == "electron.Tray.instance.on" }, findings.toString())
        assertTrue(findings.any { it.matrixId == "tray" && it.detail == "electron.Tray.instance.setContextMenu" && it.blocking }, findings.toString())
        // A closed-over menu click passed to the tray stays a recorded menu blocker.
        assertTrue(findings.any { it.matrixId == "menu" && it.detail == "electron.Menu.template.click" && it.blocking }, findings.toString())
    }
}
