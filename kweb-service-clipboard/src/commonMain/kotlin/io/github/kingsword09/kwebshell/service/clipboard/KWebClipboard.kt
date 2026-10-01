package io.github.kingsword09.kwebshell.service.clipboard

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.services.KWebNativeService
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceKey
import io.github.kingsword09.kwebshell.services.KWebServiceOperationDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.KWebServiceVersion
import io.github.kingsword09.kwebshell.services.KWebServiceVersionRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

public enum class KWebClipboardSelection(public val id: String) {
    SYSTEM("system"),
    ;

    public companion object {
        public fun fromId(id: String): KWebClipboardSelection =
            entries.singleOrNull { it.id == id }
                ?: throw KWebConfigurationException(
                    code = KWebClipboardErrorCode.SELECTION_UNSUPPORTED,
                    details = mapOf("selection" to id),
                    message = "The requested clipboard selection is not published.",
                )
    }
}

public enum class KWebClipboardFormat(public val id: String) {
    TEXT_PLAIN("text/plain"),
    TEXT_HTML("text/html"),
    TEXT_RTF("text/rtf"),
    URI_LIST("text/uri-list"),
    ;

    public companion object {
        public fun fromId(id: String): KWebClipboardFormat =
            entries.singleOrNull { it.id == id }
                ?: throw KWebConfigurationException(
                    code = KWebClipboardErrorCode.FORMAT_UNSUPPORTED,
                    details = mapOf("format" to id),
                    message = "The requested clipboard format is not published.",
                )
    }
}

public enum class KWebClipboardPayloadEncoding(public val id: String) {
    UTF8("utf8"),
    RTF_BYTES("rtf-bytes"),
    ;
}

public enum class KWebClipboardOwnership(public val id: String) {
    OWNED("owned"),
    FOREIGN("foreign"),
    EMPTY("empty"),
    UNAVAILABLE("unavailable"),
    ;
}

public class KWebClipboardReadRequest(
    public val selection: KWebClipboardSelection,
    formats: Collection<KWebClipboardFormat>,
) {
    private val formatSnapshot = formats.toList()
    public val formats: List<KWebClipboardFormat> get() = formatSnapshot.toList()

    init {
        if (formatSnapshot.isEmpty() || formatSnapshot.size > KWEB_CLIPBOARD_MAX_FORMATS ||
            formatSnapshot.size != formatSnapshot.toSet().size
        ) {
            invalid("formats", "A clipboard read must request one through four unique formats.")
        }
    }
}

public class KWebClipboardPayloadHandle internal constructor(
    public val token: String,
) {
    init {
        if (!HANDLE_PATTERN.matches(token)) {
            invalid(KWebClipboardErrorCode.PAYLOAD_NOT_FOUND, "The clipboard payload handle is invalid.")
        }
    }

    public companion object {
        internal fun fromBridge(token: String): KWebClipboardPayloadHandle = KWebClipboardPayloadHandle(token)
    }
}

public data class KWebClipboardPayloadDescriptor(
    public val handle: KWebClipboardPayloadHandle,
    public val format: KWebClipboardFormat,
    public val encoding: KWebClipboardPayloadEncoding,
    public val sizeBytes: Long,
) {
    init {
        if (sizeBytes < 0 || sizeBytes > KWEB_CLIPBOARD_MAX_ITEM_BYTES) {
            invalid(KWebClipboardErrorCode.PAYLOAD_TOO_LARGE, "The clipboard payload size is outside its bound.")
        }
    }
}

public class KWebClipboardReadResult(
    public val sequence: Long,
    available: Collection<KWebClipboardPayloadDescriptor>,
) {
    private val availableSnapshot = available.toList()
    public val available: List<KWebClipboardPayloadDescriptor> get() = availableSnapshot.toList()

    init {
        if (sequence <= 0 || availableSnapshot.size > KWEB_CLIPBOARD_MAX_FORMATS ||
            availableSnapshot.map { it.format }.size != availableSnapshot.map { it.format }.toSet().size
        ) {
            invalid(KWebClipboardErrorCode.PAYLOAD_INVALID, "The clipboard read result is invalid.")
        }
    }
}

public class KWebClipboardReadChunk(bytes: ByteArray, public val eof: Boolean) {
    private val byteSnapshot = bytes.copyOf()
    public val bytes: ByteArray get() = byteSnapshot.copyOf()

    init {
        if (bytes.size > KWEB_CLIPBOARD_MAX_READ_CHUNK_BYTES) {
            invalid(KWebClipboardErrorCode.PAYLOAD_TOO_LARGE, "The clipboard read chunk exceeds its bound.")
        }
    }
}

public class KWebClipboardWriteItem(
    public val format: KWebClipboardFormat,
    public val encoding: KWebClipboardPayloadEncoding,
    bytes: ByteArray,
) {
    private val byteSnapshot = bytes.copyOf()
    public val bytes: ByteArray get() = byteSnapshot.copyOf()

    init {
        if (bytes.size > KWEB_CLIPBOARD_MAX_ITEM_BYTES) {
            invalid(KWebClipboardErrorCode.PAYLOAD_TOO_LARGE, "A clipboard item exceeds its 4 MiB bound.")
        }
        if (format == KWebClipboardFormat.TEXT_RTF && encoding != KWebClipboardPayloadEncoding.RTF_BYTES) {
            invalid(KWebClipboardErrorCode.RTF_POLICY, "RTF must use the RTF_BYTES encoding.")
        }
        if (format != KWebClipboardFormat.TEXT_RTF && encoding != KWebClipboardPayloadEncoding.UTF8) {
            invalid(KWebClipboardErrorCode.PAYLOAD_INVALID, "Non-RTF clipboard formats must use UTF8.")
        }
    }

    override fun equals(other: Any?): Boolean =
        other is KWebClipboardWriteItem &&
            format == other.format &&
            encoding == other.encoding &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * format.hashCode() + encoding.hashCode()) + bytes.contentHashCode()
}

public class KWebClipboardWriteRequest(
    public val selection: KWebClipboardSelection,
    items: Collection<KWebClipboardWriteItem>,
) {
    private val itemSnapshot = items.toList()
    public val items: List<KWebClipboardWriteItem> get() = itemSnapshot.toList()

    init {
        if (itemSnapshot.isEmpty() || itemSnapshot.size > KWEB_CLIPBOARD_MAX_FORMATS ||
            itemSnapshot.map { it.format }.size != itemSnapshot.map { it.format }.toSet().size
        ) {
            invalid(KWebClipboardErrorCode.PAYLOAD_INVALID, "A clipboard write must contain one through four unique formats.")
        }
        if (itemSnapshot.none { it.format == KWebClipboardFormat.TEXT_PLAIN }) {
            invalid(KWebClipboardErrorCode.PAYLOAD_INVALID, "A clipboard write must include plain text.")
        }
        val total = itemSnapshot.sumOf { it.bytes.size.toLong() }
        if (total > KWEB_CLIPBOARD_MAX_WRITE_BYTES) {
            invalid(KWebClipboardErrorCode.PAYLOAD_TOO_LARGE, "A clipboard write exceeds its aggregate bound.")
        }
    }
}

public data class KWebClipboardWriteResult(public val sequence: Long) {
    init {
        if (sequence <= 0) invalid(KWebClipboardErrorCode.PAYLOAD_INVALID, "The clipboard sequence is invalid.")
    }
}

public class KWebClipboardChange(
    public val sequence: Long,
    public val selection: KWebClipboardSelection,
    formats: Collection<KWebClipboardFormat>,
    public val ownership: KWebClipboardOwnership,
) {
    private val formatSnapshot = formats.toList()
    public val formats: List<KWebClipboardFormat> get() = formatSnapshot.toList()

    init {
        if (sequence <= 0 || formatSnapshot.size > KWEB_CLIPBOARD_MAX_FORMATS ||
            formatSnapshot.size != formatSnapshot.toSet().size
        ) {
            invalid(KWebClipboardErrorCode.PAYLOAD_INVALID, "The clipboard change event is invalid.")
        }
    }
}

public interface KWebClipboard : KWebNativeService {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<KWebLifecycleState>

    public suspend fun read(request: KWebClipboardReadRequest): KWebClipboardReadResult
    public suspend fun readPayload(
        handle: KWebClipboardPayloadHandle,
        offset: Long,
        length: Int,
    ): KWebClipboardReadChunk
    public suspend fun write(request: KWebClipboardWriteRequest): KWebClipboardWriteResult
    public suspend fun clear(selection: KWebClipboardSelection): Long
    public fun changes(): Flow<KWebClipboardChange>
    public suspend fun closePayload(handle: KWebClipboardPayloadHandle)

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "clipboard",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.APPLICATION,
            operations = setOf(
                operation("read", requiresUserGesture = true),
                operation("read-payload"),
                operation("write", requiresUserGesture = true),
                operation("clear", requiresUserGesture = true),
                operation("changes"),
                operation("close-payload"),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = KWebTarget.supported,
        )

        public val Key: KWebServiceKey<KWebClipboard> = object : KWebServiceKey<KWebClipboard> {
            override val id: String = DESCRIPTOR.id
            override val contract: KWebServiceVersionRange = KWebServiceVersionRange.exact(DESCRIPTOR.version)
        }

        private fun operation(id: String, requiresUserGesture: Boolean = false): KWebServiceOperationDescriptor =
            KWebServiceOperationDescriptor(
                id = id,
                schemaVersion = 1,
                rendererPermission = "native.clipboard.$id",
                requiresUserGesture = requiresUserGesture,
            )
    }
}

public object KWebClipboardErrorCode {
    public const val SELECTION_UNSUPPORTED: String = "clipboard.selection-unsupported"
    public const val FORMAT_UNSUPPORTED: String = "clipboard.format-unsupported"
    public const val PAYLOAD_INVALID: String = "clipboard.payload-invalid"
    public const val PAYLOAD_TOO_LARGE: String = "clipboard.payload-too-large"
    public const val PAYLOAD_NOT_FOUND: String = "clipboard.payload-not-found"
    public const val PAYLOAD_LIMIT: String = "clipboard.payload-limit"
    public const val PAYLOAD_EXPIRED: String = "clipboard.payload-expired"
    public const val READ_UNAVAILABLE: String = "clipboard.read-unavailable"
    public const val WRITE_UNAVAILABLE: String = "clipboard.write-unavailable"
    public const val WRITE_OUTCOME_UNKNOWN: String = "clipboard.write-outcome-unknown"
    public const val OWNERSHIP_LOST: String = "clipboard.ownership-lost"
    public const val NATIVE_UNAVAILABLE: String = "clipboard.native-unavailable"
    public const val PLATFORM_UNAVAILABLE: String = "clipboard.platform-unavailable"
    public const val HTML_POLICY: String = "clipboard.html-policy"
    public const val RTF_POLICY: String = "clipboard.rtf-policy"
    public const val URI_POLICY: String = "clipboard.uri-policy"
    public const val CHANGE_OVERFLOW: String = "clipboard.change-overflow"
}

public const val KWEB_CLIPBOARD_MAX_FORMATS: Int = 4
public const val KWEB_CLIPBOARD_MAX_ITEM_BYTES: Int = 4 * 1024 * 1024
public const val KWEB_CLIPBOARD_MAX_WRITE_BYTES: Int = 8 * 1024 * 1024
public const val KWEB_CLIPBOARD_MAX_READ_CHUNK_BYTES: Int = 256 * 1024
public const val KWEB_CLIPBOARD_CHANGE_CAPACITY: Int = 64

private val HANDLE_PATTERN = Regex("[A-Za-z0-9_-]{43}")

internal fun invalid(code: String, message: String): Nothing =
    throw KWebConfigurationException(code = code, details = emptyMap(), message = message)
