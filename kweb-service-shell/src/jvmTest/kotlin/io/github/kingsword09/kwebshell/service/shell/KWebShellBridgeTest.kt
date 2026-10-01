package io.github.kingsword09.kwebshell.service.shell

import io.github.kingsword09.kwebshell.service.shell.generated.ActionResponse
import io.github.kingsword09.kwebshell.service.shell.generated.ShellBridgeDispatcher
import io.github.kingsword09.kwebshell.service.shell.generated.ShellBridgeHandler
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class KWebShellBridgeTest {
    @Test
    fun decodesExternalUriRequestPayload() = runBlocking {
        val dispatcher = ShellBridgeDispatcher(
            object : ShellBridgeHandler {
                override suspend fun openExternal(request: io.github.kingsword09.kwebshell.service.shell.generated.ExternalUriRequest): ActionResponse =
                    ActionResponse("open-external", request.uri, null)

                override suspend fun openResource(request: io.github.kingsword09.kwebshell.service.shell.generated.ResourceRequest): ActionResponse =
                    ActionResponse("open-resource", "handler-accepted", "file")

                override suspend fun revealResource(request: io.github.kingsword09.kwebshell.service.shell.generated.ResourceRequest): ActionResponse =
                    ActionResponse("reveal-resource", "handler-accepted", "file")

                override suspend fun trashResource(request: io.github.kingsword09.kwebshell.service.shell.generated.ResourceRequest): ActionResponse =
                    ActionResponse("trash-resource", "moved-to-trash", "file")
            },
        )

        val response = dispatcher.dispatch(
            """{"version":1,"method":"openExternal","payload":{"uri":"https://example.invalid"}}""",
        )
        assertEquals("{\"action\":\"open-external\",\"outcome\":\"https://example.invalid\",\"resourceKind\":null}", response)
    }
}
