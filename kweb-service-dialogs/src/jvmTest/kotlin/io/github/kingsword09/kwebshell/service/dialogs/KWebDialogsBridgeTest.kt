package io.github.kingsword09.kwebshell.service.dialogs

import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.services.KWebServiceGrant
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class KWebDialogsBridgeTest {
    @Test
    fun bridgeRequiresAnExactGrantForEveryOperation() {
        val service = RecordingDialogs()
        val dispatcher = service.bridgeDispatcher(KWebServicePermissionPolicy.exact(emptySet()))

        val failure = assertFailsWith<KWebBridgeException> {
            runBlocking {
                dispatcher.dispatch(
                    """{"version":1,"method":"readFile","payload":{"handle":"${"A".repeat(43)}","offset":"0","length":1}}""",
                )
            }
        }
        assertEquals("service.permission-denied", failure.code)
        assertEquals(0, service.readCalls)
    }

    @Test
    fun bridgeMapsSelectionAndHandleOperationsWithoutPathDisclosure() {
        val service = RecordingDialogs()
        val grants = KWebDialogs.DESCRIPTOR.operations.mapTo(mutableSetOf()) {
            KWebServiceGrant(KWebDialogs.DESCRIPTOR.id, it.id)
        }
        val dispatcher = service.bridgeDispatcher(KWebServicePermissionPolicy.exact(grants))

        val selection = runBlocking {
            dispatcher.dispatch(
                """{"version":1,"method":"selectFile","payload":{"mode":"open","title":"Pick","defaultName":null,"filters":[]}}""",
            )
        }
        assertEquals(true, selection.contains("\"selected\":true"))
        assertEquals(false, selection.contains("/private/"))
        val read = runBlocking {
            dispatcher.dispatch(
                """{"version":1,"method":"readFile","payload":{"handle":"${"A".repeat(43)}","offset":"0","length":2}}""",
            )
        }
        assertEquals(true, read.contains("\"bytes\":[1,2]"))
        runBlocking {
            dispatcher.dispatch(
                """{"version":1,"method":"closeFile","payload":{"handle":"${"A".repeat(43)}"}}""",
            )
        }
        assertEquals(1, service.readCalls)
        assertEquals(1, service.closeCalls)
    }

    @Test
    fun bridgePreservesCancellation() {
        val service = RecordingDialogs().also { it.cancelReads = true }
        val dispatcher = service.bridgeDispatcher(
            KWebServicePermissionPolicy.exact(
                setOf(KWebServiceGrant(KWebDialogs.DESCRIPTOR.id, "read-file")),
            ),
        )
        assertFailsWith<CancellationException> {
            runBlocking {
                dispatcher.dispatch(
                    """{"version":1,"method":"readFile","payload":{"handle":"${"A".repeat(43)}","offset":"0","length":1}}""",
                )
            }
        }
    }

    @Test
    fun everyOperationRejectsMissingGrantsBeforeCallingTheService() = runBlocking {
        val dispatcher = RecordingDialogs().bridgeDispatcher(KWebServicePermissionPolicy.exact(emptySet()))
        val requests = listOf(
            "selectFile" to """{"mode":"open","title":"Pick","defaultName":null,"filters":[]}""",
            "readFile" to """{"handle":"${"A".repeat(43)}","offset":"0","length":1}""",
            "writeFile" to """{"handle":"${"A".repeat(43)}","offset":"0","bytes":[1]}""",
            "truncateFile" to """{"handle":"${"A".repeat(43)}","sizeBytes":"0"}""",
            "closeFile" to """{"handle":"${"A".repeat(43)}"}""",
        )
        requests.forEach { (method, payload) ->
            assertEquals("service.permission-denied", assertFailsWith<KWebBridgeException> {
                dispatcher.dispatch("""{"version":1,"method":"$method","payload":$payload}""")
            }.code)
        }
    }

    @Test
    fun rendererCannotSupplyDirectoriesAndNumericStringsAreCanonical() = runBlocking {
        val service = RecordingDialogs()
        val dispatcher = service.bridgeDispatcher(KWebServicePermissionPolicy.exact(
            KWebDialogs.DESCRIPTOR.operations.map { KWebServiceGrant("dialogs", it.id) }.toSet(),
        ))
        assertFailsWith<KWebBridgeException> {
            dispatcher.dispatch("""{"version":1,"method":"selectFile","payload":{"mode":"open","title":"Pick","defaultDirectory":"/private/path","defaultName":null,"filters":[]}}""")
        }
        for (offset in listOf("-1", "+1", "01", " 1", "1.0", "9223372036854775808")) {
            assertEquals("service.request-invalid", assertFailsWith<KWebBridgeException> {
                dispatcher.dispatch("""{"version":1,"method":"readFile","payload":{"handle":"${"A".repeat(43)}","offset":"$offset","length":1}}""")
            }.code)
        }
        assertEquals(0, service.readCalls)
    }

    @Test
    fun hostFailuresNeverCopyPathsIntoRendererMessages() = runBlocking {
        val service = RecordingDialogs()
        val dispatcher = service.bridgeDispatcher(KWebServicePermissionPolicy.exact(setOf(KWebServiceGrant("dialogs", "read-file"))))
        for (error in listOf(
            IllegalStateException("Private path: /private/secret"),
            KWebNativeException("dialog.path-invalid", emptyMap(), "Private path: /private/secret"),
        )) {
            service.readFailure = error
            val failure = assertFailsWith<KWebBridgeException> {
                dispatcher.dispatch("""{"version":1,"method":"readFile","payload":{"handle":"${"A".repeat(43)}","offset":"0","length":1}}""")
            }
            assertFalse(failure.message.orEmpty().contains("/private/"))
        }
    }

    private class RecordingDialogs : KWebDialogs {
        override val descriptor = KWebDialogs.DESCRIPTOR
        override val lifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
        var readCalls = 0
        var closeCalls = 0
        var cancelReads = false
        var readFailure: Exception? = null

        override suspend fun selectFile(request: KWebFileDialogRequest): KWebFileSelection =
            KWebFileSelection("A".repeat(43), "selected.txt", 42, request.mode)

        override suspend fun readFile(handle: String, offset: Long, length: Int): KWebFileReadResult {
            readCalls++
            if (cancelReads) throw CancellationException("cancelled")
            readFailure?.let { throw it }
            return KWebFileReadResult(listOf(1, 2).take(length), eof = false)
        }

        override suspend fun writeFile(handle: String, offset: Long, bytes: List<Int>): KWebFileWriteResult =
            KWebFileWriteResult(bytes.size)

        override suspend fun truncateFile(handle: String, sizeBytes: Long): KWebFileTruncateResult =
            KWebFileTruncateResult(sizeBytes)

        override suspend fun closeFile(handle: String) {
            closeCalls++
        }

        override fun close() {
            lifecycle.value = KWebLifecycleState.CLOSED
        }
    }
}
