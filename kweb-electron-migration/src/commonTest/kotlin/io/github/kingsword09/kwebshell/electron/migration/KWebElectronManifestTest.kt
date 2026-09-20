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
            KWebElectronMigrationJson.decode(MANIFEST.replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"extra\":true"))
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

        val incompatible = MANIFEST.replace("\"schemaVersion\":2,\"requestType\"", "\"schemaVersion\":3,\"requestType\"")
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
    fun unpinnedOrInvalidElectronFixtureMajorFails() {
        val missing = MANIFEST.replace("\"electronFixtureMajor\": 44,", "")
        val missingFailure = assertFailsWith<KWebElectronMigrationException> {
            KWebElectronMigrationJson.decode(missing)
        }
        assertEquals(KWebElectronMigrationErrorCode.MANIFEST_INVALID_JSON, missingFailure.code)

        val zero = MANIFEST.replace("\"electronFixtureMajor\": 44", "\"electronFixtureMajor\": 0")
        val zeroFailure = assertFailsWith<KWebElectronMigrationException> {
            KWebElectronMigrationJson.decode(zero)
        }
        assertEquals(KWebElectronMigrationErrorCode.MANIFEST_INVALID, zeroFailure.code)
        assertEquals(
            "electronFixtureMajor",
            zeroFailure.details["field"],
        )
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

    @Test
    fun v1ManifestMigratesDeterministicallyToV2() {
        val v1 = """
            {
              "schemaVersion": 1,
              "applicationId": "io.github.kwebshell.fixture",
              "rendererGlobal": "desktop",
              "rendererRoot": "renderer",
              "rendererEntry": "index.html",
              "rendererSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "electronFixtureMajor": 44,
              "electronImports": [{"module":"electron","symbol":"contextBridge","status":"ADAPTER","matrixId":"context-bridge"}],
              "channels": [{"name":"app.getPath","schemaVersion":1,"requestType":"ElectronPathName","responseType":"string","serviceId":"app-paths","serviceVersion":"1.0.0","operationId":"resolve","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH","policy":{"rendererGrant":"native.app-paths.resolve","requiresUserGesture":false,"requiresOsConsent":false}}],
              "preloadMethods": [{"name":"getPath","channel":"app.getPath","parameterName":"name","parameterType":"ElectronPathName","returnType":"Promise<string>","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH"}],
              "requiredServices": [{"id":"app-paths","version":"1.0.0"}]
            }
        """.trimIndent()
        val migrated = KWebElectronManifestMigrator.migrate(v1, "app://fixture")
        assertEquals(KWebElectronMigrationJson.encode(migrated), KWebElectronMigrationJson.encode(KWebElectronManifestMigrator.migrate(v1, "app://fixture")))
        val badRevision = v1.replace("\"schemaVersion\":1,\"requestType\"", "\"schemaVersion\":999,\"requestType\"")
        assertFailsWith<KWebElectronMigrationException> { KWebElectronManifestMigrator.migrate(badRevision, "app://fixture") }
        assertEquals(2, migrated.schemaVersion)
        assertEquals("main", migrated.windows.single().id)
        assertEquals("default", migrated.profiles.single().id)
    }

    @Test
    fun invalidV2ReferencesAndDependencyKindsFail() {
        val base = KWebElectronMigrationJson.decode(MANIFEST)
        val invalid = listOf(
            base.copy(windows = listOf(KWebElectronWindowDefinition("main", "Main", profile = "missing"))),
            base.copy(profiles = listOf(KWebElectronProfileDefinition("default", "profiles/default")), windows = listOf(KWebElectronWindowDefinition("main", "Main", true, "default", true, "ghost"))),
            base.copy(profiles = listOf(KWebElectronProfileDefinition("a", "shared"), KWebElectronProfileDefinition("b", "shared"))),
            base.copy(lifecycleEvents = listOf(KWebElectronLifecycleEvent("", "", KWebElectronMappingStatus.DIRECT))),
            base.copy(nodeDependencies = listOf(KWebElectronNodeDependency("native-addon", KWebElectronDependencyKind.NATIVE_ADDON, KWebElectronMappingStatus.ADAPTER, "missing"))),
        )
        invalid.forEach { manifest ->
            assertFailsWith<KWebElectronMigrationException> { KWebElectronManifestValidator.validate(manifest) }
        }
    }

    @Test
    fun unsupportedNewDeclarationsCannotGenerateReadyFacade() {
        val base = KWebElectronMigrationJson.decode(MANIFEST)
        val blocked = base.copy(nodeDependencies = listOf(KWebElectronNodeDependency("better-sqlite3", KWebElectronDependencyKind.NATIVE_ADDON, KWebElectronMappingStatus.UNSUPPORTED)))
        assertFailsWith<KWebElectronMigrationException> { KWebElectronPreloadGenerator().generate(blocked) }
    }

    private companion object {
        val MANIFEST = """
            {
              "schemaVersion": 2,
          "rendererOrigin":"app://fixture",
          "rendererProfile":"default",
          "profiles":[{"id":"default","storagePath":"profiles/default","isPersistent":true}],
              "applicationId": "io.github.kwebshell.fixture",
              "rendererGlobal": "desktop",
              "rendererRoot": "renderer",
              "rendererEntry": "index.html",
              "rendererSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "electronFixtureMajor": 44,
              "electronImports": [{"module":"electron","symbol":"contextBridge","status":"ADAPTER","matrixId":"context-bridge"}],
              "channels": [{"name":"app.getPath","schemaVersion":2,"requestType":"ElectronPathName","responseType":"string","serviceId":"app-paths","serviceVersion":"1.0.0","operationId":"resolve","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH","policy":{"rendererGrant":"native.app-paths.resolve","requiresUserGesture":false,"requiresOsConsent":false}}],
              "preloadMethods": [{"name":"getPath","channel":"app.getPath","parameterName":"name","parameterType":"ElectronPathName","returnType":"Promise<string>","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH"}],
              "requiredServices": [{"id":"app-paths","version":"1.0.0"}]
            }
        """.trimIndent()
    }
}
