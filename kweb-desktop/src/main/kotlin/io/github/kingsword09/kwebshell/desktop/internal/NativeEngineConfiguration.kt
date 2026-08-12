package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale

internal data class NativeEngineConfiguration(
    val cefRuntime: Path,
    val browserSubprocess: Path,
    val resources: Path,
    val locales: Path,
    val rootCache: Path,
    val log: Path,
    val remoteDebuggingPort: Int = 0,
) {
    internal fun validated(
        operatingSystem: String = System.getProperty("os.name"),
    ): NativeEngineConfiguration {
        val runtimePath = requireRegularFile("cefRuntime", cefRuntime)
        if (remoteDebuggingPort != 0 && remoteDebuggingPort !in 1024..65535) {
            throw KWebConfigurationException(
                code = "native.engine.remote-debugging-port-invalid",
                details = mapOf("port" to remoteDebuggingPort.toString()),
                message = "The remote debugging port must be 0 or between 1024 and 65535.",
            )
        }
        val subprocessPath = requireRegularFile("browserSubprocess", browserSubprocess)
        val resourcesPath = requireDirectory("resources", resources)
        val localesPath = requireDirectory("locales", locales)
        val rootCachePath = requireDirectory("rootCache", rootCache).toRealPathSafely("rootCache")
        val logPath = requireAbsolute("log", log)
        val logParent = logPath.parent?.toRealPathSafely("log.parent")
            ?: configurationError(
                code = "native.engine.path-parent-missing",
                field = "log",
                path = logPath,
                message = "The engine log path must have an existing parent directory.",
            )
        if (logParent != rootCachePath) {
            configurationError(
                code = "native.engine.path-mismatch",
                field = "log",
                path = logPath,
                message = "The engine log must be a direct child of the declared root cache.",
            )
        }
        if (Files.exists(logPath) && !Files.isRegularFile(logPath)) {
            configurationError(
                code = "native.engine.path-type-invalid",
                field = "log",
                path = logPath,
                message = "The engine log path must identify a regular file.",
            )
        }
        try {
            Files.newOutputStream(
                logPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            ).use { }
        } catch (error: IOException) {
            throw KWebConfigurationException(
                code = "native.engine.path-not-writable",
                details = mapOf("field" to "log", "path" to logPath.toString()),
                message = "The engine log path is not writable.",
                cause = error,
            )
        }

        val normalized = NativeEngineConfiguration(
            cefRuntime = runtimePath,
            browserSubprocess = subprocessPath,
            resources = resourcesPath,
            locales = localesPath,
            rootCache = rootCachePath,
            log = logPath,
            remoteDebuggingPort = remoteDebuggingPort,
        )
        normalized.validatePlatformLayout(operatingSystem)
        return normalized
    }

    private fun validatePlatformLayout(operatingSystem: String) {
        when {
            operatingSystem.lowercase(Locale.ROOT).startsWith("mac") -> validateMacLayout()
            operatingSystem.lowercase(Locale.ROOT).startsWith("windows") -> {
                validateFlatRuntimeLayout("libcef.dll", windows = true)
            }
            operatingSystem.lowercase(Locale.ROOT).startsWith("linux") -> {
                validateFlatRuntimeLayout("libcef.so", windows = false)
            }
            else -> throw KWebConfigurationException(
                code = "native.platform.unsupported",
                details = mapOf("osName" to operatingSystem),
                message = "The current operating system is not supported by the native CEF engine.",
            )
        }
    }

    private fun validateMacLayout() {
        val framework = cefRuntime.parent ?: layoutMismatch("cefRuntime", cefRuntime)
        val frameworks = framework.parent ?: layoutMismatch("cefRuntime", cefRuntime)
        val contents = frameworks.parent ?: layoutMismatch("cefRuntime", cefRuntime)
        val application = contents.parent ?: layoutMismatch("cefRuntime", cefRuntime)
        if (
            cefRuntime.fileName.toString() != "Chromium Embedded Framework" ||
            framework.fileName.toString().endsWith(".framework").not() ||
            frameworks.fileName.toString() != "Frameworks" ||
            contents.fileName.toString() != "Contents" ||
            application.fileName.toString().endsWith(".app").not()
        ) {
            layoutMismatch("cefRuntime", cefRuntime)
        }
        if (!sameRealPath(resources, framework.resolve("Resources")) || !sameRealPath(locales, resources)) {
            layoutMismatch("resources", resources)
        }
        if (!browserSubprocess.toRealPathSafely("browserSubprocess").startsWith(frameworks.toRealPathSafely("frameworks"))) {
            layoutMismatch("browserSubprocess", browserSubprocess)
        }
        requireRuntimeFile(resources.resolve("resources.pak"))
        requireRuntimeFile(resources.resolve("icudtl.dat"))
        requireRuntimeFile(resources.resolve("en.lproj/locale.pak"))
        if (!Files.isExecutable(browserSubprocess)) {
            configurationError(
                code = "native.engine.path-not-executable",
                field = "browserSubprocess",
                path = browserSubprocess,
                message = "The browser subprocess must be executable.",
            )
        }
    }

    private fun validateFlatRuntimeLayout(runtimeFileName: String, windows: Boolean) {
        val runtimeDirectory = cefRuntime.parent ?: layoutMismatch("cefRuntime", cefRuntime)
        if (!cefRuntime.fileName.toString().equals(runtimeFileName, ignoreCase = windows)) {
            layoutMismatch("cefRuntime", cefRuntime)
        }
        if (
            !sameRealPath(resources, runtimeDirectory) ||
            !sameRealPath(locales, runtimeDirectory.resolve("locales")) ||
            !sameRealPath(browserSubprocess.parent, runtimeDirectory)
        ) {
            layoutMismatch("resources", resources)
        }
        requireRuntimeFile(resources.resolve("resources.pak"))
        requireRuntimeFile(resources.resolve("icudtl.dat"))
        requireRuntimeFile(locales.resolve("en-US.pak"))
        if (!windows && !Files.isExecutable(browserSubprocess)) {
            configurationError(
                code = "native.engine.path-not-executable",
                field = "browserSubprocess",
                path = browserSubprocess,
                message = "The browser subprocess must be executable.",
            )
        }
    }

    private fun requireRuntimeFile(path: Path) {
        if (!Files.isRegularFile(path)) {
            configurationError(
                code = "native.engine.runtime-incomplete",
                field = "runtime",
                path = path,
                message = "The pinned CEF runtime is missing a required resource file.",
            )
        }
    }

    private fun layoutMismatch(field: String, path: Path): Nothing = configurationError(
        code = "native.engine.path-mismatch",
        field = field,
        path = path,
        message = "The declared engine paths do not belong to one pinned platform runtime layout.",
    )

    private companion object {
        fun requireRegularFile(field: String, path: Path): Path {
            val absolute = requireAbsolute(field, path)
            if (!Files.isRegularFile(absolute)) {
                configurationError(
                    code = "native.engine.path-not-found",
                    field = field,
                    path = absolute,
                    message = "The declared engine file does not exist.",
                )
            }
            return absolute
        }

        fun requireDirectory(field: String, path: Path): Path {
            val absolute = requireAbsolute(field, path)
            if (!Files.isDirectory(absolute)) {
                configurationError(
                    code = "native.engine.path-not-found",
                    field = field,
                    path = absolute,
                    message = "The declared engine directory does not exist.",
                )
            }
            return absolute
        }

        fun requireAbsolute(field: String, path: Path): Path {
            if (!path.isAbsolute) {
                configurationError(
                    code = "native.engine.path-not-absolute",
                    field = field,
                    path = path,
                    message = "Every native engine path must be absolute.",
                )
            }
            return path.normalize()
        }

        fun Path.toRealPathSafely(field: String): Path = try {
            toRealPath()
        } catch (error: IOException) {
            throw KWebConfigurationException(
                code = "native.engine.path-not-found",
                details = mapOf("field" to field, "path" to toString()),
                message = "The declared engine path cannot be canonicalized.",
                cause = error,
            )
        }

        fun sameRealPath(left: Path, right: Path): Boolean =
            left.toRealPathSafely("left") == right.toRealPathSafely("right")

        fun configurationError(
            code: String,
            field: String,
            path: Path,
            message: String,
        ): Nothing = throw KWebConfigurationException(
            code = code,
            details = mapOf("field" to field, "path" to path.toString()),
            message = message,
        )
    }
}
