package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebOperatingSystem
import io.github.kingsword09.kwebshell.core.KWebTarget
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyPairGenerator

internal class KWebRuntimeReleaseTestFixture private constructor(
    val root: Path,
    val target: KWebTarget,
    val catalogPath: Path,
    val catalog: CefRuntimeCatalog,
    val payload: KWebRuntimePayloadBuildResult,
    val privateKey: Path,
    val publicKey: Path,
    val outputDirectory: Path,
) : AutoCloseable {
    fun sign(
        name: String = "release.zip",
        signingPrivateKey: Path = privateKey,
        signingPublicKey: Path = publicKey,
    ): KWebRuntimeReleaseSignResult = KWebRuntimeReleaseSigner.sign(
        KWebRuntimeReleaseSignRequest(
            payloadArchive = payload.archive,
            catalog = catalog,
            target = target,
            productVersion = PRODUCT_VERSION,
            privateKey = signingPrivateKey,
            publicKey = signingPublicKey,
            outputPack = outputDirectory.resolve(name),
        ),
    )

    fun verify(
        pack: Path,
        trustedPublicKey: Path = publicKey,
        expectedTarget: KWebTarget = target,
        expectedVersion: String = PRODUCT_VERSION,
    ): KWebRuntimeReleaseVerificationResult = KWebRuntimeReleaseVerifier.verify(
        KWebRuntimeReleaseVerificationRequest(
            pack = pack,
            catalog = catalog,
            target = expectedTarget,
            productVersion = expectedVersion,
            trustedPublicKey = trustedPublicKey,
        ),
    )

    fun createKeyPair(prefix: String): Pair<Path, Path> = generateKeyPair(root, prefix)

    override fun close() {
        deleteTree(root)
    }

    companion object {
        const val PRODUCT_VERSION: String = "0.1.0-release-test"

        fun create(target: KWebTarget): KWebRuntimeReleaseTestFixture {
            val root = Files.createTempDirectory("kweb-runtime-release-test-")
            try {
                val catalogPath = repositoryRoot().resolve("runtime/cef-runtime.json")
                val catalog = CefRuntimeCatalogLoader.load(catalogPath)
                val cefRoot = root.resolve(catalog.artifact(target).fileName.removeSuffix(".tar.bz2"))
                Files.createDirectories(cefRoot)
                Files.writeString(cefRoot.resolve("LICENSE.txt"), "CEF license\n")
                Files.writeString(cefRoot.resolve("CREDITS.html"), "<html>CEF credits</html>\n")
                val nativeRelease = root.resolve("native/build/Release")
                val nativeContract = root.resolve("native/build/contract")
                Files.createDirectories(nativeRelease)
                Files.createDirectories(nativeContract)
                createRuntime(target, nativeRelease)
                createNativeClosure(target, nativeContract)
                val output = root.resolve("output").also(Files::createDirectories)
                val payload = KWebRuntimePayloadAssembler.build(
                    KWebRuntimePayloadBuildRequest(
                        catalog = catalog,
                        target = target,
                        productVersion = PRODUCT_VERSION,
                        cefRoot = cefRoot,
                        nativeReleaseDirectory = nativeRelease,
                        nativeContractDirectory = nativeContract,
                        outputArchive = output.resolve("payload.zip"),
                    ),
                )
                val (privateKey, publicKey) = generateKeyPair(root, "primary")
                return KWebRuntimeReleaseTestFixture(
                    root,
                    target,
                    catalogPath,
                    catalog,
                    payload,
                    privateKey,
                    publicKey,
                    output,
                )
            } catch (error: Exception) {
                deleteTree(root)
                throw error
            }
        }

        private fun generateKeyPair(root: Path, prefix: String): Pair<Path, Path> {
            val pair = KeyPairGenerator.getInstance(KWEB_RUNTIME_RELEASE_SIGNATURE_ALGORITHM).generateKeyPair()
            val privateKey = root.resolve("$prefix-private.pk8")
            val publicKey = root.resolve("$prefix-public.der")
            Files.write(privateKey, pair.private.encoded)
            Files.write(publicKey, pair.public.encoded)
            return privateKey to publicKey
        }

        private fun createNativeClosure(target: KWebTarget, directory: Path) {
            KWebRuntimePayloadContract.nativeClosure(target).forEach { spec ->
                val destination = directory.resolve(spec.name)
                if (spec.type == KWebRuntimePayloadEntryType.SYMLINK) {
                    Files.createSymbolicLink(destination, Path.of(checkNotNull(spec.linkTarget)))
                } else {
                    writeBytes(destination, "native:${target.id}:${spec.name}".toByteArray())
                }
            }
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

        private fun writeBytes(path: Path, bytes: ByteArray) {
            Files.createDirectories(checkNotNull(path.parent))
            Files.write(path, bytes)
        }

        private fun repositoryRoot(): Path {
            var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
            while (current != null) {
                if (Files.isRegularFile(current.resolve("runtime/cef-runtime.json"), LinkOption.NOFOLLOW_LINKS)) {
                    return current
                }
                current = current.parent
            }
            error("Unable to locate runtime/cef-runtime.json from the test working directory.")
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

                    override fun postVisitDirectory(
                        directory: Path,
                        error: java.io.IOException?,
                    ): FileVisitResult {
                        if (error != null) throw error
                        Files.delete(directory)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
    }
}
