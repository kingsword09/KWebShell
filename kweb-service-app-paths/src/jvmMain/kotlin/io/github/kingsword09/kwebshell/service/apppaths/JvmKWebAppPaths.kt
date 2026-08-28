package io.github.kingsword09.kwebshell.service.apppaths

import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import io.github.kingsword09.kwebshell.service.apppaths.internal.AppPathsFfm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.file.Path

public object JvmKWebAppPaths {
    public fun open(
        libraryPath: Path,
        configuration: KWebAppPathsConfiguration,
    ): KWebAppPaths = try {
        FfmKWebAppPaths(libraryPath, configuration)
    } catch (error: IllegalCallerException) {
        throw KWebNativeException(
            code = "service.native-access-required",
            details = mapOf("service" to KWebAppPaths.DESCRIPTOR.id),
            message = "JDK native access must be enabled for the KWebAppPaths provider.",
            cause = error,
        )
    } catch (error: IllegalArgumentException) {
        throw KWebConfigurationException(
            code = "service.native-library-invalid",
            details = mapOf("service" to KWebAppPaths.DESCRIPTOR.id),
            message = "The KWebAppPaths native provider path or ABI is invalid.",
            cause = error,
        )
    } catch (error: IllegalStateException) {
        throw KWebNativeException(
            code = "service.native-abi-invalid",
            details = mapOf("service" to KWebAppPaths.DESCRIPTOR.id),
            message = "The KWebAppPaths native provider does not satisfy its ABI.",
            cause = error,
        )
    }
}

private class FfmKWebAppPaths(
    libraryPath: Path,
    private val configuration: KWebAppPathsConfiguration,
) : KWebAppPaths {
    private val lock = Any()
    private val native = AppPathsFfm.open(libraryPath)
    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    private var closeFailure: KWebNativeException? = null

    override val descriptor = KWebAppPaths.DESCRIPTOR
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    override suspend fun resolve(kind: KWebAppPathKind): KWebResolvedPath = withContext(Dispatchers.IO) {
        synchronized(lock) {
            requireOpen("resolve")
            try {
                val result = native.resolve(
                    kind.ordinal + 1,
                    configuration.applicationId,
                    configuration.applicationDataRoot,
                    configuration.sessionDataRoot,
                )
                if (result.kind != kind.ordinal + 1 || result.path.isBlank() || result.source.isBlank()) {
                    throw KWebNativeException(
                        code = KWebServiceErrorCode.NATIVE_FAILED,
                        details = mapOf("service" to descriptor.id, "operation" to "resolve"),
                        message = "The native KWebAppPaths provider returned an invalid result.",
                    )
                }
                KWebResolvedPath(kind, result.path, result.source)
            } catch (error: AppPathsFfm.NativeFailure) {
                throw mapFailure(kind, error)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (mutableLifecycle.value == KWebLifecycleState.CLOSED) return
            closeFailure?.let { throw it }
            if (mutableLifecycle.value != KWebLifecycleState.OPEN) {
                throw KWebNativeException(
                    code = KWebServiceErrorCode.OWNER_CLOSED,
                    details = mapOf("service" to descriptor.id, "operation" to "close"),
                    message = "The KWebAppPaths service is already closing or failed.",
                )
            }
            mutableLifecycle.value = KWebLifecycleState.CLOSING
            try {
                native.close()
                mutableLifecycle.value = KWebLifecycleState.CLOSED
            } catch (error: Throwable) {
                val failure = KWebNativeException(
                    code = KWebServiceErrorCode.NATIVE_FAILED,
                    details = mapOf("service" to descriptor.id, "operation" to "close"),
                    message = "The native KWebAppPaths provider could not close.",
                    cause = error,
                )
                closeFailure = failure
                mutableLifecycle.value = KWebLifecycleState.FAILED
                throw failure
            }
        }
    }

    private fun requireOpen(operation: String) {
        if (mutableLifecycle.value != KWebLifecycleState.OPEN) {
            throw KWebNativeException(
                code = KWebServiceErrorCode.OWNER_CLOSED,
                details = mapOf("service" to descriptor.id, "operation" to operation),
                message = "The KWebAppPaths service is not open.",
            )
        }
    }

    private fun mapFailure(kind: KWebAppPathKind, failure: AppPathsFfm.NativeFailure): KWebNativeException {
        val code = when (failure.status()) {
            AppPathsFfm.STATUS_INVALID_ARGUMENT,
            AppPathsFfm.STATUS_PATH_KIND_UNKNOWN,
            AppPathsFfm.STATUS_PATH_INVALID -> KWebServiceErrorCode.REQUEST_INVALID
            AppPathsFfm.STATUS_NATIVE_UNAVAILABLE -> KWebServiceErrorCode.OPERATION_UNAVAILABLE
            else -> KWebServiceErrorCode.NATIVE_FAILED
        }
        return KWebNativeException(
            code = code,
            details = mapOf(
                "service" to descriptor.id,
                "operation" to "resolve",
                "kind" to kind.id,
                "status" to failure.status().toString(),
                "nativeStatus" to failure.statusName(),
            ),
            message = "The KWebAppPaths provider could not resolve '${kind.id}'.",
            cause = failure,
        )
    }
}
