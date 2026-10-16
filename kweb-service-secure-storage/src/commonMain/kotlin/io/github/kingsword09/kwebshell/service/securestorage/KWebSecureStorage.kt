/**
 * The RFC 0023 typed secure-storage contract shared by the macOS Keychain, Windows
 * Credential Manager, and Linux Secret Service providers. Values are immutable and
 * bounded before a provider sees them; Kotlin owns identifier and scope policy, the
 * accessibility contract, the envelope version, and rotation status.
 *
 * Secret bytes live in a closeable buffer that is zeroed on close, and the service
 * descriptor declares no renderer operation at all.
 */
package io.github.kingsword09.kwebshell.service.securestorage

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
import kotlinx.coroutines.flow.StateFlow

/** One stable secret identifier. */
public class KWebSecretId(public val value: String) {
    init {
        if (!SECRET_ID_PATTERN.matches(value)) {
            invalid(KWebSecureStorageErrorCode.ID_INVALID, "A secret identifier must match the published identifier form.")
        }
    }

    override fun equals(other: Any?): Boolean = other is KWebSecretId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** The ownership scope of one secret. */
public enum class KWebSecretScope { APPLICATION, PROFILE }

/** One bounded secret reference. */
public data class KWebSecretRef(
    public val id: KWebSecretId,
    public val scope: KWebSecretScope = KWebSecretScope.APPLICATION,
    public val ownerId: String? = null,
) {
    init {
        when (scope) {
            KWebSecretScope.APPLICATION -> {
                if (ownerId != null) {
                    invalid(
                        KWebSecureStorageErrorCode.SCOPE_INVALID,
                        "An application-scoped secret must not declare a profile owner.",
                    )
                }
            }
            KWebSecretScope.PROFILE -> {
                if (ownerId.isNullOrBlank()) {
                    invalid(
                        KWebSecureStorageErrorCode.SCOPE_INVALID,
                        "A profile-scoped secret requires a non-blank profile owner.",
                    )
                }
                if (ownerId.length > KWEB_SECRET_MAX_OWNER_LENGTH) {
                    invalid(
                        KWebSecureStorageErrorCode.SCOPE_INVALID,
                        "A profile owner identifier must be at most $KWEB_SECRET_MAX_OWNER_LENGTH characters.",
                    )
                }
                if (ownerId.any { it.isISOControl() }) {
                    invalid(
                        KWebSecureStorageErrorCode.SCOPE_INVALID,
                        "A profile owner identifier cannot contain control characters.",
                    )
                }
            }
        }
    }
}

/** When the stored record is readable. A backend that cannot enforce one reports it absent. */
public enum class KWebSecretAccessibility {
    WHEN_UNLOCKED,
    WHEN_UNLOCKED_THIS_DEVICE_ONLY,
    AFTER_FIRST_UNLOCK,
    AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,
}

/** The declared credential backend. */
public enum class KWebSecretBackend {
    MACOS_KEYCHAIN,
    WINDOWS_CREDENTIAL_MANAGER,
    LINUX_SECRET_SERVICE,
}

/** One bounded write request. */
public data class KWebSecretPutRequest(
    public val reference: KWebSecretRef,
    public val value: ByteArray,
    public val accessibility: KWebSecretAccessibility = KWebSecretAccessibility.WHEN_UNLOCKED,
    public val requireUserPresence: Boolean = false,
) {
    init {
        if (value.isEmpty()) {
            invalid(KWebSecureStorageErrorCode.VALUE_INVALID, "A secret value cannot be empty.")
        }
        if (value.size > KWEB_SECRET_MAX_VALUE_BYTES) {
            invalid(
                KWebSecureStorageErrorCode.VALUE_INVALID,
                "A secret value accepts at most $KWEB_SECRET_MAX_VALUE_BYTES bytes.",
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is KWebSecretPutRequest && reference == other.reference && value.contentEquals(other.value) &&
            accessibility == other.accessibility && requireUserPresence == other.requireUserPresence

    override fun hashCode(): Int = 31 * reference.hashCode() + value.contentHashCode()
}

/** One stored record's metadata. It never carries the secret bytes. */
public data class KWebSecretMetadata(
    public val reference: KWebSecretRef,
    public val backend: KWebSecretBackend,
    public val accessibility: KWebSecretAccessibility,
    public val envelopeVersion: Int,
    public val generation: Int,
    public val sizeBytes: Int,
)

/**
 * One closeable secret value. Closing zeroes the buffer exactly once; the value is
 * unusable afterwards, and `toString` never reveals the bytes.
 */
public class KWebSecretValue internal constructor(private var bytes: ByteArray?) {
    public val size: Int get() = bytes?.size ?: 0

    public fun copyBytes(): ByteArray = bytes?.copyOf()
        ?: throw KWebConfigurationException(
            code = KWebSecureStorageErrorCode.VALUE_CLOSED,
            details = emptyMap(),
            message = "The secret value was already closed.",
        )

    public fun close() {
        val current = bytes ?: return
        current.fill(0)
        bytes = null
    }

    override fun toString(): String = "KWebSecretValue(size=$size)"

    public companion object {
        /** Wraps bytes a provider returned. The array is copied, never retained. */
        public fun of(bytes: ByteArray): KWebSecretValue = KWebSecretValue(bytes.copyOf())
    }
}

/** Whether the stored record matches the current envelope and policy. */
public enum class KWebSecretRotationStatus { CURRENT, STALE_ENVELOPE, STALE_POLICY }

/** Declared provider capabilities for the current target. */
public enum class KWebSecureStorageCapability {
    ACCESSIBILITY_POLICY,
    USER_PRESENCE,
    ROTATION,
    PROFILE_SCOPE,
}

public data class KWebSecureStorageCapabilities(
    public val providerId: String,
    public val backend: KWebSecretBackend,
    public val flags: Set<KWebSecureStorageCapability>,
    public val accessibilities: Set<KWebSecretAccessibility>,
    public val maxValueBytes: Int,
) {
    public fun supports(capability: KWebSecureStorageCapability): Boolean = capability in flags

    public fun supports(accessibility: KWebSecretAccessibility): Boolean = accessibility in accessibilities
}

public interface KWebSecureStorage : KWebNativeService {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<KWebLifecycleState>

    public suspend fun capabilities(): KWebSecureStorageCapabilities

    public suspend fun put(request: KWebSecretPutRequest): KWebSecretMetadata

    public suspend fun get(id: KWebSecretRef): KWebSecretValue

    /** Idempotent per identifier; an unknown identifier returns `false`. */
    public suspend fun delete(id: KWebSecretRef): Boolean

    public suspend fun exists(id: KWebSecretRef): Boolean

    public suspend fun rotate(id: KWebSecretRef): KWebSecretMetadata

    public suspend fun rotationStatus(id: KWebSecretRef): KWebSecretRotationStatus

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "secure-storage",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.APPLICATION,
            operations = setOf(
                hostOperation("capabilities"),
                hostOperation("put"),
                hostOperation("get"),
                hostOperation("delete"),
                hostOperation("exists"),
                hostOperation("rotate"),
                hostOperation("rotation-status"),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = setOf(
                KWebTarget(KWebOperatingSystem.WINDOWS, KWebArchitecture.X64),
                KWebTarget(KWebOperatingSystem.MACOS, KWebArchitecture.ARM64),
                KWebTarget(KWebOperatingSystem.LINUX, KWebArchitecture.X64),
            ),
        )

        public val Key: KWebServiceKey<KWebSecureStorage> = object : KWebServiceKey<KWebSecureStorage> {
            override val id: String = DESCRIPTOR.id
            override val contract: KWebServiceVersionRange = KWebServiceVersionRange.exact(DESCRIPTOR.version)
        }

        private fun hostOperation(id: String): KWebServiceOperationDescriptor = KWebServiceOperationDescriptor(
            id = id,
            schemaVersion = 1,
            rendererPermission = null,
            requiresUserGesture = false,
        )
    }
}

public object KWebSecureStorageErrorCode {
    public const val ID_INVALID: String = "secure-storage.id-invalid"
    public const val VALUE_INVALID: String = "secure-storage.value-invalid"
    public const val VALUE_CLOSED: String = "secure-storage.value-closed"
    public const val SCOPE_INVALID: String = "secure-storage.scope-invalid"
    public const val ACCESSIBILITY_UNSUPPORTED: String = "secure-storage.accessibility-unsupported"
    public const val USER_PRESENCE_UNSUPPORTED: String = "secure-storage.user-presence-unsupported"
    public const val USER_PRESENCE_DENIED: String = "secure-storage.user-presence-denied"
    public const val BACKEND_UNAVAILABLE: String = "secure-storage.backend-unavailable"
    public const val SERVICE_UNAVAILABLE: String = "secure-storage.service-unavailable"
    public const val LOCKED: String = "secure-storage.locked"
    public const val CANCELLED: String = "secure-storage.cancelled"
    public const val SECRET_NOT_FOUND: String = "secure-storage.secret-not-found"
    public const val CORRUPTED: String = "secure-storage.corrupted"
    public const val ROTATION_UNSUPPORTED: String = "secure-storage.rotation-unsupported"
    public const val NATIVE_FAILED: String = "secure-storage.native-failed"
    public const val OWNER_CLOSED: String = "secure-storage.owner-closed"
}

public const val KWEB_SECRET_MAX_VALUE_BYTES: Int = 4096
public const val KWEB_SECRET_MAX_OWNER_LENGTH: Int = 128
public const val KWEB_SECRET_ENVELOPE_VERSION: Int = 1

internal val SECRET_ID_PATTERN = Regex("[A-Za-z][A-Za-z0-9._-]{0,127}")

internal fun invalid(code: String, message: String): Nothing = throw KWebConfigurationException(
    code = code,
    details = emptyMap(),
    message = message,
)
