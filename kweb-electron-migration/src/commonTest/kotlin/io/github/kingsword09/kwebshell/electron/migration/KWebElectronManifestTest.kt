package io.github.kingsword09.kwebshell.electron.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KWebElectronManifestTest {
    @Test
    fun strictManifestAcceptsThePublishedAppPathsAdapter() {
        val manifest = KWebElectronMigrationJson.decode(MANIFEST)
        assertEquals("desktop", manifest.rendererGlobal)
        assertEquals(KWebElectronAdapterKind.APP_PATHS_GET_PATH, manifest.channels.single().adapter)
        assertEquals(KWebElectronMappingStatus.ADAPTER, manifest.preloadMethods.single().status)
    }

    @Test
    fun unknownFieldsAreRejectedBeforeValidation() {
        val failure = assertFailsWith<KWebElectronMigrationException> {
            KWebElectronMigrationJson.decode(MANIFEST.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"extra\":true"))
        }
        assertEquals(KWebElectronMigrationErrorCode.MANIFEST_INVALID_JSON, failure.code)
    }

    @Test
    fun undeclaredChannelsAndIncompatibleRevisionsFail() {
        val undeclared = MANIFEST.replace("\"channel\":\"app.getPath\"", "\"channel\":\"app.missing\"")
        val channelFailure = assertFailsWith<KWebElectronMigrationException> {
            KWebElectronMigrationJson.decode(undeclared)
        }
        assertEquals(KWebElectronMigrationErrorCode.CHANNEL_UNDECLARED, channelFailure.code)

        val incompatible = MANIFEST.replace("\"schemaVersion\":1,\"requestType\"", "\"schemaVersion\":2,\"requestType\"")
        val schemaFailure = assertFailsWith<KWebElectronMigrationException> {
            KWebElectronMigrationJson.decode(incompatible)
        }
        assertEquals(KWebElectronMigrationErrorCode.SCHEMA_INCOMPATIBLE, schemaFailure.code)
    }

    @Test
    fun adapterCannotLieAboutItsServiceContract() {
        val invalid = MANIFEST.replace("\"serviceVersion\":\"1.0.0\"", "\"serviceVersion\":\"2.0.0\"")
        val failure = assertFailsWith<KWebElectronMigrationException> {
            KWebElectronMigrationJson.decode(invalid)
        }
        assertEquals(KWebElectronMigrationErrorCode.SCHEMA_INCOMPATIBLE, failure.code)
    }

    @Test
    fun unsafeRendererPathIsRejected() {
        val invalid = MANIFEST.replace("\"rendererRoot\": \"renderer\"", "\"rendererRoot\": \"../renderer\"")
        val failure = assertFailsWith<KWebElectronMigrationException> {
            KWebElectronMigrationJson.decode(invalid)
        }
        assertEquals(KWebElectronMigrationErrorCode.MANIFEST_INVALID, failure.code)
    }

    @Test
    fun matrixImportsMustUseElectronModuleAndDeclaredStatus() {
        val wrongModule = MANIFEST.replace("\"module\":\"electron\"", "\"module\":\"@electron/remote\"")
        val moduleFailure = assertFailsWith<KWebElectronMigrationException> {
            KWebElectronMigrationJson.decode(wrongModule)
        }
        assertEquals(KWebElectronMigrationErrorCode.MAPPING_UNRESOLVED, moduleFailure.code)

        val wrongChannelFields = MANIFEST.replace(
            "\"status\":\"ADAPTER\",\"adapter\":\"APP_PATHS_GET_PATH\"",
            "\"status\":\"REWRITE\",\"adapter\":null",
        )
        val channelFailure = assertFailsWith<KWebElectronMigrationException> {
            KWebElectronMigrationJson.decode(wrongChannelFields)
        }
        assertEquals(KWebElectronMigrationErrorCode.MANIFEST_INVALID, channelFailure.code)
    }

    private companion object {
        val MANIFEST = """
            {
              "schemaVersion": 1,
              "applicationId": "io.github.kwebshell.fixture",
              "rendererGlobal": "desktop",
              "rendererRoot": "renderer",
              "rendererEntry": "index.html",
              "rendererSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "electronImports": [{"module":"electron","symbol":"contextBridge","status":"ADAPTER","matrixId":"context-bridge"}],
              "channels": [{"name":"app.getPath","schemaVersion":1,"requestType":"ElectronPathName","responseType":"string","serviceId":"app-paths","serviceVersion":"1.0.0","operationId":"resolve","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH"}],
              "preloadMethods": [{"name":"getPath","channel":"app.getPath","parameterName":"name","parameterType":"ElectronPathName","returnType":"Promise<string>","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH"}],
              "requiredServices": [{"id":"app-paths","version":"1.0.0"}]
            }
        """.trimIndent()
    }
}
