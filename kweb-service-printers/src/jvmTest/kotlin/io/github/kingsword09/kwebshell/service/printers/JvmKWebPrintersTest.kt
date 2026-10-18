package io.github.kingsword09.kwebshell.service.printers

import javax.print.PrintServiceLookup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The real JDK print-service provider against this host. */
class JvmKWebPrintersTest {

    @Test
    fun theSnapshotReportsExactlyTheHostPrinters() {
        val printers = JvmKWebPrinters.open()
        try {
            val snapshot = runBlocking { printers.list() }
            assertEquals(KWEB_PRINTERS_PROVIDER, snapshot.provider)
            val backend = PrintServiceLookup.lookupPrintServices(null, null).map { it.name }.toSet()
            assertEquals(backend, snapshot.printers.map { it.name.value }.toSet())
            val defaultName = PrintServiceLookup.lookupDefaultPrintService()?.name
            assertEquals(defaultName, snapshot.defaultPrinter?.name?.value)
            assertTrue(snapshot.printers.size <= KWEB_PRINTERS_MAXIMUM)
        } finally {
            printers.close()
        }
    }

    @Test
    fun aClosedServiceRefusesFurtherQueries() {
        val printers = JvmKWebPrinters.open()
        runBlocking { printers.list() }
        printers.close()
        assertEquals(io.github.kingsword09.kwebshell.core.KWebLifecycleState.CLOSED, printers.lifecycle.value)
        val error = assertFailsWith<io.github.kingsword09.kwebshell.services.KWebServiceException> {
            runBlocking { printers.list() }
        }
        assertEquals(KWebPrintersErrorCode.OWNER_CLOSED, error.code)
        printers.close()
    }
}
