/**
 * The RFC 0046 provider implemented with the JDK print service.
 *
 * `javax.print` is the platform print API of this runtime: on macOS and Linux it
 * is backed by CUPS, and on Windows by the WinSpool print subsystem. The
 * provider publishes only what that API reports, so a host without a print
 * backend yields an empty list or a typed `printers.unavailable` failure.
 */
package io.github.kingsword09.kwebshell.service.printers

import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.print.DocFlavor
import javax.print.PrintService
import javax.print.PrintServiceLookup
import javax.print.attribute.standard.Chromaticity
import javax.print.attribute.standard.Media
import javax.print.attribute.standard.MediaSize
import javax.print.attribute.standard.PrinterInfo
import javax.print.attribute.standard.PrinterIsAcceptingJobs
import javax.print.attribute.standard.PrinterLocation
import javax.print.attribute.standard.PrinterState
import javax.print.attribute.standard.Sides

/** The provider identifier recorded with every snapshot. */
public const val KWEB_PRINTERS_PROVIDER: String = "jdk-print-service"

public class JvmKWebPrinters private constructor(
    private val scope: CoroutineScope,
) : KWebPrinters {
    override val descriptor: KWebServiceDescriptor = KWebPrinters.DESCRIPTOR

    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    override suspend fun list(): KWebPrinterSnapshot = withContext(Dispatchers.IO) {
        requireOpen()
        val defaultName = try {
            PrintServiceLookup.lookupDefaultPrintService()?.name
        } catch (error: Throwable) {
            throw unavailable(error)
        }
        val services = try {
            PrintServiceLookup.lookupPrintServices(null, null)
        } catch (error: Throwable) {
            throw unavailable(error)
        } ?: throw unavailable(null)
        if (services.size > KWEB_PRINTERS_MAXIMUM) {
            throw failure(
                KWebPrintersErrorCode.LIMIT_EXCEEDED,
                mapOf("printers" to services.size.toString(), "maximum" to KWEB_PRINTERS_MAXIMUM.toString()),
            )
        }
        val printers = services.map { service -> describe(service, defaultName) }
        KWebPrinterSnapshot(provider = KWEB_PRINTERS_PROVIDER, printers = printers)
    }

    override fun close() {
        synchronized(this) {
            if (mutableLifecycle.value == KWebLifecycleState.CLOSED) return
            mutableLifecycle.value = KWebLifecycleState.CLOSING
        }
        scope.cancel()
        synchronized(this) {
            mutableLifecycle.value = KWebLifecycleState.CLOSED
        }
    }

    private fun describe(service: PrintService, defaultName: String?): KWebPrinter {
        val attributes = try {
            service.attributes
        } catch (error: Throwable) {
            throw failure(KWebPrintersErrorCode.QUERY_FAILED, mapOf("printer" to service.name), error)
        }
        val accepting = attributes.get(PrinterIsAcceptingJobs::class.java)
        val state = when (attributes.get(PrinterState::class.java)) {
            PrinterState.PROCESSING -> KWebPrinterState.PROCESSING
            PrinterState.STOPPED -> KWebPrinterState.STOPPED
            PrinterState.IDLE -> if (accepting == PrinterIsAcceptingJobs.NOT_ACCEPTING_JOBS) {
                KWebPrinterState.STOPPED
            } else {
                KWebPrinterState.IDLE
            }
            else -> if (accepting == PrinterIsAcceptingJobs.NOT_ACCEPTING_JOBS) {
                KWebPrinterState.STOPPED
            } else {
                KWebPrinterState.UNKNOWN
            }
        }
        return try {
            KWebPrinter(
                name = KWebPrinterName(service.name),
                info = attributes.get(PrinterInfo::class.java)?.toString()?.takeIf { it.isNotEmpty() },
                location = attributes.get(PrinterLocation::class.java)?.toString()?.takeIf { it.isNotEmpty() },
                isDefault = defaultName != null && service.name == defaultName,
                state = state,
                facts = factsOf(service, service.name),
            )
        } catch (error: Throwable) {
            throw failure(KWebPrintersErrorCode.CONTRACT_INVALID, mapOf("printer" to service.name), error)
        }
    }

    private fun factsOf(service: PrintService, printerName: String): KWebPrinterFacts {
        val flavor = DocFlavor.SERVICE_FORMATTED.PRINTABLE
        val color = supportedValues(service, Chromaticity::class.java, flavor, printerName)?.let { values ->
            values.any { it == Chromaticity.COLOR }
        }
        val duplex = supportedValues(service, Sides::class.java, flavor, printerName)?.let { values ->
            values.any { it == Sides.DUPLEX || it == Sides.TUMBLE }
        }
        val media = supportedValues(service, Media::class.java, flavor, printerName)
            ?.mapNotNull { value -> mediaName(value) }
            ?.distinct()
            ?.sorted()
            ?.take(KWEB_PRINTERS_MAXIMUM_MEDIA)
            ?: emptyList()
        return KWebPrinterFacts(colorSupported = color, duplexSupported = duplex, mediaSizes = media)
    }

    /**
     * The backend's attribute values, or null when it does not publish them. A
     * backend that throws for one category must not fail the whole snapshot.
     */
    private fun supportedValues(
        service: PrintService,
        category: Class<out javax.print.attribute.Attribute>,
        flavor: DocFlavor,
        printerName: String,
    ): Array<out javax.print.attribute.Attribute>? = try {
        when (val values = service.getSupportedAttributeValues(category, flavor, null)) {
            is Array<*> -> values.filterIsInstance<javax.print.attribute.Attribute>().toTypedArray()
            null -> null
            is javax.print.attribute.Attribute -> arrayOf(values)
            else -> null
        }
    } catch (error: IllegalArgumentException) {
        null
    } catch (error: Throwable) {
        throw failure(
            KWebPrintersErrorCode.QUERY_FAILED,
            mapOf("printer" to printerName, "category" to category.simpleName),
            error,
        )
    }

    private fun mediaName(value: javax.print.attribute.Attribute): String? {
        if (value !is Media) return null
        if (value is MediaSize) {
            val name = value.mediaSizeName
            if (name != null) return name.toString()
        }
        return value.toString().takeIf { it.isNotEmpty() }
    }

    private fun requireOpen() {
        val state = mutableLifecycle.value
        if (state != KWebLifecycleState.OPEN) {
            throw failure(KWebPrintersErrorCode.OWNER_CLOSED, mapOf("state" to state.name))
        }
    }

    private fun unavailable(cause: Throwable?): KWebServiceException = KWebServiceException(
        code = KWebPrintersErrorCode.UNAVAILABLE,
        details = mapOf("service" to descriptor.id, "operation" to "list"),
        message = "The host publishes no print service.",
        cause = cause,
    )

    private fun failure(
        code: String,
        details: Map<String, String> = emptyMap(),
        cause: Throwable? = null,
    ): KWebServiceException = KWebServiceException(
        code = code,
        details = details + mapOf("service" to descriptor.id, "operation" to "list"),
        message = "The printer discovery request failed with $code.",
        cause = cause,
    )

    public companion object {
        /** Opens the provider; the caller owns the returned service. */
        public fun open(): JvmKWebPrinters = JvmKWebPrinters(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
}
