package io.github.kingsword09.kwebshell.service.applicationlifecycle

import io.github.kingsword09.kwebshell.service.applicationlifecycle.internal.FfmApplicationLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path

public class JvmKWebApplicationLifecycleBackend(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : KWebApplicationLifecycleBackend {
    private var handle: Long = 0L
    private var configuration: KWebApplicationLifecycleConfiguration? = null

    override suspend fun acquire(
        configuration: KWebApplicationLifecycleConfiguration,
        initial: KWebActivationBatch,
        onActivation: suspend (KWebActivationBatch) -> Unit,
    ): KWebApplicationBackendStart {
        if (handle != 0L) {
            throw KWebApplicationLifecycleException(
                code = KWebApplicationLifecycleErrorCode.ALREADY_STARTED,
                details = emptyMap(),
                message = "The JVM application lifecycle backend has already acquired a lease.",
            )
        }
        this.configuration = configuration
        val encoded = KWebApplicationActivationCodec.encode(initial)
        val start = FfmApplicationLifecycle.acquire(
            configuration.applicationId,
            configuration.transportRoot,
            encoded,
        ) { payload ->
            scope.launch {
                onActivation(KWebApplicationActivationCodec.decode(payload))
            }
        }
        return when (start.status()) {
            FfmApplicationLifecycle.OK_PRIMARY -> {
                handle = start.handle()
                KWebApplicationBackendStart.Primary
            }

            FfmApplicationLifecycle.OK_SECONDARY -> KWebApplicationBackendStart.SecondaryForwarded
            else -> throw KWebApplicationLifecycleException(
                code = KWebApplicationLifecycleErrorCode.TRANSPORT_FAILED,
                details = mapOf("status" to start.status().toString()),
                message = "The native application lifecycle provider could not acquire or forward the lease.",
            )
        }
    }

    override suspend fun release() {
        val current = handle
        if (current != 0L) {
            FfmApplicationLifecycle.release(current)
            handle = 0L
        }
        scope.cancel()
    }

    override suspend fun relaunch(preservePendingActivation: Boolean): KWebRelaunchResult {
        val config = configuration ?: return KWebRelaunchResult.UNAVAILABLE
        val executable = config.relaunchExecutable ?: return KWebRelaunchResult.UNAVAILABLE
        val target = Path.of(config.packageRoot).resolve(executable).normalize()
        if (!config.isPackaged || !Files.isRegularFile(target) || !Files.isExecutable(target)) {
            return KWebRelaunchResult.UNAVAILABLE
        }
        try {
            ProcessBuilder(target.toString()).directory(Path.of(config.packageRoot).toFile()).start()
            return KWebRelaunchResult.ACCEPTED
        } catch (_: Throwable) {
            return KWebRelaunchResult.FAILED
        }
    }

    override suspend fun installAssociations(): KWebApplicationRegistrationReport {
        val config = requirePackagedConfiguration()
        val executable = verifiedExecutable(config)
        val digest = registerNative(config, executable, remove = false)
        return KWebApplicationRegistrationReport(
            operation = KWebApplicationRegistrationOperation.INSTALL,
            target = config.target,
            applicationId = config.applicationId,
            provider = FfmApplicationLifecycle.providerId(),
            registered = true,
            observedDigest = digest,
        )
    }

    override suspend fun removeAssociations(): KWebApplicationRegistrationReport {
        val config = requirePackagedConfiguration()
        val executable = verifiedExecutable(config)
        val digest = registerNative(config, executable, remove = true)
        return KWebApplicationRegistrationReport(
            operation = KWebApplicationRegistrationOperation.REMOVE,
            target = config.target,
            applicationId = config.applicationId,
            provider = FfmApplicationLifecycle.providerId(),
            registered = false,
            observedDigest = digest,
        )
    }

    private fun requirePackagedConfiguration(): KWebApplicationLifecycleConfiguration {
        val config = configuration ?: throw KWebApplicationLifecycleException(
            code = KWebApplicationLifecycleErrorCode.REGISTRATION_FAILED,
            details = emptyMap(),
            message = "The application lifecycle backend has not been acquired.",
        )
        if (!config.isPackaged) {
            throw KWebApplicationLifecycleException(
                code = KWebApplicationLifecycleErrorCode.REGISTRATION_FAILED,
                details = mapOf("target" to config.target.id),
                message = "OS association mutation requires a verified packaged application.",
            )
        }
        return config
    }

    private fun verifiedExecutable(config: KWebApplicationLifecycleConfiguration): Path {
        val executable = Path.of(config.packageRoot).resolve(checkNotNull(config.relaunchExecutable)).normalize()
        if (!executable.startsWith(Path.of(config.packageRoot).toAbsolutePath().normalize()) ||
            Files.isSymbolicLink(executable) || !Files.isRegularFile(executable) || !Files.isExecutable(executable)
        ) {
            throw KWebApplicationLifecycleException(
                code = KWebApplicationLifecycleErrorCode.RELAUNCH_UNAVAILABLE,
                details = mapOf("path" to executable.toString()),
                message = "The packaged executable is not a verified regular executable.",
            )
        }
        return executable
    }

    private fun registerNative(
        config: KWebApplicationLifecycleConfiguration,
        executable: Path,
        remove: Boolean,
    ): String = try {
        FfmApplicationLifecycle.registerAssociations(
            config.applicationId,
            Path.of(config.packageRoot).toAbsolutePath().normalize().toString(),
            executable.toString(),
            config.registeredSchemes.toList().sorted().joinToString(","),
            config.registeredExtensions.toList().sorted().joinToString(","),
            remove,
        )
    } catch (error: Throwable) {
        throw KWebApplicationLifecycleException(
            code = KWebApplicationLifecycleErrorCode.REGISTRATION_FAILED,
            details = mapOf("target" to config.target.id, "operation" to if (remove) "remove" else "install"),
            message = "The native application registration provider could not complete the requested operation.",
            cause = error,
        )
    }
}
