package io.github.kingsword09.kwebshell.launcher

import io.github.kingsword09.kwebshell.core.KWebTarget
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Explicit jpackage/MSIX payload layout; no runtime directory guessing. */
internal data class KWebApplicationLayout(
    val packageRoot: Path,
    val applicationDirectory: Path,
    val jreDirectory: Path,
    val nativeDirectory: Path,
    val cefDirectory: Path,
    val launcherExecutable: Path,
    val relaunchExecutable: String,
) {
    init {
        require(packageRoot.isAbsolute && packageRoot == packageRoot.normalize())
        require(applicationDirectory == packageRoot.resolve("app"))
        require(jreDirectory == packageRoot.resolve("runtime"))
        require(nativeDirectory == packageRoot.resolve("native"))
        require(cefDirectory == packageRoot.resolve("cef"))
        require(!relaunchExecutable.startsWith('/') && !relaunchExecutable.contains('\\'))
        require(relaunchExecutable.split('/').none { it.isEmpty() || it == "." || it == ".." })
    }

    companion object {
        const val PACKAGE_ROOT_PROPERTY: String = "kweb.application.root"

        fun fromPackageRoot(root: Path, target: KWebTarget): KWebApplicationLayout {
            val packageRoot = root.toAbsolutePath().normalize()
            val executableName = when (target.operatingSystem.id) {
                "windows" -> "KWebShell.exe"
                "macos", "linux" -> "KWebShell"
                else -> error("Unsupported launcher target: ${target.id}")
            }
            val executable = if (target.operatingSystem.id == "macos") {
                packageRoot.resolve("MacOS").resolve(executableName)
            } else packageRoot.resolve(executableName)
            return KWebApplicationLayout(
                packageRoot = packageRoot,
                applicationDirectory = packageRoot.resolve("app"),
                jreDirectory = packageRoot.resolve("runtime"),
                nativeDirectory = packageRoot.resolve("native"),
                cefDirectory = packageRoot.resolve("cef"),
                launcherExecutable = executable,
                relaunchExecutable = packageRoot.relativize(executable).toString().replace('\\', '/'),
            )
        }

        fun discover(codeSource: URI, target: KWebTarget, explicitRoot: String? = System.getProperty(PACKAGE_ROOT_PROPERTY)): KWebApplicationLayout {
            val root = explicitRoot?.takeIf(String::isNotBlank)?.let { value ->
                val path = Path.of(value)
                require(path.isAbsolute) { "$PACKAGE_ROOT_PROPERTY must be absolute: $value" }
                path
            } ?: run {
                val location = Path.of(codeSource)
                val containing = (if (Files.isDirectory(location)) location else location.parent)
                    ?: error("The launcher code source has no containing directory: $location")
                when (containing.fileName?.toString()) {
                    "app" -> containing.parent ?: error("The launcher app directory has no package root: $containing")
                    "Contents" -> containing
                    else -> containing
                }
            }
            return fromPackageRoot(root, target)
        }
    }
}
