package io.github.kingsword09.kwebshell.service.securestorage

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.service.securestorage.generated.MetadataRequest
import io.github.kingsword09.kwebshell.service.securestorage.generated.MetadataResponse
import io.github.kingsword09.kwebshell.service.securestorage.generated.SecretSummary
import io.github.kingsword09.kwebshell.service.securestorage.generated.SecureStorageBridgeDispatcher
import io.github.kingsword09.kwebshell.service.securestorage.generated.SecureStorageBridgeHandler
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyDecision
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import kotlinx.coroutines.CancellationException

/**
 * The facts an opt-in renderer adapter may expose: whether user presence is
 * available and the application-declared identifiers with their scope and stored
 * size. A secret byte is never renderer-visible, and an undeclared identifier is
 * denied rather than summarized.
 *
 * The service descriptor declares no renderer operation; an application opts in by
 * building this adapter with its declared allowlist and a policy engine.
 */
public object KWebSecureStorageMetadataAdapter {
    /** One application-declared identifier the renderer may summarize. */
    public data class Declared(val reference: KWebSecretRef)

    /**
     * Builds the opt-in metadata adapter. Only identifiers in `declared` are
     * summarized, and every read passes the grant, frame, and gesture policy.
     */
    public fun dispatcher(
        storage: KWebSecureStorage,
        declared: List<Declared>,
        policyEngine: KWebServicePolicyEngine,
        subject: KWebPolicySubject,
        operationPermission: String = "secure-storage.read-metadata",
        requiresUserGesture: Boolean = true,
    ): KWebBridgeDispatcher {
        return SecureStorageBridgeDispatcher(
            object : SecureStorageBridgeHandler {
                override suspend fun readMetadata(request: MetadataRequest): MetadataResponse =
                    authorized(
                        storage,
                        policyEngine,
                        subject,
                        operationPermission,
                        requiresUserGesture,
                    ) {
                        val allowed = declared.map { it.reference }.toSet()
                        val requested = request.secretIds.map { id ->
                            declared.firstOrNull { it.reference.id == KWebSecretId(id) }?.reference
                                ?: throw KWebBridgeException(
                                    KWebSecureStorageErrorCode.SECRET_NOT_FOUND,
                                    "The renderer named an undeclared secret identifier.",
                                )
                        }
                        val summaries = requested.map { reference ->
                            if (reference !in allowed) {
                                throw KWebBridgeException(
                                    KWebSecureStorageErrorCode.SECRET_NOT_FOUND,
                                    "The renderer named an undeclared secret identifier.",
                                )
                            }
                            val present = storage.exists(reference)
                            SecretSummary(
                                id = reference.id.value,
                                scope = reference.scope.name.lowercase(),
                                // Only the stored size is exposed, never the bytes.
                                sizeBytes = if (present) summarizeLength(storage, reference) else 0,
                            )
                        }
                        val capabilities = storage.capabilities()
                        MetadataResponse(
                            backend = capabilities.backend.name.lowercase(),
                            supportsUserPresence = capabilities.supports(KWebSecureStorageCapability.USER_PRESENCE),
                            secrets = summaries,
                        )
                    }
            },
        )
    }

    /** The stored size, obtained without ever returning the secret bytes. */
    private suspend fun summarizeLength(storage: KWebSecureStorage, reference: KWebSecretRef): Int {
        val value = storage.get(reference)
        return try {
            value.size
        } finally {
            value.close()
        }
    }

    private suspend fun <T> authorized(
        storage: KWebSecureStorage,
        policyEngine: KWebServicePolicyEngine,
        subject: KWebPolicySubject,
        operationPermission: String,
        requiresUserGesture: Boolean,
        action: suspend () -> T,
    ): T {
        val operation = io.github.kingsword09.kwebshell.services.KWebServiceOperationDescriptor(
            id = "read-metadata",
            schemaVersion = 1,
            rendererPermission = operationPermission,
            requiresUserGesture = requiresUserGesture,
        )
        when (val verdict = policyEngine.authorize(subject, KWebSecureStorage.DESCRIPTOR.id, operation)) {
            else -> when (verdict.decision) {
                KWebPolicyDecision.ALLOW -> Unit
                KWebPolicyDecision.DENY -> throw KWebBridgeException(
                    verdict.reasonCode,
                    "The secure-storage metadata read was denied.",
                )
                KWebPolicyDecision.PROMPT_REQUIRED -> throw KWebBridgeException(
                    "service.policy.prompt-required",
                    "The secure-storage metadata read requires consent.",
                )
            }
        }
        return try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: KWebBridgeException) {
            throw error
        } catch (error: KWebException) {
            throw KWebBridgeException(error.code, error.message ?: "The metadata read failed.", error)
        } catch (error: Exception) {
            throw KWebBridgeException("service.native-failed", "The metadata read failed.", error)
        }
    }
}
