package io.github.kingsword09.kwebshell.service.notifications

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

@JvmInline
public value class KWebNotificationId(public val value: String) {
    init {
        if (!NOTIFICATION_ID.matches(value)) invalid(KWebNotificationErrorCode.ID_INVALID, "The notification id is invalid.")
    }
}

public enum class KWebNotificationIcon { APPLICATION }
public enum class KWebNotificationUrgency { LOW, NORMAL, HIGH }
public enum class KWebNotificationTimeout { SYSTEM, SHORT, LONG, PERSISTENT }
public enum class KWebNotificationActionKind { BUTTON, REPLY }

public data class KWebNotificationAction(
    public val id: String,
    public val title: String,
    public val kind: KWebNotificationActionKind,
    public val replyPlaceholder: String? = null,
) {
    init {
        if (!ACTION_ID.matches(id)) invalid(KWebNotificationErrorCode.ACTION_INVALID, "The notification action id is invalid.")
        requireText(title, KWEB_NOTIFICATION_MAX_ACTION_TITLE_BYTES, KWebNotificationErrorCode.CONTENT_TOO_LARGE)
        when (kind) {
            KWebNotificationActionKind.BUTTON -> if (replyPlaceholder != null) {
                invalid(KWebNotificationErrorCode.ACTION_INVALID, "A button action cannot declare a reply placeholder.")
            }
            KWebNotificationActionKind.REPLY -> {
                val placeholder = replyPlaceholder ?: invalid(
                    KWebNotificationErrorCode.ACTION_INVALID,
                    "A reply action requires a reply placeholder.",
                )
                requireText(placeholder, KWEB_NOTIFICATION_MAX_REPLY_PLACEHOLDER_BYTES, KWebNotificationErrorCode.CONTENT_TOO_LARGE)
            }
        }
    }
}

public data class KWebNotificationRequest(
    public val id: KWebNotificationId,
    public val tag: String? = null,
    public val title: String,
    public val body: String,
    public val icon: KWebNotificationIcon = KWebNotificationIcon.APPLICATION,
    public val urgency: KWebNotificationUrgency = KWebNotificationUrgency.NORMAL,
    public val timeout: KWebNotificationTimeout = KWebNotificationTimeout.SYSTEM,
    public val actions: List<KWebNotificationAction> = emptyList(),
) {
    init {
        tag?.let { requireIdentity(it, "tag") }
        requireText(title, KWEB_NOTIFICATION_MAX_TITLE_BYTES, KWebNotificationErrorCode.CONTENT_TOO_LARGE)
        requireText(body, KWEB_NOTIFICATION_MAX_BODY_BYTES, KWebNotificationErrorCode.CONTENT_TOO_LARGE)
        if (actions.size > KWEB_NOTIFICATION_MAX_ACTIONS || actions.map { it.id }.toSet().size != actions.size) {
            invalid(KWebNotificationErrorCode.ACTION_INVALID, "Notification actions must be unique and within the bound.")
        }
        if (actions.count { it.kind == KWebNotificationActionKind.REPLY } > 1) {
            invalid(KWebNotificationErrorCode.ACTION_INVALID, "A notification can contain at most one reply action.")
        }
    }
}

public enum class KWebNotificationPermissionStatus {
    NOT_DETERMINED,
    GRANTED,
    DENIED,
    NOT_APPLICABLE,
    UNAVAILABLE,
}

public data class KWebNotificationPermission(
    public val status: KWebNotificationPermissionStatus,
    public val provider: String,
) {
    init {
        if (provider.isBlank() || provider.length > 128) {
            invalid(KWebNotificationErrorCode.PROVIDER_INVALID, "The notification provider identity is invalid.")
        }
    }
}

public data class KWebNotificationCapabilities(
    public val actions: Boolean,
    public val replies: Boolean,
    public val replacement: Boolean,
    public val timeout: Boolean,
    public val activation: Boolean,
)

public enum class KWebNotificationShowOutcome { SHOWN, REPLACED }

public data class KWebNotificationShowResult(
    public val id: KWebNotificationId,
    public val outcome: KWebNotificationShowOutcome,
    public val replacedId: KWebNotificationId?,
    public val sequence: ULong,
) {
    init {
        if (sequence == 0uL) invalid(KWebNotificationErrorCode.EVENT_INVALID, "The notification event sequence is invalid.")
    }
}

public data class KWebNotificationCloseResult(
    public val id: KWebNotificationId,
    public val sequence: ULong,
) {
    init {
        if (sequence == 0uL) invalid(KWebNotificationErrorCode.EVENT_INVALID, "The notification event sequence is invalid.")
    }
}

public enum class KWebNotificationCloseReason {
    PROGRAMMATIC,
    REPLACED,
    EXPIRED,
    USER_DISMISSED,
    OWNER_CLOSED,
    NATIVE,
}

public sealed interface KWebNotificationEvent {
    public val sequence: ULong
    public val id: KWebNotificationId

    public data class Shown(
        override val sequence: ULong,
        override val id: KWebNotificationId,
        public val tag: String?,
        public val replacedId: KWebNotificationId?,
    ) : KWebNotificationEvent {
        init { requireSequence(sequence) }
    }

    public data class Action(
        override val sequence: ULong,
        override val id: KWebNotificationId,
        public val actionId: String,
        public val reply: String?,
    ) : KWebNotificationEvent {
        init {
            requireSequence(sequence)
            if (!ACTION_ID.matches(actionId)) invalid(KWebNotificationErrorCode.ACTION_INVALID, "The notification action id is invalid.")
            reply?.let { requireText(it, KWEB_NOTIFICATION_MAX_REPLY_BYTES, KWebNotificationErrorCode.CONTENT_TOO_LARGE) }
        }
    }

    public data class Closed(
        override val sequence: ULong,
        override val id: KWebNotificationId,
        public val reason: KWebNotificationCloseReason,
    ) : KWebNotificationEvent {
        init { requireSequence(sequence) }
    }

    public data class Failed(
        override val sequence: ULong,
        override val id: KWebNotificationId,
        public val code: String,
    ) : KWebNotificationEvent {
        init {
            requireSequence(sequence)
            if (code.isBlank()) invalid(KWebNotificationErrorCode.EVENT_INVALID, "The notification failure code is blank.")
        }
    }
}

public data class KWebNotificationActivation(
    public val applicationId: String,
    public val notificationId: KWebNotificationId,
    public val tag: String?,
    public val actionId: String?,
    public val reply: String?,
) {
    init {
        if (applicationId.isBlank() || applicationId.length > 127 || applicationId.any { it.code < 0x21 || it.code > 0x7e }) {
            invalid(KWebNotificationErrorCode.ACTIVATION_INVALID, "The notification application identity is invalid.")
        }
        tag?.let { requireIdentity(it, "tag") }
        actionId?.let {
            if (!ACTION_ID.matches(it)) invalid(KWebNotificationErrorCode.ACTIVATION_INVALID, "The notification action id is invalid.")
        }
        reply?.let { requireText(it, KWEB_NOTIFICATION_MAX_REPLY_BYTES, KWebNotificationErrorCode.CONTENT_TOO_LARGE) }
    }

    public fun toProtocolUri(): String {
        val encoded = buildList {
            add("app=${encode(applicationId)}")
            add("id=${encode(notificationId.value)}")
            tag?.let { add("tag=${encode(it)}") }
            actionId?.let { add("action=${encode(it)}") }
            reply?.let { add("reply=${encode(it)}") }
        }
        return "kweb://notification?${encoded.joinToString("&")}"
    }
}

public fun interface KWebNotificationActivationRouter {
    public suspend fun route(activation: KWebNotificationActivation)
}

public interface KWebNotifications : KWebNativeService {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<KWebLifecycleState>
    public val events: Flow<KWebNotificationEvent>

    public suspend fun permission(): KWebNotificationPermission
    public suspend fun requestPermission(): KWebNotificationPermission
    public suspend fun capabilities(): KWebNotificationCapabilities
    public suspend fun show(request: KWebNotificationRequest): KWebNotificationShowResult
    public suspend fun close(id: KWebNotificationId): KWebNotificationCloseResult

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "notifications",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.APPLICATION,
            operations = setOf(
                operation("permission"),
                operation("request-permission", gesture = true, consent = true),
                operation("capabilities"),
                operation("show", consent = true),
                operation("close", consent = true),
                operation("events", rendererPermission = null),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = KWebTarget.supported,
        )

        public val Key: KWebServiceKey<KWebNotifications> = object : KWebServiceKey<KWebNotifications> {
            override val id: String = DESCRIPTOR.id
            override val contract: KWebServiceVersionRange = KWebServiceVersionRange.exact(DESCRIPTOR.version)
        }

        private fun operation(
            id: String,
            gesture: Boolean = false,
            consent: Boolean = false,
            rendererPermission: String? = "native.notifications.$id",
        ): KWebServiceOperationDescriptor = KWebServiceOperationDescriptor(
            id = id,
            schemaVersion = 1,
            rendererPermission = rendererPermission,
            requiresUserGesture = gesture,
            requiresOsConsent = consent,
        )
    }
}

public object KWebNotificationErrorCode {
    public const val ID_INVALID: String = "notifications.id-invalid"
    public const val CONTENT_TOO_LARGE: String = "notifications.content-too-large"
    public const val ACTION_INVALID: String = "notifications.action-invalid"
    public const val ICON_UNSUPPORTED: String = "notifications.icon-unsupported"
    public const val ACTIONS_UNSUPPORTED: String = "notifications.actions-unsupported"
    public const val REPLY_UNSUPPORTED: String = "notifications.reply-unsupported"
    public const val TIMEOUT_UNSUPPORTED: String = "notifications.timeout-unsupported"
    public const val PERMISSION_UNDETERMINED: String = "notifications.permission-undetermined"
    public const val PERMISSION_DENIED: String = "notifications.permission-denied"
    public const val PLATFORM_UNAVAILABLE: String = "notifications.platform-unavailable"
    public const val NATIVE_FAILED: String = "notifications.native-failed"
    public const val NOT_FOUND: String = "notifications.not-found"
    public const val RATE_LIMITED: String = "notifications.rate-limited"
    public const val REPLACEMENT_CONFLICT: String = "notifications.replacement-conflict"
    public const val ACTIVATION_INVALID: String = "notifications.activation-invalid"
    public const val ACTIVATION_ROUTE_FAILED: String = "notifications.activation-route-failed"
    public const val OUTCOME_UNKNOWN: String = "notifications.outcome-unknown"
    public const val EVENT_INVALID: String = "notifications.event-invalid"
    public const val PROVIDER_INVALID: String = "notifications.provider-invalid"
}

public const val KWEB_NOTIFICATION_MAX_TITLE_BYTES: Int = 256
public const val KWEB_NOTIFICATION_MAX_BODY_BYTES: Int = 4096
public const val KWEB_NOTIFICATION_MAX_ACTIONS: Int = 3
public const val KWEB_NOTIFICATION_MAX_ACTION_TITLE_BYTES: Int = 128
public const val KWEB_NOTIFICATION_MAX_REPLY_PLACEHOLDER_BYTES: Int = 128
public const val KWEB_NOTIFICATION_MAX_REPLY_BYTES: Int = 1024
public const val KWEB_NOTIFICATION_MAX_ACTIVE: Int = 32
public const val KWEB_NOTIFICATION_MAX_SHOWS_PER_MINUTE: Int = 16
public const val KWEB_NOTIFICATION_EVENT_CAPACITY: Int = 64

private val NOTIFICATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val ACTION_ID = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
private val IDENTITY = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

private fun requireIdentity(value: String, field: String) {
    if (!IDENTITY.matches(value)) invalid(KWebNotificationErrorCode.ID_INVALID, "The notification $field is invalid.")
}

private fun requireText(value: String, limit: Int, code: String) {
    if (value.isEmpty() || value.toByteArray().size > limit || value.any { it.code < 0x20 || it.code == 0x7f }) {
        invalid(code, "The notification text is empty, oversized, or contains a control character.")
    }
}

private fun requireSequence(sequence: ULong) {
    if (sequence == 0uL) invalid(KWebNotificationErrorCode.EVENT_INVALID, "The notification event sequence is invalid.")
}

private fun encode(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        if ((unsigned in 0x41..0x5a) || (unsigned in 0x61..0x7a) || (unsigned in 0x30..0x39) || unsigned in setOf(0x2d, 0x2e, 0x5f, 0x7e)) {
            append(unsigned.toChar())
        } else {
            append('%')
            append("0123456789ABCDEF"[unsigned ushr 4])
            append("0123456789ABCDEF"[unsigned and 0x0f])
        }
    }
}

private fun invalid(code: String, message: String): Nothing = throw KWebConfigurationException(
    code = code,
    details = emptyMap(),
    message = message,
)
