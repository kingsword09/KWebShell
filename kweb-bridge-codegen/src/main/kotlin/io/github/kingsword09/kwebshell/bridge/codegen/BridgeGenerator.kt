package io.github.kingsword09.kwebshell.bridge.codegen

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

public data class BridgeGeneratedSources(
    public val kotlin: String,
    public val typescript: String,
    public val browserJavascript: String,
)

public class BridgeGenerator public constructor() {
    public fun generate(schemaText: String): BridgeGeneratedSources {
        val schema = try {
            SCHEMA_JSON.decodeFromString<BridgeSchema>(schemaText)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("The bridge schema is invalid: ${error.message}", error)
        }
        validate(schema)
        return BridgeGeneratedSources(
            kotlin = generateKotlin(schema),
            typescript = generateTypescript(schema),
            browserJavascript = generateBrowserJavascript(schema),
        )
    }

    public fun generate(schema: Path, outputDirectory: Path): List<Path> {
        val sources = generate(Files.readString(schema))
        Files.createDirectories(outputDirectory)
        val outputs = listOf(
            outputDirectory.resolve("${schemaBaseName(schema)}Bridge.kt") to sources.kotlin,
            outputDirectory.resolve("${schemaBaseName(schema)}Bridge.ts") to sources.typescript,
            outputDirectory.resolve("${schemaBaseName(schema)}Bridge.js") to sources.browserJavascript,
        )
        outputs.forEach { (path, content) -> Files.writeString(path, content) }
        return outputs.map { it.first }
    }

    private fun validate(schema: BridgeSchema) {
        require(schema.version == 1) { "Only bridge schema version 1 is supported." }
        require(TYPE_NAME.matches(schema.namespace)) { "The bridge namespace is invalid." }
        require(schema.namespace !in JAVASCRIPT_GLOBAL_NAMES) {
            "The bridge namespace collides with a JavaScript global."
        }
        require(PACKAGE_NAME.matches(schema.kotlinPackage)) { "The Kotlin package is invalid." }
        require(schema.kotlinPackage.split('.').none { it in KOTLIN_KEYWORDS }) {
            "The Kotlin package contains a reserved keyword."
        }
        require(schema.types.isNotEmpty()) { "The bridge schema must declare at least one type." }
        require(schema.methods.isNotEmpty()) { "The bridge schema must declare at least one method." }
        requireUnique(schema.types.map { it.name }, "type")
        requireUnique(schema.methods.map { it.name }, "method")
        val generatedTypeNames = setOf(
            "${schema.namespace}Handler",
            "${schema.namespace}Dispatcher",
            "${schema.namespace}Client",
            "KWebBridgeCallOptions",
            "KWebBridgeError",
        )
        val typeNames = schema.types.mapTo(mutableSetOf()) { type ->
            require(TYPE_NAME.matches(type.name)) { "Invalid bridge type name '${type.name}'." }
            require(type.name !in KOTLIN_TYPE_NAMES && type.name !in generatedTypeNames) {
                "Bridge type name '${type.name}' collides with a generated or Kotlin type."
            }
            require(type.fields.isNotEmpty()) { "Bridge type '${type.name}' has no fields." }
            requireUnique(type.fields.map { it.name }, "field in ${type.name}")
            type.fields.forEach { field ->
                require(FIELD_NAME.matches(field.name)) { "Invalid bridge field name '${field.name}'." }
                require(field.name !in KOTLIN_KEYWORDS) {
                    "Bridge field name '${type.name}.${field.name}' is a Kotlin keyword."
                }
            }
            type.name
        }
        schema.types.forEach { type ->
            type.fields.forEach { field ->
                require(field.type in PRIMITIVES || field.type in typeNames) {
                    "Bridge field '${type.name}.${field.name}' has unknown type '${field.type}'."
                }
            }
        }
        schema.methods.forEach { method ->
            require(METHOD_NAME.matches(method.name)) { "Invalid bridge method name '${method.name}'." }
            require(method.name !in KOTLIN_KEYWORDS) {
                "Bridge method name '${method.name}' is a Kotlin keyword."
            }
            require(method.request in typeNames) { "Unknown request type '${method.request}'." }
            require(method.response in typeNames) { "Unknown response type '${method.response}'." }
        }
        val methodNames = schema.methods.mapTo(mutableSetOf()) { it.name }
        val streamNames = mutableSetOf<String>()
        schema.streams.forEach { stream ->
            require(METHOD_NAME.matches(stream.name)) { "Invalid bridge stream name '${stream.name}'." }
            require(stream.name !in KOTLIN_KEYWORDS) {
                "Bridge stream name '${stream.name}' is a Kotlin keyword."
            }
            require(stream.name !in methodNames) {
                "Bridge stream '${stream.name}' collides with a declared method."
            }
            require(stream.name !in streamNames) { "Duplicate stream name '${stream.name}'." }
            streamNames += stream.name
            require(stream.request in typeNames) { "Unknown stream request type '${stream.request}'." }
            if (!stream.binary) {
                require(stream.chunk in typeNames) { "Unknown stream chunk type '${stream.chunk}'." }
                require(stream.maxAggregateBytes == null) {
                    "A non-binary stream cannot declare maxAggregateBytes."
                }
            }
            require(stream.capacity in 1..65536) {
                "Stream '${stream.name}' capacity must be an integer from 1 through 65536."
            }
            require(stream.schemaVersion == 1) { "Only stream schema version 1 is supported." }
            if (stream.maxAggregateBytes != null) {
                require(stream.maxAggregateBytes >= 1) {
                    "Stream '${stream.name}' maxAggregateBytes must be at least one byte."
                }
            }
        }
        streamNames.forEach { name ->
            require((name + "Ack").length <= 64) {
                "Stream '$name' acknowledgement method name exceeds 64 characters."
            }
            require((name + "Ack") !in methodNames) {
                "Stream '$name' acknowledgement collides with a declared method."
            }
        }
    }

    private fun generateKotlin(schema: BridgeSchema): String = buildString {
        appendLine("// Generated by KWebShell bridge codegen. Do not edit.")
        appendLine("package ${schema.kotlinPackage}")
        appendLine()
        appendLine("import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher")
        appendLine("import io.github.kingsword09.kwebshell.bridge.KWebBridgeException")
        appendLine("import io.github.kingsword09.kwebshell.bridge.KWebBridgeProtocol")
        appendLine("import io.github.kingsword09.kwebshell.bridge.KWebBridgeRequest")
        appendLine("import io.github.kingsword09.kwebshell.bridge.KWebStreamBridgeDispatcher")
        appendLine("import io.github.kingsword09.kwebshell.bridge.KWebStreamBridgeErrorCode")
        appendLine("import io.github.kingsword09.kwebshell.bridge.KWebStreamCreditGate")
        appendLine("import io.github.kingsword09.kwebshell.bridge.KWebStreamFrame")
        appendLine("import io.github.kingsword09.kwebshell.bridge.KWebStreamFrameKind")
        appendLine("import io.github.kingsword09.kwebshell.bridge.KWebStreamFrameSink")
        appendLine("import io.github.kingsword09.kwebshell.bridge.KWebStreamProtocol")
        appendLine("import io.github.kingsword09.kwebshell.bridge.KWEB_STREAM_COMPLETED")
        appendLine("import kotlinx.coroutines.CancellationException")
        appendLine("import kotlinx.coroutines.flow.Flow")
        appendLine("import kotlinx.serialization.SerializationException")
        appendLine("import kotlinx.serialization.Serializable")
        appendLine("import kotlinx.serialization.decodeFromString")
        appendLine("import kotlinx.serialization.encodeToString")
        appendLine("import kotlinx.serialization.json.buildJsonObject")
        appendLine("import kotlinx.serialization.json.encodeToJsonElement")
        appendLine("import kotlinx.serialization.json.put")
        appendLine()
        schema.types.forEach { type ->
            appendLine("@Serializable")
            appendLine("public data class ${type.name}(")
            type.fields.forEach { field ->
                appendLine("    public val ${field.name}: ${kotlinType(field)},")
            }
            appendLine(")")
            appendLine()
        }
        appendLine("public interface ${schema.namespace}Handler {")
        schema.methods.forEach { method ->
            appendLine("    public suspend fun ${method.name}(request: ${method.request}): ${method.response}")
        }
        appendLine("}")
        appendLine()
        if (schema.streams.isNotEmpty()) {
            appendLine("@kotlinx.serialization.Serializable")
            appendLine("public class StreamAck(")
            appendLine("    public val granted: Int,")
            appendLine(")")
            appendLine()
            appendLine("public interface ${schema.namespace}StreamHandler {")
            schema.streams.forEach { stream ->
                val chunk = if (stream.binary) "ByteArray" else stream.chunk
                appendLine("    public fun ${stream.name}(request: ${stream.request}): kotlinx.coroutines.flow.Flow<$chunk>")
            }
            appendLine("}")
            appendLine()
        }
        appendLine("public class ${schema.namespace}Dispatcher(")
        appendLine("    private val handler: ${schema.namespace}Handler,")
        appendLine(") : KWebBridgeDispatcher {")
        appendLine("    override suspend fun dispatch(requestJson: String): String {")
        appendLine("        val request = KWebBridgeProtocol.decodeRequest(requestJson)")
        appendLine("        return when (request.method) {")
        schema.methods.forEach { method ->
            appendLine("            \"${method.name}\" -> KWebBridgeProtocol.json.encodeToString(")
            appendLine("                handler.${method.name}(")
            appendLine("                    decodePayload<${method.request}>(request.payload.toString()),")
            appendLine("                ),")
            appendLine("            )")
        }
        appendLine("            else -> throw KWebBridgeException(")
        appendLine("                code = \"bridge.method.unknown\",")
        appendLine("                message = \"Unknown bridge method '${'$'}{request.method}'.\",")
        appendLine("            )")
        appendLine("        }")
        appendLine("    }")
        appendLine()
        appendLine("    private inline fun <reified T> decodePayload(payload: String): T = try {")
        appendLine("        KWebBridgeProtocol.json.decodeFromString<T>(payload)")
        appendLine("    } catch (error: SerializationException) {")
        appendLine("        throw KWebBridgeException(")
        appendLine("            code = \"service.request-invalid\",")
        appendLine("            message = \"The bridge request payload is invalid.\",")
        appendLine("            cause = error,")
        appendLine("        )")
        appendLine("    }")
        appendLine("}")
        append(generateKotlinStreams(schema))
    }

    /**
     * Generates the typed stream dispatcher: one persistent-query stream per
     * declared stream, credit-gated frame publication, and acknowledgement
     * handling. Binary streams carry base64 chunks bounded by maxAggregateBytes.
     */
    private fun generateKotlinStreams(schema: BridgeSchema): String {
        if (schema.streams.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("public class ${schema.namespace}StreamDispatcher(")
            appendLine("    private val handler: ${schema.namespace}StreamHandler,")
            appendLine(") : KWebStreamBridgeDispatcher {")
            appendLine("    public override val streamMethods: Set<String> = setOf(")
            schema.streams.forEach { stream -> appendLine("        \"${stream.name}\",") }
            appendLine("    )")
            appendLine()
            appendLine("    public override val acknowledgedMethods: Set<String> = setOf(")
            schema.streams.forEach { stream -> appendLine("        \"${stream.name}Ack\",") }
            appendLine("    )")
            appendLine()
            appendLine("    public override fun capacity(method: String): Int = when (method) {")
            schema.streams.forEach { stream ->
                appendLine("        \"${stream.name}\" -> ${stream.capacity}")
            }
            appendLine("        else -> throw KWebBridgeException(")
            appendLine("            code = KWebStreamBridgeErrorCode.ACK_UNKNOWN_STREAM,")
            appendLine("            message = \"Unknown stream method '${'$'}method'.\",")
            appendLine("        )")
            appendLine("    }")
            appendLine()
            appendLine("    public override suspend fun acknowledge(request: KWebBridgeRequest, gate: KWebStreamCreditGate) {")
            appendLine("        when (request.method) {")
            schema.streams.forEach { stream ->
                appendLine("            \"${stream.name}Ack\" -> gate.grant(decodePayload<StreamAck>(request.payload.toString()).granted)")
            }
            appendLine("            else -> throw KWebBridgeException(")
            appendLine("                code = KWebStreamBridgeErrorCode.ACK_UNKNOWN_STREAM,")
            appendLine("                message = \"Unknown stream acknowledgement '${'$'}{request.method}'.\",")
            appendLine("            )")
            appendLine("        }")
            appendLine("    }")
            appendLine()
            appendLine("    public override suspend fun dispatchStream(")
            appendLine("        request: KWebBridgeRequest,")
            appendLine("        sink: KWebStreamFrameSink,")
            appendLine("        gate: KWebStreamCreditGate,")
            appendLine("    ) {")
            appendLine("        when (request.method) {")
            schema.streams.forEach { stream ->
                val chunkType = if (stream.binary) "ByteArray" else stream.chunk
                appendLine("            \"${stream.name}\" -> {")
                appendLine("                val decoded = decodePayload<${stream.request}>(request.payload.toString())")
                appendLine("                var sequence = 1L")
                appendLine("                handler.${stream.name}(decoded).collect { chunk ->")
                appendLine("                    if (!gate.acquire()) {")
                appendLine("                        throw CancellationException(KWebStreamBridgeErrorCode.TRANSPORT_CLOSED)")
                appendLine("                    }")
                if (stream.binary) {
                    appendLine("                    val payload = buildJsonObject {")
                    appendLine("                        put(\"bytes\", java.util.Base64.getEncoder().encodeToString(chunk))")
                    appendLine("                    }")
                } else {
                    appendLine("                    val payload = KWebBridgeProtocol.json.encodeToJsonElement(chunk)")
                }
                appendLine("                    sink.send(")
                appendLine("                        KWebStreamProtocol.encode(")
                appendLine("                            KWebStreamFrame(")
                appendLine("                                version = KWebStreamProtocol.VERSION,")
                appendLine("                                sequence = sequence,")
                appendLine("                                kind = KWebStreamFrameKind.DATA,")
                appendLine("                                payload = payload,")
                appendLine("                            ),")
                appendLine("                        ),")
                appendLine("                    )")
                appendLine("                    sequence += 1")
                appendLine("                }")
                appendLine("                sink.complete(")
                appendLine("                    KWebStreamProtocol.encode(")
                appendLine("                        KWebStreamFrame(")
                appendLine("                            version = KWebStreamProtocol.VERSION,")
                appendLine("                            sequence = 0,")
                appendLine("                            kind = KWebStreamFrameKind.TERMINAL,")
                appendLine("                            reason = KWEB_STREAM_COMPLETED,")
                appendLine("                        ),")
                appendLine("                    ),")
                appendLine("                )")
                appendLine("            }")
            }
            appendLine("            else -> throw KWebBridgeException(")
            appendLine("                code = \"bridge.method.unknown\",")
            appendLine("                message = \"Unknown stream method '${'$'}{request.method}'.\",")
            appendLine("            )")
            appendLine("        }")
            appendLine("    }")
            appendLine()
            appendLine("    private inline fun <reified T> decodePayload(payload: String): T = try {")
            appendLine("        KWebBridgeProtocol.json.decodeFromString<T>(payload)")
            appendLine("    } catch (error: SerializationException) {")
            appendLine("        throw KWebBridgeException(")
            appendLine("            code = \"service.request-invalid\",")
            appendLine("            message = \"The stream request payload is invalid.\",")
            appendLine("            cause = error,")
            appendLine("        )")
            appendLine("    }")
            appendLine("}")
        }
    }

    private fun generateTypescript(schema: BridgeSchema): String = buildString {
        appendLine("// Generated by KWebShell bridge codegen. Do not edit.")
        schema.types.forEach { type ->
            appendLine("export interface ${type.name} {")
            type.fields.forEach { field ->
                appendLine("  ${field.name}: ${typescriptType(field)};")
            }
            appendLine("}")
            appendLine()
        }
        appendLine("export interface KWebBridgeCallOptions {")
        appendLine("  readonly signal?: AbortSignal;")
        appendLine("  readonly timeoutMs?: number;")
        appendLine("}")
        appendLine()
        appendLine("export class KWebBridgeError extends Error {")
        appendLine("  public constructor(public readonly code: string, message: string) {")
        appendLine("    super(message);")
        appendLine("    this.name = \"KWebBridgeError\";")
        appendLine("  }")
        appendLine("}")
        appendLine()
        if (schema.streams.isNotEmpty()) {
            appendLine("export interface KWebBridgeStreamCallOptions {")
            appendLine("  readonly signal?: AbortSignal;")
            appendLine("  readonly idleTimeoutMs?: number;")
            appendLine("}")
            appendLine()
            appendLine("export interface KWebBridgeStream<Chunk> extends AsyncIterable<Chunk> {")
            appendLine("  readonly close: () => void;")
            appendLine("}")
            appendLine()
        }
        appendLine("export interface ${schema.namespace}Client {")
        schema.methods.forEach { method ->
            appendLine("  ${method.name}(request: ${method.request}, options?: KWebBridgeCallOptions): Promise<${method.response}>;")
        }
        schema.streams.forEach { stream ->
            val chunk = if (stream.binary) "{ bytes: string }" else stream.chunk
            appendLine("  open${stream.name.replaceFirstChar { it.uppercaseChar() }}(request: ${stream.request}, options?: KWebBridgeStreamCallOptions): KWebBridgeStream<$chunk>;")
        }
        appendLine("}")
        appendLine()
        appendLine("declare global {")
        appendLine("  interface Window {")
        appendLine("    __kwebBridgeQuery(options: {")
        appendLine("      request: string;")
        appendLine("      persistent: boolean;")
        appendLine("      onSuccess(response: string): void;")
        appendLine("      onFailure(errorCode: number, errorMessage: string): void;")
        appendLine("    }): number;")
        appendLine("    __kwebBridgeCancel(requestId: number): void;")
        appendLine("  }")
        appendLine("}")
        appendLine()
        appendLine("${typescriptRuntime(schema)}")
    }

    private fun generateBrowserJavascript(schema: BridgeSchema): String = buildString {
        appendLine("// Generated by KWebShell bridge codegen. Do not edit.")
        appendLine("(() => {")
        appendLine("  \"use strict\";")
        appendLine("  class KWebBridgeError extends Error {")
        appendLine("    constructor(code, message) {")
        appendLine("      super(message);")
        appendLine("      this.name = \"KWebBridgeError\";")
        appendLine("      this.code = code;")
        appendLine("    }")
        appendLine("  }")
        appendLine("  ${javascriptInvokeFunction().prependIndent("  ").trimStart()}")
        appendLine("  const createClient = () => Object.freeze({")
        schema.methods.forEach { method ->
            appendLine("    ${method.name}: (request, options) => invoke(\"${method.name}\", request, options),")
        }
        schema.streams.forEach { stream ->
            appendLine("    open${stream.name.replaceFirstChar { it.uppercaseChar() }}: (request, options) => openStream(\"${stream.name}\", \"${stream.name}Ack\", request, ${stream.capacity}, ${stream.binary.toString()}, ${stream.maxAggregateBytes?.toString() ?: "null"}, options),")
        }
        appendLine("  });")
        append(generateBrowserStreamJavascript(schema))
        appendLine("  Object.defineProperty(globalThis, \"${schema.namespace}\", {")
        appendLine("    configurable: false,")
        appendLine("    enumerable: false,")
        appendLine("    writable: false,")
        appendLine("    value: Object.freeze({ createClient, KWebBridgeError }),")
        appendLine("  });")
        appendLine("})();")
    }

    private fun typescriptRuntime(schema: BridgeSchema): String = buildString {
        appendLine("function invoke<T>(method: string, payload: unknown, options: KWebBridgeCallOptions = {}): Promise<T> {")
        appendLine("  return new Promise<T>((resolve, reject) => {")
        appendLine("    if (!Number.isSafeInteger(options.timeoutMs ?? 30000) || (options.timeoutMs ?? 30000) <= 0 || (options.timeoutMs ?? 30000) > 2147483647) {")
        appendLine("      reject(new KWebBridgeError(\"bridge.timeout.invalid\", \"timeoutMs must be an integer from 1 through 2147483647.\"));")
        appendLine("      return;")
        appendLine("    }")
        appendLine("    if (options.signal?.aborted) {")
        appendLine("      reject(new KWebBridgeError(\"bridge.call.cancelled\", \"The bridge call was cancelled.\"));")
        appendLine("      return;")
        appendLine("    }")
        appendLine("    let settled = false;")
        appendLine("    let queryId = 0;")
        appendLine("    const finish = (operation: () => void): void => {")
        appendLine("      if (settled) return;")
        appendLine("      settled = true;")
        appendLine("      clearTimeout(timer);")
        appendLine("      options.signal?.removeEventListener(\"abort\", cancel);")
        appendLine("      operation();")
        appendLine("    };")
        appendLine("    const cancel = (): void => finish(() => {")
        appendLine("      if (queryId !== 0) window.__kwebBridgeCancel(queryId);")
        appendLine("      reject(new KWebBridgeError(\"bridge.call.cancelled\", \"The bridge call was cancelled.\"));")
        appendLine("    });")
        appendLine("    const timer = setTimeout(() => finish(() => {")
        appendLine("      if (queryId !== 0) window.__kwebBridgeCancel(queryId);")
        appendLine("      reject(new KWebBridgeError(\"bridge.call.timeout\", \"The bridge call timed out.\"));")
        appendLine("    }), options.timeoutMs ?? 30000);")
        appendLine("    options.signal?.addEventListener(\"abort\", cancel, { once: true });")
        appendLine("    if (options.signal?.aborted) {")
        appendLine("      cancel();")
        appendLine("      return;")
        appendLine("    }")
        appendLine("    try {")
        appendLine("      queryId = window.__kwebBridgeQuery({")
        appendLine("      request: JSON.stringify({ version: 1, method, payload }),")
        appendLine("      persistent: false,")
        appendLine("        onSuccess: response => finish(() => {")
        appendLine("          try { resolve(JSON.parse(response) as T); }")
        appendLine("          catch { reject(new KWebBridgeError(\"bridge.response.invalid-json\", \"The bridge response is not valid JSON.\")); }")
        appendLine("        }),")
        appendLine("        onFailure: (_errorCode, errorMessage) => finish(() => {")
        appendLine("        let failure: { code?: string; message?: string } = {};")
        appendLine("        try { failure = JSON.parse(errorMessage); } catch { failure = {}; }")
        appendLine("        reject(new KWebBridgeError(failure.code ?? \"bridge.transport.failed\", failure.message ?? errorMessage));")
        appendLine("        }),")
        appendLine("      });")
        appendLine("      if (settled) window.__kwebBridgeCancel(queryId);")
        appendLine("    } catch (error) {")
        appendLine("      finish(() => reject(new KWebBridgeError(\"bridge.transport.failed\", error instanceof Error ? error.message : String(error))));")
        appendLine("    }")
        appendLine("  });")
        appendLine("}")
        appendLine()
        appendLine("export function create${schema.namespace}Client(): ${schema.namespace}Client {")
        appendLine("  return Object.freeze({")
        schema.methods.forEach { method ->
            appendLine(
                "    ${method.name}: (request: ${method.request}, options?: KWebBridgeCallOptions) => " +
                    "invoke<${method.response}>(\"${method.name}\", request, options),",
            )
        }
        schema.streams.forEach { stream ->
            appendLine(
                "    open${stream.name.replaceFirstChar { it.uppercaseChar() }}: (request: ${stream.request}, options?: KWebBridgeStreamCallOptions) => " +
                    "openStream<${if (stream.binary) "{ bytes: string }" else stream.chunk}>(\"${stream.name}\", \"${stream.name}Ack\", request, ${stream.capacity}, ${stream.binary.toString()}, " +
                    "${stream.maxAggregateBytes?.toString() ?: "null"}, options),",
            )
        }
        appendLine("  });")
        appendLine("}")
        append(typescriptStreamRuntime(schema))
    }.trimEnd()

    private fun javascriptInvokeFunction(): String = """
        function invoke(method, payload, options = {}) {
          return new Promise((resolve, reject) => {
            const timeoutMs = options.timeoutMs ?? 30000;
            if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0 || timeoutMs > 2147483647) {
              reject(new KWebBridgeError("bridge.timeout.invalid", "timeoutMs must be an integer from 1 through 2147483647."));
              return;
            }
            if (options.signal?.aborted) {
              reject(new KWebBridgeError("bridge.call.cancelled", "The bridge call was cancelled."));
              return;
            }
            let settled = false;
            let queryId = 0;
            const finish = operation => {
              if (settled) return;
              settled = true;
              clearTimeout(timer);
              options.signal?.removeEventListener("abort", cancel);
              operation();
            };
            const cancel = () => finish(() => {
              if (queryId !== 0) globalThis.__kwebBridgeCancel(queryId);
              reject(new KWebBridgeError("bridge.call.cancelled", "The bridge call was cancelled."));
            });
            const timer = setTimeout(() => finish(() => {
              if (queryId !== 0) globalThis.__kwebBridgeCancel(queryId);
              reject(new KWebBridgeError("bridge.call.timeout", "The bridge call timed out."));
            }), timeoutMs);
            options.signal?.addEventListener("abort", cancel, { once: true });
            if (options.signal?.aborted) {
              cancel();
              return;
            }
            try {
              queryId = globalThis.__kwebBridgeQuery({
                request: JSON.stringify({ version: 1, method, payload }),
                persistent: false,
                onSuccess: response => finish(() => {
                  try { resolve(JSON.parse(response)); }
                  catch { reject(new KWebBridgeError("bridge.response.invalid-json", "The bridge response is not valid JSON.")); }
                }),
                onFailure: (_errorCode, errorMessage) => finish(() => {
                  let failure = {};
                  try { failure = JSON.parse(errorMessage); } catch { failure = {}; }
                  reject(new KWebBridgeError(failure.code ?? "bridge.transport.failed", failure.message ?? errorMessage));
                }),
              });
              if (settled) globalThis.__kwebBridgeCancel(queryId);
            } catch (error) {
              finish(() => reject(new KWebBridgeError("bridge.transport.failed", error instanceof Error ? error.message : String(error))));
            }
          });
        }
    """.trimIndent()

    private fun generateBrowserStreamJavascript(schema: BridgeSchema): String {
        if (schema.streams.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("  let kwebStreamCounter = 0;")
            appendLine("  const openStream = (method, ackMethod, payload, capacity, binary, maxAggregateBytes, options = {}) => {")
            appendLine("    const streamId = ++kwebStreamCounter;")
            appendLine("    const frames = [];")
            appendLine("    const errors = [];")
            appendLine("    let done = false;")
            appendLine("    let queryId = 0;")
            appendLine("    let expected = 0;")
            appendLine("    let aggregate = 0;")
            appendLine("    let sinceAck = 0;")
            appendLine("    let notify = null;")
            appendLine("    let idleTimer = null;")
            appendLine("    const batchSize = Math.max(1, Math.floor(capacity / 2));")
            appendLine("    const wake = () => { const waiting = notify; notify = null; if (waiting) waiting(); };")
            appendLine("    const close = () => {")
            appendLine("      if (done) return;")
            appendLine("      done = true;")
            appendLine("      if (idleTimer !== null) clearTimeout(idleTimer);")
            appendLine("      if (queryId !== 0) globalThis.__kwebBridgeCancel(queryId);")
            appendLine("      wake();")
            appendLine("    };")
            appendLine("    const terminate = (code, message) => {")
            appendLine("      if (done) return;")
            appendLine("      done = true;")
            appendLine("      if (idleTimer !== null) clearTimeout(idleTimer);")
            appendLine("      if (queryId !== 0) globalThis.__kwebBridgeCancel(queryId);")
            appendLine("      errors.push(new KWebBridgeError(code, message));")
            appendLine("      wake();")
            appendLine("    };")
            appendLine("    const ack = () => {")
            appendLine("      if (sinceAck <= 0) return;")
            appendLine("      const granted = sinceAck;")
            appendLine("      sinceAck = 0;")
            appendLine("      globalThis.__kwebBridgeQuery({")
            appendLine("        request: JSON.stringify({ version: 1, method: ackMethod, payload: { granted }, streamId }),")
            appendLine("        persistent: false,")
            appendLine("        onSuccess: () => {},")
            appendLine("        onFailure: () => {},")
            appendLine("      });")
            appendLine("    };")
            appendLine("    const armIdle = () => {")
            appendLine("      if (idleTimer !== null) clearTimeout(idleTimer);")
            appendLine("      const idle = options.idleTimeoutMs;")
            appendLine("      if (idle === undefined) return;")
            appendLine("      idleTimer = setTimeout(() => terminate(\"bridge.stream.idle-timeout\", \"The stream received no frame within the idle timeout.\"), idle);")
            appendLine("    };")
            appendLine("    const onSuccess = response => {")
            appendLine("      if (done) return;")
            appendLine("      let frame;")
            appendLine("      try { frame = JSON.parse(response); }")
            appendLine("      catch { terminate(\"bridge.stream.frame-invalid\", \"The stream frame is not valid JSON.\"); return; }")
            appendLine("      if (frame.kind === \"terminal\") {")
            appendLine("        done = true;")
            appendLine("        if (idleTimer !== null) clearTimeout(idleTimer);")
            appendLine("        if (frame.reason !== undefined && frame.reason !== \"bridge.stream.completed\") {")
            appendLine("          errors.push(new KWebBridgeError(frame.reason, \"The stream ended with a terminal result.\"));")
            appendLine("        }")
            appendLine("        wake();")
            appendLine("        return;")
            appendLine("      }")
            appendLine("      if (frame.sequence !== expected + 1) {")
            appendLine("        terminate(\"bridge.stream.sequence-invalid\", \"The stream frame sequence is not contiguous.\");")
            appendLine("        return;")
            appendLine("      }")
            appendLine("      expected = frame.sequence;")
            appendLine("      if (binary) {")
            appendLine("        aggregate += (frame.payload && frame.payload.bytes ? frame.payload.bytes.length : 0);")
            appendLine("        if (maxAggregateBytes !== null && aggregate > maxAggregateBytes) {")
            appendLine("          terminate(\"bridge.stream.aggregate-exceeded\", \"The stream exceeded its maximum aggregate size.\");")
            appendLine("          return;")
            appendLine("        }")
            appendLine("      }")
            appendLine("      frames.push({ sequence: frame.sequence, payload: frame.payload });")
            appendLine("      armIdle();")
            appendLine("      wake();")
            appendLine("    };")
            appendLine("    const onFailure = (_errorCode, errorMessage) => {")
            appendLine("      if (done) return;")
            appendLine("      let failure = {};")
            appendLine("      try { failure = JSON.parse(errorMessage); } catch { failure = {}; }")
            appendLine("      done = true;")
            appendLine("      if (idleTimer !== null) clearTimeout(idleTimer);")
            appendLine("      if ((failure.code || \"\") !== \"bridge.stream.completed\") {")
            appendLine("        errors.push(new KWebBridgeError(failure.code || \"bridge.transport.failed\", failure.message || errorMessage));")
            appendLine("      }")
            appendLine("      wake();")
            appendLine("    };")
            appendLine("    queryId = globalThis.__kwebBridgeQuery({")
            appendLine("      request: JSON.stringify({ version: 1, method, payload, streamId }),")
            appendLine("      persistent: true,")
            appendLine("      onSuccess,")
            appendLine("      onFailure,")
            appendLine("    });")
            appendLine("    armIdle();")
            appendLine("    if (options.signal !== undefined) {")
            appendLine("      if (options.signal.aborted) close();")
            appendLine("      else options.signal.addEventListener(\"abort\", close, { once: true });")
            appendLine("    }")
            appendLine("    const next = () => new Promise((resolve, reject) => {")
            appendLine("      const step = () => {")
            appendLine("        if (errors.length > 0) { reject(errors.shift()); return; }")
            appendLine("        if (frames.length > 0) {")
            appendLine("          const frame = frames.shift();")
            appendLine("          sinceAck += 1;")
            appendLine("          if (sinceAck >= batchSize) ack();")
            appendLine("          resolve({ done: false, value: binary ? frame.payload.bytes : frame.payload });")
            appendLine("          return;")
            appendLine("        }")
            appendLine("        if (done) { resolve({ done: true, value: undefined }); return; }")
            appendLine("        notify = step;")
            appendLine("      };")
            appendLine("      step();")
            appendLine("    });")
            appendLine("    return {")
            appendLine("      close,")
            appendLine("      [Symbol.asyncIterator]() { return { next }; },")
            appendLine("    };")
            appendLine("  };")
        }
    }

    private fun kotlinType(field: BridgeField): String {
        val base = KOTLIN_TYPES[field.type] ?: field.type
        val value = if (field.isList) "List<$base>" else base
        return if (field.nullable) "$value?" else value
    }

    private fun typescriptType(field: BridgeField): String {
        val base = TYPESCRIPT_TYPES[field.type] ?: field.type
        val value = if (field.isList) "readonly $base[]" else base
        return if (field.nullable) "$value | null" else value
    }

    private fun requireUnique(values: List<String>, description: String) {
        require(values.size == values.toSet().size) { "Duplicate $description names are not allowed." }
    }

    private fun schemaBaseName(path: Path): String =
        path.fileName.toString().substringBeforeLast('.').split('-', '_').joinToString("") {
            it.replaceFirstChar { character -> character.uppercaseChar() }
        }

    private companion object {
        val SCHEMA_JSON: Json = Json { ignoreUnknownKeys = false }
        val TYPE_NAME = Regex("[A-Z][A-Za-z0-9]{0,63}")
        val FIELD_NAME = Regex("[a-z][A-Za-z0-9]{0,63}")
        val METHOD_NAME = FIELD_NAME
        val PACKAGE_NAME = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+")
        val PRIMITIVES = setOf("string", "int32", "double", "boolean")
        val KOTLIN_TYPES = mapOf(
            "string" to "String",
            "int32" to "Int",
            "double" to "Double",
            "boolean" to "Boolean",
        )
        val TYPESCRIPT_TYPES = mapOf(
            "string" to "string",
            "int32" to "number",
            "double" to "number",
            "boolean" to "boolean",
        )
        val KOTLIN_TYPE_NAMES = KOTLIN_TYPES.values.toSet() + setOf(
            "List",
            "Serializable",
            "KWebBridgeDispatcher",
            "KWebBridgeException",
            "KWebBridgeProtocol",
        )
        val KOTLIN_KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
            "in", "interface", "is", "null", "object", "package", "return", "super", "this",
            "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
            "by", "catch", "constructor", "delegate", "dynamic", "field", "file", "finally",
            "get", "import", "init", "param", "property", "receiver", "set", "setparam", "value",
            "where", "actual", "abstract", "annotation", "companion", "const", "crossinline",
            "data", "enum", "expect", "external", "final", "infix", "inline", "inner", "internal",
            "lateinit", "noinline", "open", "operator", "out", "override", "private", "protected",
            "public", "reified", "sealed", "suspend", "tailrec", "vararg",
        )
        val JAVASCRIPT_GLOBAL_NAMES = setOf(
            "AbortController", "AbortSignal", "Array", "ArrayBuffer", "BigInt", "Boolean",
            "DataView", "Date", "Error", "EvalError", "FinalizationRegistry", "Function",
            "JSON", "Map", "Math", "Number", "Object", "Promise", "Proxy", "RangeError",
            "ReferenceError", "Reflect", "RegExp", "Set", "String", "Symbol", "SyntaxError",
            "TypeError", "URIError", "Uint8Array", "WeakMap", "WeakRef", "WeakSet", "Window",
            "Document", "Event", "Node", "URL", "WebSocket", "XMLHttpRequest",
        )
    }
}

    /**
     * The generated stream runtime: one persistent query per open stream, an
     * ordered credit-acknowledged async queue, declared terminal frames, and
     * explicit close()/AbortSignal handling. There is no generic postMessage
     * and no Node Buffer: binary chunks are base64 strings.
     */
    private fun typescriptStreamRuntime(schema: BridgeSchema): String {
        if (schema.streams.isEmpty()) return ""
        return buildString {
        appendLine()
        appendLine("const KWEB_STREAM_COMPLETED = \"bridge.stream.completed\";")
        appendLine()
        appendLine("function openStream<Chunk>(")
        appendLine("  method: string,")
        appendLine("  ackMethod: string,")
        appendLine("  payload: unknown,")
        appendLine("  capacity: number,")
        appendLine("  binary: boolean,")
        appendLine("  maxAggregateBytes: number | null,")
        appendLine("  options: KWebBridgeStreamCallOptions = {},")
        appendLine("): KWebBridgeStream<Chunk> {")
        appendLine("  const frames: Array<{ seq: number; payload?: unknown }> = [];")
        appendLine("  const errors: KWebBridgeError[] = [];")
        appendLine("  const streamId = ++kwebStreamCounter;")
        appendLine("  let done = false;")
        appendLine("  let queryId = 0;")
        appendLine("  let expected = 0;")
        appendLine("  let aggregate = 0;")
        appendLine("  let sinceAck = 0;")
        appendLine("  let notify: (() => void) | null = null;")
        appendLine("  let idleTimer: ReturnType<typeof setTimeout> | null = null;")
        appendLine("  const batchSize = Math.max(1, Math.floor(capacity / 2));")
        appendLine("  const wake = (): void => {")
        appendLine("    const waiting = notify;")
        appendLine("    notify = null;")
        appendLine("    waiting?.();")
        appendLine("  };")
        appendLine("  const close = (): void => {")
        appendLine("    if (done) return;")
        appendLine("    done = true;")
        appendLine("    if (idleTimer !== null) clearTimeout(idleTimer);")
        appendLine("    if (queryId !== 0) window.__kwebBridgeCancel(queryId);")
        appendLine("    wake();")
        appendLine("  };")
        appendLine("  const terminate = (code: string, message: string): void => {")
        appendLine("    if (done) return;")
        appendLine("    done = true;")
        appendLine("    if (idleTimer !== null) clearTimeout(idleTimer);")
        appendLine("    if (queryId !== 0) window.__kwebBridgeCancel(queryId);")
        appendLine("    errors.push(new KWebBridgeError(code, message));")
        appendLine("    wake();")
        appendLine("  };")
        appendLine("  const ack = (): void => {")
        appendLine("    if (sinceAck <= 0) return;")
        appendLine("    const granted = sinceAck;")
        appendLine("    sinceAck = 0;")
        appendLine("    window.__kwebBridgeQuery({")
        appendLine("      request: JSON.stringify({ version: 1, method: ackMethod, payload: { granted }, streamId }),")
        appendLine("      persistent: false,")
        appendLine("      onSuccess: () => {},")
        appendLine("      onFailure: () => {},")
        appendLine("    });")
        appendLine("  };")
        appendLine("  const armIdle = (): void => {")
        appendLine("    if (idleTimer !== null) clearTimeout(idleTimer);")
        appendLine("    const idle = options.idleTimeoutMs;")
        appendLine("    if (idle === undefined) return;")
        appendLine("    idleTimer = setTimeout(() => terminate(\"bridge.stream.idle-timeout\", \"The stream received no frame within the idle timeout.\"), idle);")
        appendLine("  };")
        appendLine("  const onSuccess = (response: string): void => {")
        appendLine("    if (done) return;")
        appendLine("    let frame: { kind?: string; seq?: number; payload?: { bytes?: string }; reason?: string };")
        appendLine("    try { frame = JSON.parse(response); }")
        appendLine("    catch { terminate(\"bridge.stream.frame-invalid\", \"The stream frame is not valid JSON.\"); return; }")
        appendLine("    if (frame.kind === \"terminal\") {")
        appendLine("      done = true;")
        appendLine("      if (idleTimer !== null) clearTimeout(idleTimer);")
        appendLine("      if (frame.reason !== undefined && frame.reason !== KWEB_STREAM_COMPLETED) {")
        appendLine("        errors.push(new KWebBridgeError(frame.reason, \"The stream ended with a terminal result.\"));")
        appendLine("      }")
        appendLine("      wake();")
        appendLine("      return;")
        appendLine("    }")
        appendLine("    if (frame.sequence !== expected + 1) {")
        appendLine("      terminate(\"bridge.stream.sequence-invalid\", \"The stream frame sequence is not contiguous.\");")
        appendLine("      return;")
        appendLine("    }")
        appendLine("    expected = frame.sequence;")
        appendLine("    if (binary) {")
        appendLine("      aggregate += frame.payload?.bytes?.length ?? 0;")
        appendLine("      if (maxAggregateBytes !== null && aggregate > maxAggregateBytes) {")
        appendLine("        terminate(\"bridge.stream.aggregate-exceeded\", \"The stream exceeded its maximum aggregate size.\");")
        appendLine("        return;")
        appendLine("      }")
        appendLine("    }")
        appendLine("    frames.push({ sequence: frame.sequence, payload: frame.payload });")
        appendLine("    armIdle();")
        appendLine("    wake();")
        appendLine("  };")
        appendLine("  const onFailure = (_errorCode: number, errorMessage: string): void => {")
        appendLine("    if (done) return;")
        appendLine("    let failure: { code?: string; message?: string } = {};")
        appendLine("    try { failure = JSON.parse(errorMessage); } catch { failure = {}; }")
        appendLine("    done = true;")
        appendLine("    if (idleTimer !== null) clearTimeout(idleTimer);")
        appendLine("    if ((failure.code ?? \"\") !== KWEB_STREAM_COMPLETED) {")
        appendLine("      errors.push(new KWebBridgeError(failure.code ?? \"bridge.transport.failed\", failure.message ?? errorMessage));")
        appendLine("    }")
        appendLine("    wake();")
        appendLine("  };")
        appendLine("  queryId = window.__kwebBridgeQuery({")
        appendLine("    request: JSON.stringify({ version: 1, method, payload, streamId }),")
        appendLine("    persistent: true,")
        appendLine("    onSuccess,")
        appendLine("    onFailure,")
        appendLine("  });")
        appendLine("  armIdle();")
        appendLine("  if (options.signal !== undefined) {")
        appendLine("    if (options.signal.aborted) close();")
        appendLine("    else options.signal.addEventListener(\"abort\", close, { once: true });")
        appendLine("  }")
        appendLine("  const next = (): Promise<IteratorResult<Chunk>> => new Promise((resolve, reject) => {")
        appendLine("    const step = (): void => {")
        appendLine("      if (errors.length > 0) { reject(errors.shift()!); return; }")
        appendLine("      if (frames.length > 0) {")
        appendLine("        const frame = frames.shift()!;")
        appendLine("        sinceAck += 1;")
        appendLine("        if (sinceAck >= batchSize) ack();")
        appendLine("        resolve({ done: false, value: (binary ? (frame.payload as { bytes: string }) : frame.payload) as Chunk });")
        appendLine("        return;")
        appendLine("      }")
        appendLine("      if (done) { resolve({ done: true, value: undefined }); return; }")
        appendLine("      notify = step;")
        appendLine("    };")
        appendLine("    step();")
        appendLine("  });")
        appendLine("  return {")
        appendLine("    close,")
        appendLine("    [Symbol.asyncIterator]() {")
        appendLine("      return { next };")
        appendLine("    },")
        appendLine("  };")
        appendLine("}")
        }
    }
