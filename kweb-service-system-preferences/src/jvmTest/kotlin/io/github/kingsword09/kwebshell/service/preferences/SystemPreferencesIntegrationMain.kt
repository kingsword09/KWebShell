package io.github.kingsword09.kwebshell.service.preferences

import io.github.kingsword09.kwebshell.core.KWebAppearanceSource
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.services.KWebServiceException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * The real provider fixture: opens the packaged provider of this target,
 * records the declared capabilities and the published facts, and drives one
 * declared appearance request together with the ordered update it produces.
 */
public fun main() {
    val libraryPath = System.getProperty("kweb.preferences.native.library.path")
        ?: throw IllegalStateException("The fixture requires -Dkweb.preferences.native.library.path.")
    val target = System.getProperty("kweb.preferences.target", "unknown")
    val root = Path.of(System.getProperty("kweb.preferences.integration.root", "build/reports/preferences-integration"))
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
    val reportFile = root.resolve("preferences-integration.json")
    Files.writeString(reportFile, report.toString() + "\n")
    if (failure != null) {
        System.err.println("The system-preferences integration fixture failed: $reportFile")
        throw failure
    }
    println("The system-preferences integration fixture passed: $reportFile")
}

private suspend fun run(libraryPath: String, target: String, details: MutableMap<String, String>) {
    val sensitiveKey = when {
        target.startsWith("macos") -> KWebSystemPreferenceSensitiveKeys.MACOS_VOICEOVER
        target.startsWith("windows") -> KWebSystemPreferenceSensitiveKeys.WINDOWS_SCREEN_READER
        else -> KWebSystemPreferenceSensitiveKeys.LINUX_A11Y
    }
    details["sensitiveKeys"] = sensitiveKey
    val service = try {
        JvmKWebSystemPreferences.open(
            applicationId = "io.github.kingsword09.kwebshell.preferences.fixture",
            nativeLibrary = Path.of(libraryPath),
            packageIdentity = "kwebshell-preferences-fixture",
            sensitiveKeys = setOf(sensitiveKey),
        )
    } catch (error: KWebException) {
        // A target without the declared facility is a typed environment boundary,
        // not a fixture failure: the capability report carries the reason.
        details["host"] = error.code
        details["provider"] = "unavailable"
        details["appearanceRequest"] = "skipped"
        return
    }
    try {
        details["host"] = "available"
        val capabilities = service.capabilities()
        details["provider"] = capabilities.providerId
        details["capabilities"] = capabilities.publishedFacts.map { it.name }.sorted().joinToString(",")
        details["liveFacts"] = capabilities.liveFacts.map { it.name }.sorted().joinToString(",")
        details["declaredKeys"] = capabilities.sensitiveKeys.sorted().joinToString(",")

        val initial = service.snapshot()
        details["facts"] = initial.facts.map { it.name }.sorted().joinToString(",")
        details["colorScheme"] = initial.colorScheme?.name ?: "absent"
        details["contrast"] = initial.contrast?.name ?: "absent"
        details["reducedMotion"] = initial.reducedMotion?.toString() ?: "absent"
        details["reducedTransparency"] = initial.reducedTransparency?.toString() ?: "absent"
        details["differentiateWithoutColor"] = initial.differentiateWithoutColor?.toString() ?: "absent"
        details["invertColors"] = initial.invertColors?.toString() ?: "absent"
        details["accentColor"] = initial.accentColor?.let { "${it.hex}:${it.source.name}" } ?: "absent"
        details["textScale"] = initial.textScale?.percent?.toString() ?: "absent"
        details["screenReader"] = initial.screenReader?.toString() ?: "absent"
        details["initialSequence"] = initial.sequence.toString()

        val system = service.requestAppearance(KWebAppearanceSource.SYSTEM)
        check(system.effective == KWebAppearanceSource.SYSTEM) {
            "The system appearance request did not report the system source."
        }
        val requested = when (initial.colorScheme) {
            KWebColorScheme.LIGHT -> KWebAppearanceSource.DARK
            KWebColorScheme.DARK -> KWebAppearanceSource.LIGHT
            null -> KWebAppearanceSource.DARK
        }
        val expectedScheme = if (requested == KWebAppearanceSource.DARK) {
            KWebColorScheme.DARK
        } else {
            KWebColorScheme.LIGHT
        }
        details["appearanceRequest"] = requested.name
        try {
            val applied = service.requestAppearance(requested)
            check(applied.effective == requested) { "The appearance request did not become effective." }
            details["appearanceRequest"] = "${requested.name}:applied"
            val event = withTimeoutOrNull(5_000) {
                service.events.first { current ->
                    current is KWebSystemPreferenceEvent.Changed &&
                        KWebPreferenceFact.APPEARANCE_SOURCE in current.changedFacts
                }
            }
            checkNotNull(event) { "The applied appearance produced no ordered update." }
            val changed = event as KWebSystemPreferenceEvent.Changed
            check(changed.sequence > system.sequence) { "The change sequence did not advance." }
            check(changed.snapshot.appearanceSource == requested) {
                "The changed snapshot does not report the requested source."
            }
            check(changed.snapshot.colorScheme == expectedScheme) {
                "The published color scheme does not follow the applied appearance."
            }
            details["appearanceEvent"] = "changed:${changed.changedFacts.map { it.name }.sorted().joinToString(",")}"
            details["appearanceSequence"] = changed.sequence.toString()
            val restored = service.requestAppearance(KWebAppearanceSource.SYSTEM)
            check(restored.effective == KWebAppearanceSource.SYSTEM) { "The appearance was not restored." }
            details["restoredSequence"] = restored.sequence.toString()
        } catch (error: KWebServiceException) {
            // A target that publishes no override request reports the typed boundary.
            check(error.code == KWebSystemPreferenceErrorCode.APPEARANCE_UNSUPPORTED) {
                "The appearance request failed with ${error.code} instead of the typed boundary."
            }
            details["appearanceRequest"] = "${requested.name}:${error.code}"
        }
        val final = service.snapshot()
        details["finalSequence"] = final.sequence.toString()
    } finally {
        service.close()
    }
    check(service.lifecycle.value == KWebLifecycleState.CLOSED) { "The service did not close." }
}
