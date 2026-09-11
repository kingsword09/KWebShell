package io.github.kingsword09.kwebshell.service.apppaths

import io.github.kingsword09.kwebshell.services.KWebNativeService
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceKey
import io.github.kingsword09.kwebshell.services.KWebServiceOperationDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.KWebServiceVersion
import io.github.kingsword09.kwebshell.services.KWebServiceVersionRange
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebTarget
import kotlinx.coroutines.flow.StateFlow

public enum class KWebAppPathKind(public val id: String, public val electronName: String?) {
    HOME("home", "home"),
    APP_DATA("app-data", "appData"),
    APP_CACHE("app-cache", null),
    USER_DATA("user-data", "userData"),
    SESSION_DATA("session-data", "sessionData"),
    TEMP("temp", "temp"),
    DESKTOP("desktop", "desktop"),
    DOCUMENTS("documents", "documents"),
    DOWNLOADS("downloads", "downloads"),
    MUSIC("music", "music"),
    PICTURES("pictures", "pictures"),
    VIDEOS("videos", "videos"),
    ;

    public companion object {
        public fun fromId(id: String): KWebAppPathKind = entries.singleOrNull { it.id == id }
            ?: throw KWebConfigurationException(
                code = "service.request-invalid",
                details = mapOf("kind" to id),
                message = "The requested application path kind is not published.",
            )
    }
}

public data class KWebAppPathsConfiguration(
    public val applicationId: String,
    public val applicationDataRoot: String,
    public val sessionDataRoot: String,
) {
    init {
        if (applicationId.length > 127 || !APPLICATION_ID.matches(applicationId)) {
            throw KWebConfigurationException(
                code = "service.request-invalid",
                details = mapOf("applicationId" to applicationId),
                message = "The application identifier must be one safe reverse-DNS component string.",
            )
        }
        if (applicationDataRoot.isBlank() || applicationDataRoot.any { it == '\u0000' } ||
            sessionDataRoot.isBlank() || sessionDataRoot.any { it == '\u0000' }
        ) {
            throw KWebConfigurationException(
                code = "service.request-invalid",
                details = emptyMap(),
                message = "Application and session data roots must be non-empty paths without NUL bytes.",
            )
        }
    }

    private companion object {
        val APPLICATION_ID = Regex(
            "[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?)+",
        )
    }
}

public data class KWebResolvedPath(
    public val kind: KWebAppPathKind,
    public val path: String,
    public val source: String,
)

public interface KWebAppPaths : KWebNativeService {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<KWebLifecycleState>

    public suspend fun resolve(kind: KWebAppPathKind): KWebResolvedPath

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "app-paths",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.APPLICATION,
            operations = setOf(
                KWebServiceOperationDescriptor(
                    id = "resolve",
                    schemaVersion = 1,
                    rendererPermission = "native.app-paths.resolve",
                    requiresUserGesture = false,
                ),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = KWebTarget.supported,
        )

        public val Key: KWebServiceKey<KWebAppPaths> = object : KWebServiceKey<KWebAppPaths> {
            override val id: String = DESCRIPTOR.id
            override val contract: KWebServiceVersionRange = KWebServiceVersionRange.exact(DESCRIPTOR.version)
        }
    }
}
