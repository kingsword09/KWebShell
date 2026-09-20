package io.github.kingsword09.kwebshell.electron.migration

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KWebElectronInventoryRegressionTest {
    private fun scan(source: String, file: String = "entry.ts"): KWebElectronInventoryReport {
        val root = Files.createTempDirectory("migration-ast")
        try {
            root.resolve(file).writeText(source)
            return KWebElectronInventoryScanner().scan(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun commentsStringsAndShadowedBindingsAreNotExecutableElectronUsage() {
        val report = scan("""
            // eval(input); require(dynamic);
            const help = "eval(input)";
            function local(require: (s: string) => string, ipcRenderer: any) {
                require("react"); ipcRenderer.invoke(dynamic);
            }
        """.trimIndent())
        assertTrue(report.findings.isEmpty(), report.findings.toString())
    }

    @Test fun dynamicImportsTemplatesAliasesAndNativePackagesBlock() {
        listOf(
            "require(`./plugins/${'$'}{name}`);",
            "import(moduleName);",
            "const run = eval; run(code);",
            "globalThis[\"eval\"](code);",
            "import { ipcRenderer as ipc } from 'electron'; ipc.invoke(channelName);",
            "const { ipcRenderer: ipc } = require('electron'); ipc.send(channelName);",
            "const native = require('better-sqlite3');",
        ).forEach { source ->
            assertTrue(scan(source).blockingFindings.isNotEmpty(), source)
        }
    }

    @Test fun staticRequireWithWhitespaceIsNotDynamic() {
        assertTrue(scan("require( 'react');").findings.none { it.kind == KWebElectronInventoryFindingKind.DYNAMIC_EXECUTION })
    }

    @Test fun packageDependenciesHaveExactCoordinatesAndNeverDisappear() {
        val report = scan("{\n  \"dependencies\": {\n    \"better-sqlite3\": \"12.0.0\"\n  }\n}\n", "package.json")
        val finding = report.blockingFindings.single()
        assertEquals(3, finding.line)
        assertEquals(5, finding.column)
        assertTrue(finding.expression.contains("better-sqlite3"))
    }

    @Test fun malformedSourceIsBlockingInsteadOfPartiallyClassified() {
        assertTrue(scan("import { ipcRenderer from 'electron';").blockingFindings.isNotEmpty())
    }

    @Test fun relativeReExportsResolveAcrossWorkspacePackages() {
        val root = Files.createTempDirectory("migration-workspaces")
        try {
            Files.createDirectories(root.resolve("packages/shared"))
            root.resolve("packages/shared/index.ts").writeText("export { ipcRenderer as ipc } from 'electron';")
            root.resolve("entry.ts").writeText("import { ipc } from './packages/shared';\nipc.invoke(channelName);")
            val report = KWebElectronInventoryScanner().scan(root)
            assertTrue(report.findings.any { it.path == "entry.ts" && it.line == 2 && it.blocking })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
