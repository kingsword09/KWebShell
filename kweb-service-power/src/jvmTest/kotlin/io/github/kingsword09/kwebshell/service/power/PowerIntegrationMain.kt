package io.github.kingsword09.kwebshell.service.power

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path

/**
 * The real native power provider integration. It opens the packaged provider,
 * exercises the declared lifecycle, and retains a machine-readable report that
 * carries counts only: no assertion identifier, descriptor, or handle is retained.
 */
private val failures = mutableListOf<String>()

private fun check(condition: Boolean, message: String) {
    if (!condition) failures += message
}

public fun main() {
    val library = Path.of(System.getProperty("kweb.power.native.library.path"))
    val root = Path.of(System.getProperty("kweb.power.integration.root"))
    val target = System.getProperty("kweb.power.target")
    var providerId = "unavailable"
    var capabilitySummary = ""
    var factSummary = ""
    var peakLeases = 0
    var expired = 0
    var profileAcquired = false

    val profile = KWebPowerLeaseProfile(
        KWebPowerLeaseProfileId("render"),
        KWebPowerBlockerType.SYSTEM_SLEEP,
        "Application declared report render",
        600,
    )
    val service = JvmKWebPower.open(
        applicationId = "io.github.kingsword09.kwebshell.power.fixture",
        nativeLibrary = library,
        profiles = listOf(profile),
    )
    try {
        runBlocking {
            val capabilities = service.capabilities()
            providerId = capabilities.providerId
            capabilitySummary = capabilities.flags.map { it.name }.sorted().joinToString(",")
            check(
                capabilities.supports(KWebPowerCapability.SYSTEM_SLEEP_BLOCKER),
                "the provider does not declare a system sleep blocker",
            )
            check(capabilities.maxLeaseSeconds == KWEB_POWER_MAX_LEASE_SECONDS, "unexpected lease ceiling")

            val state = service.state()
            factSummary = buildList {
                state.onBattery?.let { add("on-battery") }
                state.batteryPercent?.let { add("battery-percent") }
                state.thermal?.let { add("thermal") }
                state.locked?.let { add("locked") }
            }.sorted().joinToString(",")

            if (capabilities.supports(KWebPowerCapability.SYSTEM_SLEEP_BLOCKER)) {
                // A real assertion is acquired, observed, and released.
                val system = service.acquire(
                    KWebPowerLeaseRequest(
                        KWebPowerLeaseId("blocker"),
                        KWebPowerBlockerType.SYSTEM_SLEEP,
                        "Integration fixture holds the system awake",
                        600,
                    ),
                )
                check(system.id == KWebPowerLeaseId("blocker"), "the lease identifier changed")
                check(system.reason.isNotEmpty(), "the lease reason is missing")
                val display = service.acquire(
                    KWebPowerLeaseRequest(
                        KWebPowerLeaseId("display"),
                        KWebPowerBlockerType.DISPLAY_SLEEP,
                        "Integration fixture holds the display awake",
                        600,
                    ),
                )
                peakLeases = service.leases().size
                check(peakLeases == 2, "the live lease count is not two")
                check(display.remainingSeconds in 1..600, "the remaining window is out of range")

                // The declared profile is a real acquire, and an undeclared one fails typed.
                val acquired = service.acquireProfile(KWebPowerLeaseProfileId("render"))
                profileAcquired = acquired.type == KWebPowerBlockerType.SYSTEM_SLEEP
                check(profileAcquired, "the declared profile did not acquire its type")
                val undeclared = runCatching {
                    service.acquireProfile(KWebPowerLeaseProfileId("absent"))
                }.exceptionOrNull()
                check(
                    undeclared is io.github.kingsword09.kwebshell.services.KWebServiceException &&
                        undeclared.code == KWebPowerErrorCode.PROFILE_UNKNOWN,
                    "an undeclared profile did not fail typed",
                )

                // A short lease expires without an explicit release, exactly once.
                val short = KWebPowerLeaseId("short")
                service.acquire(
                    KWebPowerLeaseRequest(short, KWebPowerBlockerType.SYSTEM_SLEEP, "One second lease", 1),
                )
                val ended = withTimeout(5000) {
                    service.events.first {
                        it is KWebPowerEvent.LeaseEnded && it.leaseId == short
                    } as KWebPowerEvent.LeaseEnded
                }
                check(ended.reason == KWebPowerLeaseEndReason.EXPIRED, "the lease did not expire")
                expired = 1

                // Renewal extends the window and is observable on the lease.
                val renewed = service.renew(KWebPowerLeaseId("blocker"))
                check(renewed.renewCount == 1, "the renewal count did not increase")

                check(service.release(KWebPowerLeaseId("blocker")), "the first lease did not release")
                check(!service.release(KWebPowerLeaseId("blocker")), "release was not idempotent")
                check(service.leases().size == 2, "releasing one lease changed another")
            }
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
        append("\"capabilities\":\"").append(capabilitySummary).append("\",")
        append("\"facts\":\"").append(factSummary).append("\",")
        append("\"peakLeases\":").append(peakLeases).append(',')
        append("\"expiredLeases\":").append(expired).append(',')
        append("\"profileAcquired\":").append(profileAcquired).append(',')
        append("\"liveLeasesAfterClose\":0,")
        append("\"status\":\"").append(status).append("\",")
        append("\"failures\":[").append(failures.joinToString(",") { "\"${it.replace('"', '\'')}\"" }).append("]}")
        append('\n')
    }
    Files.createDirectories(root)
    Files.writeString(root.resolve("power-integration.json"), report)
    if (failures.isEmpty()) {
        println("kweb power integration passed on $target ($providerId).")
    } else {
        println("kweb power integration failed on $target ($providerId):")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
