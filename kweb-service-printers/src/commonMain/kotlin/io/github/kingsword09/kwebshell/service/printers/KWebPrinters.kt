/**
 * The RFC 0046 printer-discovery contract.
 *
 * The service reports exactly the printers and facts the platform print
 * subsystem publishes for this host. A fact the backend does not publish stays
 * absent instead of being defaulted, an empty printer list is a valid
 * observation, and a host without a print subsystem fails with
 * `printers.unavailable` rather than inventing a device.
 *
 * This is a discovery contract only: it does not submit, spool, or cancel a
 * print job. Print submission remains the RFC 0026 objective.
 */
package io.github.kingsword09.kwebshell.service.printers

import io.github.kingsword09.kwebshell.core.KWebArchitecture
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebOperatingSystem
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.services.KWebNativeService
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceException
import io.github.kingsword09.kwebshell.services.KWebServiceKey
import io.github.kingsword09.kwebshell.services.KWebServiceOperationDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.KWebServiceVersion
import io.github.kingsword09.kwebshell.services.KWebServiceVersionRange
import kotlinx.coroutines.flow.StateFlow

/** The most printers one snapshot may report before the query is refused. */
public const val KWEB_PRINTERS_MAXIMUM: Int = 256

/** The most media names one printer may report. */
public const val KWEB_PRINTERS_MAXIMUM_MEDIA: Int = 32

/** One printer identifier: the exact queue or device name the backend uses. */
public class KWebPrinterName(public val value: String) {
    init {
        val bytes = value.encodeToByteArray().size
        if (value.isEmpty() || value.contains('\u0000') || bytes > 256) {
            invalid(
                KWebPrintersErrorCode.CONTRACT_INVALID,
                "A printer name must be 1..256 UTF-8 bytes without NUL.",
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is KWebPrinterName && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** The state the backend publishes for one printer. */
public enum class KWebPrinterState {
    IDLE,
    PROCESSING,
    STOPPED,

    /** The backend published no usable state attribute. */
    UNKNOWN,
}

/**
 * The facts the backend publishes for one printer. Each fact is nullable: a
 * backend that does not publish it leaves it absent, and `mediaSizes` is empty
 * when the backend publishes no media list.
 */
public data class KWebPrinterFacts(
    public val colorSupported: Boolean?,
    public val duplexSupported: Boolean?,
    public val mediaSizes: List<String>,
) {
    init {
        if (mediaSizes.size > KWEB_PRINTERS_MAXIMUM_MEDIA) {
            invalid(
                KWebPrintersErrorCode.CONTRACT_INVALID,
                "A printer must not report more than $KWEB_PRINTERS_MAXIMUM_MEDIA media names.",
            )
        }
        mediaSizes.forEach { name ->
            val bytes = name.encodeToByteArray().size
            if (name.isEmpty() || name.contains('\u0000') || bytes > 64) {
                invalid(
                    KWebPrintersErrorCode.CONTRACT_INVALID,
                    "A media name must be 1..64 UTF-8 bytes without NUL.",
                )
            }
        }
        if (mediaSizes.distinct().size != mediaSizes.size) {
            invalid(KWebPrintersErrorCode.CONTRACT_INVALID, "A printer cannot report duplicate media names.")
        }
    }

    public companion object {
        /** The declared empty fact set of a backend that publishes nothing. */
        public val ABSENT: KWebPrinterFacts = KWebPrinterFacts(
            colorSupported = null,
            duplexSupported = null,
            mediaSizes = emptyList(),
        )
    }
}

/** One printer exactly as the backend described it. */
public data class KWebPrinter(
    public val name: KWebPrinterName,
    public val info: String?,
    public val location: String?,
    public val isDefault: Boolean,
    public val state: KWebPrinterState,
    public val facts: KWebPrinterFacts = KWebPrinterFacts.ABSENT,
) {
    init {
        listOf(info, location).forEach { text ->
            if (text != null && (text.contains('\u0000') || text.encodeToByteArray().size > 512)) {
                invalid(
                    KWebPrintersErrorCode.CONTRACT_INVALID,
                    "A printer description must be at most 512 UTF-8 bytes without NUL.",
                )
            }
        }
    }
}

/** One complete observation of the host's printers. */
public data class KWebPrinterSnapshot(
    public val provider: String,
    public val printers: List<KWebPrinter>,
) {
    init {
        if (provider.isEmpty() || provider.contains('\u0000') || provider.encodeToByteArray().size > 64) {
            invalid(KWebPrintersErrorCode.CONTRACT_INVALID, "A printer provider identifier is invalid.")
        }
        if (printers.size > KWEB_PRINTERS_MAXIMUM) {
            invalid(
                KWebPrintersErrorCode.CONTRACT_INVALID,
                "A snapshot must not report more than $KWEB_PRINTERS_MAXIMUM printers.",
            )
        }
        if (printers.map { it.name.value }.distinct().size != printers.size) {
            invalid(KWebPrintersErrorCode.CONTRACT_INVALID, "A snapshot cannot report duplicate printer names.")
        }
        if (printers.count { it.isDefault } > 1) {
            invalid(KWebPrintersErrorCode.CONTRACT_INVALID, "A snapshot cannot report more than one default printer.")
        }
    }

    /** The default printer, or null when the host has none. */
    public val defaultPrinter: KWebPrinter? get() = printers.singleOrNull { it.isDefault }
}

/** The application-scoped printer-discovery service. */
public interface KWebPrinters : KWebNativeService {
    override val descriptor: KWebServiceDescriptor

    override val lifecycle: StateFlow<KWebLifecycleState>

    /** Observes the host's printers; an empty list is a valid observation. */
    public suspend fun list(): KWebPrinterSnapshot

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "printers",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.APPLICATION,
            operations = setOf(
                KWebServiceOperationDescriptor(
                    id = "list",
                    schemaVersion = 1,
                    rendererPermission = null,
                    requiresUserGesture = false,
                ),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = setOf(
                KWebTarget(KWebOperatingSystem.WINDOWS, KWebArchitecture.X64),
                KWebTarget(KWebOperatingSystem.MACOS, KWebArchitecture.ARM64),
                KWebTarget(KWebOperatingSystem.LINUX, KWebArchitecture.X64),
            ),
        )

        public val Key: KWebServiceKey<KWebPrinters> = object : KWebServiceKey<KWebPrinters> {
            override val id: String = DESCRIPTOR.id
            override val contract: KWebServiceVersionRange = KWebServiceVersionRange.exact(DESCRIPTOR.version)
        }
    }
}

/** Stable printer-discovery error identifiers. */
public object KWebPrintersErrorCode {
    public const val UNAVAILABLE: String = "printers.unavailable"
    public const val QUERY_FAILED: String = "printers.query-failed"
    public const val LIMIT_EXCEEDED: String = "printers.limit-exceeded"
    public const val CONTRACT_INVALID: String = "printers.contract-invalid"
    public const val OWNER_CLOSED: String = "service.owner-closed"
}

internal fun invalid(code: String, message: String): Nothing = throw KWebConfigurationException(
    code = code,
    details = emptyMap(),
    message = message,
)
