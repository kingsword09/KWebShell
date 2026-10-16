/**
 * The RFC 0022 typed power contract shared by the Win32 power-request, macOS
 * IOPMAssertion, and Linux logind/UPower providers. Values are immutable and
 * bounded before a provider sees them; Kotlin owns lease identity, the duration
 * ceiling, capability reporting, expiry, and ordered event delivery.
 */
package io.github.kingsword09.kwebshell.service.power

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

/** One stable application-owned lease identifier. */
public class KWebPowerLeaseId(public val value: String) {
    init {
        if (!POWER_ID_PATTERN.matches(value)) {
            invalid(KWebPowerErrorCode.ID_INVALID, "A power lease identifier must match the published identifier form.")
        }
    }

    override fun equals(other: Any?): Boolean = other is KWebPowerLeaseId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** One application-declared lease profile a renderer may acquire. */
public class KWebPowerLeaseProfileId(public val value: String) {
    init {
        if (!POWER_ID_PATTERN.matches(value)) {
            invalid(KWebPowerErrorCode.ID_INVALID, "A power lease profile identifier must match the published identifier form.")
        }
    }

    override fun equals(other: Any?): Boolean = other is KWebPowerLeaseProfileId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** One declared blocker type. */
public enum class KWebPowerBlockerType { SYSTEM_SLEEP, DISPLAY_SLEEP }

/** One thermal level the session may publish. */
public enum class KWebPowerThermalLevel { NOMINAL, FAIR, SERIOUS, CRITICAL }

/** Why a live lease ended. */
public enum class KWebPowerLeaseEndReason { EXPIRED, RELEASED, OWNER_CLOSED }

/** One bounded lease request. Every lease carries a maximum duration. */
public data class KWebPowerLeaseRequest(
    public val id: KWebPowerLeaseId,
    public val type: KWebPowerBlockerType,
    public val reason: String,
    public val maxDurationSeconds: Int,
) {
    init {
        validateReason(reason)
        validateDuration(maxDurationSeconds)
    }
}

/** One application-declared profile the renderer may acquire by identifier. */
public data class KWebPowerLeaseProfile(
    public val id: KWebPowerLeaseProfileId,
    public val type: KWebPowerBlockerType,
    public val reason: String,
    public val maxDurationSeconds: Int,
) {
    init {
        validateReason(reason)
        validateDuration(maxDurationSeconds)
    }
}

/** One live lease. The granted window is the request's declared maximum. */
public data class KWebPowerLease(
    public val id: KWebPowerLeaseId,
    public val type: KWebPowerBlockerType,
    public val reason: String,
    public val grantedSeconds: Int,
    public val remainingSeconds: Int,
    public val renewCount: Int,
)

/**
 * The facts the session publishes. A fact the session does not provide is
 * `null`; it is never defaulted.
 */
public data class KWebPowerState(
    public val onBattery: Boolean?,
    public val batteryPercent: Int?,
    public val thermal: KWebPowerThermalLevel?,
    public val locked: Boolean?,
) {
    init {
        batteryPercent?.let {
            if (it !in 0..100) {
                invalid(KWebPowerErrorCode.BATTERY_INVALID, "A battery percentage must be between 0 and 100.")
            }
        }
    }
}

/** Declared provider capabilities for the current target and session. */
public enum class KWebPowerCapability {
    SUSPEND_EVENTS,
    LOCK_EVENTS,
    POWER_SOURCE_STATE,
    THERMAL_STATE,
    SYSTEM_SLEEP_BLOCKER,
    DISPLAY_SLEEP_BLOCKER,
    LEASE_RENEWAL,
}

public data class KWebPowerCapabilities(
    public val providerId: String,
    public val flags: Set<KWebPowerCapability>,
    public val maxLeases: Int,
    public val maxLeasesPerType: Int,
    public val minLeaseSeconds: Int,
    public val maxLeaseSeconds: Int,
) {
    public fun supports(capability: KWebPowerCapability): Boolean = capability in flags
}

/** One ordered service event. Sequences start at 1 and never repeat. */
public sealed interface KWebPowerEvent {
    public val sequence: ULong

    public data class Suspended(override val sequence: ULong) : KWebPowerEvent

    public data class Resumed(override val sequence: ULong) : KWebPowerEvent

    public data class Locked(override val sequence: ULong) : KWebPowerEvent

    public data class Unlocked(override val sequence: ULong) : KWebPowerEvent

    public data class PowerSourceChanged(
        public val onBattery: Boolean,
        override val sequence: ULong,
    ) : KWebPowerEvent

    public data class ThermalChanged(
        public val level: KWebPowerThermalLevel,
        override val sequence: ULong,
    ) : KWebPowerEvent

    public data class LeaseEnded(
        public val leaseId: KWebPowerLeaseId,
        public val reason: KWebPowerLeaseEndReason,
        override val sequence: ULong,
    ) : KWebPowerEvent

    public data class Failed(
        public val code: String,
        override val sequence: ULong,
    ) : KWebPowerEvent
}

public interface KWebPower : KWebNativeService {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<KWebLifecycleState>

    /** Ordered, replayable service events. */
    public val events: Flow<KWebPowerEvent>

    public suspend fun capabilities(): KWebPowerCapabilities

    public suspend fun state(): KWebPowerState

    /** Installs one lease, or fails typed and installs nothing. */
    public suspend fun acquire(request: KWebPowerLeaseRequest): KWebPowerLease

    /** Extends a live lease to the declared ceiling from the moment of renewal. */
    public suspend fun renew(id: KWebPowerLeaseId): KWebPowerLease

    /** Releases one lease; an unknown identifier returns `false` instead of failing. */
    public suspend fun release(id: KWebPowerLeaseId): Boolean

    public suspend fun leases(): List<KWebPowerLease>

    /**
     * Acquires one application-declared profile. The renderer bridge calls this
     * after its origin, frame, grant, gesture, and consent checks, so a page can
     * only select a profile the application declared and can never name a type or
     * an arbitrary duration.
     */
    public suspend fun acquireProfile(id: KWebPowerLeaseProfileId): KWebPowerLease

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "power",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.APPLICATION,
            operations = setOf(
                hostOperation("capabilities"),
                hostOperation("state"),
                hostOperation("acquire"),
                hostOperation("renew"),
                hostOperation("release"),
                hostOperation("leases"),
                hostOperation("events"),
                rendererOperation("read", permission = RENDERER_READ_PERMISSION, requiresUserGesture = false),
                rendererOperation("acquire-profile", permission = RENDERER_ACQUIRE_PERMISSION, requiresUserGesture = true),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = setOf(
                KWebTarget(KWebOperatingSystem.WINDOWS, KWebArchitecture.X64),
                KWebTarget(KWebOperatingSystem.MACOS, KWebArchitecture.ARM64),
                KWebTarget(KWebOperatingSystem.LINUX, KWebArchitecture.X64),
            ),
        )

        public val Key: KWebServiceKey<KWebPower> = object : KWebServiceKey<KWebPower> {
            override val id: String = DESCRIPTOR.id
            override val contract: KWebServiceVersionRange = KWebServiceVersionRange.exact(DESCRIPTOR.version)
        }

        /** The renderer permission that gates the bounded state summary. */
        public const val RENDERER_READ_PERMISSION: String = "power.read"

        /** The renderer permission that gates a declared-profile acquisition. */
        public const val RENDERER_ACQUIRE_PERMISSION: String = "power.acquire"

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

public object KWebPowerErrorCode {
    public const val ID_INVALID: String = "power.id-invalid"
    public const val REASON_INVALID: String = "power.reason-invalid"
    public const val DURATION_INVALID: String = "power.duration-invalid"
    public const val DURATION_EXCEEDED: String = "power.duration-exceeded"
    public const val TYPE_UNSUPPORTED: String = "power.type-unsupported"
    public const val LIMIT_EXCEEDED: String = "power.limit-exceeded"
    public const val LEASE_UNKNOWN: String = "power.lease-unknown"
    public const val RENEWAL_EXCEEDED: String = "power.renewal-exceeded"
    public const val PROFILE_UNKNOWN: String = "power.profile-unknown"
    public const val BATTERY_INVALID: String = "power.battery-invalid"
    public const val PLATFORM_UNAVAILABLE: String = "power.platform-unavailable"
    public const val SERVICE_UNAVAILABLE: String = "power.service-unavailable"
    public const val FACT_UNKNOWN: String = "power.fact-unknown"
    public const val FACT_NOT_RENDERER_VISIBLE: String = "power.fact-not-renderer-visible"
    public const val NATIVE_FAILED: String = "power.native-failed"
    public const val GESTURE_REQUIRED: String = "power.gesture-required"
    public const val CONSENT_DENIED: String = "power.consent-denied"
    public const val OWNER_CLOSED: String = "power.owner-closed"
}

public const val KWEB_POWER_MAX_LEASES: Int = 8
public const val KWEB_POWER_MAX_LEASES_PER_TYPE: Int = 4
public const val KWEB_POWER_MAX_REASON_BYTES: Int = 96
public const val KWEB_POWER_MIN_LEASE_SECONDS: Int = 1
public const val KWEB_POWER_MAX_LEASE_SECONDS: Int = 14400
public const val KWEB_POWER_EVENT_REPLAY: Int = 64

internal val POWER_ID_PATTERN = Regex("[A-Za-z][A-Za-z0-9._-]{0,127}")

internal fun validateReason(reason: String) {
    val bytes = reason.encodeToByteArray()
    if (bytes.isEmpty() || reason.isBlank()) {
        invalid(KWebPowerErrorCode.REASON_INVALID, "A power lease reason cannot be empty.")
    }
    if (bytes.size > KWEB_POWER_MAX_REASON_BYTES) {
        invalid(KWebPowerErrorCode.REASON_INVALID, "A power lease reason accepts at most $KWEB_POWER_MAX_REASON_BYTES UTF-8 bytes.")
    }
    if (reason.any { it.isISOControl() }) {
        invalid(KWebPowerErrorCode.REASON_INVALID, "A power lease reason cannot contain control characters.")
    }
}

internal fun validateDuration(seconds: Int) {
    if (seconds < KWEB_POWER_MIN_LEASE_SECONDS) {
        invalid(KWebPowerErrorCode.DURATION_INVALID, "A power lease requires a positive maximum duration.")
    }
    if (seconds > KWEB_POWER_MAX_LEASE_SECONDS) {
        invalid(KWebPowerErrorCode.DURATION_EXCEEDED, "A power lease duration must not exceed $KWEB_POWER_MAX_LEASE_SECONDS seconds.")
    }
}

internal fun invalid(code: String, message: String): Nothing = throw KWebConfigurationException(
    code = code,
    details = emptyMap(),
    message = message,
)
