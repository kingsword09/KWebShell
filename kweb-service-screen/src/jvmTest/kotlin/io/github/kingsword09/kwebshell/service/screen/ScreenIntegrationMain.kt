package io.github.kingsword09.kwebshell.service.screen

import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * The real provider fixture: opens the packaged provider of this target, records
 * the declared capabilities and the published topology, and drives one conversion
 * and one resolution.
 */
public fun main() {
    val libraryPath = System.getProperty("kweb.screen.native.library.path")
        ?: throw IllegalStateException("The fixture requires -Dkweb.screen.native.library.path.")
    val target = System.getProperty("kweb.screen.target", "unknown")
    val root = Path.of(System.getProperty("kweb.screen.integration.root", "build/reports/screen-integration"))
    Files.createDirectories(root)

    val details = linkedMapOf<String, String>()
    var failure: Throwable? = null
    try {
        runBlocking { run(libraryPath, target, details) }
    } catch (error: Throwable) {
        failure = error
        details["failure"] = error::class.simpleName ?: "Throwable"
        details["failureCode"] = (error as? KWebException)?.code ?: "none"
    }
    val report = buildJsonObject {
        put("schemaVersion", 1)
        put("target", target)
        put("library", Path.of(libraryPath).fileName.toString())
        put("outcome", if (failure == null) "passed" else "failed")
        details.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
    }
    val reportFile = root.resolve("screen-integration.json")
    Files.writeString(reportFile, report.toString() + "\n")
    if (failure != null) {
        System.err.println("The screen integration fixture failed: $reportFile")
        throw failure
    }
    println("The screen integration fixture passed: $reportFile")
}

private suspend fun run(libraryPath: String, target: String, details: MutableMap<String, String>) {
    val service = try {
        JvmKWebScreens.open(
            applicationId = "io.github.kingsword09.kwebshell.screen.fixture",
            nativeLibrary = Path.of(libraryPath),
            packageIdentity = "kwebshell-screen-fixture",
        )
    } catch (error: KWebException) {
        // A session without the declared facility is a typed environment boundary,
        // not a fixture failure: the capability report carries the reason.
        details["host"] = error.code
        details["provider"] = "unavailable"
        return
    }
    try {
        details["host"] = "available"
        val capabilities = service.capabilities()
        details["provider"] = capabilities.providerId
        details["session"] = capabilities.session.name
        details["capabilities"] = capabilities.capabilities.map { it.name }.sorted().joinToString(",")
        details["spaces"] = capabilities.spaces.map { it.name }.sorted().joinToString(",")

        val topology = service.topology()
        details["displays"] = topology.displays.size.toString()
        details["primary"] = topology.primary?.id?.value ?: "none"
        val first = topology.primary ?: topology.displays.first()
        details["geometry"] = "${first.geometry.width}x${first.geometry.height}+${first.geometry.left}+${first.geometry.top}"
        details["nativeGeometry"] =
            "${first.nativeGeometry.width}x${first.nativeGeometry.height}+${first.nativeGeometry.left}+${first.nativeGeometry.top}"
        details["scale"] = first.scale?.permille?.toString() ?: "absent"
        details["workArea"] = first.workArea?.let { "${it.width}x${it.height}+${it.left}+${it.top}" } ?: "absent"
        details["refresh"] = first.refreshHertz?.toString() ?: "absent"
        details["rotation"] = first.rotation?.name ?: "absent"
        details["colorDepth"] = first.colorDepthBits?.toString() ?: "absent"
        details["physical"] = first.physicalSize?.let { "${it.widthMillimetres}x${it.heightMillimetres}mm" } ?: "absent"

        // Resolution never guesses: the display centre resolves to its own display.
        val centre = KWebScreenPoint(first.geometry.left + first.geometry.width / 2, first.geometry.top + first.geometry.height / 2)
        val resolved = service.displayAt(centre, KWebScreenSpace.LOGICAL)
        check(resolved == first.id) { "The display centre did not resolve to its own display." }
        val nearest = service.nearest(centre, KWebScreenSpace.LOGICAL)
        check(nearest == first.id) { "The nearest display from the centre is not the display itself." }
        details["resolved"] = resolved.value

        // One declared conversion, and the physical space when the session publishes it.
        val converted = service.convert(
            KWebScreenConversion(first.id, KWebScreenSpace.LOGICAL, KWebScreenSpace.NATIVE, centre),
        )
        check(converted.x >= first.nativeGeometry.left && converted.x < first.nativeGeometry.right) {
            "The converted point left the display's native rectangle."
        }
        details["conversion"] = "${converted.x},${converted.y}"
        details["physicalConversion"] = try {
            val physical = service.convert(
                KWebScreenConversion(first.id, KWebScreenSpace.LOGICAL, KWebScreenSpace.PHYSICAL, centre),
            )
            "${physical.x},${physical.y}"
        } catch (error: KWebException) {
            error.code
        }

        // The cursor is published where the session provides it and typed where not.
        details["cursor"] = try {
            val cursor = service.cursor()
            "${cursor.point.x},${cursor.point.y}:${cursor.space.name}:${cursor.displayId?.value ?: "none"}"
        } catch (error: KWebException) {
            error.code
        }

        // A provider read that observes no change publishes no event.
        val reread = service.topology()
        check(reread.displays.size == topology.displays.size) { "The topology changed between two reads." }
        details["topologyStable"] = "true"
    } finally {
        service.close()
    }
    check(service.lifecycle.value == KWebLifecycleState.CLOSED) { "The service did not close." }
}
