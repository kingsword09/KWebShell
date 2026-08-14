package io.github.kingsword09.kwebshell.extensions

public enum class KWebExtensionPackageFormat {
    UNPACKED,
    CRX3,
}

public data class KWebExtensionPackage(
    public val manifest: KWebExtensionManifest,
    public val extensionId: String,
    public val publicKeyBase64: String,
    public val permissionReview: KWebExtensionPermissionReview,
    public val format: KWebExtensionPackageFormat,
)

public object KWebExtensionId {
    public fun fromSha256Hash(hash: ByteArray): String {
        if (hash.size < ID_BYTES) {
            extensionFailure(
                code = "extensions.id.hash-too-short",
                details = mapOf("size" to hash.size.toString()),
                message = "An extension ID requires at least 16 bytes of SHA-256 output.",
            )
        }
        return buildString(ID_BYTES * 2) {
            repeat(ID_BYTES) { index ->
                val value = hash[index].toInt() and 0xff
                append(ALPHABET[value ushr 4])
                append(ALPHABET[value and 0x0f])
            }
        }
    }

    public fun fromSha256Hex(hex: String): String {
        if (hex.length != SHA256_HEX_LENGTH || !hex.all { it in HEX_DIGITS }) {
            extensionFailure(
                code = "extensions.id.sha256-hex-invalid",
                details = mapOf("length" to hex.length.toString()),
                message = "The SHA-256 hash must be exactly 64 lowercase hexadecimal characters.",
            )
        }
        val bytes = ByteArray(ID_BYTES)
        repeat(ID_BYTES) { index ->
            bytes[index] = hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return fromSha256Hash(bytes)
    }

    public fun isValid(value: String): Boolean = EXTENSION_ID.matches(value)

    private const val ID_BYTES: Int = 16
    private const val SHA256_HEX_LENGTH: Int = 64
    private const val ALPHABET: String = "abcdefghijklmnop"
    private const val HEX_DIGITS: String = "0123456789abcdef"
    private val EXTENSION_ID = Regex("[a-p]{32}")
}
