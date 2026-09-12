package io.github.kingsword09.kwebshell.services.consent

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.services.policy.KWebConsentRequest
import io.github.kingsword09.kwebshell.services.policy.KWebConsentStatus
import io.github.kingsword09.kwebshell.services.policy.KWebOsConsentProvider
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Windows consent provider backed by the real CapabilityAccessManager consent
 * store. `HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\
 * CapabilityAccessManager\ConsentStore\<capability>\Value` is the OS's own
 * record; nothing is written and no prompt is automated.
 */
public class WindowsCapabilityAccessConsentProvider(
    private val capability: String,
) : KWebOsConsentProvider {
    init {
        if (capability.isBlank() || capability.any { it == '\\' || it == '/' || it == '\u0000' }) {
            throw KWebConfigurationException(
                code = "service.consent.capability-invalid",
                details = mapOf("capability" to capability),
                message = "A Windows capability name must be one safe path-free segment.",
            )
        }
    }

    override val facility: String = "windows.capability-access.$capability"

    override suspend fun status(request: KWebConsentRequest): KWebConsentStatus = withContext(Dispatchers.IO) {
        Arena.ofConfined().use { arena ->
            val lookup = SymbolLookup.libraryLookup("advapi32", arena)
            val regGetValue = Linker.nativeLinker().downcallHandle(
                lookup.find("RegGetValueA").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                ),
            )
            val subKey = arena.allocateFrom(SUBKEY_PREFIX + capability)
            val valueName = arena.allocateFrom("Value")
            val data = arena.allocate(DATA_CAPACITY)
            val dataSize = arena.allocate(ValueLayout.JAVA_INT)
            dataSize.set(ValueLayout.JAVA_INT, 0, DATA_CAPACITY.toInt())

            val status = regGetValue.invokeWithArguments(
                HKCU,
                subKey,
                valueName,
                RRF_RT_REG_SZ,
                MemorySegment.NULL,
                data,
                dataSize,
            ) as Long

            when (status) {
                ERROR_SUCCESS -> when (data.getString(0)) {
                    "Allow" -> KWebConsentStatus.GRANTED
                    "Deny" -> KWebConsentStatus.DENIED
                    else -> KWebConsentStatus.RESTRICTED
                }
                ERROR_FILE_NOT_FOUND -> KWebConsentStatus.NOT_CONFIGURED
                else -> KWebConsentStatus.TEMPORARILY_UNAVAILABLE
            }
        }
    }

    private companion object {
        const val HKCU: Long = 0x80000001L
        const val RRF_RT_REG_SZ: Int = 0x2
        const val ERROR_SUCCESS: Long = 0L
        const val ERROR_FILE_NOT_FOUND: Long = 2L
        const val DATA_CAPACITY: Long = 512
        const val SUBKEY_PREFIX: String =
            "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\"
    }
}

/**
 * macOS consent provider backed by the real Transparency, Consent, and Control
 * machinery. `AXIsProcessTrusted` reports the OS's current decision for the
 * Accessibility TCC service without prompting and without flipping any state.
 */
public class MacOsTccConsentProvider(
    tccService: String = "accessibility",
) : KWebOsConsentProvider {
    override val facility: String = "macos.tcc.$tccService"

    private val trustedHandle: MethodHandle by lazy {
        // Loading through the class loader namespace (System.load) is required:
        // dlopen from an arbitrary arena lookup can fail inside forking test
        // workers even though the same framework loads in a plain JVM.
        System.load(FRAMEWORK_PATH)
        val lookup = SymbolLookup.loaderLookup()
        Linker.nativeLinker().downcallHandle(
            lookup.find("AXIsProcessTrusted").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN),
        )
    }

    override suspend fun status(request: KWebConsentRequest): KWebConsentStatus = withContext(Dispatchers.IO) {
        if (trustedHandle.invoke() as Boolean) KWebConsentStatus.GRANTED else KWebConsentStatus.DENIED
    }

    private companion object {
        const val FRAMEWORK_PATH: String =
            "/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices"
    }
}

/**
 * Linux consent provider backed by the real XDG desktop portal permission
 * store. `xdg-permission-store get <table> <id>` reads the OS's own permission
 * record; a missing entry reports not-configured. The provider never writes
 * and never automates a prompt.
 */
public class LinuxPortalPermissionStoreConsentProvider(
    private val table: String,
    private val entryId: String = "kwebshell",
) : KWebOsConsentProvider {
    init {
        if (table.isBlank() || entryId.isBlank() ||
            listOf(table, entryId).any { value -> value.any { it.isWhitespace() || it == '\u0000' } }
        ) {
            throw KWebConfigurationException(
                code = "service.consent.store-invalid",
                details = mapOf("table" to table, "entry" to entryId),
                message = "A permission store table and entry must be single safe segments.",
            )
        }
    }

    override val facility: String = "linux.portal-permission-store.$table"

    override suspend fun status(request: KWebConsentRequest): KWebConsentStatus = withContext(Dispatchers.IO) {
        // The portal CLI ships at distribution-specific absolute locations and
        // is not always on PATH; a missing facility is a real not-configured.
        var process: Process? = null
        for (tool in TOOL_CANDIDATES) {
            try {
                process = ProcessBuilder(tool, "get", table, entryId)
                    .redirectErrorStream(false)
                    .start()
                break
            } catch (error: java.io.IOException) {
                continue
            }
        }
        process ?: return@withContext KWebConsentStatus.NOT_CONFIGURED
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@withContext KWebConsentStatus.TEMPORARILY_UNAVAILABLE
        }
        when (process.exitValue()) {
            0 -> when (process.inputStream.bufferedReader().readText().trim()) {
                "true" -> KWebConsentStatus.GRANTED
                "false" -> KWebConsentStatus.DENIED
                else -> KWebConsentStatus.RESTRICTED
            }
            // The portal store reports a missing entry as a lookup failure.
            else -> KWebConsentStatus.NOT_CONFIGURED
        }
    }

    public companion object {
        private val TOOL_CANDIDATES: List<String> = listOf(
            "xdg-permission-store",
            "/usr/libexec/xdg-permission-store",
            "/usr/lib/xdg-permission-store",
        )
    }
}
