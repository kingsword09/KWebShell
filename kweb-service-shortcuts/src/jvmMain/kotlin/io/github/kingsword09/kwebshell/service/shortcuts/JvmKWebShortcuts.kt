package io.github.kingsword09.kwebshell.service.shortcuts

import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.service.shortcuts.internal.ShortcutsFfm
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import io.github.kingsword09.kwebshell.services.KWebServiceException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * The JVM shortcut session. It requires the packaged provider library explicitly,
 * validates every provider value through the shared contract, and owns ordering,
 * capability caching, and the sticky close failure.
 */
public class JvmKWebShortcutsSession internal constructor(
    private val native: ShortcutsFfm,
    private val scope: CoroutineScope,
) : KWebShortcuts {

    override val descriptor: KWebServiceDescriptor = KWebShortcuts.DESCRIPTOR

    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    private val mutableEvents = MutableSharedFlow<KWebShortcutEvent>(
        replay = KWEB_SHORTCUTS_EVENT_REPLAY,
        extraBufferCapacity = KWEB_SHORTCUTS_EVENT_REPLAY,
    )
    override val events: SharedFlow<KWebShortcutEvent> = mutableEvents.asSharedFlow()

    private val poller: Job = scope.launch(Dispatchers.IO) { pollEvents() }
    private var capabilitiesCache: KWebShortcutCapabilities? = null
    private var closeFailure: KWebException? = null

    override suspend fun capabilities(): KWebShortcutCapabilities {
        requireOpen()
        capabilitiesCache?.let { return it }
        val result = translate("capabilities") { native.capabilities() }
        val capabilities = KWebShortcutCapabilities(
            providerId = result.providerId,
            flags = KWebShortcutCapability.entries
                .filter { (result.capabilityBits and (1 shl it.ordinal)) != 0 }
                .toSet(),
            modifiers = KWebShortcutModifier.entries
                .filter { (result.modifierBits and modifierBit(it)) != 0 }
                .toSet(),
            mediaKeys = KWebShortcutMediaKey.entries
                .filter { (result.mediaKeyBits and mediaBit(it)) != 0 }
                .toSet(),
            maxRegistrations = result.maxRegistrations,
        )
        capabilitiesCache = capabilities
        return capabilities
    }

    override suspend fun register(registration: KWebShortcutRegistration): KWebShortcutRegistrationResult {
        requireOpen()
        val result = translate("register") { native.register(requestOf(registration)) }
        return KWebShortcutRegistrationResult(KWebShortcutId(result.registrationId), result.sequence.toULong())
    }

    override suspend fun replaceAll(registrations: List<KWebShortcutRegistration>): KWebShortcutReplacementResult {
        requireOpen()
        if (registrations.size > KWEB_SHORTCUTS_MAX_REGISTRATIONS) {
            throw failure(KWebShortcutErrorCode.LIMIT_EXCEEDED, "replace-all", emptyMap())
        }
        val previous = translate("registrations") { native.registrations() }.map { it.registrationId }.toSet()
        val result = translate("replace-all") { native.replaceAll(registrations.map(::requestOf)) }
        val applied = registrations.map { it.id }
        val appliedIds = applied.map { it.value }.toSet()
        return KWebShortcutReplacementResult(
            applied = applied,
            removed = previous.filterNot { it in appliedIds }.map { KWebShortcutId(it) },
            appliedAtSequence = result.sequence.toULong(),
        )
    }

    override suspend fun unregister(id: KWebShortcutId): KWebShortcutUnregistration {
        requireOpen()
        val wasRegistered = translate("unregister") { native.unregister(id.value) }
        return KWebShortcutUnregistration(id, wasRegistered)
    }

    override suspend fun isRegistered(id: KWebShortcutId): Boolean {
        requireOpen()
        return translate("is-registered") { native.isRegistered(id.value) }
    }

    override suspend fun registrations(): List<KWebShortcutRegistration> {
        requireOpen()
        return translate("registrations") { native.registrations() }.map(::registrationOf)
    }

    override suspend fun dispatch(commandId: KWebShortcutCommandId): KWebShortcutId {
        requireOpen()
        val result = translate("dispatch") { native.dispatch(commandId.value) }
        return KWebShortcutId(result.registrationId)
    }

    override fun close() {
        synchronized(this) {
            if (mutableLifecycle.value == KWebLifecycleState.CLOSED) return
            mutableLifecycle.value = KWebLifecycleState.CLOSING
        }
        poller.cancel()
        var failure: KWebException? = null
        try {
            translate("close") { native.close() }
        } catch (error: KWebException) {
            failure = error
        } catch (error: Throwable) {
            failure = KWebServiceException(
                code = KWebServiceErrorCode.NATIVE_FAILED,
                details = mapOf("service" to descriptor.id, "operation" to "close"),
                message = "The shortcut provider failed while closing.",
                cause = error,
            )
        }
        scope.cancel()
        synchronized(this) {
            closeFailure = failure
            mutableLifecycle.value = if (failure == null) KWebLifecycleState.CLOSED else KWebLifecycleState.FAILED
        }
        closeFailure?.let { throw it }
    }

    private suspend fun pollEvents() {
        while (scope.isActive) {
            var empty = false
            while (!empty) {
                val event = try {
                    native.pollEvent()
                } catch (error: Throwable) {
                    return
                }
                if (event == null) {
                    empty = true
                } else {
                    mutableEvents.emit(decodeEvent(event))
                }
            }
            delay(5)
        }
    }

    private fun decodeEvent(event: ShortcutsFfm.Event): KWebShortcutEvent {
        val sequence = event.sequence.toULong()
        return when (event.kind) {
            ShortcutsFfm.EVENT_INVOKED -> KWebShortcutEvent.Invoked(
                registrationId = KWebShortcutId(event.registrationId),
                commandId = KWebShortcutCommandId(event.commandId),
                source = when (event.source) {
                    ShortcutsFfm.SOURCE_RENDERER -> KWebShortcutInvocationSource.RENDERER
                    else -> KWebShortcutInvocationSource.PLATFORM
                },
                sequence = sequence,
            )
            ShortcutsFfm.EVENT_LOST -> KWebShortcutEvent.Lost(
                registrationId = KWebShortcutId(event.registrationId),
                commandId = KWebShortcutCommandId(event.commandId),
                reason = when (event.reason) {
                    ShortcutsFfm.LOSS_SESSION_LOCK -> KWebShortcutLossReason.SESSION_LOCK
                    ShortcutsFfm.LOSS_PERMISSION_REVOKED -> KWebShortcutLossReason.PERMISSION_REVOKED
                    ShortcutsFfm.LOSS_PLATFORM_REMOVED -> KWebShortcutLossReason.PLATFORM_REMOVED
                    else -> KWebShortcutLossReason.CONFLICT
                },
                sequence = sequence,
            )
            ShortcutsFfm.EVENT_REMOVED -> KWebShortcutEvent.Removed(
                registrationId = KWebShortcutId(event.registrationId),
                reason = when (event.reason) {
                    ShortcutsFfm.REMOVAL_REPLACED -> KWebShortcutRemovalReason.REPLACED
                    ShortcutsFfm.REMOVAL_OWNER_CLOSED -> KWebShortcutRemovalReason.OWNER_CLOSED
                    ShortcutsFfm.REMOVAL_PLATFORM_REMOVED -> KWebShortcutRemovalReason.PLATFORM_REMOVED
                    else -> KWebShortcutRemovalReason.UNREGISTERED
                },
                sequence = sequence,
            )
            else -> KWebShortcutEvent.Failed(
                registrationId = event.registrationId.takeIf { it.isNotEmpty() }?.let { KWebShortcutId(it) },
                code = event.code.ifEmpty { KWebShortcutErrorCode.NATIVE_FAILED },
                sequence = sequence,
            )
        }
    }

    private fun requestOf(registration: KWebShortcutRegistration): ShortcutsFfm.Request {
        val (kind, code) = codeOf(registration.accelerator.key)
        return ShortcutsFfm.Request(
            registration.id.value,
            registration.commandId.value,
            registration.accelerator.modifiers.fold(0) { bits, modifier -> bits or modifierBit(modifier) },
            kind,
            code,
        )
    }

    private fun registrationOf(flat: ShortcutsFfm.FlatRegistration): KWebShortcutRegistration =
        KWebShortcutRegistration(
            id = KWebShortcutId(flat.registrationId),
            accelerator = KWebShortcutAccelerator(
                modifiers = KWebShortcutModifier.entries.filter { (flat.modifierBits and modifierBit(it)) != 0 }.toSet(),
                key = keyOf(flat.keyKind, flat.keyCode),
            ),
            commandId = KWebShortcutCommandId(flat.commandId),
        )

    private fun keyOf(kind: Int, code: Int): KWebShortcutKey = when (kind) {
        ShortcutsFfm.KEY_MEDIA -> KWebShortcutKey.Media(
            KWebShortcutMediaKey.entries.getOrNull(code - 1)
                ?: throw failure(KWebShortcutErrorCode.ACCELERATOR_UNSUPPORTED, "registrations", mapOf("key" to code.toString())),
        )
        else -> KWebShortcutKey.Named(
            KWebShortcutNamedKey.entries.firstOrNull { namedCode(it) == code }
                ?: throw failure(KWebShortcutErrorCode.ACCELERATOR_UNSUPPORTED, "registrations", mapOf("key" to code.toString())),
        )
    }

    private fun codeOf(key: KWebShortcutKey): Pair<Int, Int> = when (key) {
        is KWebShortcutKey.Named -> ShortcutsFfm.KEY_NAMED to namedCode(key.key)
        is KWebShortcutKey.Media -> ShortcutsFfm.KEY_MEDIA to mediaBit(key.key).let { Integer.numberOfTrailingZeros(it) + 1 }
    }

    private fun requireOpen() {
        val state = mutableLifecycle.value
        if (state != KWebLifecycleState.OPEN) {
            throw failure(KWebShortcutErrorCode.OWNER_CLOSED, "require-open", mapOf("state" to state.name))
        }
    }

    private fun failure(code: String, operation: String, details: Map<String, String>): KWebServiceException =
        KWebServiceException(
            code = code,
            details = details + mapOf("service" to descriptor.id, "operation" to operation),
            message = "The shortcut operation '$operation' failed with $code.",
        )

    /** Maps one provider status to its published typed service error. */
    private fun <T> translate(operation: String, block: () -> T): T = try {
        block()
    } catch (error: ShortcutsFfm.ShortcutsNativeException) {
        throw failure(
            when (error.status) {
                ShortcutsFfm.STATUS_PLATFORM_UNAVAILABLE -> KWebShortcutErrorCode.PLATFORM_UNAVAILABLE
                ShortcutsFfm.STATUS_ACCELERATOR_UNSUPPORTED -> KWebShortcutErrorCode.ACCELERATOR_UNSUPPORTED
                ShortcutsFfm.STATUS_MODIFIER_UNSUPPORTED -> KWebShortcutErrorCode.MODIFIER_UNSUPPORTED
                ShortcutsFfm.STATUS_MEDIA_PERMISSION_DENIED -> KWebShortcutErrorCode.MEDIA_PERMISSION_DENIED
                ShortcutsFfm.STATUS_CONFLICT -> KWebShortcutErrorCode.CONFLICT
                ShortcutsFfm.STATUS_RESERVED -> KWebShortcutErrorCode.RESERVED
                ShortcutsFfm.STATUS_DUPLICATE_ID -> KWebShortcutErrorCode.DUPLICATE_ID
                ShortcutsFfm.STATUS_LIMIT_EXCEEDED -> KWebShortcutErrorCode.LIMIT_EXCEEDED
                ShortcutsFfm.STATUS_REGISTRATION_UNKNOWN -> KWebShortcutErrorCode.REGISTRATION_UNKNOWN
                ShortcutsFfm.STATUS_COMMAND_UNDECLARED -> KWebShortcutErrorCode.COMMAND_UNDECLARED
                ShortcutsFfm.STATUS_OWNER_CLOSED -> KWebShortcutErrorCode.OWNER_CLOSED
                else -> KWebShortcutErrorCode.NATIVE_FAILED
            },
            operation,
            mapOf("native-status" to error.status.toString()),
        )
    }
}

public object JvmKWebShortcuts {
    /**
     * Opens the packaged shortcut provider. There is no fallback provider: a missing
     * native library or an absent session facility fails immediately.
     */
    public fun open(
        applicationId: String,
        nativeLibrary: Path,
        packageIdentity: String = applicationId,
    ): JvmKWebShortcutsSession = JvmKWebShortcutsSession(
        ShortcutsFfm.open(nativeLibrary, applicationId, packageIdentity),
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
}

internal fun modifierBit(modifier: KWebShortcutModifier): Int = when (modifier) {
    KWebShortcutModifier.CONTROL -> ShortcutsFfm.MOD_CONTROL
    KWebShortcutModifier.ALT -> ShortcutsFfm.MOD_ALT
    KWebShortcutModifier.SHIFT -> ShortcutsFfm.MOD_SHIFT
    KWebShortcutModifier.META -> ShortcutsFfm.MOD_META
}

internal fun mediaBit(key: KWebShortcutMediaKey): Int = 1 shl key.ordinal

internal fun namedCode(key: KWebShortcutNamedKey): Int = when (key.ordinal) {
    in 0..25 -> 'A'.code + key.ordinal
    in 26..35 -> '0'.code + (key.ordinal - 26)
    in 36..59 -> 0x100 + (key.ordinal - 36)
    else -> 0x200 + (key.ordinal - 60)
}
