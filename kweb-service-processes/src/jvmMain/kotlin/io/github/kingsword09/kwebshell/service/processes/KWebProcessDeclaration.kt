/**
 * The host-side declaration of the packaged executables and working directories a
 * managed process may use. Resolved paths and digests never reach a renderer and
 * never appear in a retained report; the provider verifies the digest of the
 * resolved file immediately before it starts anything.
 */
package io.github.kingsword09.kwebshell.service.processes

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** One application-declared packaged executable with its verified identity. */
public class KWebProcessExecutable private constructor(
    public val id: KWebProcessExecutableId,
    public val path: Path,
    public val sha256: String,
) {
    public companion object {
        /**
         * Declares one packaged executable and binds it to the digest of the file
         * this declaration actually read. A missing or unreadable file fails here
         * instead of at launch.
         */
        public fun packaged(id: String, path: Path): KWebProcessExecutable {
            val resolved = path.toAbsolutePath().normalize()
            if (!Files.isRegularFile(resolved)) {
                invalid(
                    KWebProcessesErrorCode.EXECUTABLE_MISSING,
                    "A declared packaged executable must be a regular file.",
                )
            }
            return KWebProcessExecutable(KWebProcessExecutableId(id), resolved, digestOf(resolved))
        }

        /**
         * Declares a JVM worker entry point: the running JVM's own launcher, the
         * declared class path, and one main class. The worker is an ordinary
         * managed process with the declared limits, so a worker cannot outlive its
         * owner and its virtual reservations are never clipped by a virtual cap.
         */
        public fun jvmWorker(
            id: String,
            mainClass: String,
            classPath: List<Path>,
            jvmArguments: List<String> = emptyList(),
        ): KWebProcessExecutable {
            if (mainClass.isBlank() || !MAIN_CLASS.matches(mainClass)) {
                invalid(KWebProcessesErrorCode.ARGUMENT_INVALID, "A JVM worker requires a binary class name.")
            }
            if (classPath.isEmpty()) {
                invalid(KWebProcessesErrorCode.ARGUMENT_INVALID, "A JVM worker requires at least one class path entry.")
            }
            val launcher = ProcessHandle.current().info().command().orElse(null)?.let { Path.of(it) }
                ?: javaHomeLauncher()
                ?: invalid(
                    KWebProcessesErrorCode.PLATFORM_UNAVAILABLE,
                    "The running JVM does not publish its own launcher path.",
                )
            if (!Files.isRegularFile(launcher)) {
                invalid(
                    KWebProcessesErrorCode.PLATFORM_UNAVAILABLE,
                    "The running JVM launcher is not a regular file.",
                )
            }
            val declared = mutableListOf<String>()
            declared += jvmArguments
            declared += listOf("-cp", classPath.joinToString(java.io.File.pathSeparator) { it.toAbsolutePath().normalize().toString() })
            declared += mainClass
            return KWebProcessExecutable(KWebProcessExecutableId(id), launcher, digestOf(launcher)).also {
                it.workerArguments = declared.toList()
            }
        }

        private fun javaHomeLauncher(): Path? {
            val home = System.getProperty("java.home") ?: return null
            val executable = if (System.getProperty("os.name").lowercase().startsWith("windows")) "java.exe" else "java"
            val candidate = Path.of(home, "bin", executable)
            return if (Files.isRegularFile(candidate)) candidate else null
        }

        /** The lowercase hex SHA-256 of one file. */
        public fun digestOf(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { stream ->
                val buffer = ByteArray(65536)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        private val MAIN_CLASS = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
    }

    /**
     * The launcher arguments a JVM worker declaration implies. Empty for a
     * packaged executable, whose arguments are declared by each specification.
     */
    public var workerArguments: List<String> = emptyList()
        private set
}

/** One application-declared working directory. */
public class KWebProcessWorkingDirectory(public val id: KWebProcessWorkingDirectoryId, path: Path) {
    public val path: Path = path.toAbsolutePath().normalize()

    init {
        if (!Files.isDirectory(this.path)) {
            invalid(
                KWebProcessesErrorCode.WORKING_DIRECTORY_INVALID,
                "A declared working directory must be an existing directory.",
            )
        }
    }
}

/** The complete host-side process declaration of one application. */
public data class KWebProcessDeclaration(
    public val applicationId: String,
    public val executables: List<KWebProcessExecutable>,
    public val workingDirectories: List<KWebProcessWorkingDirectory>,
) {
    init {
        if (applicationId.isBlank()) {
            invalid(KWebProcessesErrorCode.ID_INVALID, "A process declaration requires an application identity.")
        }
        val executableIds = executables.map { it.id.value }
        if (executableIds.size != executableIds.toSet().size) {
            invalid(KWebProcessesErrorCode.EXECUTABLE_UNKNOWN, "A declaration cannot repeat an executable identifier.")
        }
        val directoryIds = workingDirectories.map { it.id.value }
        if (directoryIds.size != directoryIds.toSet().size) {
            invalid(
                KWebProcessesErrorCode.WORKING_DIRECTORY_INVALID,
                "A declaration cannot repeat a working directory identifier.",
            )
        }
    }

    public fun executable(id: KWebProcessExecutableId): KWebProcessExecutable? = executables.singleOrNull { it.id == id }

    public fun workingDirectory(id: KWebProcessWorkingDirectoryId): KWebProcessWorkingDirectory? =
        workingDirectories.singleOrNull { it.id == id }
}
