package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebOperatingSystem
import io.github.kingsword09.kwebshell.core.KWebTarget
import java.nio.charset.StandardCharsets

internal data class KWebRuntimePayloadNativeSpec(
    val name: String,
    val type: KWebRuntimePayloadEntryType,
    val linkTarget: String? = null,
)

internal data class KWebRuntimePayloadRequiredPath(
    val path: String,
    val type: KWebRuntimePayloadEntryType,
    val mode: String,
)

internal object KWebRuntimePayloadContract {
    val pathComparator: Comparator<String> = Comparator(::compareUtf8)

    fun expectedCefRootName(artifact: CefRuntimeArtifact): String {
        payloadRequire(
            artifact.fileName.endsWith(CEF_ARCHIVE_SUFFIX),
            code = "runtime.payload.catalog-artifact-name-invalid",
            details = mapOf("fileName" to artifact.fileName),
            message = "The catalog artifact name does not end in '$CEF_ARCHIVE_SUFFIX'.",
        )
        return artifact.fileName.removeSuffix(CEF_ARCHIVE_SUFFIX)
    }

    fun sourceArtifact(artifact: CefRuntimeArtifact): KWebRuntimePayloadSourceArtifact =
        KWebRuntimePayloadSourceArtifact(
            fileName = artifact.fileName,
            size = artifact.size,
            checksumAlgorithm = artifact.checksum.algorithm,
            checksum = artifact.checksum.value,
        )

    fun nativeClosure(target: KWebTarget): List<KWebRuntimePayloadNativeSpec> =
        when (target.operatingSystem) {
            KWebOperatingSystem.WINDOWS -> listOf(
                KWebRuntimePayloadNativeSpec(
                    name = "kwebshell_engine.dll",
                    type = KWebRuntimePayloadEntryType.FILE,
                ),
                KWebRuntimePayloadNativeSpec(
                    name = "kwebshell_jni.dll",
                    type = KWebRuntimePayloadEntryType.FILE,
                ),
            )

            KWebOperatingSystem.MACOS -> listOf(
                KWebRuntimePayloadNativeSpec(
                    name = "libkwebshell_engine.1.0.0.dylib",
                    type = KWebRuntimePayloadEntryType.FILE,
                ),
                KWebRuntimePayloadNativeSpec(
                    name = "libkwebshell_engine.1.dylib",
                    type = KWebRuntimePayloadEntryType.SYMLINK,
                    linkTarget = "libkwebshell_engine.1.0.0.dylib",
                ),
                KWebRuntimePayloadNativeSpec(
                    name = "libkwebshell_engine.dylib",
                    type = KWebRuntimePayloadEntryType.SYMLINK,
                    linkTarget = "libkwebshell_engine.1.dylib",
                ),
                KWebRuntimePayloadNativeSpec(
                    name = "libkwebshell_jni.dylib",
                    type = KWebRuntimePayloadEntryType.FILE,
                ),
            )

            KWebOperatingSystem.LINUX -> listOf(
                KWebRuntimePayloadNativeSpec(
                    name = "libkwebshell_engine.so.1.0.0",
                    type = KWebRuntimePayloadEntryType.FILE,
                ),
                KWebRuntimePayloadNativeSpec(
                    name = "libkwebshell_engine.so.1",
                    type = KWebRuntimePayloadEntryType.SYMLINK,
                    linkTarget = "libkwebshell_engine.so.1.0.0",
                ),
                KWebRuntimePayloadNativeSpec(
                    name = "libkwebshell_engine.so",
                    type = KWebRuntimePayloadEntryType.SYMLINK,
                    linkTarget = "libkwebshell_engine.so.1",
                ),
                KWebRuntimePayloadNativeSpec(
                    name = "libkwebshell_jni.so",
                    type = KWebRuntimePayloadEntryType.FILE,
                ),
            )
        }

    fun knownNativeRuntimeNames(): Set<String> =
        KWebTarget.supported
            .flatMap(::nativeClosure)
            .mapTo(linkedSetOf(), KWebRuntimePayloadNativeSpec::name)

    fun runtimeRequiredPaths(target: KWebTarget): List<KWebRuntimePayloadRequiredPath> =
        when (target.operatingSystem) {
            KWebOperatingSystem.WINDOWS -> listOf(
                requiredExecutable("runtime/KWebShell.exe"),
                requiredExecutable("runtime/libcef.dll"),
            )

            KWebOperatingSystem.MACOS -> listOf(
                KWebRuntimePayloadRequiredPath(
                    path = "runtime/KWebShell.app/",
                    type = KWebRuntimePayloadEntryType.DIRECTORY,
                    mode = KWEB_RUNTIME_PAYLOAD_DIRECTORY_MODE,
                ),
                requiredExecutable("runtime/KWebShell.app/Contents/MacOS/KWebShell"),
                KWebRuntimePayloadRequiredPath(
                    path =
                        "runtime/KWebShell.app/Contents/Frameworks/" +
                            "Chromium Embedded Framework.framework/",
                    type = KWebRuntimePayloadEntryType.DIRECTORY,
                    mode = KWEB_RUNTIME_PAYLOAD_DIRECTORY_MODE,
                ),
                requiredExecutable(
                    "runtime/KWebShell.app/Contents/Frameworks/" +
                        "Chromium Embedded Framework.framework/Versions/A/" +
                        "Chromium Embedded Framework",
                ),
            )

            KWebOperatingSystem.LINUX -> listOf(
                requiredExecutable("runtime/KWebShell"),
                requiredExecutable("runtime/libcef.so"),
            )
        }

    fun validateProductVersion(productVersion: String) {
        payloadRequire(
            productVersion.matches(PRODUCT_VERSION_PATTERN),
            code = "runtime.payload.product-version-invalid",
            details = mapOf("productVersion" to productVersion),
            message = "The KWebShell product version is not a canonical payload version.",
        )
    }

    fun validateManifest(
        manifest: KWebRuntimePayloadManifest,
        catalog: CefRuntimeCatalog,
        target: KWebTarget,
        productVersion: String,
    ) {
        validateProductVersion(productVersion)
        val catalogManifest = catalog.manifest
        val artifact = catalog.artifact(target)
        payloadRequire(
            manifest.schemaVersion == KWEB_RUNTIME_PAYLOAD_SCHEMA_VERSION,
            code = "runtime.payload.manifest-schema-mismatch",
            details = mapOf("schemaVersion" to manifest.schemaVersion.toString()),
            message = "The runtime payload manifest schema is not supported.",
        )
        payloadRequire(
            manifest.product == KWEB_RUNTIME_PAYLOAD_PRODUCT,
            code = "runtime.payload.manifest-product-mismatch",
            details = mapOf("product" to manifest.product),
            message = "The runtime payload product identity is not KWebShell.",
        )
        payloadRequire(
            manifest.productVersion == productVersion,
            code = "runtime.payload.manifest-product-version-mismatch",
            details = mapOf("actual" to manifest.productVersion, "expected" to productVersion),
            message = "The runtime payload product version does not match the requested version.",
        )
        payloadRequire(
            manifest.target == target.id,
            code = "runtime.payload.manifest-target-mismatch",
            details = mapOf("actual" to manifest.target, "expected" to target.id),
            message = "The runtime payload target does not match the requested target.",
        )
        payloadRequire(
            manifest.cefVersion == catalogManifest.cefVersion &&
                manifest.chromiumVersion == catalogManifest.chromiumVersion,
            code = "runtime.payload.manifest-engine-version-mismatch",
            details = mapOf(
                "actualCef" to manifest.cefVersion,
                "expectedCef" to catalogManifest.cefVersion,
                "actualChromium" to manifest.chromiumVersion,
                "expectedChromium" to catalogManifest.chromiumVersion,
            ),
            message = "The runtime payload engine versions do not match the pinned catalog.",
        )
        val expectedArtifact = sourceArtifact(artifact)
        payloadRequire(
            manifest.sourceArtifact == expectedArtifact,
            code = "runtime.payload.manifest-source-artifact-mismatch",
            details = mapOf(
                "actual" to manifest.sourceArtifact.fileName,
                "expected" to expectedArtifact.fileName,
            ),
            message = "The runtime payload source artifact does not match the pinned catalog.",
        )
        payloadRequire(
            manifest.entries.isNotEmpty(),
            code = "runtime.payload.manifest-entries-empty",
            message = "The runtime payload manifest does not contain payload entries.",
        )

        val paths = manifest.entries.map(KWebRuntimePayloadEntry::path)
        payloadRequire(
            paths.size == paths.toSet().size,
            code = "runtime.payload.manifest-entry-duplicate",
            message = "The runtime payload manifest contains duplicate paths.",
        )
        payloadRequire(
            paths == paths.sortedWith(pathComparator),
            code = "runtime.payload.manifest-entry-order-invalid",
            message = "The runtime payload manifest paths are not in lexical UTF-8 order.",
        )
        manifest.entries.forEach(::validateManifestEntry)
        payloadRequire(
            manifest.treeSha256.matches(SHA256_PATTERN) &&
                manifest.treeSha256 == KWebRuntimePayloadManifestCodec.treeSha256(manifest.entries),
            code = "runtime.payload.manifest-tree-digest-mismatch",
            details = mapOf("treeSha256" to manifest.treeSha256),
            message = "The runtime payload tree digest does not match its canonical entries.",
        )
        validatePayloadShape(manifest.entries, target)
    }

    fun validatePayloadEntryPath(path: String, type: KWebRuntimePayloadEntryType) {
        val directory = type == KWebRuntimePayloadEntryType.DIRECTORY
        payloadRequire(
            path.isNotEmpty() && path.toByteArray(StandardCharsets.UTF_8).size <= ZIP_NAME_MAX_BYTES,
            code = "runtime.payload.path-length-invalid",
            details = mapOf("path" to path),
            message = "The runtime payload path has an invalid UTF-8 length.",
        )
        payloadRequire(
            path.none { it in FORBIDDEN_PORTABLE_CHARACTERS || it.code < 0x20 || it.code == 0x7f },
            code = "runtime.payload.path-character-invalid",
            details = mapOf("path" to path),
            message = "The runtime payload path contains an unsafe character.",
        )
        payloadRequire(
            directory == path.endsWith('/'),
            code = "runtime.payload.path-type-mismatch",
            details = mapOf("path" to path, "type" to type.name),
            message = "The runtime payload directory marker does not match its entry type.",
        )
        val withoutMarker = if (directory) path.dropLast(1) else path
        val segments = withoutMarker.split('/')
        payloadRequire(
            withoutMarker.isNotEmpty() &&
                !withoutMarker.startsWith('/') &&
                segments.all { segment ->
                    segment != "." && segment != ".." && isSafePortableSegment(segment)
                },
            code = "runtime.payload.path-unsafe",
            details = mapOf("path" to path),
            message = "The runtime payload path is absolute, ambiguous, or traverses a parent.",
        )
        payloadRequire(
            segments.first() in PAYLOAD_ROOTS,
            code = "runtime.payload.path-root-invalid",
            details = mapOf("path" to path),
            message = "The runtime payload path is outside the declared payload roots.",
        )
    }

    fun validateLinkTarget(entryPath: String, linkTarget: String) {
        payloadRequire(
            linkTarget.isNotEmpty() &&
                !linkTarget.startsWith('/') &&
                !WINDOWS_ABSOLUTE_PATH.matches(linkTarget) &&
                linkTarget.none {
                    it in FORBIDDEN_PORTABLE_CHARACTERS || it.code < 0x20 || it.code == 0x7f
                },
            code = "runtime.payload.symlink-target-invalid",
            details = mapOf("path" to entryPath, "linkTarget" to linkTarget),
            message = "The runtime payload symbolic link target is not a safe relative path.",
        )
        val targetSegments = linkTarget.split('/')
        payloadRequire(
            targetSegments.all { segment ->
                segment == "." || segment == ".." || isSafePortableSegment(segment)
            },
            code = "runtime.payload.symlink-target-invalid",
            details = mapOf("path" to entryPath, "linkTarget" to linkTarget),
            message = "The runtime payload symbolic link target contains an empty segment.",
        )

        val entrySegments = entryPath.removeSuffix("/").split('/')
        val root = entrySegments.first()
        val resolved = entrySegments.dropLast(1).toMutableList()
        targetSegments.forEach { segment ->
            when (segment) {
                "." -> Unit
                ".." -> {
                    payloadRequire(
                        resolved.size > 1,
                        code = "runtime.payload.symlink-escape",
                        details = mapOf("path" to entryPath, "linkTarget" to linkTarget),
                        message = "The runtime payload symbolic link escapes its payload root.",
                    )
                    resolved.removeAt(resolved.lastIndex)
                }

                else -> resolved += segment
            }
        }
        payloadRequire(
            resolved.isNotEmpty() && resolved.first() == root,
            code = "runtime.payload.symlink-escape",
            details = mapOf("path" to entryPath, "linkTarget" to linkTarget),
            message = "The runtime payload symbolic link crosses a payload root.",
        )
    }

    private fun validateManifestEntry(entry: KWebRuntimePayloadEntry) {
        validatePayloadEntryPath(entry.path, entry.type)
        payloadRequire(
            entry.sha256.matches(SHA256_PATTERN),
            code = "runtime.payload.manifest-entry-digest-invalid",
            details = mapOf("path" to entry.path, "sha256" to entry.sha256),
            message = "The runtime payload manifest entry has an invalid SHA-256 digest.",
        )
        when (entry.type) {
            KWebRuntimePayloadEntryType.DIRECTORY -> payloadRequire(
                entry.mode == KWEB_RUNTIME_PAYLOAD_DIRECTORY_MODE &&
                    entry.size == 0L &&
                    entry.sha256 == KWEB_RUNTIME_PAYLOAD_EMPTY_SHA256 &&
                    entry.linkTarget == null,
                code = "runtime.payload.manifest-directory-invalid",
                details = mapOf("path" to entry.path),
                message = "The runtime payload directory metadata is invalid.",
            )

            KWebRuntimePayloadEntryType.FILE -> payloadRequire(
                entry.mode in setOf(KWEB_RUNTIME_PAYLOAD_FILE_MODE, KWEB_RUNTIME_PAYLOAD_EXECUTABLE_MODE) &&
                    entry.size >= 0L &&
                    entry.linkTarget == null,
                code = "runtime.payload.manifest-file-invalid",
                details = mapOf("path" to entry.path),
                message = "The runtime payload file metadata is invalid.",
            )

            KWebRuntimePayloadEntryType.SYMLINK -> {
                val linkTarget = entry.linkTarget ?: payloadFailure(
                    code = "runtime.payload.manifest-symlink-invalid",
                    details = mapOf("path" to entry.path),
                    message = "The runtime payload symbolic link metadata is missing its target.",
                )
                payloadRequire(
                    entry.mode == KWEB_RUNTIME_PAYLOAD_SYMLINK_MODE,
                    code = "runtime.payload.manifest-symlink-invalid",
                    details = mapOf("path" to entry.path),
                    message = "The runtime payload symbolic link metadata is invalid.",
                )
                validateLinkTarget(entry.path, linkTarget)
                val bytes = linkTarget.toByteArray(StandardCharsets.UTF_8)
                payloadRequire(
                    entry.size == bytes.size.toLong() && entry.sha256 == sha256(bytes),
                    code = "runtime.payload.manifest-symlink-invalid",
                    details = mapOf("path" to entry.path),
                    message = "The runtime payload symbolic link digest or size is invalid.",
                )
            }
        }
    }

    private fun validatePayloadShape(entries: List<KWebRuntimePayloadEntry>, target: KWebTarget) {
        val byPath = entries.associateBy(KWebRuntimePayloadEntry::path)
        PAYLOAD_ROOTS.forEach { root ->
            requireEntry(
                byPath,
                KWebRuntimePayloadRequiredPath(
                    path = "$root/",
                    type = KWebRuntimePayloadEntryType.DIRECTORY,
                    mode = KWEB_RUNTIME_PAYLOAD_DIRECTORY_MODE,
                ),
            )
        }
        payloadRequire(
            entries.all { it.path.substringBefore('/') in PAYLOAD_ROOTS },
            code = "runtime.payload.layout-root-invalid",
            message = "The runtime payload contains an entry outside its declared roots.",
        )

        val licensePaths = entries.filter { it.path.startsWith("licenses/") }.mapTo(linkedSetOf()) { it.path }
        payloadRequire(
            licensePaths == LICENSE_PATHS,
            code = "runtime.payload.license-layout-invalid",
            details = mapOf("actual" to licensePaths.joinToString()),
            message = "The runtime payload license tree is incomplete or contains unexpected entries.",
        )
        listOf("licenses/CEF-LICENSE.txt", "licenses/CEF-CREDITS.html").forEach { path ->
            val entry = byPath.getValue(path)
            payloadRequire(
                entry.type == KWebRuntimePayloadEntryType.FILE &&
                    entry.mode == KWEB_RUNTIME_PAYLOAD_FILE_MODE &&
                    entry.size > 0L,
                code = "runtime.payload.license-file-invalid",
                details = mapOf("path" to path),
                message = "The runtime payload CEF notice is missing or empty.",
            )
        }

        val expectedNative = nativeClosure(target)
        val expectedNativePaths = linkedSetOf("native/").apply {
            expectedNative.forEach { add("native/${it.name}") }
        }
        val actualNativePaths = entries.filter { it.path.startsWith("native/") }.mapTo(linkedSetOf()) { it.path }
        payloadRequire(
            actualNativePaths == expectedNativePaths,
            code = "runtime.payload.native-layout-invalid",
            details = mapOf(
                "actual" to actualNativePaths.joinToString(),
                "expected" to expectedNativePaths.joinToString(),
            ),
            message = "The runtime payload native library closure is incomplete or contains extra entries.",
        )
        expectedNative.forEach { spec ->
            val entry = byPath.getValue("native/${spec.name}")
            payloadRequire(
                entry.type == spec.type &&
                    entry.mode == if (spec.type == KWebRuntimePayloadEntryType.SYMLINK) {
                        KWEB_RUNTIME_PAYLOAD_SYMLINK_MODE
                    } else {
                        KWEB_RUNTIME_PAYLOAD_EXECUTABLE_MODE
                    } &&
                    entry.linkTarget == spec.linkTarget,
                code = "runtime.payload.native-entry-invalid",
                details = mapOf("path" to entry.path),
                message = "The runtime payload native library does not match its target closure.",
            )
        }

        val runtimeEntries = entries.filter { it.path.startsWith("runtime/") }
        payloadRequire(
            runtimeEntries.any { it.type == KWebRuntimePayloadEntryType.FILE },
            code = "runtime.payload.runtime-tree-empty",
            message = "The runtime payload does not contain any runtime files.",
        )
        if (target.operatingSystem == KWebOperatingSystem.MACOS) {
            payloadRequire(
                runtimeEntries.all { it.path == "runtime/" || it.path.startsWith("runtime/KWebShell.app/") },
                code = "runtime.payload.macos-runtime-layout-invalid",
                message = "The macOS runtime payload contains a standalone helper outside KWebShell.app.",
            )
        }
        runtimeRequiredPaths(target).forEach { requireEntry(byPath, it) }
    }

    private fun requireEntry(
        entries: Map<String, KWebRuntimePayloadEntry>,
        required: KWebRuntimePayloadRequiredPath,
    ) {
        val entry = entries[required.path]
        payloadRequire(
            entry != null &&
                entry.type == required.type &&
                entry.mode == required.mode &&
                (required.type != KWebRuntimePayloadEntryType.FILE || entry.size > 0L),
            code = "runtime.payload.required-entry-missing",
            details = mapOf("path" to required.path),
            message = "The runtime payload is missing required target entry '${required.path}'.",
        )
    }

    private fun requiredExecutable(path: String): KWebRuntimePayloadRequiredPath =
        KWebRuntimePayloadRequiredPath(
            path = path,
            type = KWebRuntimePayloadEntryType.FILE,
            mode = KWEB_RUNTIME_PAYLOAD_EXECUTABLE_MODE,
        )

    private fun isSafePortableSegment(segment: String): Boolean {
        if (segment.isEmpty() || segment.endsWith('.') || segment.endsWith(' ')) return false
        val baseName = segment.substringBefore('.').uppercase()
        return baseName !in WINDOWS_RESERVED_NAMES &&
            !baseName.matches(WINDOWS_NUMBERED_RESERVED_NAME)
    }

    private fun compareUtf8(left: String, right: String): Int {
        val leftBytes = left.toByteArray(StandardCharsets.UTF_8)
        val rightBytes = right.toByteArray(StandardCharsets.UTF_8)
        val sharedSize = minOf(leftBytes.size, rightBytes.size)
        repeat(sharedSize) { index ->
            val comparison = (leftBytes[index].toInt() and 0xff) - (rightBytes[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return leftBytes.size - rightBytes.size
    }

    private val PAYLOAD_ROOTS: Set<String> = linkedSetOf("licenses", "native", "runtime")
    private val LICENSE_PATHS: Set<String> = linkedSetOf(
        "licenses/",
        "licenses/CEF-CREDITS.html",
        "licenses/CEF-LICENSE.txt",
    )
    private val PRODUCT_VERSION_PATTERN: Regex = Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,127}")
    private val SHA256_PATTERN: Regex = Regex("[0-9a-f]{64}")
    private val WINDOWS_ABSOLUTE_PATH: Regex = Regex("^[A-Za-z]:.*")
    private val WINDOWS_RESERVED_NAMES: Set<String> = setOf("CON", "PRN", "AUX", "NUL")
    private val WINDOWS_NUMBERED_RESERVED_NAME: Regex = Regex("(?:COM|LPT)[1-9]")
    private val FORBIDDEN_PORTABLE_CHARACTERS: Set<Char> = setOf('\\', ':', '<', '>', '"', '|', '?', '*')
    private const val CEF_ARCHIVE_SUFFIX: String = ".tar.bz2"
    private const val ZIP_NAME_MAX_BYTES: Int = 65_535
}

internal fun payloadRequire(
    condition: Boolean,
    code: String,
    details: Map<String, String> = emptyMap(),
    message: String,
) {
    if (!condition) {
        payloadFailure(code = code, details = details, message = message)
    }
}
