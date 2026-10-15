package io.github.kingsword09.kwebshell.service.power

import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.service.power.internal.PowerFfm
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
 * The JVM power session. It requires the packaged provider library explicitly and
 * owns lease identity, the declared lease profiles, capability caching, and the
 * sticky close failure.
 */
public class JvmKWebPowerSession internal constructor(
    private val native: PowerFfm,
    private val profiles: Map<KWebPowerLeaseProfileId, KWebPowerLeaseProfile>,
    private val scope: CoroutineScope,
) : KWebPower {

    override val descriptor: KWebServiceDescriptor = KWebPower.DESCRIPTOR

    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    private val mutableEvents = MutableSharedFlow<KWebPowerEvent>(
        replay = KWEB_POWER_EVENT_REPLAY,
        extraBufferCapacity = KWEB_POWER_EVENT_REPLAY,
    )
    override val events: SharedFlow<KWebPowerEvent> = mutableEvents.asSharedFlow()

    private val poller: Job = scope.launch(Dispatchers.IO) { pollEvents() }
    private var capabilitiesCache: KWebPowerCapabilities? = null
    private var closeFailure: KWebException? = null

    override suspend fun capabilities(): KWebPowerCapabilities {
        requireOpen()
        capabilitiesCache?.let { return it }
        val result = translate("capabilities") { native.capabilities() }
        val capabilities = KWebPowerCapabilities(
            providerId = result.providerId,
            flags = KWebPowerCapability.entries
                .filter { (result.capabilityBits and (1 shl it.ordinal)) != 0 }
                .toSet(),
            maxLeases = result.maxLeases,
            maxLeasesPerType = result.maxLeasesPerType,
            minLeaseSeconds = result.minLeaseSeconds,
            maxLeaseSeconds = result.maxLeaseSeconds,
        )
        capabilitiesCache = capabilities
        return capabilities
    }

    override suspend fun state(): KWebPowerState {
        requireOpen()
        val result = translate("state") { native.state() }
        return KWebPowerState(
            onBattery = if ((result.factBits and PowerFfm.FACT_ON_BATTERY) != 0) result.onBattery else null,
            batteryPercent = if ((result.factBits and PowerFfm.FACT_BATTERY_PERCENT) != 0) {
                result.batteryPercent
            } else {
                null
            },
            thermal = if ((result.factBits and PowerFfm.FACT_THERMAL) != 0) thermalOf(result.thermal) else null,
            locked = if ((result.factBits and PowerFfm.FACT_LOCKED) != 0) result.locked else null,
        )
    }

    override suspend fun acquire(request: KWebPowerLeaseRequest): KWebPowerLease {
        requireOpen()
        val result = translate("acquire") {
            native.acquire(request.id.value, request.reason, typeCode(request.type), request.maxDurationSeconds)
        }
        return leaseOf(result, request.reason)
    }

    override suspend fun renew(id: KWebPowerLeaseId): KWebPowerLease {
        requireOpen()
        val existing = translate("leases") { native.leases() }.singleOrNull { it.leaseId == id.value }
        val renewed = translate("renew") { native.renew(id.value) }
        return leaseOf(renewed, existing?.reason ?: "Application power lease")
    }

    override suspend fun release(id: KWebPowerLeaseId): Boolean {
        requireOpen()
        return translate("release") { native.release(id.value) }
    }

    override suspend fun leases(): List<KWebPowerLease> {
        requireOpen()
        return translate("leases") { native.leases() }.map { flat ->
            KWebPowerLease(
                id = KWebPowerLeaseId(flat.leaseId),
                type = typeOf(flat.type),
                reason = flat.reason,
                grantedSeconds = flat.grantedSeconds,
                remainingSeconds = flat.remainingSeconds,
                renewCount = flat.renewCount,
            )
        }
    }

    override suspend fun acquireProfile(id: KWebPowerLeaseProfileId): KWebPowerLease {
        requireOpen()
        val profile = profiles[id]
            ?: throw failure(KWebPowerErrorCode.PROFILE_UNKNOWN, "acquire-profile", mapOf("profile" to id.value))
        return acquire(
            KWebPowerLeaseRequest(
                id = KWebPowerLeaseId("profile-" + id.value),
                type = profile.type,
                reason = profile.reason,
                maxDurationSeconds = profile.maxDurationSeconds,
            ),
        )
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
                message = "The power provider failed while closing.",
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

    private fun decodeEvent(event: PowerFfm.Event): KWebPowerEvent {
        val sequence = event.sequence.toULong()
        return when (event.kind) {
            PowerFfm.EVENT_SUSPENDED -> KWebPowerEvent.Suspended(sequence)
            PowerFfm.EVENT_RESUMED -> KWebPowerEvent.Resumed(sequence)
            PowerFfm.EVENT_LOCKED -> KWebPowerEvent.Locked(sequence)
            PowerFfm.EVENT_UNLOCKED -> KWebPowerEvent.Unlocked(sequence)
            PowerFfm.EVENT_POWER_SOURCE_CHANGED ->
                KWebPowerEvent.PowerSourceChanged(onBattery = event.value != 0, sequence = sequence)
            PowerFfm.EVENT_THERMAL_CHANGED ->
                KWebPowerEvent.ThermalChanged(thermalOf(event.value), sequence)
            PowerFfm.EVENT_LEASE_ENDED -> KWebPowerEvent.LeaseEnded(
                leaseId = KWebPowerLeaseId(event.leaseId),
                reason = when (event.reason) {
                    PowerFfm.END_RELEASED -> KWebPowerLeaseEndReason.RELEASED
                    PowerFfm.END_OWNER_CLOSED -> KWebPowerLeaseEndReason.OWNER_CLOSED
                    else -> KWebPowerLeaseEndReason.EXPIRED
                },
                sequence = sequence,
            )
            else -> KWebPowerEvent.Failed(
                code = event.code.ifEmpty { KWebPowerErrorCode.NATIVE_FAILED },
                sequence = sequence,
            )
        }
    }

    private fun leaseOf(result: PowerFfm.LeaseResult, reason: String): KWebPowerLease = KWebPowerLease(
        id = KWebPowerLeaseId(result.leaseId),
        type = typeOf(result.type),
        reason = reason,
        grantedSeconds = result.grantedSeconds,
        remainingSeconds = result.remainingSeconds,
        renewCount = result.renewCount,
    )

    private fun requireOpen() {
        val state = mutableLifecycle.value
        if (state != KWebLifecycleState.OPEN) {
            throw failure(KWebPowerErrorCode.OWNER_CLOSED, "require-open", mapOf("state" to state.name))
        }
    }

    private fun failure(code: String, operation: String, details: Map<String, String>): KWebServiceException =
        KWebServiceException(
            code = code,
            details = details + mapOf("service" to descriptor.id, "operation" to operation),
            message = "The power operation '$operation' failed with $code.",
        )

    /** Maps one provider status to its published typed service error. */
    private fun <T> translate(operation: String, block: () -> T): T = try {
        block()
    } catch (error: PowerFfm.PowerNativeException) {
        throw failure(
            when (error.status) {
                PowerFfm.STATUS_PLATFORM_UNAVAILABLE -> KWebPowerErrorCode.PLATFORM_UNAVAILABLE
                PowerFfm.STATUS_SERVICE_UNAVAILABLE -> KWebPowerErrorCode.SERVICE_UNAVAILABLE
                PowerFfm.STATUS_TYPE_UNSUPPORTED -> KWebPowerErrorCode.TYPE_UNSUPPORTED
                PowerFfm.STATUS_DURATION_EXCEEDED -> KWebPowerErrorCode.DURATION_EXCEEDED
                PowerFfm.STATUS_LIMIT_EXCEEDED -> KWebPowerErrorCode.LIMIT_EXCEEDED
                PowerFfm.STATUS_LEASE_UNKNOWN -> KWebPowerErrorCode.LEASE_UNKNOWN
                PowerFfm.STATUS_RENEWAL_EXCEEDED -> KWebPowerErrorCode.RENEWAL_EXCEEDED
                PowerFfm.STATUS_OWNER_CLOSED -> KWebPowerErrorCode.OWNER_CLOSED
                else -> KWebPowerErrorCode.NATIVE_FAILED
            },
            operation,
            mapOf("native-status" to error.status.toString()),
        )
    }
}

public object JvmKWebPower {
    /**
     * Opens the packaged power provider. There is no fallback provider: a missing
     * native library or an absent session facility fails immediately.
     */
    public fun open(
        applicationId: String,
        nativeLibrary: Path,
        profiles: List<KWebPowerLeaseProfile> = emptyList(),
        packageIdentity: String = applicationId,
    ): JvmKWebPowerSession = JvmKWebPowerSession(
        PowerFfm.open(nativeLibrary, applicationId, packageIdentity),
        profiles.associateBy { it.id },
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
}

internal fun typeCode(type: KWebPowerBlockerType): Int = when (type) {
    KWebPowerBlockerType.SYSTEM_SLEEP -> PowerFfm.TYPE_SYSTEM_SLEEP
    KWebPowerBlockerType.DISPLAY_SLEEP -> PowerFfm.TYPE_DISPLAY_SLEEP
}

internal fun typeOf(code: Int): KWebPowerBlockerType = when (code) {
    PowerFfm.TYPE_DISPLAY_SLEEP -> KWebPowerBlockerType.DISPLAY_SLEEP
    else -> KWebPowerBlockerType.SYSTEM_SLEEP
}

internal fun thermalOf(code: Int): KWebPowerThermalLevel = when (code) {
    PowerFfm.THERMAL_FAIR -> KWebPowerThermalLevel.FAIR
    PowerFfm.THERMAL_SERIOUS -> KWebPowerThermalLevel.SERIOUS
    PowerFfm.THERMAL_CRITICAL -> KWebPowerThermalLevel.CRITICAL
    else -> KWebPowerThermalLevel.NOMINAL
}
