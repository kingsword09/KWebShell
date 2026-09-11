package io.github.kingsword09.kwebshell.electron.migration

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KWebElectronPreloadGeneratorTest {
    @Test
    fun generatesSourceCompatibleFacadeWithoutElectronRuntime() {
        val sources = KWebElectronPreloadGenerator().generate(manifest())
        assertContains(sources.typescript, "getPath(name: ElectronPathName")
        assertContains(sources.declarations, "interface DesktopApi")
        assertContains(sources.javascript, "client.resolve({ kind }, options)")
        assertContains(sources.javascript, "Object.defineProperty(globalThis, \"desktop\"")
        assertContains(sources.javascript, "migration.path-name.unsupported")
        kotlin.test.assertTrue(!sources.javascript.contains("ipcRenderer"))
        kotlin.test.assertTrue(!sources.javascript.contains("require(\"electron\")"))
    }

    @Test
    fun unresolvedMappingsCannotBeGenerated() {
        val unresolved = manifest().copy(
            channels = manifest().channels.map {
                it.copy(
                    status = KWebElectronMappingStatus.REWRITE,
                    adapter = null,
                    serviceId = null,
                    serviceVersion = null,
                    operationId = null,
                    policy = null,
                )
            },
            preloadMethods = manifest().preloadMethods.map { it.copy(status = KWebElectronMappingStatus.REWRITE, adapter = null) },
        )
        val failure = assertFailsWith<KWebElectronMigrationException> {
            KWebElectronPreloadGenerator().generate(unresolved)
        }
        assertEquals(KWebElectronMigrationErrorCode.MAPPING_UNRESOLVED, failure.code)
    }

    @Test
    fun generationIsByteForByteDeterministic() {
        val generator = KWebElectronPreloadGenerator()
        assertEquals(generator.generate(manifest()), generator.generate(manifest()))
    }

    private fun manifest(): KWebElectronManifest = KWebElectronMigrationJson.decode(
        """
        {
          "schemaVersion":1,
          "applicationId":"io.github.kwebshell.fixture",
          "rendererGlobal":"desktop",
          "rendererRoot":"renderer",
          "rendererEntry":"index.html",
          "rendererSha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "electronFixtureMajor":44,
          "electronImports":[{"module":"electron","symbol":"contextBridge","status":"ADAPTER","matrixId":"context-bridge"}],
          "channels":[{"name":"app.getPath","schemaVersion":1,"requestType":"ElectronPathName","responseType":"string","serviceId":"app-paths","serviceVersion":"1.0.0","operationId":"resolve","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH","policy":{"rendererGrant":"native.app-paths.resolve","requiresUserGesture":false,"requiresOsConsent":false}}],
          "preloadMethods":[{"name":"getPath","channel":"app.getPath","parameterName":"name","parameterType":"ElectronPathName","returnType":"Promise<string>","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH""adapter":"APP_PATHS_GET_PATH"}],
          "requiredServices":[{"id":"app-paths","version":"1.0.0"}]
        }
        """.trimIndent(),
    )
}
