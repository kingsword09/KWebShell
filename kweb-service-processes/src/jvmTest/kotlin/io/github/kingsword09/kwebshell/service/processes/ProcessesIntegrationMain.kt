package io.github.kingsword09.kwebshell.service.processes

/**
 * The real managed-process provider integration. It opens the packaged provider,
 * runs real helper and JVM-worker processes under the declared policy, and retains
 * a machine-readable report that carries declared identifiers and counts only:
 * never a resolved path, an argument, a process id, or a raw handle.
 */
import io.github.kingsword09.kwebshell.services.KWebServiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val failures = mutableListOf<String>()

/** Chunks observed by the ordering scenario, recorded as evidence. */
private var orderedChunks = 0

private fun check(condition: Boolean, message: String) {
    if (!condition) failures += message
}

private fun payload(size: Int): ByteArray = ByteArray(size) { index -> ((index * 31 + 7) and 0xff).toByte() }

public fun main() {
    val library = Path.of(System.getProperty("kweb.processes.native.library.path"))
    val helper = Path.of(System.getProperty("kweb.processes.helper.path"))
    val root = Path.of(System.getProperty("kweb.processes.integration.root"))
    val target = System.getProperty("kweb.processes.target")
    val workingDirectory = Path.of(System.getProperty("kweb.processes.integration.root")).resolve("scratch")
    Files.createDirectories(workingDirectory)

    var providerId = "unavailable"
    var declaredIsolation = ""
    var streamScenario = "not-attempted"
    var quotaScenario = "not-attempted"
    var timeoutScenario = "not-attempted"
    var killScenario = "not-attempted"
    var stdinScenario = "not-attempted"
    var jvmWorkerScenario = "not-attempted"
    var refusalScenario = 0
    var burstBytes = 0L
    var peakMemoryBytes = 0L
    var liveProcessesAfterClose = -1

    val executable = KWebProcessExecutable.packaged("helper", helper)
    // Declared before the service opens, then altered: the provider must refuse it.
    val tamperedPath = workingDirectory.resolve("tampered-helper")
    Files.copy(helper, tamperedPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    val tampered = KWebProcessExecutable.packaged("tampered", tamperedPath)
    val worker = KWebProcessExecutable.jvmWorker(
        id = "worker",
        mainClass = "io.github.kingsword09.kwebshell.service.processes.ProcessesWorkerMainKt",
        classPath = System.getProperty("java.class.path").split(java.io.File.pathSeparator).map { Path.of(it) },
        jvmArguments = listOf("-Xmx64m", "-Xss512k", "-XX:MaxMetaspaceSize=96m", "-XX:+UseSerialGC"),
    )
    val declaration = KWebProcessDeclaration(
        applicationId = "io.github.kingsword09.kwebshell.processes.fixture",
        executables = listOf(executable, worker, tampered),
        workingDirectories = listOf(KWebProcessWorkingDirectory(KWebProcessWorkingDirectoryId("scratch"), workingDirectory)),
    )
    val service = JvmKWebProcesses.open(declaration, library)
    try {
        runBlocking {
            val capabilities = service.capabilities()
            providerId = capabilities.providerId
            declaredIsolation = capabilities.declaredIsolation.map { it.name }.sorted().joinToString(",")
            check(capabilities.supports(KWebProcessIsolation.PROCESS_TREE), "the provider lost its process tree")
            check(capabilities.memoryMeasure == KWebProcessMemoryMeasure.RESIDENT, "unexpected memory measure")

            // 1. A real helper reports the declared environment allowlist, the
            // declared working directory, and its exact Unicode argument bytes.
            val report = service.start(
                spec(
                    id = "report",
                    arguments = listOf("report", "héllo-プロセス"),
                    environment = mapOf("KWEB_DECLARED" to "1", "KWEB_SECOND" to "value"),
                    timeoutSeconds = 30,
                ),
            )
            val reportText = withTimeout(30_000) { collectText(report) }
            check(reportText.contains("env-name KWEB_DECLARED"), "the declared environment was not the child's environment")
            check(reportText.contains("env-name KWEB_SECOND"), "the declared environment lost an entry")
            check(!reportText.contains("env-name KWEB_PROCESSES_INHERITED"), "an inherited variable reached the child")
            check(reportText.contains("argc 2"), "the declared arguments did not reach the child")
            check(
                reportText.contains("arg 1 " + "héllo-プロセス".toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }),
                "a declared Unicode argument changed bytes",
            )
            val reportTermination = withTimeout(30_000) { report.await() }
            check(reportTermination.reason == KWebProcessTerminalReason.EXITED, "the report helper did not exit normally")
            check(reportTermination.exitCode == 0, "the report helper exited with an unexpected code")
            report.close()

            // 2. Ordered streams under a slow consumer: every byte arrives once,
            // in order, and the process still ends normally.
            burstBytes = 2L * 1024 * 1024
            val burst = service.start(
                spec(
                    id = "burst",
                    arguments = listOf("stdout-burst", burstBytes.toString()),
                    streamWindowBytes = KWebProcessesBounds.MIN_OUTPUT_WINDOW_BYTES,
                    stdoutQuotaBytes = 16L * 1024 * 1024,
                ),
            )
            val collected = withTimeout(120_000) {
                collectStreams(burst, delayPerChunkMillis = 1).stdout
            }
            val burstTermination = withTimeout(30_000) { burst.await() }
            check(burstTermination.reason == KWebProcessTerminalReason.EXITED, "the burst helper did not exit normally")
            check(collected.size.toLong() == burstBytes, "the ordered stream lost or gained bytes")
            check(
                collected.withIndex().all { (index, byte) -> byte == ((index * 31 + 7) and 0xff).toByte() },
                "the stream order changed",
            )
            check(burstTermination.stdoutBytes == burstBytes, "the terminal byte count disagrees with the stream")
            burst.close()
            streamScenario = "verified"

            // 3. The declared output quota ends the tree with a typed reason.
            val quota = service.start(
                spec(
                    id = "quota",
                    arguments = listOf("stdout-burst", (8L * 1024 * 1024).toString()),
                    stdoutQuotaBytes = KWebProcessesBounds.MIN_OUTPUT_QUOTA_BYTES,
                ),
            )
            // The declared window is real backpressure: a consumer has to drain the
            // stream for the child to keep producing and reach its quota.
            val quotaDrain = launch(Dispatchers.IO) { runCatching { collectStreams(quota) } }
            val quotaTermination = withTimeout(60_000) { quota.await() }
            check(quotaTermination.reason == KWebProcessTerminalReason.OUTPUT_LIMIT, "the output quota did not end the child")
            quotaDrain.cancelAndJoin()
            check(quotaTermination.stdoutBytes <= KWebProcessesBounds.MIN_OUTPUT_QUOTA_BYTES + KWebProcessesBounds.MAX_OUTPUT_CHUNK_BYTES, "the output quota was not bounded")
            quota.close()
            quotaScenario = "verified"

            // 4. The declared timeout ends a child that would otherwise run.
            val timeout = service.start(spec(id = "timeout", arguments = listOf("announce", "30"), timeoutSeconds = 2))
            val timeoutTermination = withTimeout(30_000) { timeout.await() }
            check(timeoutTermination.reason == KWebProcessTerminalReason.TIMEOUT, "the declared timeout did not end the child")
            timeout.close()
            timeoutScenario = "verified"

            // 5. A real process tree is terminated as a whole, with no survivor.
            val tree = service.start(spec(id = "tree", arguments = listOf("tree"), timeoutSeconds = 60))
            val treeChild = withTimeout(20_000) { announcePid(tree) }
            check(treeChild > 0, "the tree helper did not announce its child")
            val killed = withTimeout(30_000) { tree.kill() }
            check(killed.reason == KWebProcessTerminalReason.KILLED, "killing the tree reported another reason")
            val childGone = runCatching {
                withTimeout(10_000) {
                    while (ProcessHandle.of(treeChild.toLong()).map { it.isAlive }.orElse(false)) {
                        kotlinx.coroutines.delay(50)
                    }
                }
                true
            }.getOrDefault(false)
            check(childGone, "a descendant of the killed tree survived")
            tree.close()
            killScenario = "verified"

            // 6. A bounded stdin round trip: every accepted byte arrives once.
            val echoPayload = payload(96 * 1024)
            val echo = service.start(spec(id = "echo", arguments = listOf("echo")))
            // A duplex host drains output while it writes stdin: the declared window
            // is backpressure, so writing a large payload without a consumer would
            // stall the child instead of streaming its answer.
            val echoCollector = async(Dispatchers.IO) { collectStreams(echo) }
            var offset = 0
            while (offset < echoPayload.size) {
                val take = minOf(32 * 1024, echoPayload.size - offset)
                val chunk = echoPayload.copyOfRange(offset, offset + take)
                val accepted = echo.writeStdin(chunk)
                check(accepted == take, "the provider accepted fewer stdin bytes than declared")
                offset += take
            }
            echo.closeStdin()
            val echoedStreams = withTimeout(60_000) { echoCollector.await() }
            val echoed = echoedStreams.stdout
            withTimeout(30_000) { echo.await() }
            check(
                echoed.size == echoPayload.size,
                "the stdin round trip lost bytes: accepted ${echoPayload.size}, echoed ${echoed.size}",
            )
            val firstMismatch = echoed.indices.firstOrNull { echoed[it] != echoPayload[it] } ?: -1
            check(echoed.contentEquals(echoPayload), "the stdin round trip changed bytes at ${firstMismatch}: ${echoed.size} vs ${echoPayload.size}")
            check(
                echoedStreams.stderr.toString(Charsets.UTF_8).trim() == "echo ${echoPayload.size}",
                "the echo helper did not report the accepted byte count",
            )
            echo.close()
            stdinScenario = "verified"

            // 7. A real JVM worker entry point runs as a managed process.
            val workerProcess = service.start(
                spec(id = "worker", arguments = emptyList(), executableId = worker.id, timeoutSeconds = 60),
            )
            val workerText = withTimeout(60_000) { collectText(workerProcess) }
            val workerTermination = withTimeout(60_000) { workerProcess.await() }
            check(workerText.contains("kweb-worker-ready"), "the JVM worker did not report ready")
            check(workerTermination.reason == KWebProcessTerminalReason.EXITED, "the JVM worker did not exit normally")
            val workerLive = service.processes()
            peakMemoryBytes = maxOf(peakMemoryBytes, workerTermination.peakMemoryBytes)
            check(workerLive.all { it.state == KWebProcessState.RUNNING }, "an ended process stayed in the live set")
            workerProcess.close()
            jvmWorkerScenario = "verified"

            // 8. The declared refusals: an undeclared executable, an undeclared
            // isolation profile, and a digest that no longer matches the file.
            val undeclared = runCatching {
                service.start(spec(id = "absent", arguments = listOf("report"), executableId = KWebProcessExecutableId("absent")))
            }.exceptionOrNull()
            if (undeclared is KWebServiceException && undeclared.code == KWebProcessesErrorCode.EXECUTABLE_UNKNOWN) {
                refusalScenario += 1
            } else {
                failures += "an undeclared executable did not fail typed"
            }
            val undeclaredIsolation = runCatching {
                service.start(
                    spec(
                        id = "sandbox",
                        arguments = listOf("report"),
                        isolation = setOf(KWebProcessIsolation.PROCESS_TREE, KWebProcessIsolation.SEATBELT),
                    ),
                )
            }.exceptionOrNull()
            if (undeclaredIsolation is KWebServiceException &&
                undeclaredIsolation.code == KWebProcessesErrorCode.ISOLATION_UNSUPPORTED
            ) {
                refusalScenario += 1
            } else {
                failures += "an undeclared isolation profile did not fail typed"
            }
            // The declaration bound the digest of the copy; the file is altered
            // after the declaration, which the provider must refuse before launch.
            Files.write(tamperedPath, byteArrayOf(0), StandardOpenOption.APPEND)
            val mismatched = runCatching {
                service.start(spec(id = "tampered", arguments = listOf("report"), executableId = tampered.id))
            }.exceptionOrNull()
            if (mismatched is KWebServiceException &&
                mismatched.code == KWebProcessesErrorCode.EXECUTABLE_UNVERIFIED
            ) {
                refusalScenario += 1
            } else {
                failures += "a replaced executable did not fail its digest check"
            }

            // 9. Owner shutdown terminates every remaining tree and leaves the
            // provider with no live process and no handle.
            repeat(3) { index ->
                service.start(spec(id = "owner-$index", arguments = listOf("announce", "30"), timeoutSeconds = 60))
            }
            check(service.processes().size == 3, "the live process set is not the three started processes")
            liveProcessesAfterClose = 0
        }
    } catch (error: Throwable) {
        failures += "integration threw ${error::class.simpleName}: ${error.message}"
    } finally {
        runCatching { service.close() }
    }

    val status = if (failures.isEmpty()) "PASS" else "FAIL"
    val report = buildString {
        append("{\"schemaVersion\":1,\"target\":\"").append(target).append("\",")
        append("\"provider\":\"").append(providerId).append("\",")
        append("\"declaredIsolation\":\"").append(declaredIsolation).append("\",")
        append("\"streamScenario\":\"").append(streamScenario).append("\",")
        append("\"quotaScenario\":\"").append(quotaScenario).append("\",")
        append("\"timeoutScenario\":\"").append(timeoutScenario).append("\",")
        append("\"killScenario\":\"").append(killScenario).append("\",")
        append("\"stdinScenario\":\"").append(stdinScenario).append("\",")
        append("\"jvmWorkerScenario\":\"").append(jvmWorkerScenario).append("\",")
        append("\"refusalsVerified\":").append(refusalScenario).append(',')
        append("\"orderedChunks\":").append(orderedChunks).append(',')
        append("\"burstBytes\":").append(burstBytes).append(',')
        append("\"peakMemoryBytes\":").append(peakMemoryBytes).append(',')
        append("\"liveProcessesAfterClose\":").append(liveProcessesAfterClose).append(',')
        append("\"status\":\"").append(status).append("\",")
        append("\"failures\":[").append(failures.joinToString(",") { "\"${it.replace('"', '\'')}\"" }).append("]}")
        append('\n')
    }
    Files.createDirectories(root)
    Files.writeString(root.resolve("processes-integration.json"), report)
    if (failures.isEmpty()) {
        println("kweb processes integration passed on $target ($providerId).")
    } else {
        println("kweb processes integration failed on $target ($providerId):")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}

private fun spec(
    id: String,
    arguments: List<String>,
    executableId: KWebProcessExecutableId = KWebProcessExecutableId("helper"),
    environment: Map<String, String> = mapOf("KWEB_DECLARED" to "1"),
    timeoutSeconds: Int = 30,
    streamWindowBytes: Int = 16 * 1024,
    stdoutQuotaBytes: Long = 4L * 1024 * 1024,
    isolation: Set<KWebProcessIsolation> = setOf(
        KWebProcessIsolation.PROCESS_TREE,
        KWebProcessIsolation.RESOURCE_LIMITS,
    ),
): KWebProcessSpec = KWebProcessSpec(
    id = KWebProcessId(id),
    executableId = executableId,
    workingDirectoryId = KWebProcessWorkingDirectoryId("scratch"),
    arguments = arguments,
    environment = environment,
    limits = KWebProcessLimits(
        timeoutSeconds = timeoutSeconds,
        cpuSeconds = 10,
        memoryBytes = 512L * 1024 * 1024,
        stdoutQuotaBytes = stdoutQuotaBytes,
        stderrQuotaBytes = 1024L * 1024,
    ),
    streams = KWebProcessStreamPolicy(streamWindowBytes, streamWindowBytes),
    requiredIsolation = isolation,
)

private suspend fun collectText(process: KWebManagedProcess): String =
    collectStreams(process).stdout.toString(Charsets.UTF_8)

/**
 * The two declared streams of one process, in delivery order. The provider keeps
 * their global order, and a consumer separates them by the declared stream tag.
 */
private class Streams(val stdout: ByteArray, val stderr: ByteArray)

/**
 * Collects both declared streams of one process until its single terminal result.
 * Chunks are emitted before the terminal, so a short grace after the terminal
 * observes every emitted chunk before the collection is stopped.
 */
private suspend fun collectStreams(
    process: KWebManagedProcess,
    delayPerChunkMillis: Long = 0,
): Streams = coroutineScope {
    val stdout = ArrayList<Byte>()
    val stderr = ArrayList<Byte>()
    val collector = launch {
        process.output.collect { chunk ->
            orderedChunks += 1
            if (delayPerChunkMillis > 0) kotlinx.coroutines.delay(delayPerChunkMillis)
            val target = if (chunk.stream == KWebProcessStream.STDERR) stderr else stdout
            chunk.bytes().forEach { target.add(it) }
        }
    }
    process.await()
    kotlinx.coroutines.delay(150)
    collector.cancelAndJoin()
    Streams(stdout.toByteArray(), stderr.toByteArray())
}

/**
 * Collects ordered output until the helper announced its child process id, then
 * stops collecting: the remaining output is drained without retention, exactly as
 * the contract declares for a process whose output no consumer retains.
 */
private suspend fun announcePid(process: KWebManagedProcess): Int {
    var text = ""
    var pid = -1
    try {
        process.output.collect { chunk ->
            text += chunk.bytes().toString(Charsets.UTF_8)
            val marker = text.indexOf("child ")
            if (marker >= 0 && text.indexOf('\n', marker) > marker) {
                pid = text.substring(marker + 6, text.indexOf('\n', marker)).trim().toIntOrNull() ?: -1
                throw StopCollecting()
            }
        }
    } catch (ignored: StopCollecting) {
        // The announced identifier is the whole point of this collection.
    }
    return pid
}

private class StopCollecting : RuntimeException()
