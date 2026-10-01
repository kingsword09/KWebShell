package io.github.kingsword09.kwebshell.service.clipboard

import io.github.kingsword09.kwebshell.core.KWebException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

public fun main(args: Array<String>): Unit = runBlocking {
    when (args.firstOrNull()) {
        "foreign-write" -> foreignWrite()
        "read" -> independentRead()
        else -> runParent()
    }
}

private suspend fun foreignWrite() {
    val signal = Path.of(
        System.getProperty("kweb.clipboard.foreign.signal")
            ?: error("Missing foreign signal path"),
    )
    val stop = Path.of(
        System.getProperty("kweb.clipboard.foreign.stop")
            ?: error("Missing foreign stop path"),
    )
    val service = JvmKWebClipboard.open()
    try {
        service.write(
            KWebClipboardWriteRequest(
                KWebClipboardSelection.SYSTEM,
                listOf(
                    KWebClipboardWriteItem(
                        KWebClipboardFormat.TEXT_PLAIN,
                        KWebClipboardPayloadEncoding.UTF8,
                        "foreign-owner".toByteArray(StandardCharsets.UTF_8),
                    ),
                ),
            ),
        )
        Files.writeString(signal, "ready")
        while (!Files.exists(stop)) delay(50)
    } finally {
        service.close()
    }
}

private suspend fun independentRead() {
    val service = JvmKWebClipboard.open()
    try {
        val result = service.read(
            KWebClipboardReadRequest(
                KWebClipboardSelection.SYSTEM,
                listOf(KWebClipboardFormat.TEXT_PLAIN),
            ),
        )
        check(result.available.any { it.format == KWebClipboardFormat.TEXT_PLAIN }) {
            "The independent reader could not observe plain text."
        }
    } finally {
        service.close()
    }
}

private suspend fun runParent() = coroutineScope {
    val root = Path.of(
        System.getProperty("kweb.clipboard.integration.root")
            ?: error("Missing kweb.clipboard.integration.root"),
    )
    Files.createDirectories(root)
    val service = JvmKWebClipboard.open()
    try {
        service.clear(KWebClipboardSelection.SYSTEM)

        val allFormats = listOf(
            KWebClipboardWriteItem(
                KWebClipboardFormat.TEXT_PLAIN,
                KWebClipboardPayloadEncoding.UTF8,
                "KWeb clipboard 🌏\nline".toByteArray(StandardCharsets.UTF_8),
            ),
            KWebClipboardWriteItem(
                KWebClipboardFormat.TEXT_HTML,
                KWebClipboardPayloadEncoding.UTF8,
                "<p><strong>KWeb</strong> clipboard</p>".toByteArray(StandardCharsets.UTF_8),
            ),
            KWebClipboardWriteItem(
                KWebClipboardFormat.TEXT_RTF,
                KWebClipboardPayloadEncoding.RTF_BYTES,
                "{\\rtf1\\ansi KWeb clipboard}".toByteArray(StandardCharsets.ISO_8859_1),
            ),
            KWebClipboardWriteItem(
                KWebClipboardFormat.URI_LIST,
                KWebClipboardPayloadEncoding.UTF8,
                "https://example.com/kweb\nfile:///tmp/kweb-clipboard".toByteArray(StandardCharsets.UTF_8),
            ),
        )
        service.write(KWebClipboardWriteRequest(KWebClipboardSelection.SYSTEM, allFormats))
        val read = service.read(
            KWebClipboardReadRequest(
                KWebClipboardSelection.SYSTEM,
                allFormats.map { it.format },
            ),
        )
        check(read.available.map { it.format }.toSet() == allFormats.map { it.format }.toSet()) {
            "The JDK provider did not publish all fixed clipboard formats: ${read.available.map { it.format }}"
        }
        val hashes = linkedMapOf<String, String>()
        val sizes = linkedMapOf<String, Int>()
        read.available.forEach { descriptor ->
            val bytes = readAll(service, descriptor)
            hashes[descriptor.format.id] = sha256(bytes)
            sizes[descriptor.format.id] = bytes.size
        }

        val stale = read.available.first().handle
        service.write(
            KWebClipboardWriteRequest(
                KWebClipboardSelection.SYSTEM,
                listOf(
                    KWebClipboardWriteItem(
                        KWebClipboardFormat.TEXT_PLAIN,
                        KWebClipboardPayloadEncoding.UTF8,
                        "replacement".toByteArray(StandardCharsets.UTF_8),
                    ),
                ),
            ),
        )
        val staleFailure = runCatching {
            service.readPayload(stale, 0, 32)
        }.exceptionOrNull()
        check(staleFailure is KWebException && staleFailure.code == KWebClipboardErrorCode.PAYLOAD_EXPIRED) {
            "A payload handle survived a clipboard sequence change: ${staleFailure}"
        }

        val readerProcesses = (1..4).map {
            async { runChild("read") }
        }.awaitAll()
        check(readerProcesses.all { it == 0 }) { "An independent clipboard reader failed." }

        val ownershipEvent = async {
            service.changes()
                .drop(1)
                .filter { it.ownership == KWebClipboardOwnership.FOREIGN || it.ownership == KWebClipboardOwnership.EMPTY }
                .first()
        }
        delay(100)
        val signal = root.resolve("foreign-ready")
        val stop = root.resolve("foreign-stop")
        val foreignProcess = startChild(
            "foreign-write",
            mapOf(
                "kweb.clipboard.foreign.signal" to signal.toString(),
                "kweb.clipboard.foreign.stop" to stop.toString(),
            ),
        )
        withTimeout(10_000) {
            while (!Files.exists(signal)) delay(50)
        }
        val observedOwnership = withTimeout(10_000) { ownershipEvent.await() }
        Files.writeString(stop, "stop")
        check(foreignProcess.waitFor() == 0) { "The foreign clipboard writer failed." }

        service.write(
            KWebClipboardWriteRequest(
                KWebClipboardSelection.SYSTEM,
                listOf(
                    KWebClipboardWriteItem(
                        KWebClipboardFormat.TEXT_PLAIN,
                        KWebClipboardPayloadEncoding.UTF8,
                        ByteArray(0),
                    ),
                ),
            ),
        )
        val emptyText = service.read(
            KWebClipboardReadRequest(KWebClipboardSelection.SYSTEM, listOf(KWebClipboardFormat.TEXT_PLAIN)),
        )
        check(emptyText.available.size == 1 && readAll(service, emptyText.available.single()).isEmpty()) {
            "An empty plain-text clipboard value did not round-trip."
        }

        service.clear(KWebClipboardSelection.SYSTEM)
        val empty = service.read(
            KWebClipboardReadRequest(
                KWebClipboardSelection.SYSTEM,
                listOf(KWebClipboardFormat.TEXT_PLAIN, KWebClipboardFormat.TEXT_HTML),
            ),
        )
        check(empty.available.isEmpty()) { "The clear operation left clipboard formats visible." }

        val evidence = buildString {
            appendLine("{")
            appendLine("  \"schemaVersion\": 1,")
            appendLine("  \"contractRevision\": \"2026-09-30.3\",")
            appendLine("  \"provider\": \"${(service as NativeKWebClipboard).providerIdForEvidence()}\",")
            appendLine("  \"runtime\": \"JDK-${Runtime.version().feature()}\",")
            appendLine("  \"target\": \"${currentTarget()}\",")
            appendLine("  \"formatIds\": [\"text/plain\", \"text/html\", \"text/rtf\", \"text/uri-list\"],")
            appendLine("  \"roundTripSizes\": ${jsonMap(sizes)},")
            appendLine("  \"roundTripSha256\": ${jsonMap(hashes)},")
            appendLine("  \"payloadExpiredAfterWrite\": true,")
            appendLine("  \"independentReaderCount\": 4,")
            appendLine("  \"ownershipTransition\": \"${observedOwnership.ownership.id}\",")
            appendLine("  \"emptyAfterClear\": true,")
            appendLine("  \"emptyPlainTextRoundTrip\": true,")
            appendLine("  \"absolutePathsRetained\": false")
            appendLine("}")
        }
        Files.writeString(root.resolve("clipboard-evidence.json"), evidence)
    } finally {
        service.close()
        check(service.lifecycle.value.name == "CLOSED") { "The clipboard service did not close." }
    }
    println("KWebClipboard integration passed.")
}

private fun currentTarget(): String {
    val os = System.getProperty("os.name").lowercase().let {
        when {
            it.startsWith("windows") -> "windows"
            it.startsWith("mac") -> "macos"
            it.startsWith("linux") -> "linux"
            else -> error("Unsupported clipboard target OS: $it")
        }
    }
    val architecture = when (System.getProperty("os.arch").lowercase()) {
        "x86_64", "amd64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> error("Unsupported clipboard target architecture: ${System.getProperty("os.arch")}")
    }
    return "$os-$architecture"
}


private suspend fun readAll(
    service: KWebClipboard,
    descriptor: KWebClipboardPayloadDescriptor,
): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    var offset = 0L
    while (true) {
        val chunk = service.readPayload(descriptor.handle, offset, 128 * 1024)
        output.write(chunk.bytes)
        offset += chunk.bytes.size
        if (chunk.eof) return output.toByteArray()
        check(chunk.bytes.isNotEmpty()) { "A non-terminal clipboard chunk was empty." }
    }
}

private fun runChild(mode: String): Int {
    val process = startChild(mode, emptyMap())
    val output = process.inputStream.bufferedReader().readText()
    val exit = process.waitFor()
    check(exit == 0) { "Clipboard child '$mode' failed with $exit: $output" }
    return exit
}

private fun startChild(mode: String, properties: Map<String, String>): Process {
    val java = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
    )
    val command = mutableListOf(
        java.toString(),
        "-Djava.awt.headless=false",
        "-Dkweb.clipboard.native.library.path=" +
            (System.getProperty("kweb.clipboard.native.library.path")
                ?: error("Missing native clipboard library path")),
    )
    properties.forEach { (key, value) -> command += "-D$key=$value" }
    command += listOf(
        "-cp",
        System.getProperty("java.class.path"),
        "io.github.kingsword09.kwebshell.service.clipboard.ClipboardIntegrationMainKt",
        mode,
    )
    return ProcessBuilder(command).redirectErrorStream(true).start()
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun jsonMap(values: Map<String, *>): String =
    values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "\"$key\":${if (value is String) "\"$value\"" else value}"
    }
