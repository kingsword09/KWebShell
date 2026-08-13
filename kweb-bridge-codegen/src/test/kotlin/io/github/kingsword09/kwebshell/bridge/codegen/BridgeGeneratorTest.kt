package io.github.kingsword09.kwebshell.bridge.codegen

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BridgeGeneratorTest {
    @Test
    fun generatesKotlinTypesAndBothBrowserClients() {
        val generated = BridgeGenerator().generate(SCHEMA)

        assertContains(generated.kotlin, "public data class ProbeRequest")
        assertContains(generated.kotlin, "public suspend fun probe(request: ProbeRequest): ProbeResponse")
        assertContains(generated.typescript, "probe(request: ProbeRequest")
        assertContains(
            generated.typescript,
            "probe: (request: ProbeRequest, options?: KWebBridgeCallOptions)",
        )
        assertContains(generated.typescript, "AbortSignal")
        assertContains(generated.browserJavascript, "globalThis.__kwebBridgeQuery")
        assertContains(generated.browserJavascript, "bridge.call.timeout")
        assertContains(generated.browserJavascript, "bridge.response.invalid-json")
        assertContains(generated.browserJavascript, "if (settled) globalThis.__kwebBridgeCancel(queryId)")
        assertContains(generated.browserJavascript, "if (options.signal?.aborted) {\n        cancel();\n        return;")
    }

    @Test
    fun rejectsUnknownSchemaFieldsAndUnknownTypes() {
        assertFailsWith<IllegalArgumentException> {
            BridgeGenerator().generate(SCHEMA.replace("\"version\": 1", "\"version\": 1, \"extra\": true"))
        }
        assertFailsWith<IllegalArgumentException> {
            BridgeGenerator().generate(SCHEMA.replaceFirst("\"type\":\"string\"", "\"type\":\"any\""))
        }
    }

    @Test
    fun rejectsInvalidAndCollidingKotlinNames() {
        assertFailsWith<IllegalArgumentException> {
            BridgeGenerator().generate(SCHEMA.replace("\"name\":\"probe\"", "\"name\":\"Probe-Method\""))
        }
        assertFailsWith<IllegalArgumentException> {
            BridgeGenerator().generate(SCHEMA.replace("\"name\":\"probe\"", "\"name\":\"when\""))
        }
        assertFailsWith<IllegalArgumentException> {
            BridgeGenerator().generate(SCHEMA.replace("\"name\":\"text\"", "\"name\":\"object\""))
        }
        assertFailsWith<IllegalArgumentException> {
            BridgeGenerator().generate(SCHEMA.replace("\"name\":\"ProbeRequest\"", "\"name\":\"String\""))
        }
        assertFailsWith<IllegalArgumentException> {
            BridgeGenerator().generate(
                SCHEMA.replace("io.github.kingsword09.kwebshell.generated", "io.github.when.generated"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BridgeGenerator().generate(SCHEMA.replace("ConformanceBridge", "Promise"))
        }
    }

    @Test
    fun generatesNullableListsWithoutChangingElementNullability() {
        val generated = BridgeGenerator().generate(
            SCHEMA.replace(
                "{\"name\":\"text\",\"type\":\"string\"}",
                "{\"name\":\"text\",\"type\":\"string\",\"list\":true,\"nullable\":true}",
            ),
        )

        assertContains(generated.kotlin, "public val text: List<String>?")
        assertContains(generated.typescript, "text: readonly string[] | null;")
    }

    @Test
    fun generationIsByteForByteDeterministic() {
        val generator = BridgeGenerator()

        assertEquals(generator.generate(SCHEMA), generator.generate(SCHEMA))
    }

    private companion object {
        val SCHEMA = """
            {
              "namespace": "ConformanceBridge",
              "kotlinPackage": "io.github.kingsword09.kwebshell.generated",
              "version": 1,
              "types": [
                {"name":"ProbeRequest","fields":[{"name":"text","type":"string"}]},
                {"name":"ProbeResponse","fields":[{"name":"text","type":"string"}]}
              ],
              "methods": [{"name":"probe","request":"ProbeRequest","response":"ProbeResponse"}]
            }
        """.trimIndent()
    }
}
