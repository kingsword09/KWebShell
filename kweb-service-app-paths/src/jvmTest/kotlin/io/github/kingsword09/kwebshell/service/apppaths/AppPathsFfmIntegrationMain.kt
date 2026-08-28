package io.github.kingsword09.kwebshell.service.apppaths

import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import io.github.kingsword09.kwebshell.services.KWebNativeServiceRegistry
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.absolute

private const val LIBRARY_PROPERTY = "kweb.services.native.library.path"
private const val ROOT_PROPERTY = "kweb.services.integration.root"

public fun main(): Unit = runBlocking {
    val library = requiredPath(LIBRARY_PROPERTY)
    val root = requiredPath(ROOT_PROPERTY).absolute().normalize()
    Files.createDirectories(root)
    val appRoot = root.resolve("application-data")
    val sessionRoot = root.resolve("session-data")
    Files.createDirectories(appRoot)
    Files.createDirectories(sessionRoot)
    val configuration = KWebAppPathsConfiguration(
        applicationId = "io.github.kwebshell.integration",
        applicationDataRoot = appRoot.toString(),
        sessionDataRoot = sessionRoot.toString(),
    )
    val service = JvmKWebAppPaths.open(library, configuration)
    val registry = KWebNativeServiceRegistry()
    registry.install(KWebAppPaths.Key, service)
    check(registry.require(KWebAppPaths.Key) === service)
    try {
        check(service.lifecycle.value == KWebLifecycleState.OPEN)
        val configured = service.resolve(KWebAppPathKind.USER_DATA)
        check(Path.of(configured.path) == appRoot.toRealPath())
        val session = service.resolve(KWebAppPathKind.SESSION_DATA)
        check(Path.of(session.path) == sessionRoot.toRealPath())
        check(!Files.exists(appRoot.resolve("created-by-lookup")))

        listOf(
            KWebAppPathKind.HOME,
            KWebAppPathKind.APP_DATA,
            KWebAppPathKind.APP_CACHE,
            KWebAppPathKind.TEMP,
        ).forEach { kind ->
            val resolved = service.resolve(kind)
            check(resolved.kind == kind)
            check(resolved.path.isNotBlank())
            check(resolved.source.isNotBlank())
            check(Path.of(resolved.path).isAbsolute)
        }

        listOf(
            KWebAppPathKind.DESKTOP,
            KWebAppPathKind.DOCUMENTS,
            KWebAppPathKind.DOWNLOADS,
            KWebAppPathKind.MUSIC,
            KWebAppPathKind.PICTURES,
            KWebAppPathKind.VIDEOS,
        ).forEach { kind ->
            try {
                val resolved = service.resolve(kind)
                check(resolved.kind == kind)
                check(Path.of(resolved.path).isAbsolute)
            } catch (error: KWebNativeException) {
                check(error.code == KWebServiceErrorCode.OPERATION_UNAVAILABLE) {
                    "Unexpected failure for ${kind.id}: ${error.code}"
                }
            }
        }
    } finally {
        registry.close()
        service.close()
        service.close()
        check(service.lifecycle.value == KWebLifecycleState.CLOSED)
        val closed = runCatching { service.resolve(KWebAppPathKind.HOME) }.exceptionOrNull()
        check(closed is KWebNativeException && closed.code == KWebServiceErrorCode.OWNER_CLOSED)
        if (Files.exists(root)) {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
    println("KWebAppPaths FFM integration passed on ${System.getProperty("os.name")}.")
}

private fun requiredPath(property: String): Path =
    Path.of(System.getProperty(property) ?: error("Missing system property $property"))
