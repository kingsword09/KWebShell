package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebTarget

internal data class KWebRuntimeReleaseContentDigest(
    val size: Long,
    val sha256: String,
    val crc32: Long,
)

internal object KWebRuntimeReleaseContract {
    fun validateManifest(
        manifest: KWebRuntimeReleaseManifest,
        catalog: CefRuntimeCatalog,
        target: KWebTarget,
        productVersion: String,
        payloadDigest: KWebRuntimeReleaseContentDigest,
        trustedKeyId: String,
    ) {
        releaseRequire(
            manifest.schemaVersion == KWEB_RUNTIME_RELEASE_SCHEMA_VERSION,
            code = "runtime.release.metadata-schema-unsupported",
            details = { mapOf("schemaVersion" to manifest.schemaVersion.toString()) },
            message = "The signed runtime release metadata schema is unsupported.",
        )
        releaseRequire(
            manifest.product == KWEB_RUNTIME_RELEASE_PRODUCT,
            code = "runtime.release.metadata-product-mismatch",
            details = { mapOf("product" to manifest.product) },
            message = "The signed runtime release metadata names an unexpected product.",
        )
        releaseRequire(
            manifest.productVersion == productVersion,
            code = "runtime.release.metadata-product-version-mismatch",
            details = {
                mapOf(
                    "actual" to manifest.productVersion,
                    "expected" to productVersion,
                )
            },
            message = "The signed runtime release product version does not match its request.",
        )
        releaseRequire(
            manifest.target == target.id,
            code = "runtime.release.metadata-target-mismatch",
            details = {
                mapOf(
                    "actual" to manifest.target,
                    "expected" to target.id,
                )
            },
            message = "The signed runtime release target does not match its request.",
        )

        val catalogManifest = catalog.manifest
        releaseRequire(
            manifest.cefVersion == catalogManifest.cefVersion &&
                manifest.chromiumVersion == catalogManifest.chromiumVersion,
            code = "runtime.release.metadata-runtime-version-mismatch",
            details = {
                mapOf(
                    "cefVersion" to manifest.cefVersion,
                    "chromiumVersion" to manifest.chromiumVersion,
                )
            },
            message = "The signed runtime release CEF or Chromium version does not match its catalog.",
        )
        releaseRequire(
            manifest.payload.fileName == KWEB_RUNTIME_RELEASE_PAYLOAD_FILE_NAME,
            code = "runtime.release.metadata-payload-name-invalid",
            details = { mapOf("fileName" to manifest.payload.fileName) },
            message = "The signed runtime release payload file name is not canonical.",
        )
        releaseRequire(
            manifest.payload.size == payloadDigest.size && manifest.payload.size > 0L,
            code = "runtime.release.metadata-payload-size-mismatch",
            details = {
                mapOf(
                    "actual" to manifest.payload.size.toString(),
                    "expected" to payloadDigest.size.toString(),
                )
            },
            message = "The signed runtime release payload size does not match its bytes.",
        )
        releaseRequire(
            manifest.payload.sha256.matches(KWEB_RUNTIME_RELEASE_SHA256_PATTERN) &&
                manifest.payload.sha256 == payloadDigest.sha256,
            code = "runtime.release.metadata-payload-digest-mismatch",
            details = {
                mapOf(
                    "actual" to manifest.payload.sha256,
                    "expected" to payloadDigest.sha256,
                )
            },
            message = "The signed runtime release payload digest does not match its bytes.",
        )
        releaseRequire(
            manifest.payload.treeSha256.matches(KWEB_RUNTIME_RELEASE_SHA256_PATTERN),
            code = "runtime.release.metadata-payload-tree-invalid",
            details = { mapOf("treeSha256" to manifest.payload.treeSha256) },
            message = "The signed runtime release tree digest is not a SHA-256 value.",
        )
        releaseRequire(
            manifest.signatureAlgorithm == KWEB_RUNTIME_RELEASE_SIGNATURE_ALGORITHM,
            code = "runtime.release.metadata-signature-algorithm-invalid",
            details = { mapOf("algorithm" to manifest.signatureAlgorithm) },
            message = "The signed runtime release does not use Ed25519.",
        )
        releaseRequire(
            manifest.keyId.matches(KWEB_RUNTIME_RELEASE_KEY_ID_PATTERN) &&
                manifest.keyId == trustedKeyId,
            code = "runtime.release.metadata-key-id-mismatch",
            details = { mapOf("actual" to manifest.keyId, "expected" to trustedKeyId) },
            message = "The signed runtime release key ID does not match the trusted public key.",
        )
    }

    fun validatePayloadManifest(
        manifest: KWebRuntimeReleaseManifest,
        payloadManifest: KWebRuntimePayloadManifest,
    ) {
        releaseRequire(
            manifest.productVersion == payloadManifest.productVersion &&
                manifest.target == payloadManifest.target &&
                manifest.cefVersion == payloadManifest.cefVersion &&
                manifest.chromiumVersion == payloadManifest.chromiumVersion,
            code = "runtime.release.metadata-payload-identity-mismatch",
            message = "The signed release identity does not match its nested payload manifest.",
        )
        releaseRequire(
            manifest.payload.treeSha256 == payloadManifest.treeSha256,
            code = "runtime.release.metadata-payload-tree-mismatch",
            details = {
                mapOf(
                    "actual" to manifest.payload.treeSha256,
                    "expected" to payloadManifest.treeSha256,
                )
            },
            message = "The signed release tree digest does not match its nested payload manifest.",
        )
    }
}
