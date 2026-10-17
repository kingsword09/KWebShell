package io.github.kingsword09.kwebshell.service.processes

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The published RFC 0024 contract bounds, descriptor, and value rules. */
class KWebProcessesContractTest {

    private fun spec(
        id: String = "worker",
        executable: String = "helper",
        workingDirectory: String = "scratch",
        arguments: List<String> = listOf("report"),
        environment: Map<String, String> = mapOf("KWEB_DECLARED" to "1"),
        limits: KWebProcessLimits = limits(),
        streams: KWebProcessStreamPolicy = streams(),
        isolation: Set<KWebProcessIsolation> = setOf(
            KWebProcessIsolation.PROCESS_TREE,
            KWebProcessIsolation.RESOURCE_LIMITS,
        ),
    ): KWebProcessSpec = KWebProcessSpec(
        id = KWebProcessId(id),
        executableId = KWebProcessExecutableId(executable),
        workingDirectoryId = KWebProcessWorkingDirectoryId(workingDirectory),
        arguments = arguments,
        environment = environment,
        limits = limits,
        streams = streams,
        requiredIsolation = isolation,
    )

    private fun limits(
        timeoutSeconds: Int = 30,
        cpuSeconds: Int = 10,
        memoryBytes: Long = KWebProcessesBounds.MIN_MEMORY_BYTES,
        stdoutQuotaBytes: Long = KWebProcessesBounds.MIN_OUTPUT_QUOTA_BYTES,
        stderrQuotaBytes: Long = KWebProcessesBounds.MIN_OUTPUT_QUOTA_BYTES,
    ): KWebProcessLimits = KWebProcessLimits(
        timeoutSeconds = timeoutSeconds,
        cpuSeconds = cpuSeconds,
        memoryBytes = memoryBytes,
        stdoutQuotaBytes = stdoutQuotaBytes,
        stderrQuotaBytes = stderrQuotaBytes,
    )

    private fun streams(
        stdoutWindowBytes: Int = KWebProcessesBounds.MIN_OUTPUT_WINDOW_BYTES,
        stderrWindowBytes: Int = KWebProcessesBounds.MIN_OUTPUT_WINDOW_BYTES,
    ): KWebProcessStreamPolicy = KWebProcessStreamPolicy(stdoutWindowBytes, stderrWindowBytes)

    @Test
    fun identifiersAcceptThePublishedFormAndRejectEverythingElse() {
        assertEquals("worker-1", KWebProcessId("worker-1").value)
        assertEquals("a", KWebProcessExecutableId("a").value)
        assertEquals("a.b_c-d", KWebProcessWorkingDirectoryId("a.b_c-d").value)
        assertEquals(128, KWebProcessId("a" + "b".repeat(127)).value.length)
        for (value in listOf("", "1worker", "-worker", "worker/child", "worker child", "a".repeat(129) + "b")) {
            val failure = assertFailsWith<KWebConfigurationException> { KWebProcessId(value) }
            assertEquals(KWebProcessesErrorCode.ID_INVALID, failure.code)
        }
    }

    @Test
    fun anArgumentOrEnvironmentEntryBeyondItsBoundFailsBeforeAProviderSeesIt() {
        assertFailsWith<KWebConfigurationException> {
            spec(arguments = listOf("a".repeat(KWebProcessesBounds.MAX_ARGUMENT_BYTES + 1)))
        }.also { assertEquals(KWebProcessesErrorCode.ARGUMENT_INVALID, it.code) }
        assertFailsWith<KWebConfigurationException> {
            spec(arguments = (0..KWebProcessesBounds.MAX_ARGUMENTS).map { "a$it" })
        }.also { assertEquals(KWebProcessesErrorCode.ARGUMENT_INVALID, it.code) }
        assertFailsWith<KWebConfigurationException> { spec(arguments = listOf("with\u0000nul")) }
            .also { assertEquals(KWebProcessesErrorCode.ARGUMENT_INVALID, it.code) }
        // Unicode arguments are ordinary declared bytes and must be accepted.
        assertEquals(1, spec(arguments = listOf("héllo-プロセス")).arguments.size)

        assertFailsWith<KWebConfigurationException> {
            spec(environment = mapOf("1BAD" to "1"))
        }.also { assertEquals(KWebProcessesErrorCode.ENVIRONMENT_INVALID, it.code) }
        assertFailsWith<KWebConfigurationException> {
            spec(environment = mapOf("KWEB" to "v".repeat(KWebProcessesBounds.MAX_ENVIRONMENT_VALUE_BYTES + 1)))
        }.also { assertEquals(KWebProcessesErrorCode.ENVIRONMENT_INVALID, it.code) }
        assertFailsWith<KWebConfigurationException> {
            spec(environment = (0..KWebProcessesBounds.MAX_ENVIRONMENT).associate { "KWEB_$it" to "1" })
        }.also { assertEquals(KWebProcessesErrorCode.ENVIRONMENT_INVALID, it.code) }
    }

    @Test
    fun limitsAndStreamWindowsAreRequiredAndBounded() {
        assertEquals(30, spec().limits.timeoutSeconds)
        assertFailsWith<KWebConfigurationException> { limits(timeoutSeconds = 0) }
            .also { assertEquals(KWebProcessesErrorCode.LIMIT_INVALID, it.code) }
        assertFailsWith<KWebConfigurationException> {
            limits(timeoutSeconds = KWebProcessesBounds.MAX_TIMEOUT_SECONDS + 1)
        }.also { assertEquals(KWebProcessesErrorCode.LIMIT_INVALID, it.code) }
        assertFailsWith<KWebConfigurationException> { limits(cpuSeconds = 0) }
            .also { assertEquals(KWebProcessesErrorCode.LIMIT_INVALID, it.code) }
        assertFailsWith<KWebConfigurationException> {
            limits(memoryBytes = KWebProcessesBounds.MIN_MEMORY_BYTES - 1)
        }.also { assertEquals(KWebProcessesErrorCode.LIMIT_INVALID, it.code) }
        assertFailsWith<KWebConfigurationException> {
            limits(memoryBytes = KWebProcessesBounds.MAX_MEMORY_BYTES + 1)
        }.also { assertEquals(KWebProcessesErrorCode.LIMIT_INVALID, it.code) }
        assertFailsWith<KWebConfigurationException> {
            limits(stdoutQuotaBytes = KWebProcessesBounds.MIN_OUTPUT_QUOTA_BYTES - 1)
        }.also { assertEquals(KWebProcessesErrorCode.LIMIT_INVALID, it.code) }
        assertFailsWith<KWebConfigurationException> {
            limits(stderrQuotaBytes = KWebProcessesBounds.MAX_OUTPUT_QUOTA_BYTES + 1)
        }.also { assertEquals(KWebProcessesErrorCode.LIMIT_INVALID, it.code) }
        assertFailsWith<KWebConfigurationException> {
            streams(stdoutWindowBytes = KWebProcessesBounds.MIN_OUTPUT_WINDOW_BYTES - 1)
        }.also { assertEquals(KWebProcessesErrorCode.STREAM_INVALID, it.code) }
        assertFailsWith<KWebConfigurationException> {
            streams(stderrWindowBytes = KWebProcessesBounds.MAX_OUTPUT_WINDOW_BYTES + 1)
        }.also { assertEquals(KWebProcessesErrorCode.STREAM_INVALID, it.code) }
    }

    @Test
    fun aSpecificationAlwaysRequiresItsProcessTree() {
        assertFailsWith<KWebConfigurationException> {
            spec(isolation = setOf(KWebProcessIsolation.RESOURCE_LIMITS))
        }.also { assertEquals(KWebProcessesErrorCode.ISOLATION_INVALID, it.code) }
        assertEquals(
            setOf(KWebProcessIsolation.PROCESS_TREE, KWebProcessIsolation.RESOURCE_LIMITS),
            spec().requiredIsolation,
        )
        assertTrue(
            spec(isolation = setOf(KWebProcessIsolation.PROCESS_TREE, KWebProcessIsolation.SEATBELT))
                .requiredIsolation
                .contains(KWebProcessIsolation.SEATBELT),
        )
    }

    @Test
    fun theDescriptorPublishesHostOperationsOnly() {
        val descriptor = KWebProcesses.DESCRIPTOR
        assertEquals("processes", descriptor.id)
        assertEquals("1.0.0", descriptor.version.toString())
        assertContains(descriptor.operations.map { it.id }, "start")
        assertContains(descriptor.operations.map { it.id }, "capabilities")
        assertContains(descriptor.operations.map { it.id }, "processes")
        // No renderer operation exists: a page can never name an executable, an
        // argument, or a working directory through the service descriptor.
        assertTrue(descriptor.operations.all { it.rendererPermission == null })
        assertTrue(descriptor.operations.none { it.requiresUserGesture })
        assertEquals(3, descriptor.operations.size)
        assertEquals("processes", KWebProcesses.Key.id)
    }

    @Test
    fun anOutputChunkIsImmutableAndNeverLeaksBytesThroughToString() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val chunk = KWebProcessOutputChunk(sequence = 7, stream = KWebProcessStream.STDOUT, bytes = bytes, totalBytes = 4)
        bytes[0] = 99
        assertEquals(4, chunk.size)
        assertEquals(1, chunk.bytes()[0])
        val copy = chunk.bytes()
        copy[0] = 42
        assertEquals(1, chunk.bytes()[0])
        assertEquals(chunk, KWebProcessOutputChunk(7, KWebProcessStream.STDOUT, byteArrayOf(1, 2, 3, 4), 4))
        assertTrue(!chunk.toString().contains("1, 2"))
        assertTrue(chunk.toString().contains("sequence=7"))
    }

    @Test
    fun theCapabilityReportAnswersDeclaredIsolation() {
        val capabilities = KWebProcessCapabilities(
            providerId = "processes.macos.posix-exec",
            declaredIsolation = setOf(KWebProcessIsolation.PROCESS_TREE, KWebProcessIsolation.RESOURCE_LIMITS),
            memoryMeasure = KWebProcessMemoryMeasure.RESIDENT,
            maxLiveProcesses = KWebProcessesBounds.MAX_LIVE_PROCESSES,
            liveProcesses = 0,
            bounds = KWebProcessesBounds.PUBLISHED,
        )
        assertTrue(capabilities.supports(KWebProcessIsolation.PROCESS_TREE))
        assertTrue(!capabilities.supports(KWebProcessIsolation.SEATBELT))
        assertEquals(8, capabilities.bounds.maxLiveProcesses)
        assertEquals(65536, capabilities.bounds.maxStdinChunkBytes)
    }
}
