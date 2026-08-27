package io.github.kingsword09.kwebshell.example.benchmark

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.core.KWebBounds
import io.github.kingsword09.kwebshell.core.KWebCapability
import io.github.kingsword09.kwebshell.core.KWebEngine
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebPage
import io.github.kingsword09.kwebshell.core.KWebPageEvent
import io.github.kingsword09.kwebshell.core.KWebPageEventType
import io.github.kingsword09.kwebshell.core.KWebProfile
import io.github.kingsword09.kwebshell.desktop.KWebDesktop
import io.github.kingsword09.kwebshell.desktop.KWebDesktopEngineConfiguration
import io.github.kingsword09.kwebshell.example.support.KWebExampleCdpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import java.awt.GraphicsConfiguration
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

public data class BenchmarkConfiguration(
    public val cefRuntime: Path,
    public val browserSubprocess: Path,
    public val resources: Path,
    public val locales: Path,
    public val rootCache: Path,
    public val outputDirectory: Path,
    public val workloadRoot: Path,
    public val workloadLock: Path,
    public val baselineCatalog: Path,
    public val profileName: String = "application-benchmark",
    public val width: Int = 1440,
    public val height: Int = 980,
    public val timeoutMs: Long = 90_000L,
    public val warmupPairs: Int = 1,
    public val measuredPairs: Int = 10,
    public val machineClass: String = "unspecified",
    public val benchmarkGitRevision: String = "working-tree",
) {
    init {
        require(width > 0 && height > 0) { "Benchmark window dimensions must be positive." }
        require(timeoutMs > 0L) { "Benchmark timeout must be positive." }
        require(warmupPairs == 1) { "The benchmark requires exactly one warmup pair." }
        require(measuredPairs >= 10) { "The benchmark requires at least ten measured pairs." }
        require(profileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) { "Benchmark Profile name is unsafe." }
    }

    public companion object {
        public fun fromSystemProperties(): BenchmarkConfiguration = BenchmarkConfiguration(
            cefRuntime = requiredPath("kweb.engine.cef.runtime.path"),
            browserSubprocess = requiredPath("kweb.engine.subprocess.path"),
            resources = requiredPath("kweb.engine.resources.path"),
            locales = requiredPath("kweb.engine.locales.path"),
            rootCache = requiredPath("kweb.benchmark.root"),
            outputDirectory = requiredPath("kweb.benchmark.output"),
            workloadRoot = requiredPath("kweb.benchmark.workload.root"),
            workloadLock = requiredPath("kweb.benchmark.workload.lock"),
            baselineCatalog = requiredPath("kweb.benchmark.baseline.catalog"),
            machineClass = System.getProperty("kweb.benchmark.machine-class")?.takeIf(String::isNotBlank) ?: "unspecified",
            benchmarkGitRevision = System.getProperty("kweb.benchmark.git-revision")?.takeIf(String::isNotBlank) ?: "working-tree",
        )
    }
}

public class BenchmarkRunner(
    private val configuration: BenchmarkConfiguration,
) {
    public fun run(): Path {
        Files.createDirectories(configuration.rootCache)
        Files.createDirectories(configuration.outputDirectory)
        if (Files.list(configuration.rootCache).use { it.findAny().isPresent }) {
            throw BenchmarkException("runner.root-not-fresh", "Benchmark root '${configuration.rootCache}' is not empty; a fresh root is required.")
        }
        val lock = BenchmarkWorkloadVerifier.loadAndVerify(configuration.workloadLock, configuration.workloadRoot)
        val runtimeSha256 = BenchmarkDigest.sha256(configuration.cefRuntime)
        val host = BenchmarkHost.current()
        val baseline = BenchmarkBaselineVerifier.load(
            path = configuration.baselineCatalog,
            runtimeSha256 = runtimeSha256,
            workloadAggregateSha256 = lock.aggregateSha256,
            platform = host.platform,
            architecture = host.architecture,
            machineClass = configuration.machineClass,
        )
        val server = BenchmarkServer(configuration.workloadRoot)
        val artifactWriter = BenchmarkArtifactWriter(configuration.outputDirectory)
        val rawSamples = mutableListOf<BenchmarkRawSample>()
        val rawArtifacts = mutableListOf<BenchmarkRawArtifact>()
        val screenshots = mutableListOf<BenchmarkScreenshotArtifact>()
        var failure: Throwable? = null
        try {
            val totalPairs = configuration.warmupPairs + configuration.measuredPairs
            for (pairIndex in 0 until totalPairs) {
                val measured = pairIndex >= configuration.warmupPairs
                for (phase in listOf("cold", "warm")) {
                    val sampleId = UUID.randomUUID().toString()
                    val profileName = "${configuration.profileName}-${pairIndex.toString().padStart(2, '0')}"
                    val pageUrl = server.registerSample(sampleId, phase, profileName)
                    val paths = SamplePaths(configuration.outputDirectory.resolve(".staging"), sampleId, phase, pairIndex)
                    Files.createDirectories(paths.root)
                    runPhaseProcess(
                        pageUrl = pageUrl,
                        phase = phase,
                        sampleId = sampleId,
                        profileName = profileName,
                        measured = measured,
                        paths = paths,
                    )
                    val observation = server.awaitObservation(sampleId, configuration.timeoutMs)
                    val sample = readAndValidateSample(
                        observation = observation,
                        phase = phase,
                        sampleId = sampleId,
                        pairIndex = pairIndex,
                        profileName = profileName,
                        measured = measured,
                        paths = paths,
                        lock = lock,
                        runtimeSha256 = runtimeSha256,
                    )
                    rawSamples += sample
                    rawArtifacts += artifactWriter.writeRaw(sample)
                    if (measured && pairIndex == configuration.warmupPairs) {
                        screenshots += artifactWriter.writeScreenshot(sample, paths.screenshot)
                    }
                    Files.deleteIfExists(paths.screenshot)
                }
            }
            val report = BenchmarkAggregator.aggregate(
                lock,
                rawSamples,
                rawArtifacts,
                screenshots,
                configuration.warmupPairs,
                configuration.measuredPairs,
            )
            BenchmarkBaselineVerifier.verify(report, baseline)
            return artifactWriter.writeReport(report)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { server.close() } catch (error: Throwable) { if (failure == null) throw error else failure.addSuppressed(error) }
        }
    }

    private fun runPhaseProcess(
        pageUrl: String,
        phase: String,
        sampleId: String,
        profileName: String,
        measured: Boolean,
        paths: SamplePaths,
    ) {
        val cdpPort = findFreePort()
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java")
        if (!Files.isRegularFile(javaExecutable)) throw BenchmarkException("runner.java-missing", "JDK executable '$javaExecutable' is missing.")
        val classpath = System.getProperty("java.class.path")?.takeIf(String::isNotBlank) ?: throw BenchmarkException("runner.classpath-missing", "Benchmark coordinator classpath is empty.")
        val command = buildList {
            add(javaExecutable.toString()); add("--enable-native-access=ALL-UNNAMED"); add("-Djava.awt.headless=false")
            listOf("kweb.native.library.path", "kweb.engine.cef.runtime.path", "kweb.engine.subprocess.path", "kweb.engine.resources.path", "kweb.engine.locales.path", "kweb.benchmark.workload.root", "kweb.benchmark.workload.lock", "kweb.benchmark.baseline.catalog").forEach { name ->
                val value = System.getProperty(name)?.takeIf(String::isNotBlank) ?: throw BenchmarkException("runner.property-missing", "Missing required property '$name'.")
                add("-D$name=$value")
            }
            add("-Dkweb.benchmark.root=${configuration.rootCache.toAbsolutePath()}")
            add("-Dkweb.benchmark.output=${configuration.outputDirectory.toAbsolutePath()}")
            add("-Dkweb.benchmark.page.url=$pageUrl"); add("-Dkweb.benchmark.phase=$phase"); add("-Dkweb.benchmark.sample.id=$sampleId")
            add("-Dkweb.benchmark.profile=$profileName"); add("-Dkweb.benchmark.measured=$measured"); add("-Dkweb.benchmark.screenshot=${paths.screenshot}"); add("-Dkweb.benchmark.host=${paths.host}"); add("-Dkweb.benchmark.log=${paths.log}")
            add("-Dkweb.benchmark.cdp.port=$cdpPort")
            add("-cp"); add(classpath); add("io.github.kingsword09.kwebshell.example.benchmark.MainKt"); add("phase")
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(paths.log.toFile()).start()
        if (!process.waitFor(configuration.timeoutMs * 2, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw BenchmarkException("runner.phase-timeout", "Benchmark phase '$phase' sample '$sampleId' exceeded its timeout.")
        }
        if (process.exitValue() != 0) throw BenchmarkException("runner.phase-failed", "Benchmark phase '$phase' sample '$sampleId' exited ${process.exitValue()}: ${readLog(paths.log)}")
    }

    private fun readAndValidateSample(
        observation: BenchmarkPageObservation,
        phase: String,
        sampleId: String,
        pairIndex: Int,
        profileName: String,
        measured: Boolean,
        paths: SamplePaths,
        lock: BenchmarkWorkloadLock,
        runtimeSha256: String,
    ): BenchmarkRawSample {
        val host = try { BenchmarkJson.format.decodeFromString<HostEvidence>(Files.readString(paths.host)) } catch (error: Throwable) { throw BenchmarkException("runner.host-evidence-invalid", "Host evidence for '$sampleId' is invalid.", error) }
        val benchmarkHost = BenchmarkHost.current()
        val sample = BenchmarkRawSample(
            schemaVersion = BenchmarkMetricCatalog.SCHEMA_VERSION, sampleId = sampleId, pairIndex = pairIndex, measured = measured, phase = phase, profileName = profileName,
            startedAtEpochMs = host.startedAtEpochMs, endedAtEpochMs = host.endedAtEpochMs, workloadAggregateSha256 = lock.aggregateSha256,
            runtimeSha256 = runtimeSha256, chromiumProduct = host.chromiumProduct, protocolVersion = host.protocolVersion, revision = host.revision,
            javaScriptVersion = host.javaScriptVersion, gpuVendor = observation.evidence.getValue("gpuVendor"), gpuRenderer = observation.evidence.getValue("gpuRenderer"), displayScale = host.metrics.getValue("host.display.scale"),
            platform = benchmarkHost.platform, architecture = benchmarkHost.architecture, machineClass = configuration.machineClass,
            benchmarkGitRevision = configuration.benchmarkGitRevision, page = observation, hostMetrics = host.metrics, eventSequences = host.eventSequences, eventTypes = host.eventTypes, evidence = host.evidence,
        )
        BenchmarkValidator.validateRaw(sample)
        Files.deleteIfExists(paths.host)
        Files.deleteIfExists(paths.log)
        return sample
    }

    private fun readLog(path: Path): String = if (Files.isRegularFile(path)) Files.readString(path).takeLast(48 * 1024) else "<log missing>"

    private fun findFreePort(): Int = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { socket ->
        socket.localPort.takeIf { it in 1024..65535 } ?: throw BenchmarkException("runner.cdp-port-invalid", "The OS allocated an invalid CDP port.")
    }

    private data class SamplePaths(val root: Path, val sampleId: String, val phase: String, val pairIndex: Int) {
        val screenshot: Path get() = root.resolve("$sampleId.png")
        val host: Path get() = root.resolve("$sampleId-host.json")
        val log: Path get() = root.resolve("$sampleId.log")
    }
}

@kotlinx.serialization.Serializable
internal data class HostEvidence(
    val schemaVersion: Int,
    val phase: String,
    val sampleId: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val chromiumProduct: String,
    val protocolVersion: String,
    val revision: String,
    val javaScriptVersion: String,
    val metrics: Map<String, Double>,
    val eventSequences: List<Long>,
    val eventTypes: List<String>,
    val evidence: Map<String, String>,
)

private fun isWindows(): Boolean = System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows")
private fun requiredPath(name: String): Path = Path.of(System.getProperty(name)?.takeIf(String::isNotBlank) ?: throw BenchmarkException("runner.property-missing", "Missing required property '$name'.")).toAbsolutePath().normalize()
