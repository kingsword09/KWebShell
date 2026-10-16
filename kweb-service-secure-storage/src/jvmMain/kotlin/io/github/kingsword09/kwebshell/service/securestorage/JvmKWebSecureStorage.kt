package io.github.kingsword09.kwebshell.service.securestorage

import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.service.securestorage.internal.SecureStorageFfm
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import io.github.kingsword09.kwebshell.services.KWebServiceException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

/**
 * The JVM secure-storage session. It requires the packaged provider library
 * explicitly, owns the scope-qualified reference policy, and converts the ABI's
 * value buffer into a closeable zeroizable value.
 */
public class JvmKWebSecureStorageSession internal constructor(
    private val native: SecureStorageFfm,
    private val scope: CoroutineScope,
) : KWebSecureStorage {

    override val descriptor: KWebServiceDescriptor = KWebSecureStorage.DESCRIPTOR

    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    private var capabilitiesCache: KWebSecureStorageCapabilities? = null
    private var closeFailure: KWebException? = null

    override suspend fun capabilities(): KWebSecureStorageCapabilities {
        requireOpen()
        capabilitiesCache?.let { return it }
        val result = translate("capabilities") { native.capabilities() }
        val capabilities = KWebSecureStorageCapabilities(
            providerId = result.providerId,
            backend = backendOf(result.backend),
            flags = KWebSecureStorageCapability.entries
                .filter { (result.capabilityBits and (1 shl it.ordinal)) != 0 }
                .toSet(),
            accessibilities = KWebSecretAccessibility.entries
                .filter { (result.accessibilityBits and (1 shl it.ordinal)) != 0 }
                .toSet(),
            maxValueBytes = result.maxValueBytes,
        )
        capabilitiesCache = capabilities
        return capabilities
    }

    override suspend fun put(request: KWebSecretPutRequest): KWebSecretMetadata {
        requireOpen()
        val result = translate("put") {
            native.put(
                request.reference.id.value,
                scopeCode(request.reference.scope),
                request.reference.ownerId ?: "",
                request.value,
                accessibilityCode(request.accessibility),
                request.requireUserPresence,
            )
        }
        return KWebSecretMetadata(
            reference = request.reference,
            backend = backendOf(result.backend),
            accessibility = accessibilityOf(result.accessibility),
            envelopeVersion = result.envelopeVersion,
            generation = result.generation,
            sizeBytes = result.sizeBytes,
        )
    }

    override suspend fun get(id: KWebSecretRef): KWebSecretValue {
        requireOpen()
        val result = translate("get") {
            native.get(id.id.value, scopeCode(id.scope), id.ownerId ?: "")
        }
        return KWebSecretValue.of(result.bytes)
    }

    override suspend fun delete(id: KWebSecretRef): Boolean {
        requireOpen()
        return translate("delete") { native.delete(id.id.value, scopeCode(id.scope), id.ownerId ?: "") }
    }

    override suspend fun exists(id: KWebSecretRef): Boolean {
        requireOpen()
        return translate("exists") { native.exists(id.id.value, scopeCode(id.scope), id.ownerId ?: "") }
    }

    override suspend fun rotate(id: KWebSecretRef): KWebSecretMetadata {
        requireOpen()
        val result = translate("rotate") {
            native.rotate(id.id.value, scopeCode(id.scope), id.ownerId ?: "")
        }
        return KWebSecretMetadata(
            reference = id,
            backend = backendOf(result.backend),
            accessibility = accessibilityOf(result.accessibility),
            envelopeVersion = result.envelopeVersion,
            generation = result.generation,
            sizeBytes = result.sizeBytes,
        )
    }

    override suspend fun rotationStatus(id: KWebSecretRef): KWebSecretRotationStatus {
        requireOpen()
        val result = translate("rotation-status") {
            native.rotationStatus(id.id.value, scopeCode(id.scope), id.ownerId ?: "")
        }
        return when (result.status) {
            SecureStorageFfm.ROTATION_STALE_ENVELOPE -> KWebSecretRotationStatus.STALE_ENVELOPE
            SecureStorageFfm.ROTATION_STALE_POLICY -> KWebSecretRotationStatus.STALE_POLICY
            else -> KWebSecretRotationStatus.CURRENT
        }
    }

    override fun close() {
        synchronized(this) {
            if (mutableLifecycle.value == KWebLifecycleState.CLOSED) return
            mutableLifecycle.value = KWebLifecycleState.CLOSING
        }
        var failure: KWebException? = null
        try {
            translate("close") { native.close() }
        } catch (error: KWebException) {
            failure = error
        } catch (error: Throwable) {
            failure = KWebServiceException(
                code = KWebServiceErrorCode.NATIVE_FAILED,
                details = mapOf("service" to descriptor.id, "operation" to "close"),
                message = "The secure-storage provider failed while closing.",
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

    private fun requireOpen() {
        val state = mutableLifecycle.value
        if (state != KWebLifecycleState.OPEN) {
            throw failure(KWebSecureStorageErrorCode.OWNER_CLOSED, "require-open", mapOf("state" to state.name))
        }
    }

    private fun failure(code: String, operation: String, details: Map<String, String>): KWebServiceException =
        KWebServiceException(
            code = code,
            details = details + mapOf("service" to descriptor.id, "operation" to operation),
            message = "The secure-storage operation '$operation' failed with $code.",
        )

    /** Maps one provider status to its published typed service error. */
    private fun <T> translate(operation: String, block: () -> T): T = try {
        block()
    } catch (error: SecureStorageFfm.SecureStorageNativeException) {
        throw failure(
            when (error.status) {
                SecureStorageFfm.STATUS_BACKEND_UNAVAILABLE -> KWebSecureStorageErrorCode.BACKEND_UNAVAILABLE
                SecureStorageFfm.STATUS_SERVICE_UNAVAILABLE -> KWebSecureStorageErrorCode.SERVICE_UNAVAILABLE
                SecureStorageFfm.STATUS_ACCESSIBILITY_UNSUPPORTED -> KWebSecureStorageErrorCode.ACCESSIBILITY_UNSUPPORTED
                SecureStorageFfm.STATUS_USER_PRESENCE_UNSUPPORTED -> KWebSecureStorageErrorCode.USER_PRESENCE_UNSUPPORTED
                SecureStorageFfm.STATUS_USER_PRESENCE_DENIED -> KWebSecureStorageErrorCode.USER_PRESENCE_DENIED
                SecureStorageFfm.STATUS_LOCKED -> KWebSecureStorageErrorCode.LOCKED
                SecureStorageFfm.STATUS_CANCELLED -> KWebSecureStorageErrorCode.CANCELLED
                SecureStorageFfm.STATUS_SECRET_NOT_FOUND -> KWebSecureStorageErrorCode.SECRET_NOT_FOUND
                SecureStorageFfm.STATUS_CORRUPTED -> KWebSecureStorageErrorCode.CORRUPTED
                SecureStorageFfm.STATUS_ROTATION_UNSUPPORTED -> KWebSecureStorageErrorCode.ROTATION_UNSUPPORTED
                SecureStorageFfm.STATUS_OWNER_CLOSED -> KWebSecureStorageErrorCode.OWNER_CLOSED
                else -> KWebSecureStorageErrorCode.NATIVE_FAILED
            },
            operation,
            mapOf("native-status" to error.status.toString()),
        )
    }
}

public object JvmKWebSecureStorage {
    /**
     * Opens the packaged secure-storage provider. There is no fallback provider: a
     * missing native library or an absent credential backend fails immediately.
     */
    public fun open(
        applicationId: String,
        nativeLibrary: Path,
        packageIdentity: String = applicationId,
    ): JvmKWebSecureStorageSession = JvmKWebSecureStorageSession(
        SecureStorageFfm.open(nativeLibrary, applicationId, packageIdentity),
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
}

internal fun scopeCode(scope: KWebSecretScope): Int = when (scope) {
    KWebSecretScope.APPLICATION -> SecureStorageFfm.SCOPE_APPLICATION
    KWebSecretScope.PROFILE -> SecureStorageFfm.SCOPE_PROFILE
}

internal fun accessibilityCode(accessibility: KWebSecretAccessibility): Int = when (accessibility) {
    KWebSecretAccessibility.WHEN_UNLOCKED -> SecureStorageFfm.ACCESS_WHEN_UNLOCKED
    KWebSecretAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY -> SecureStorageFfm.ACCESS_WHEN_UNLOCKED_THIS_DEVICE_ONLY
    KWebSecretAccessibility.AFTER_FIRST_UNLOCK -> SecureStorageFfm.ACCESS_AFTER_FIRST_UNLOCK
    KWebSecretAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY ->
        SecureStorageFfm.ACCESS_AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY
}

internal fun accessibilityOf(code: Int): KWebSecretAccessibility = when (code) {
    SecureStorageFfm.ACCESS_WHEN_UNLOCKED_THIS_DEVICE_ONLY -> KWebSecretAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY
    SecureStorageFfm.ACCESS_AFTER_FIRST_UNLOCK -> KWebSecretAccessibility.AFTER_FIRST_UNLOCK
    SecureStorageFfm.ACCESS_AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY ->
        KWebSecretAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY
    else -> KWebSecretAccessibility.WHEN_UNLOCKED
}

internal fun backendOf(code: Int): KWebSecretBackend = when (code) {
    SecureStorageFfm.BACKEND_WINDOWS_CREDENTIAL_MANAGER -> KWebSecretBackend.WINDOWS_CREDENTIAL_MANAGER
    SecureStorageFfm.BACKEND_LINUX_SECRET_SERVICE -> KWebSecretBackend.LINUX_SECRET_SERVICE
    else -> KWebSecretBackend.MACOS_KEYCHAIN
}
