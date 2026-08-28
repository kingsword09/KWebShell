package io.github.kingsword09.kwebshell.services

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebServiceContractTest {
    @Test
    fun versionRejectsNegativeComponentsWithTypedConfigurationError() {
        val failure = assertFailsWith<KWebConfigurationException> {
            KWebServiceVersion(-1, 0, 0)
        }
        assertEquals("service.version-invalid", failure.code)
    }

    @Test
    fun registryRequiresExactKeysAndRejectsDuplicates() {
        val service = RecordingService("demo", KWebServiceVersion(1, 0, 0))
        val registry = KWebNativeServiceRegistry()
        val key = RecordingKey("demo", KWebServiceVersion(1, 0, 0))

        registry.install(key, service)
        assertEquals(service, registry.require(key))
        val duplicate = assertFailsWith<KWebServiceException> { registry.install(key, service) }
        assertEquals("service.duplicate-installation", duplicate.code)
        val mismatch = assertFailsWith<KWebServiceException> {
            registry.require(RecordingKey("demo", KWebServiceVersion(1, 1, 0)))
        }
        assertEquals(KWebServiceErrorCode.VERSION_INCOMPATIBLE, mismatch.code)
        registry.close()
        assertEquals(KWebLifecycleState.CLOSED, registry.lifecycle.value)
        assertEquals(1, service.closeCalls)
    }

    @Test
    fun registryReportsMissingServiceAndOwnerClose() {
        val registry = KWebNativeServiceRegistry()
        val key = RecordingKey("missing", KWebServiceVersion(1, 0, 0))
        val missing = assertFailsWith<KWebServiceException> { registry.require(key) }
        assertEquals(KWebServiceErrorCode.NOT_INSTALLED, missing.code)
        registry.close()
        val closed = assertFailsWith<KWebServiceException> { registry.require(key) }
        assertEquals(KWebServiceErrorCode.OWNER_CLOSED, closed.code)
    }

    @Test
    fun registryAttemptsEveryServiceAndRetainsTerminalFailure() {
        val closeOrder = mutableListOf<String>()
        val registry = KWebNativeServiceRegistry()
        val first = RecordingService("first", KWebServiceVersion(1, 0, 0), closeOrder)
        val failing = RecordingService(
            "failing",
            KWebServiceVersion(1, 0, 0),
            closeOrder,
            failOnClose = true,
        )
        registry.install(RecordingKey("first", KWebServiceVersion(1, 0, 0)), first)
        registry.install(RecordingKey("failing", KWebServiceVersion(1, 0, 0)), failing)

        val failure = assertFailsWith<KWebNativeException> { registry.close() }
        assertEquals(KWebServiceErrorCode.NATIVE_FAILED, failure.code)
        assertEquals(KWebLifecycleState.FAILED, registry.lifecycle.value)
        assertEquals(listOf("failing", "first"), closeOrder)
        assertEquals(1, first.closeCalls)
        assertEquals(1, failing.closeCalls)

        val repeated = assertFailsWith<KWebNativeException> { registry.close() }
        assertTrue(repeated === failure)
        val useAfterFailure = assertFailsWith<KWebServiceException> {
            registry.require(RecordingKey("first", KWebServiceVersion(1, 0, 0)))
        }
        assertEquals(KWebServiceErrorCode.OWNER_CLOSED, useAfterFailure.code)
    }

    @Test
    fun registryRejectsClosedServiceInstallationAndAccess() {
        val closed = RecordingService("closed", KWebServiceVersion(1, 0, 0))
        closed.close()
        val registry = KWebNativeServiceRegistry()
        val key = RecordingKey("closed", KWebServiceVersion(1, 0, 0))
        val installFailure = assertFailsWith<KWebServiceException> {
            registry.install(key, closed)
        }
        assertEquals(KWebServiceErrorCode.OWNER_CLOSED, installFailure.code)

        val open = RecordingService("open", KWebServiceVersion(1, 0, 0))
        registry.install(RecordingKey("open", KWebServiceVersion(1, 0, 0)), open)
        open.close()
        val accessFailure = assertFailsWith<KWebServiceException> {
            registry.require(RecordingKey("open", KWebServiceVersion(1, 0, 0)))
        }
        assertEquals(KWebServiceErrorCode.OWNER_CLOSED, accessFailure.code)
        registry.close()
    }

    private class RecordingKey(
        override val id: String,
        override val version: KWebServiceVersion,
    ) : KWebServiceKey<RecordingService>

    private class RecordingService(
        id: String,
        version: KWebServiceVersion,
        private val closeOrder: MutableList<String> = mutableListOf(),
        private val failOnClose: Boolean = false,
    ) : KWebNativeService {
        private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
        var closeCalls: Int = 0
        override val lifecycle = mutableLifecycle
        override val descriptor = KWebServiceDescriptor(
            id = id,
            version = version,
            scope = KWebServiceScope.APPLICATION,
            operations = setOf(KWebServiceOperationDescriptor("resolve", 1, null, false)),
            requiredCapabilities = emptySet(),
            supportedTargets = KWebTarget.supported,
        )

        override fun close() {
            closeCalls += 1
            closeOrder += descriptor.id
            if (failOnClose) {
                throw IllegalStateException("close failed")
            }
            mutableLifecycle.value = KWebLifecycleState.CLOSED
        }
    }
}
