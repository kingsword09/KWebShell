package io.github.kingsword09.kwebshell.service.files

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.bridge.KWebBridgeRequest
import io.github.kingsword09.kwebshell.bridge.KWebStreamBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebStreamCreditGate
import io.github.kingsword09.kwebshell.bridge.KWebStreamFrameSink
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine

/**
 * Owns one PAGE-scoped files provider across committed navigations.
 *
 * The bridge object remains stable for the native browser, while the provider
 * and its opaque handles are replaced at every navigation boundary. A
 * non-matching origin has no active provider and therefore cannot inherit a
 * previous workspace capability.
 */
public class JvmKWebFilesPageOwner(
    private val engineId: String,
    private val profileId: String,
    private val pageId: String,
    private val expectedOrigin: String,
    private val configuration: JvmKWebFilesConfiguration,
    private val policyEngine: KWebServicePolicyEngine,
) : AutoCloseable {
    private val lock = Any()
    private var navigationId: Long = 1
    private var activeOrigin: String? = null
    private var service: KWebFiles? = null
    private var closed = false
    private var initialNavigation = true

    init {
        require(expectedOrigin.isNotBlank()) { "A files page owner requires an exact origin." }
        activateIfExpected(expectedOrigin)
    }

    /** Invalidates every handle before a new main-frame navigation commits. */
    public fun onNavigationStarted() {
        synchronized(lock) {
            if (closed) return
            if (initialNavigation) return
            navigationId += 1
            closeServiceLocked()
            activeOrigin = null
        }
    }

    /** Creates a fresh provider only for the declared exact committed origin. */
    public fun onNavigationCommitted(origin: String) {
        synchronized(lock) {
            if (closed) return
            val normalizedOrigin = canonicalOrigin(origin)
            if (initialNavigation) {
                initialNavigation = false
                if (normalizedOrigin == canonicalOrigin(expectedOrigin) && service != null) {
                    activeOrigin = normalizedOrigin
                    return
                }
            }
            closeServiceLocked()
            activeOrigin = null
            if (normalizedOrigin == canonicalOrigin(expectedOrigin)) {
                activeOrigin = normalizedOrigin
                service = JvmKWebFiles.open(
                    KWebFileOwnerScope(engineId, profileId, pageId, normalizedOrigin, navigationId),
                    configuration,
                )
            }
        }
    }

    public fun bridgeDispatcher(): KWebBridgeDispatcher = object : KWebBridgeDispatcher {
        override suspend fun dispatch(requestJson: String): String {
            val snapshot = snapshot()
            return snapshot.service.bridgeDispatcher(policyEngine, snapshot.subject).dispatch(requestJson)
        }
    }

    public fun bridgeStreamDispatcher(): KWebStreamBridgeDispatcher = object : KWebStreamBridgeDispatcher {
        override val streamMethods: Set<String> = setOf("watchDirectory")
        override val acknowledgedMethods: Set<String> = setOf("watchDirectoryAck")

        override fun capacity(method: String): Int {
            if (method != "watchDirectory") {
                throw KWebBridgeException("bridge.stream.ack-unknown-stream", "The files stream method is not published.")
            }
            return KWEB_FILES_WATCH_CAPACITY
        }

        override suspend fun acknowledge(request: KWebBridgeRequest, gate: KWebStreamCreditGate) {
            val snapshot = snapshot()
            snapshot.service.bridgeStreamDispatcher(policyEngine, snapshot.subject).acknowledge(request, gate)
        }

        override suspend fun dispatchStream(
            request: KWebBridgeRequest,
            sink: KWebStreamFrameSink,
            gate: KWebStreamCreditGate,
        ) {
            val snapshot = snapshot()
            snapshot.service.bridgeStreamDispatcher(policyEngine, snapshot.subject)
                .dispatchStream(request, sink, gate)
        }
    }

    public fun isClosed(): Boolean = synchronized(lock) { closed }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            closeServiceLocked()
            activeOrigin = null
        }
    }

    private fun activateIfExpected(origin: String) {
        synchronized(lock) {
            if (origin != expectedOrigin) return
            activeOrigin = origin
            service = JvmKWebFiles.open(
                KWebFileOwnerScope(engineId, profileId, pageId, origin, navigationId),
                configuration,
            )
        }
    }

    private fun canonicalOrigin(origin: String): String = origin.trimEnd('/')

    private fun snapshot(): Snapshot = synchronized(lock) {
        if (closed) throw KWebBridgeException("service.owner-closed", "The files page owner is closed.")
        val currentService = service
            ?: throw KWebBridgeException("service.owner-closed", "The files service is not active for this origin.")
        Snapshot(
            currentService,
            KWebPolicySubject(
                engineId = engineId,
                profileId = profileId,
                pageId = pageId,
                origin = activeOrigin ?: expectedOrigin,
                scope = KWebServiceScope.PAGE,
            ),
        )
    }

    private fun closeServiceLocked() {
        service?.close()
        service = null
    }

    private data class Snapshot(
        val service: KWebFiles,
        val subject: KWebPolicySubject,
    )

    private companion object {
        const val KWEB_FILES_WATCH_CAPACITY: Int = 64
    }
}
