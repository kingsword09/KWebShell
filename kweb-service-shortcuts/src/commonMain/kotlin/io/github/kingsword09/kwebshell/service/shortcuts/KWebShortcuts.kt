/**
 * The RFC 0021 typed global-shortcut contract shared by the Win32, macOS Carbon,
 * and Linux X11 providers. Values are immutable and bounded before a provider sees
 * them; Kotlin owns registration identity, capability reporting, atomic bulk
 * replacement, and ordered invocation, loss, removal, and failure events.
 */
package io.github.kingsword09.kwebshell.service.shortcuts

import io.github.kingsword09.kwebshell.core.KWebArchitecture
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebOperatingSystem
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

/** One stable application-owned registration identifier. */
public class KWebShortcutId(public val value: String) {
    init {
        if (!SHORTCUT_ID_PATTERN.matches(value)) {
            invalid(KWebShortcutErrorCode.ID_INVALID, "A shortcut identifier must match the published identifier form.")
        }
    }

    override fun equals(other: Any?): Boolean = other is KWebShortcutId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** One application-declared command a shortcut invokes. */
public class KWebShortcutCommandId(public val value: String) {
    init {
        if (!SHORTCUT_ID_PATTERN.matches(value)) {
            invalid(KWebShortcutErrorCode.ID_INVALID, "A shortcut command identifier must match the published identifier form.")
        }
    }

    override fun equals(other: Any?): Boolean = other is KWebShortcutCommandId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** One modifier a provider can register. */
public enum class KWebShortcutModifier { CONTROL, ALT, SHIFT, META }

/** A portable named key. Platform-only keys stay capability facts, not members. */
public enum class KWebShortcutNamedKey {
    A, B, C, D, E, F, G, H, I, J, K, L, M,
    N, O, P, Q, R, S, T, U, V, W, X, Y, Z,
    NUM_0, NUM_1, NUM_2, NUM_3, NUM_4, NUM_5, NUM_6, NUM_7, NUM_8, NUM_9,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    F13, F14, F15, F16, F17, F18, F19, F20, F21, F22, F23, F24,
    SPACE, TAB, ENTER, ESCAPE, BACKSPACE, DELETE, INSERT, HOME, END, PAGE_UP, PAGE_DOWN,
    ARROW_LEFT, ARROW_RIGHT, ARROW_UP, ARROW_DOWN,
    MINUS, EQUALS, BRACKET_LEFT, BRACKET_RIGHT, BACKSLASH, SEMICOLON, QUOTE, COMMA, PERIOD, SLASH, BACKQUOTE,
}

/** One media key a provider can register where the session allows it. */
public enum class KWebShortcutMediaKey {
    PLAY_PAUSE, NEXT_TRACK, PREVIOUS_TRACK, STOP, VOLUME_UP, VOLUME_DOWN, MUTE
}

/** One bounded key: a portable named key or a media key. */
public sealed interface KWebShortcutKey {
    public data class Named(public val key: KWebShortcutNamedKey) : KWebShortcutKey

    public data class Media(public val key: KWebShortcutMediaKey) : KWebShortcutKey
}

/**
 * One bounded accelerator. A named key requires at least one modifier so the
 * service cannot silently claim a bare key the session reserves; a media key may
 * carry none.
 */
public class KWebShortcutAccelerator(
    modifiers: Set<KWebShortcutModifier>,
    public val key: KWebShortcutKey,
) {
    private val modifierSnapshot = modifiers.toList().sortedBy { it.ordinal }

    public val modifiers: Set<KWebShortcutModifier> get() = modifierSnapshot.toSet()

    init {
        if (modifiers.any { it !in KWebShortcutModifier.entries }) {
            invalid(KWebShortcutErrorCode.ACCELERATOR_INVALID, "An accelerator declares an unknown modifier.")
        }
        if (key is KWebShortcutKey.Named && modifierSnapshot.isEmpty()) {
            invalid(KWebShortcutErrorCode.ACCELERATOR_INVALID, "A named key requires at least one modifier.")
        }
    }

    override fun equals(other: Any?): Boolean =
        other is KWebShortcutAccelerator && modifierSnapshot == other.modifierSnapshot && key == other.key

    override fun hashCode(): Int = 31 * modifierSnapshot.hashCode() + key.hashCode()

    override fun toString(): String =
        buildString {
            modifierSnapshot.forEach { append(it.name).append('+') }
            when (val current = key) {
                is KWebShortcutKey.Named -> append(current.key.name)
                is KWebShortcutKey.Media -> append("MEDIA_").append(current.key.name)
            }
        }
}

/** One bounded registration request. */
public data class KWebShortcutRegistration(
    public val id: KWebShortcutId,
    public val accelerator: KWebShortcutAccelerator,
    public val commandId: KWebShortcutCommandId,
)

public data class KWebShortcutRegistrationResult(
    public val id: KWebShortcutId,
    public val appliedAtSequence: ULong,
)

public data class KWebShortcutReplacementResult(
    public val applied: List<KWebShortcutId>,
    public val removed: List<KWebShortcutId>,
    public val appliedAtSequence: ULong,
)

public data class KWebShortcutUnregistration(
    public val id: KWebShortcutId,
    public val wasRegistered: Boolean,
)

/** Declared provider capabilities for the current target and session. */
public enum class KWebShortcutCapability {
    GLOBAL_REGISTRATION,
    MEDIA_KEYS,
    ATOMIC_REPLACEMENT,
    INVOCATION_REPORTING,
    SESSION_LOCK_RELEASE,
    MEDIA_KEY_PERMISSION,
}

public data class KWebShortcutCapabilities(
    public val providerId: String,
    public val flags: Set<KWebShortcutCapability>,
    public val modifiers: Set<KWebShortcutModifier>,
    public val mediaKeys: Set<KWebShortcutMediaKey>,
    public val maxRegistrations: Int,
) {
    public fun supports(capability: KWebShortcutCapability): Boolean = capability in flags

    public fun supports(modifier: KWebShortcutModifier): Boolean = modifier in modifiers

    public fun supports(mediaKey: KWebShortcutMediaKey): Boolean = mediaKey in mediaKeys
}

/** Whether an invocation came from a native activation or a renderer dispatch. */
public enum class KWebShortcutInvocationSource { PLATFORM, RENDERER }

/** Why an active registration stopped being live without an explicit removal. */
public enum class KWebShortcutLossReason { CONFLICT, SESSION_LOCK, PERMISSION_REVOKED, PLATFORM_REMOVED }

/** Why the service removed a registration. */
public enum class KWebShortcutRemovalReason { UNREGISTERED, REPLACED, OWNER_CLOSED, PLATFORM_REMOVED }

/** One ordered service event. Sequences start at 1 and never repeat. */
public sealed interface KWebShortcutEvent {
    public val sequence: ULong

    public data class Invoked(
        public val registrationId: KWebShortcutId,
        public val commandId: KWebShortcutCommandId,
        public val source: KWebShortcutInvocationSource,
        override val sequence: ULong,
    ) : KWebShortcutEvent

    public data class Lost(
        public val registrationId: KWebShortcutId,
        public val commandId: KWebShortcutCommandId,
        public val reason: KWebShortcutLossReason,
        override val sequence: ULong,
    ) : KWebShortcutEvent

    public data class Removed(
        public val registrationId: KWebShortcutId,
        public val reason: KWebShortcutRemovalReason,
        override val sequence: ULong,
    ) : KWebShortcutEvent

    public data class Failed(
        public val registrationId: KWebShortcutId?,
        public val code: String,
        override val sequence: ULong,
    ) : KWebShortcutEvent
}

public interface KWebShortcuts : KWebNativeService {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<KWebLifecycleState>

    /** Ordered, replayable service events. */
    public val events: Flow<KWebShortcutEvent>

    public suspend fun capabilities(): KWebShortcutCapabilities

    public suspend fun register(registration: KWebShortcutRegistration): KWebShortcutRegistrationResult

    /** Atomically replaces the whole live set; a failure leaves the previous set live. */
    public suspend fun replaceAll(registrations: List<KWebShortcutRegistration>): KWebShortcutReplacementResult

    /** Idempotent per identifier; an unknown identifier returns `wasRegistered = false`. */
    public suspend fun unregister(id: KWebShortcutId): KWebShortcutUnregistration

    public suspend fun isRegistered(id: KWebShortcutId): Boolean

    public suspend fun registrations(): List<KWebShortcutRegistration>

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "shortcuts",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.APPLICATION,
            operations = setOf(
                hostOperation("capabilities"),
                hostOperation("register"),
                hostOperation("replace-all"),
                hostOperation("unregister"),
                hostOperation("is-registered"),
                hostOperation("registrations"),
                hostOperation("events"),
                rendererOperation("read", permission = RENDERER_READ_PERMISSION, requiresUserGesture = false),
                rendererOperation("dispatch", permission = RENDERER_DISPATCH_PERMISSION, requiresUserGesture = true),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = setOf(
                KWebTarget(KWebOperatingSystem.WINDOWS, KWebArchitecture.X64),
                KWebTarget(KWebOperatingSystem.MACOS, KWebArchitecture.ARM64),
                KWebTarget(KWebOperatingSystem.LINUX, KWebArchitecture.X64),
            ),
        )

        public val Key: KWebServiceKey<KWebShortcuts> = object : KWebServiceKey<KWebShortcuts> {
            override val id: String = DESCRIPTOR.id
            override val contract: KWebServiceVersionRange = KWebServiceVersionRange.exact(DESCRIPTOR.version)
        }

        /** The renderer permission that gates the summary read. */
        public const val RENDERER_READ_PERMISSION: String = "shortcuts.read"

        /** The renderer permission that gates a declared-command dispatch. */
        public const val RENDERER_DISPATCH_PERMISSION: String = "shortcuts.dispatch"

        private fun hostOperation(id: String): KWebServiceOperationDescriptor = KWebServiceOperationDescriptor(
            id = id,
            schemaVersion = 1,
            rendererPermission = null,
            requiresUserGesture = false,
        )

        private fun rendererOperation(
            id: String,
            permission: String,
            requiresUserGesture: Boolean,
        ): KWebServiceOperationDescriptor = KWebServiceOperationDescriptor(
            id = id,
            schemaVersion = 1,
            rendererPermission = permission,
            requiresUserGesture = requiresUserGesture,
        )
    }
}

public object KWebShortcutErrorCode {
    public const val ID_INVALID: String = "shortcuts.id-invalid"
    public const val ACCELERATOR_INVALID: String = "shortcuts.accelerator-invalid"
    public const val ACCELERATOR_UNSUPPORTED: String = "shortcuts.accelerator-unsupported"
    public const val MODIFIER_UNSUPPORTED: String = "shortcuts.modifier-unsupported"
    public const val MEDIA_PERMISSION_DENIED: String = "shortcuts.media-permission-denied"
    public const val CONFLICT: String = "shortcuts.conflict"
    public const val RESERVED: String = "shortcuts.reserved"
    public const val DUPLICATE_ID: String = "shortcuts.duplicate-id"
    public const val LIMIT_EXCEEDED: String = "shortcuts.limit-exceeded"
    public const val REGISTRATION_UNKNOWN: String = "shortcuts.registration-unknown"
    public const val COMMAND_UNDECLARED: String = "shortcuts.command-undeclared"
    public const val GESTURE_REQUIRED: String = "shortcuts.gesture-required"
    public const val CONSENT_DENIED: String = "shortcuts.consent-denied"
    public const val PLATFORM_UNAVAILABLE: String = "shortcuts.platform-unavailable"
    public const val NATIVE_FAILED: String = "shortcuts.native-failed"
    public const val OWNER_CLOSED: String = "shortcuts.owner-closed"
}

public const val KWEB_SHORTCUTS_MAX_REGISTRATIONS: Int = 32
public const val KWEB_SHORTCUTS_EVENT_REPLAY: Int = 64

internal val SHORTCUT_ID_PATTERN = Regex("[A-Za-z][A-Za-z0-9._-]{0,127}")

internal fun invalid(code: String, message: String): Nothing = throw KWebConfigurationException(
    code = code,
    details = emptyMap(),
    message = message,
)
