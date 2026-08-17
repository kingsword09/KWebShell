package io.github.kingsword09.kwebshell.runtime

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

internal object KWebRuntimeReleaseKeys {
    fun loadPrivateKey(path: Path): PrivateKey {
        val bytes = readKey(path, "private")
        return try {
            KeyFactory.getInstance(KWEB_RUNTIME_RELEASE_SIGNATURE_ALGORITHM)
                .generatePrivate(PKCS8EncodedKeySpec(bytes))
                .also { key ->
                    releaseRequire(
                        key.encoded.contentEquals(bytes),
                        code = "runtime.release.private-key-non-canonical",
                        message = "The release private key is not canonical PKCS#8 DER.",
                    )
                }
        } catch (error: KWebRuntimeReleaseException) {
            throw error
        } catch (error: Exception) {
            releaseFailure(
                code = "runtime.release.private-key-invalid",
                details = mapOf("path" to path.toString()),
                message = "The release private key is not valid PKCS#8 Ed25519 DER.",
                cause = error,
            )
        }
    }

    fun loadPublicKey(path: Path): PublicKey {
        val bytes = readKey(path, "public")
        return try {
            KeyFactory.getInstance(KWEB_RUNTIME_RELEASE_SIGNATURE_ALGORITHM)
                .generatePublic(X509EncodedKeySpec(bytes))
                .also { key ->
                    releaseRequire(
                        key.encoded.contentEquals(bytes),
                        code = "runtime.release.public-key-non-canonical",
                        message = "The trusted release public key is not canonical X.509 DER.",
                    )
                }
        } catch (error: KWebRuntimeReleaseException) {
            throw error
        } catch (error: Exception) {
            releaseFailure(
                code = "runtime.release.public-key-invalid",
                details = mapOf("path" to path.toString()),
                message = "The trusted release public key is not valid X.509 Ed25519 DER.",
                cause = error,
            )
        }
    }

    fun keyId(publicKey: PublicKey): String = sha256(publicKey.encoded)

    fun requireMatchingPair(privateKey: PrivateKey, publicKey: PublicKey) {
        val probe = "KWebShell release key pair check v1".toByteArray(Charsets.US_ASCII)
        val signature = sign(privateKey, probe)
        releaseRequire(
            verify(publicKey, probe, signature),
            code = "runtime.release.key-pair-mismatch",
            message = "The release private and public keys do not form one Ed25519 key pair.",
        )
    }

    fun sign(privateKey: PrivateKey, bytes: ByteArray): ByteArray {
        return try {
            Signature.getInstance(KWEB_RUNTIME_RELEASE_SIGNATURE_ALGORITHM).run {
                initSign(privateKey)
                update(KWEB_RUNTIME_RELEASE_SIGNATURE_DOMAIN)
                update(bytes)
                sign()
            }.also { signature ->
                releaseRequire(
                    signature.size == KWEB_RUNTIME_RELEASE_SIGNATURE_SIZE,
                    code = "runtime.release.signature-size-invalid",
                    details = { mapOf("size" to signature.size.toString()) },
                    message = "Ed25519 produced an unexpected signature size.",
                )
            }
        } catch (error: KWebRuntimeReleaseException) {
            throw error
        } catch (error: Exception) {
            releaseFailure(
                code = "runtime.release.sign-failed",
                message = "Unable to sign the canonical runtime release metadata.",
                cause = error,
            )
        }
    }

    fun verify(publicKey: PublicKey, bytes: ByteArray, signatureBytes: ByteArray): Boolean {
        if (signatureBytes.size != KWEB_RUNTIME_RELEASE_SIGNATURE_SIZE) return false
        return try {
            Signature.getInstance(KWEB_RUNTIME_RELEASE_SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(KWEB_RUNTIME_RELEASE_SIGNATURE_DOMAIN)
                update(bytes)
                verify(signatureBytes)
            }
        } catch (_: java.security.SignatureException) {
            false
        } catch (error: Exception) {
            releaseFailure(
                code = "runtime.release.signature-verification-failed",
                message = "Unable to verify the Ed25519 runtime release signature.",
                cause = error,
            )
        }
    }

    private fun readKey(path: Path, kind: String): ByteArray {
        releaseRequire(
            path.isAbsolute && path == path.normalize(),
            code = "runtime.release.$kind-key-path-invalid",
            details = { mapOf("path" to path.toString()) },
            message = "The release $kind key path must be absolute and normalized.",
        )
        releaseRequire(
            !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
            code = "runtime.release.$kind-key-file-invalid",
            details = { mapOf("path" to path.toString()) },
            message = "The release $kind key must be a regular non-symbolic-link file.",
        )
        val size = try {
            Files.size(path)
        } catch (error: Exception) {
            releaseFailure(
                code = "runtime.release.$kind-key-read-failed",
                details = mapOf("path" to path.toString()),
                message = "Unable to inspect the release $kind key.",
                cause = error,
            )
        }
        releaseRequire(
            size in 1..KWEB_RUNTIME_RELEASE_MAX_KEY_BYTES,
            code = "runtime.release.$kind-key-size-invalid",
            details = { mapOf("path" to path.toString(), "size" to size.toString()) },
            message = "The release $kind key has an invalid size.",
        )
        return try {
            Files.readAllBytes(path)
        } catch (error: Exception) {
            releaseFailure(
                code = "runtime.release.$kind-key-read-failed",
                details = mapOf("path" to path.toString()),
                message = "Unable to read the release $kind key.",
                cause = error,
            )
        }
    }
}
