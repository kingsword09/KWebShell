package io.github.kingsword09.kwebshell.service.power

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KWebPowerContractTest {
    private fun request(
        id: String = "render",
        type: KWebPowerBlockerType = KWebPowerBlockerType.DISPLAY_SLEEP,
        reason: String = "Exporting a report",
        seconds: Int = 600,
    ) = KWebPowerLeaseRequest(KWebPowerLeaseId(id), type, reason, seconds)

    @Test
    fun identifiersAreBoundedAndStable() {
        assertEquals("render", KWebPowerLeaseId("render").value)
        KWebPowerLeaseProfileId("background.sync")
        assertFailsWith<KWebConfigurationException> { KWebPowerLeaseId("1render") }
        assertFailsWith<KWebConfigurationException> { KWebPowerLeaseId("render now") }
        assertFailsWith<KWebConfigurationException> { KWebPowerLeaseId("a".repeat(129)) }
        assertFailsWith<KWebConfigurationException> { KWebPowerLeaseProfileId("") }
    }

    @Test
    fun reasonsAreBoundedAndControlFree() {
        request(reason = "Exporting a report")
        assertFailsWith<KWebConfigurationException> { request(reason = "   ") }
        assertFailsWith<KWebConfigurationException> { request(reason = "") }
        assertFailsWith<KWebConfigurationException> {
            request(reason = "r".repeat(KWEB_POWER_MAX_REASON_BYTES + 1))
        }
        assertFailsWith<KWebConfigurationException> { request(reason = "bad\u0000reason") }
    }

    @Test
    fun durationsAreRequiredAndCeilinged() {
        request(seconds = KWEB_POWER_MIN_LEASE_SECONDS)
        request(seconds = KWEB_POWER_MAX_LEASE_SECONDS)
        val zero = assertFailsWith<KWebConfigurationException> { request(seconds = 0) }
        assertEquals(KWebPowerErrorCode.DURATION_INVALID, zero.code)
        val negative = assertFailsWith<KWebConfigurationException> { request(seconds = -1) }
        assertEquals(KWebPowerErrorCode.DURATION_INVALID, negative.code)
        val tooLong = assertFailsWith<KWebConfigurationException> { request(seconds = KWEB_POWER_MAX_LEASE_SECONDS + 1) }
        assertEquals(KWebPowerErrorCode.DURATION_EXCEEDED, tooLong.code)
    }

    @Test
    fun profilesCarryTheSameRulesAsRequests() {
        KWebPowerLeaseProfile(
            KWebPowerLeaseProfileId("media"),
            KWebPowerBlockerType.SYSTEM_SLEEP,
            "Playing back media",
            3600,
        )
        assertFailsWith<KWebConfigurationException> {
            KWebPowerLeaseProfile(KWebPowerLeaseProfileId("media"), KWebPowerBlockerType.SYSTEM_SLEEP, "", 60)
        }
        assertFailsWith<KWebConfigurationException> {
            KWebPowerLeaseProfile(
                KWebPowerLeaseProfileId("media"),
                KWebPowerBlockerType.SYSTEM_SLEEP,
                "Playing back media",
                KWEB_POWER_MAX_LEASE_SECONDS + 1,
            )
        }
    }

    @Test
    fun stateKeepsUnpublishedFactsAbsent() {
        val absent = KWebPowerState(onBattery = null, batteryPercent = null, thermal = null, locked = null)
        assertNull(absent.onBattery)
        assertNull(absent.thermal)
        KWebPowerState(onBattery = true, batteryPercent = 0, thermal = KWebPowerThermalLevel.NOMINAL, locked = false)
        KWebPowerState(onBattery = false, batteryPercent = 100, thermal = null, locked = null)
        assertFailsWith<KWebConfigurationException> {
            KWebPowerState(onBattery = true, batteryPercent = 101, thermal = null, locked = null)
        }
    }

    @Test
    fun capabilityReportAnswersDeclaredQueries() {
        val capabilities = KWebPowerCapabilities(
            providerId = "power.macos.workspace-iokit",
            flags = setOf(
                KWebPowerCapability.SUSPEND_EVENTS,
                KWebPowerCapability.LOCK_EVENTS,
                KWebPowerCapability.SYSTEM_SLEEP_BLOCKER,
                KWebPowerCapability.DISPLAY_SLEEP_BLOCKER,
            ),
            maxLeases = KWEB_POWER_MAX_LEASES,
            maxLeasesPerType = KWEB_POWER_MAX_LEASES_PER_TYPE,
            minLeaseSeconds = KWEB_POWER_MIN_LEASE_SECONDS,
            maxLeaseSeconds = KWEB_POWER_MAX_LEASE_SECONDS,
        )
        assertTrue(capabilities.supports(KWebPowerCapability.SYSTEM_SLEEP_BLOCKER))
        assertTrue(!capabilities.supports(KWebPowerCapability.THERMAL_STATE))
    }

    @Test
    fun eventsCarryAStrictlyOrderedIdentity() {
        KWebPowerEvent.Suspended(1u)
        KWebPowerEvent.Resumed(2u)
        KWebPowerEvent.Locked(3u)
        KWebPowerEvent.Unlocked(4u)
        KWebPowerEvent.PowerSourceChanged(true, 5u)
        KWebPowerEvent.ThermalChanged(KWebPowerThermalLevel.SERIOUS, 6u)
        val ended = KWebPowerEvent.LeaseEnded(KWebPowerLeaseId("render"), KWebPowerLeaseEndReason.EXPIRED, 7u)
        assertEquals(KWebPowerLeaseEndReason.EXPIRED, ended.reason)
        KWebPowerEvent.Failed(KWebPowerErrorCode.NATIVE_FAILED, 8u)
    }

    @Test
    fun descriptorPublishesHostAndPolicyGatedRendererOperations() {
        val descriptor = KWebPower.DESCRIPTOR
        assertEquals("power", descriptor.id)
        assertEquals("1.0.0", descriptor.version.toString())
        assertEquals(3, descriptor.supportedTargets.size)
        val host = descriptor.operations.filter { it.rendererPermission == null }.map { it.id }.toSet()
        assertEquals(
            setOf("capabilities", "state", "acquire", "renew", "release", "leases", "events"),
            host,
        )
        val read = descriptor.operations.single { it.id == "read" }
        assertEquals(KWebPower.RENDERER_READ_PERMISSION, read.rendererPermission)
        assertEquals(false, read.requiresUserGesture)
        val acquire = descriptor.operations.single { it.id == "acquire-profile" }
        assertEquals(KWebPower.RENDERER_ACQUIRE_PERMISSION, acquire.rendererPermission)
        assertEquals(true, acquire.requiresUserGesture)
        assertEquals(KWebLifecycleState.OPEN, KWebLifecycleState.OPEN)
    }
}
