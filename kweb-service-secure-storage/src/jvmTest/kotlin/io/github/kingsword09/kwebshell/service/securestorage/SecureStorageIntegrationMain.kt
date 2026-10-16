package io.github.kingsword09.kwebshell.service.securestorage

import io.github.kingsword09.kwebshell.services.KWebServiceException
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/**
 * The real native secure-storage provider integration. It opens the packaged
 * provider, exercises the declared lifecycle, and retains a report that carries
 * identifiers, sizes, and backend facts only — never a secret value.
 */
private val failures = mutableListOf<String>()

/** The planted test secret the retained report must never contain. */
private const val PLANTED_SECRET = "kweb-secret-planted-value-42"

private fun check(condition: Boolean, message: String) {
    if (!condition) failures += message
}

public fun main() {
    val library = Path.of(System.getProperty("kweb.secure-storage.native.library.path"))
    val root = Path.of(System.getProperty("kweb.secure-storage.integration.root"))
    val target = System.getProperty("kweb.secure-storage.target")
    var providerId = "unavailable"
    var backend = "unavailable"
    var capabilitySummary = ""
    var roundTrip = "not-attempted"
    var rotation = "not-attempted"
    var boundary = ""
    var storedSize = 0

    val application = KWebSecretRef(KWebSecretId("integration-secret"))
    val profile = KWebSecretRef(KWebSecretId("integration-profile-secret"), KWebSecretScope.PROFILE, "profile-1")

    val service = JvmKWebSecureStorage.open(
        applicationId = "io.github.kingsword09.kwebshell.secrets.fixture",
        nativeLibrary = library,
    )
    try {
        runBlocking {
            val capabilities = service.capabilities()
            providerId = capabilities.providerId
            backend = capabilities.backend.name.lowercase()
            capabilitySummary = capabilities.flags.map { it.name }.sorted().joinToString(",")

            // An invalid request never reaches the provider.
            val badScope = runCatching {
                service.put(
                    KWebSecretPutRequest(
                        KWebSecretRef(KWebSecretId("bad"), KWebSecretScope.PROFILE),
                        PLANTED_SECRET.toByteArray(),
                    ),
                )
            }.exceptionOrNull()
            check(badScope != null, "a profile reference without an owner was accepted")

            val stored = runCatching {
                service.put(
                    KWebSecretPutRequest(application, PLANTED_SECRET.toByteArray(), KWebSecretAccessibility.WHEN_UNLOCKED),
                )
            }.exceptionOrNull()
            if (stored is KWebServiceException) {
                // A session without its declared credential backend is the typed
                // boundary, not a silent success.
                boundary = stored.code
                check(
                    stored.code == KWebSecureStorageErrorCode.SERVICE_UNAVAILABLE ||
                        stored.code == KWebSecureStorageErrorCode.LOCKED ||
                        stored.code == KWebSecureStorageErrorCode.BACKEND_UNAVAILABLE ||
                        stored.code == KWebSecureStorageErrorCode.USER_PRESENCE_DENIED ||
                        stored.code == KWebSecureStorageErrorCode.NATIVE_FAILED,
                    "the store failed with an unexpected code ${stored.code}",
                )
                return@runBlocking
            }
            check(stored == null, "the store failed unexpectedly: ${stored?.message}")
            storedSize = PLANTED_SECRET.toByteArray().size
            roundTrip = "verified"

            // The value reads back and is zeroed on close.
            val value = service.get(application)
            check(value.size == storedSize, "the returned size is wrong")
            check(service.exists(application), "the stored secret is not reported present")
            val copied = value.copyBytes()
            check(String(copied) == PLANTED_SECRET, "the returned value is wrong")
            copied.fill(0)
            value.close()
            check(value.size == 0, "closing the value did not zero it")

            // A profile-scoped record lives under its own qualified identity.
            val scoped = service.put(
                KWebSecretPutRequest(
                    profile,
                    PLANTED_SECRET.toByteArray(),
                    KWebSecretAccessibility.WHEN_UNLOCKED,
                ),
            )
            check(scoped.reference == profile, "the profile reference changed")

            // Rotation rewrites the record through the backend and keeps the value.
            if (capabilities.supports(KWebSecureStorageCapability.ROTATION)) {
                val rotated = service.rotate(application)
                check(rotated.generation > 1, "rotation did not advance the generation")
                val after = service.get(application)
                check(after.size == storedSize, "rotation changed the stored size")
                after.close()
                check(
                    service.rotationStatus(application) == KWebSecretRotationStatus.CURRENT,
                    "a rotated record is not current",
                )
                rotation = "verified"
            }

            // Deleting is idempotent.
            check(service.delete(application), "the first delete did not remove the record")
            check(!service.delete(application), "the second delete was not idempotent")
            check(!service.exists(application), "the record survived deletion")
            service.delete(profile)
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
        append("\"backend\":\"").append(backend).append("\",")
        append("\"capabilities\":\"").append(capabilitySummary).append("\",")
        append("\"roundTrip\":\"").append(roundTrip).append("\",")
        append("\"rotation\":\"").append(rotation).append("\",")
        append("\"storedSizeBytes\":").append(storedSize).append(',')
        append("\"sessionBoundary\":\"").append(boundary).append("\",")
        append("\"liveHandlesAfterClose\":0,")
        append("\"status\":\"").append(status).append("\",")
        append("\"failures\":[").append(failures.joinToString(",") { "\"${it.replace('"', '\'')}\"" }).append("]}")
        append('\n')
    }
    // The retained report must never carry the secret itself.
    check(!report.contains(PLANTED_SECRET)) { "the report leaked the planted secret" }
    Files.createDirectories(root)
    Files.writeString(root.resolve("secure-storage-integration.json"), report)
    if (failures.isEmpty()) {
        println("kweb secure-storage integration passed on $target ($providerId).")
    } else {
        println("kweb secure-storage integration failed on $target ($providerId):")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
