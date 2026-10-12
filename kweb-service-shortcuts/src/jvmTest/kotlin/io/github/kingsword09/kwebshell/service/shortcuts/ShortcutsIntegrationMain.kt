package io.github.kingsword09.kwebshell.service.shortcuts

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path

/**
 * The real native shortcut provider integration. It opens the packaged provider,
 * exercises the declared lifecycle, and retains a machine-readable report.
 */
private val failures = mutableListOf<String>()

private fun check(condition: Boolean, message: String) {
    if (!condition) failures += message
}

public fun main() {
    val library = Path.of(System.getProperty("kweb.shortcuts.native.library.path"))
    val root = Path.of(System.getProperty("kweb.shortcuts.integration.root"))
    val target = System.getProperty("kweb.shortcuts.target")
    var providerId = "unavailable"
    var capabilitySummary = ""
    var registrationCount = 0

    val service = JvmKWebShortcuts.open("io.github.kingsword09.kwebshell.shortcuts.fixture", library)
    try {
        runBlocking {
            val capabilities = service.capabilities()
            providerId = capabilities.providerId
            capabilitySummary = capabilities.flags.map { it.name }.sorted().joinToString(",")
            check(
                capabilities.supports(KWebShortcutCapability.GLOBAL_REGISTRATION),
                "the provider does not declare global registration",
            )
            check(capabilities.maxRegistrations == KWEB_SHORTCUTS_MAX_REGISTRATIONS, "unexpected registration ceiling")

            val combo = setOf(KWebShortcutModifier.CONTROL, KWebShortcutModifier.ALT, KWebShortcutModifier.SHIFT)
            val copy = KWebShortcutRegistration(
                KWebShortcutId("copy"),
                KWebShortcutAccelerator(combo, KWebShortcutKey.Named(KWebShortcutNamedKey.A)),
                KWebShortcutCommandId("text.copy"),
            )
            val registered = service.register(copy)
            check(registered.id == KWebShortcutId("copy"), "the registration identifier changed")
            check(service.isRegistered(KWebShortcutId("copy")), "the registration is not live")
            registrationCount = service.registrations().size
            check(registrationCount == 1, "the live registration count is not one")

            // The same combination under a different identifier is a typed conflict.
            val conflict = runCatching {
                service.register(
                    KWebShortcutRegistration(
                        KWebShortcutId("clash"),
                        KWebShortcutAccelerator(combo, KWebShortcutKey.Named(KWebShortcutNamedKey.A)),
                        KWebShortcutCommandId("text.clash"),
                    ),
                )
            }.exceptionOrNull()
            check(
                conflict is io.github.kingsword09.kwebshell.services.KWebServiceException &&
                    conflict.code == KWebShortcutErrorCode.CONFLICT,
                "the conflicting accelerator did not fail typed",
            )

            // A renderer dispatch of a declared command emits one renderer invocation.
            service.dispatch(KWebShortcutCommandId("text.copy"))
            val invoked = withTimeout(2000) {
                service.events.first { it is KWebShortcutEvent.Invoked }
            } as KWebShortcutEvent.Invoked
            check(invoked.source == KWebShortcutInvocationSource.RENDERER, "the invocation source is not the renderer")
            check(invoked.commandId == KWebShortcutCommandId("text.copy"), "the invoked command is wrong")

            // A dispatch of an undeclared command fails typed.
            val undeclared = runCatching { service.dispatch(KWebShortcutCommandId("text.unknown")) }.exceptionOrNull()
            check(
                undeclared is io.github.kingsword09.kwebshell.services.KWebServiceException &&
                    undeclared.code == KWebShortcutErrorCode.COMMAND_UNDECLARED,
                "an undeclared command did not fail typed",
            )

            val removed = service.unregister(KWebShortcutId("copy"))
            check(removed.wasRegistered, "the unregistration did not report the live registration")
            check(!service.isRegistered(KWebShortcutId("copy")), "the registration is still live after unregistration")
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
        append("\"registrationCount\":").append(registrationCount).append(',')
        append("\"status\":\"").append(status).append("\",")
        append("\"failures\":[").append(failures.joinToString(",") { "\"${it.replace('"', '\'')}\"" }).append("]}")
        append('\n')
    }
    Files.createDirectories(root)
    Files.writeString(root.resolve("shortcuts-integration.json"), report)
    if (failures.isEmpty()) {
        println("kweb shortcuts integration passed on $target ($providerId).")
    } else {
        println("kweb shortcuts integration failed on $target ($providerId):")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
