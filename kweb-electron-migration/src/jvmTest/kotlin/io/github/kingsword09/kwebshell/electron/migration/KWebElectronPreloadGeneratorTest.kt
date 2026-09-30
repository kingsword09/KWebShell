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

    @Test
    fun namedApplicationStreamsGenerateAsyncIterableFacade() {
        val streamManifest = manifest().copy(
            streams = listOf(
                KWebElectronStream(
                    name = "progress",
                    method = "downloadProgress",
                    requestType = "DownloadRequest",
                    chunkType = "ProgressChunk",
                    capacity = 32,
                    status = KWebElectronMappingStatus.ADAPTER,
                    adapter = KWebElectronAdapterKind.NAMED_APPLICATION_STREAM,
                    policy = KWebElectronChannelPolicy(
                        rendererGrant = "native.downloads.progress",
                        requiresUserGesture = false,
                        requiresOsConsent = false,
                    ),
                ),
            ),
        )
        val sources = KWebElectronPreloadGenerator().generate(streamManifest)
        assertContains(sources.typescript, "interface KWebElectronStream<Chunk> extends AsyncIterable<Chunk>")
        assertContains(sources.typescript, "progress: (request: DownloadRequest")
        assertContains(sources.typescript, "close(): void")
        assertContains(sources.declarations, "KWebApplicationStreamsBridge")
        assertContains(sources.javascript, "bridgeStreams.openDownloadProgress(request, options)")
    }

    @Test
    fun filesOperationGeneratesNamedFilesBridgeCall() {
        val filesManifest = manifest().copy(
            channels = manifest().channels + KWebElectronChannel(
                name = "fs.readFile",
                schemaVersion = 2,
                requestType = "FilesReadRequest",
                responseType = "FilesReadResponse",
                serviceId = "files",
                serviceVersion = "1.0.0",
                operationId = "read-file",
                status = KWebElectronMappingStatus.ADAPTER,
                adapter = KWebElectronAdapterKind.FILES_OPERATION,
                policy = KWebElectronChannelPolicy(
                    rendererGrant = "native.files.read-file",
                    requiresUserGesture = false,
                    requiresOsConsent = false,
                ),
            ),
            preloadMethods = manifest().preloadMethods + KWebElectronPreloadMethod(
                name = "readFile",
                channel = "fs.readFile",
                parameterName = "request",
                parameterType = "FilesReadRequest",
                returnType = "Promise<FilesReadResponse>",
                status = KWebElectronMappingStatus.ADAPTER,
                adapter = KWebElectronAdapterKind.FILES_OPERATION,
            ),
            requiredServices = manifest().requiredServices + KWebElectronServiceRequirement("files", "1.0.0"),
        )
        val sources = KWebElectronPreloadGenerator().generate(filesManifest)
        assertContains(sources.typescript, "readFile(request: FilesReadRequest")
        assertContains(sources.typescript, "interface FilesReadRequest")
        assertContains(sources.typescript, "bytes: readonly number[]")
        assertContains(sources.typescript, "FilesBridge")
        assertContains(sources.javascript, "filesClient.readFile(request, options)")
    }

    @Test
    fun filesWatchGeneratesFilesStreamCall() {
        val filesManifest = manifest().copy(
            streams = listOf(
                KWebElectronStream(
                    name = "watchDirectory",
                    method = "watchDirectory",
                    requestType = "FilesWatchRequest",
                    chunkType = "FilesWatchEvent",
                    capacity = 64,
                    status = KWebElectronMappingStatus.ADAPTER,
                    adapter = KWebElectronAdapterKind.FILES_WATCH_DIRECTORY,
                    policy = KWebElectronChannelPolicy(
                        rendererGrant = "native.files.watch-directory",
                        requiresUserGesture = false,
                        requiresOsConsent = false,
                    ),
                ),
            ),
        )
        val sources = KWebElectronPreloadGenerator().generate(filesManifest)
        assertContains(sources.typescript, "watchDirectory: (request: FilesWatchRequest")
        assertContains(sources.typescript, "FilesBridge")
        assertContains(sources.javascript, "filesClient.openWatchDirectory(request, options)")
        kotlin.test.assertTrue(!sources.javascript.contains("KWebApplicationStreamsBridge"))
    }

    @Test
    fun generatedArtifactsMatchSharedCrossPlatformGoldenBytes() {
        val root = java.nio.file.Path.of("kweb-electron-migration/src/jvmTest/resources")
        val fixture = KWebElectronMigrationJson.decode(java.nio.file.Files.readString(root.resolve("migration-fixture/migration-manifest.json")))
        val sources = KWebElectronPreloadGenerator().generate(fixture)
        mapOf("KWebElectronPreload.ts" to sources.typescript, "KWebElectronPreload.d.ts" to sources.declarations, "KWebElectronPreload.js" to sources.javascript, "KWebElectronHostChecklist.md" to sources.checklist).forEach { (name, actual) ->
            assertEquals(java.nio.file.Files.readString(root.resolve("migration-golden/$name")), actual, name)
        }
    }

    private fun manifest(): KWebElectronManifest = KWebElectronMigrationJson.decode(
        """
        {
          "schemaVersion":2,
          "rendererOrigin":"app://fixture",
          "rendererProfile":"default",
          "profiles":[{"id":"default","storagePath":"profiles/default","isPersistent":true}],
          "applicationId":"io.github.kwebshell.fixture",
          "rendererGlobal":"desktop",
          "rendererRoot":"renderer",
          "rendererEntry":"index.html",
          "rendererSha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "electronFixtureMajor":44,
          "electronImports":[{"module":"electron","symbol":"contextBridge","status":"ADAPTER","matrixId":"context-bridge"}],
          "channels":[{"name":"app.getPath","schemaVersion":2,"requestType":"ElectronPathName","responseType":"string","serviceId":"app-paths","serviceVersion":"1.0.0","operationId":"resolve","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH","policy":{"rendererGrant":"native.app-paths.resolve","requiresUserGesture":false,"requiresOsConsent":false}}],
          "preloadMethods":[{"name":"getPath","channel":"app.getPath","parameterName":"name","parameterType":"ElectronPathName","returnType":"Promise<string>","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH"}],
          "requiredServices":[{"id":"app-paths","version":"1.0.0"}]
        }
        """.trimIndent(),
    )
}
