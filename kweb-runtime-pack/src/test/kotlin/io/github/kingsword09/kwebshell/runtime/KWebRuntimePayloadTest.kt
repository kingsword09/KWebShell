package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebOperatingSystem
import io.github.kingsword09.kwebshell.core.KWebTarget
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDateTime
import java.util.Enumeration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebRuntimePayloadTest {
    @Test
    fun buildsAndVerifiesEverySupportedTargetLayout() {
        KWebTarget.supported.sortedBy { it.id }.forEach { target ->
            withFixture(target) { fixture ->
                val result = build(fixture)
                val manifest = KWebRuntimePayloadVerifier.verify(
                    KWebRuntimePayloadVerificationRequest(
                        archive = result.archive,
                        catalog = fixture.catalog,
                        target = target,
                        productVersion = PRODUCT_VERSION,
                    ),
                )
                assertEquals(result.manifest, manifest)
                assertTrue(Files.size(result.archive) > 0L)
            }
        }
    }

    @Test
    fun rebuildingByteIdenticalTreesIgnoresInputMtimes() {
        withFixture(KWebTarget.parse("macos-arm64")) { first ->
            val firstResult = build(first, "first.zip")
            setTreeTimes(first.cefRoot, Instant.parse("2001-01-01T00:00:00Z"))
            setTreeTimes(first.nativeRelease, Instant.parse("2002-02-02T02:02:02Z"))
            setTreeTimes(first.nativeContract, Instant.parse("2003-03-03T03:03:03Z"))
            val secondResult = build(first, "second.zip")
            assertEquals(
                Files.readAllBytes(firstResult.archive).toList(),
                Files.readAllBytes(secondResult.archive).toList(),
            )
            assertEquals(firstResult.archiveSha256, secondResult.archiveSha256)
        }
    }

    @Test
    fun rejectsMissingNoticeBeforeCreatingAnArchive() {
        withFixture(KWebTarget.parse("linux-x64")) { fixture ->
            Files.delete(fixture.cefRoot.resolve("LICENSE.txt"))
            val error = assertFailsWith<KWebRuntimePayloadException> { build(fixture) }
            assertEquals("runtime.payload.notice-invalid", error.code)
        }
    }

    @Test
    fun rejectsMissingEngineBindingBeforeCreatingAnArchive() {
        withFixture(KWebTarget.parse("windows-x64")) { fixture ->
            Files.delete(fixture.nativeContract.resolve("kwebshell_engine.dll"))
            val error = assertFailsWith<KWebRuntimePayloadException> { build(fixture) }
            assertTrue(
                error.code == "runtime.payload.input-attributes-failed" ||
                    error.code == "runtime.payload.input-file-invalid",
                error.code,
            )
        }
    }

    @Test
    fun rejectsInputSymlinkThatEscapesItsRoot() {
        withFixture(KWebTarget.parse("macos-arm64")) { fixture ->
            val escape = fixture.nativeRelease.resolve("KWebShell.app/Contents/MacOS/escape")
            Files.createSymbolicLink(escape, Path.of("../../../../outside"))
            Files.writeString(fixture.root.resolve("outside"), "outside")
            val error = assertFailsWith<KWebRuntimePayloadException> { build(fixture) }
            assertEquals("runtime.payload.input-symlink-escape", error.code)
        }
    }

    @Test
    fun rejectsRecursiveInputSymlink() {
        withFixture(KWebTarget.parse("macos-arm64")) { fixture ->
            val recursive = fixture.nativeRelease.resolve(
                "KWebShell.app/Contents/Frameworks/" +
                    "Chromium Embedded Framework.framework/Versions/A/A",
            )
            Files.createSymbolicLink(recursive, Path.of("A"))
            val error = assertFailsWith<KWebRuntimePayloadException> { build(fixture) }
            assertEquals("runtime.payload.input-symlink-target-missing", error.code)
        }
    }

    @Test
    fun rejectsTrailingArchiveData() {
        withFixture(KWebTarget.parse("linux-x64")) { fixture ->
            val result = build(fixture)
            Files.write(result.archive, byteArrayOf(0x01, 0x02), StandardOpenOption.APPEND)
            val error = assertFailsWith<KWebRuntimePayloadException> {
                KWebRuntimePayloadVerifier.verify(
                    KWebRuntimePayloadVerificationRequest(
                        archive = result.archive,
                        catalog = fixture.catalog,
                        target = fixture.target,
                        productVersion = PRODUCT_VERSION,
                    ),
                )
            }
            assertEquals("runtime.payload.archive-trailing-data", error.code)
        }
    }

    @Test
    fun rejectsArchiveCorruption() {
        withFixture(KWebTarget.parse("linux-arm64")) { fixture ->
            val result = build(fixture)
            val bytes = Files.readAllBytes(result.archive)
            bytes[0] = (bytes[0].toInt() xor 0x40).toByte()
            Files.write(result.archive, bytes)
            val error = assertFailsWith<KWebRuntimePayloadException> {
                KWebRuntimePayloadVerifier.verify(
                    KWebRuntimePayloadVerificationRequest(
                        archive = result.archive,
                        catalog = fixture.catalog,
                        target = fixture.target,
                        productVersion = PRODUCT_VERSION,
                    ),
                )
            }
            assertTrue(error.code.startsWith("runtime.payload.archive-"), error.code)
        }
    }

    @Test
    fun rejectsNonCanonicalManifestBytes() {
        withFixture(KWebTarget.parse("linux-x64")) { fixture ->
            val result = build(fixture)
            rewriteArchive(result.archive, transform = { name, bytes ->
                if (name == KWEB_RUNTIME_PAYLOAD_MANIFEST_PATH) {
                    val text = bytes.toString(StandardCharsets.UTF_8)
                    text.replace("}\n", "} \n").toByteArray(StandardCharsets.UTF_8)
                } else {
                    bytes
                }
            })
            val error = assertFailsWith<KWebRuntimePayloadException> { verify(fixture, result.archive) }
            assertEquals("runtime.payload.manifest-non-canonical", error.code)
        }
    }

    @Test
    fun rejectsPayloadBytesThatDoNotMatchManifest() {
        withFixture(KWebTarget.parse("linux-x64")) { fixture ->
            val result = build(fixture)
            rewriteArchive(result.archive, transform = { name, bytes ->
                if (name == "runtime/libcef.so") {
                    bytes.copyOf().also { changed ->
                        changed[0] = (changed[0].toInt() xor 0x01).toByte()
                    }
                } else {
                    bytes
                }
            })
            val error = assertFailsWith<KWebRuntimePayloadException> { verify(fixture, result.archive) }
            assertEquals("runtime.payload.archive-entry-digest-mismatch", error.code)
        }
    }

    @Test
    fun rejectsManifestTargetTampering() {
        withFixture(KWebTarget.parse("linux-x64")) { fixture ->
            val result = build(fixture)
            rewriteArchive(result.archive, transform = { name, bytes ->
                if (name == KWEB_RUNTIME_PAYLOAD_MANIFEST_PATH) {
                    String(bytes, StandardCharsets.UTF_8)
                        .replace("\"target\": \"linux-x64\"", "\"target\": \"windows-x64\"")
                        .toByteArray(StandardCharsets.UTF_8)
                } else {
                    bytes
                }
            })
            val error = assertFailsWith<KWebRuntimePayloadException> { verify(fixture, result.archive) }
            assertEquals("runtime.payload.manifest-target-mismatch", error.code)
        }
    }

    @Test
    fun rejectsManifestTreeDigestTampering() {
        withFixture(KWebTarget.parse("linux-x64")) { fixture ->
            val result = build(fixture)
            rewriteArchive(result.archive, transform = { name, bytes ->
                if (name == KWEB_RUNTIME_PAYLOAD_MANIFEST_PATH) {
                    String(bytes, StandardCharsets.UTF_8)
                        .replace(result.manifest.treeSha256, "0".repeat(64))
                        .toByteArray(StandardCharsets.UTF_8)
                } else {
                    bytes
                }
            })
            val error = assertFailsWith<KWebRuntimePayloadException> { verify(fixture, result.archive) }
            assertEquals("runtime.payload.manifest-tree-digest-mismatch", error.code)
        }
    }

    @Test
    fun rejectsDuplicateArchiveNames() {
        withFixture(KWebTarget.parse("linux-x64")) { fixture ->
            val result = build(fixture)
            duplicateFirstArchiveEntry(result.archive)
            val error = assertFailsWith<KWebRuntimePayloadException> { verify(fixture, result.archive) }
            assertEquals("runtime.payload.archive-entry-duplicate", error.code)
        }
    }

    @Test
    fun rejectsUnsafeArchivePaths() {
        withFixture(KWebTarget.parse("linux-x64")) { fixture ->
            val archive = fixture.outputDirectory.resolve("unsafe.zip")
            writeUnsafeArchive(archive)
            val error = assertFailsWith<KWebRuntimePayloadException> { verify(fixture, archive) }
            assertEquals("runtime.payload.path-unsafe", error.code)
        }
    }

    @Test
    fun rejectsArchivePrefixBytes() {
        withFixture(KWebTarget.parse("linux-x64")) { fixture ->
            val result = build(fixture)
            val original = Files.readAllBytes(result.archive)
            Files.write(result.archive, byteArrayOf(0x4b, 0x57) + original)
            val error = assertFailsWith<KWebRuntimePayloadException> { verify(fixture, result.archive) }
            assertEquals("runtime.payload.archive-prefix-invalid", error.code)
        }
    }

    @Test
    fun rejectsTargetMismatchDuringIndependentVerification() {
        withFixture(KWebTarget.parse("linux-x64")) { fixture ->
            val result = build(fixture)
            val error = assertFailsWith<KWebRuntimePayloadException> {
                KWebRuntimePayloadVerifier.verify(
                    KWebRuntimePayloadVerificationRequest(
                        archive = result.archive,
                        catalog = fixture.catalog,
                        target = KWebTarget.parse("windows-x64"),
                        productVersion = PRODUCT_VERSION,
                    ),
                )
            }
            assertEquals("runtime.payload.manifest-target-mismatch", error.code)
        }
    }

    @Test
    fun rejectsCatalogMismatchedCefRootName() {
        withFixture(KWebTarget.parse("macos-arm64")) { fixture ->
            val renamed = fixture.root.resolve("cef-wrong-version")
            Files.move(fixture.cefRoot, renamed)
            val error = assertFailsWith<KWebRuntimePayloadException> {
                build(fixture.copy(cefRoot = renamed))
            }
            assertEquals("runtime.payload.cef-root-name-mismatch", error.code)
        }
    }

    @Test
    fun rejectsNativeClosureForAnotherTarget() {
        withFixture(KWebTarget.parse("windows-x64")) { fixture ->
            KWebRuntimePayloadContract.nativeClosure(fixture.target).forEach { spec ->
                Files.delete(fixture.nativeContract.resolve(spec.name))
            }
            KWebRuntimePayloadContract.nativeClosure(KWebTarget.parse("linux-x64")).forEach { spec ->
                val destination = fixture.nativeContract.resolve(spec.name)
                if (spec.type == KWebRuntimePayloadEntryType.SYMLINK) {
                    Files.createSymbolicLink(destination, Path.of(checkNotNull(spec.linkTarget)))
                } else {
                    writeBytes(destination, "foreign-native".toByteArray())
                }
            }
            val error = assertFailsWith<KWebRuntimePayloadException> { build(fixture) }
            assertEquals("runtime.payload.native-target-mismatch", error.code)
        }
    }

    @Test
    fun rejectsSpecialFilesystemEntries() {
        assumeFalse(
            KWebHostTargetDetector.detect().operatingSystem == KWebOperatingSystem.WINDOWS,
            "Unix-domain socket creation is unavailable on Windows.",
        )
        withFixture(KWebTarget.parse("linux-x64")) { fixture ->
            val fifo = fixture.nativeRelease.resolve("special.fifo")
            val process = ProcessBuilder("mkfifo", fifo.toString()).redirectErrorStream(true).start()
            val output = process.inputStream.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
            assertEquals(0, process.waitFor(), output)
            val error = assertFailsWith<KWebRuntimePayloadException> { build(fixture) }
            assertEquals("runtime.payload.input-special-file", error.code)
        }
    }

    @Test
    fun atomicallyReplacesAnExistingRegularOutput() {
        withFixture(KWebTarget.parse("windows-arm64")) { fixture ->
            val output = fixture.outputDirectory.resolve("payload.zip")
            val original = "existing-output".toByteArray(StandardCharsets.UTF_8)
            Files.write(output, original)
            val result = build(fixture)
            assertTrue(!Files.readAllBytes(output).contentEquals(original))
            assertEquals(result.manifest, verify(fixture, output))
        }
    }

    @Test
    fun preservesExistingOutputWhenInputValidationFails() {
        withFixture(KWebTarget.parse("linux-x64")) { fixture ->
            val output = fixture.outputDirectory.resolve("payload.zip")
            val original = "existing-output".toByteArray(StandardCharsets.UTF_8)
            Files.write(output, original)
            Files.delete(fixture.cefRoot.resolve("CREDITS.html"))
            assertFailsWith<KWebRuntimePayloadException> { build(fixture) }
            assertContentEquals(original, Files.readAllBytes(output))
            Files.newDirectoryStream(fixture.outputDirectory).use { stream ->
                assertEquals(listOf("payload.zip"), stream.map { it.fileName.toString() }.sorted())
            }
        }
    }

    @Test
    fun rejectsNonCanonicalTimestampModeAndFileType() {
        val cases = listOf<Pair<String, (String, ZipArchiveEntry) -> Unit>>(
            "runtime.payload.archive-entry-timestamp-invalid" to { name, entry ->
                if (name == "runtime/libcef.so") {
                    entry.setTimeLocal(LocalDateTime.of(2001, 1, 1, 0, 0, 0))
                }
            },
            "runtime.payload.archive-entry-mode-invalid" to { name, entry ->
                if (name == "runtime/libcef.so") {
                    entry.setUnixMode(UnixStat.FILE_FLAG or 0b110000000)
                }
            },
            "runtime.payload.archive-entry-type-invalid" to { name, entry ->
                if (name == "runtime/libcef.so") {
                    entry.setUnixMode(0x1000 or 0b110100100)
                }
            },
        )
        cases.forEach { (expectedCode, mutate) ->
            withFixture(KWebTarget.parse("linux-x64")) { fixture ->
                val result = build(fixture)
                rewriteArchive(
                    archive = result.archive,
                    mutateEntry = mutate,
                )
                val error = assertFailsWith<KWebRuntimePayloadException> { verify(fixture, result.archive) }
                assertEquals(expectedCode, error.code)
            }
        }
    }

    private fun build(fixture: Fixture, name: String = "payload.zip"): KWebRuntimePayloadBuildResult =
        KWebRuntimePayloadAssembler.build(
            KWebRuntimePayloadBuildRequest(
                catalog = fixture.catalog,
                target = fixture.target,
                productVersion = PRODUCT_VERSION,
                cefRoot = fixture.cefRoot,
                nativeReleaseDirectory = fixture.nativeRelease,
                nativeContractDirectory = fixture.nativeContract,
                outputArchive = fixture.outputDirectory.resolve(name),
            ),
        )

    private fun verify(fixture: Fixture, archive: Path): KWebRuntimePayloadManifest =
        KWebRuntimePayloadVerifier.verify(
            KWebRuntimePayloadVerificationRequest(
                archive = archive,
                catalog = fixture.catalog,
                target = fixture.target,
                productVersion = PRODUCT_VERSION,
            ),
        )

    private fun rewriteArchive(
        archive: Path,
        transform: (String, ByteArray) -> ByteArray = { _, bytes -> bytes },
        mutateEntry: (String, ZipArchiveEntry) -> Unit = { _, _ -> },
    ) {
        val temporary = archive.resolveSibling(".${archive.fileName}.rewrite")
        ZipFile.builder().setPath(archive).setCharset(StandardCharsets.UTF_8).get().use { input ->
            ZipArchiveOutputStream(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { output ->
                configureArchive(output)
                input.entriesInPhysicalOrder.asList().forEach { original ->
                    val bytes = input.getInputStream(original).use { it.readAllBytes() }
                    val changed = transform(original.name, bytes)
                    val entry = ZipArchiveEntry(original.name)
                    entry.setTimeLocal(LocalDateTime.of(2000, 1, 1, 0, 0, 0))
                    entry.setUnixMode(original.unixMode)
                    entry.method = original.method
                    entry.size = changed.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(changed) }.value
                    mutateEntry(original.name, entry)
                    output.putArchiveEntry(entry)
                    output.write(changed)
                    output.closeArchiveEntry()
                }
                output.finish()
            }
        }
        Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun duplicateFirstArchiveEntry(archive: Path) {
        val temporary = archive.resolveSibling(".${archive.fileName}.duplicate")
        ZipFile.builder().setPath(archive).setCharset(StandardCharsets.UTF_8).get().use { input ->
            val entries = input.entriesInPhysicalOrder.asList()
            ZipArchiveOutputStream(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { output ->
                configureArchive(output)
                (entries + entries.first()).forEach { original ->
                    val bytes = input.getInputStream(original).use { it.readAllBytes() }
                    val entry = ZipArchiveEntry(original.name)
                    entry.setTimeLocal(LocalDateTime.of(2000, 1, 1, 0, 0, 0))
                    entry.setUnixMode(original.unixMode)
                    entry.method = original.method
                    entry.size = bytes.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                    output.putArchiveEntry(entry)
                    output.write(bytes)
                    output.closeArchiveEntry()
                }
                output.finish()
            }
        }
        Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun writeUnsafeArchive(archive: Path) {
        ZipArchiveOutputStream(
            archive,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { output ->
            configureArchive(output)
            val bytes = "unsafe".toByteArray(StandardCharsets.UTF_8)
            val entry = ZipArchiveEntry("../evil")
            entry.setTimeLocal(LocalDateTime.of(2000, 1, 1, 0, 0, 0))
            entry.setUnixMode(UnixStat.FILE_FLAG or 0b110100100)
            entry.method = ZipArchiveOutputStream.DEFLATED
            entry.size = bytes.size.toLong()
            entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
            output.putArchiveEntry(entry)
            output.write(bytes)
            output.closeArchiveEntry()
            output.finish()
        }
    }

    private fun configureArchive(output: ZipArchiveOutputStream) {
        output.setEncoding(StandardCharsets.UTF_8.name())
        output.setUseLanguageEncodingFlag(true)
        output.setFallbackToUTF8(false)
        output.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER)
        output.setUseZip64(Zip64Mode.Never)
        output.setLevel(java.util.zip.Deflater.BEST_COMPRESSION)
    }

    private fun <T> withFixture(target: KWebTarget, block: (Fixture) -> T): T {
        val root = Files.createTempDirectory("kweb-runtime-payload-test-")
        return try {
            block(createFixture(root, target))
        } finally {
            deleteTree(root)
        }
    }

    private fun createFixture(root: Path, target: KWebTarget): Fixture {
        val catalog = CefRuntimeCatalogLoader.load(
            repositoryRoot().resolve("runtime/cef-runtime.json"),
        )
        val cefRoot = root.resolve(catalog.artifact(target).fileName.removeSuffix(".tar.bz2"))
        Files.createDirectories(cefRoot)
        Files.writeString(cefRoot.resolve("LICENSE.txt"), "CEF license\n")
        Files.writeString(cefRoot.resolve("CREDITS.html"), "<html>CEF credits</html>\n")

        val nativeRelease = root.resolve("native/build/Release")
        val nativeContract = root.resolve("native/build/contract")
        Files.createDirectories(nativeRelease)
        Files.createDirectories(nativeContract)
        createRuntime(target, nativeRelease)
        KWebRuntimePayloadContract.nativeClosure(target).forEach { spec ->
            val destination = nativeContract.resolve(spec.name)
            if (spec.type == KWebRuntimePayloadEntryType.SYMLINK) {
                Files.createSymbolicLink(destination, Path.of(checkNotNull(spec.linkTarget)))
            } else {
                writeBytes(destination, "native:${target.id}:${spec.name}".toByteArray())
            }
        }
        Files.writeString(nativeContract.resolve("ignored-contract-test"), "not packaged")
        return Fixture(
            root = root,
            target = target,
            catalog = catalog,
            cefRoot = cefRoot,
            nativeRelease = nativeRelease,
            nativeContract = nativeContract,
            outputDirectory = root.resolve("output").also(Files::createDirectories),
        )
    }

    private fun createRuntime(target: KWebTarget, release: Path) {
        when (target.operatingSystem) {
            KWebOperatingSystem.MACOS -> {
                val app = release.resolve("KWebShell.app")
                writeBytes(app.resolve("Contents/MacOS/KWebShell"), "mac-host".toByteArray())
                val framework = app.resolve(
                    "Contents/Frameworks/Chromium Embedded Framework.framework",
                )
                val version = framework.resolve("Versions/A")
                writeBytes(version.resolve("Chromium Embedded Framework"), "cef-framework".toByteArray())
                writeBytes(version.resolve("Libraries/libEGL.dylib"), "egl".toByteArray())
                writeBytes(version.resolve("Resources/Info.plist"), "plist".toByteArray())
                Files.createSymbolicLink(
                    framework.resolve("Chromium Embedded Framework"),
                    Path.of("Versions/A/Chromium Embedded Framework"),
                )
                Files.createSymbolicLink(framework.resolve("Libraries"), Path.of("Versions/A/Libraries"))
                Files.createSymbolicLink(framework.resolve("Resources"), Path.of("Versions/A/Resources"))
                Files.createSymbolicLink(framework.resolve("Versions/Current"), Path.of("A"))
            }

            KWebOperatingSystem.WINDOWS -> {
                writeBytes(release.resolve("KWebShell.exe"), "windows-host".toByteArray())
                writeBytes(release.resolve("libcef.dll"), "cef-dll".toByteArray())
                writeBytes(release.resolve("icudtl.dat"), "icu".toByteArray())
            }

            KWebOperatingSystem.LINUX -> {
                writeBytes(release.resolve("KWebShell"), "linux-host".toByteArray())
                writeBytes(release.resolve("libcef.so"), "cef-so".toByteArray())
                writeBytes(release.resolve("icudtl.dat"), "icu".toByteArray())
            }
        }
    }

    private fun setTreeTimes(root: Path, instant: Instant) {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (!attributes.isSymbolicLink) Files.setLastModifiedTime(file, FileTime.from(instant))
                    return FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (!attributes.isSymbolicLink) Files.setLastModifiedTime(directory, FileTime.from(instant))
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun writeBytes(path: Path, bytes: ByteArray) {
        Files.createDirectories(checkNotNull(path.parent))
        Files.write(path, bytes)
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun <T> Enumeration<T>.asList(): List<T> = buildList {
        while (this@asList.hasMoreElements()) add(this@asList.nextElement())
    }

    private fun repositoryRoot(): Path {
        var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (current != null) {
            val candidate = current.resolve("runtime/cef-runtime.json")
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) return current
            current = current.parent
        }
        error("Unable to locate runtime/cef-runtime.json from the test working directory.")
    }

    private data class Fixture(
        val root: Path,
        val target: KWebTarget,
        val catalog: CefRuntimeCatalog,
        val cefRoot: Path,
        val nativeRelease: Path,
        val nativeContract: Path,
        val outputDirectory: Path,
    )

    private companion object {
        const val PRODUCT_VERSION: String = "0.1.0-test"
    }
}
