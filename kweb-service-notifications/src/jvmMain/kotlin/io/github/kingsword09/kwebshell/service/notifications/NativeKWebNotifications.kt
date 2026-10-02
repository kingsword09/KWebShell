package io.github.kingsword09.kwebshell.service.notifications

import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

internal interface NotificationNativeExecutor : AutoCloseable {
    val providerId: String
    fun permission(): NativeNotificationPermission
    fun requestPermission(): NativeNotificationPermission
    fun capabilities(): NativeNotificationCapabilities
    fun show(request: NativeNotificationRequest)
    fun close(id: String)
    fun pollEvent(): NativeNotificationEvent?
    override fun close()
}

internal data class NativeNotificationPermission(
    val status: KWebNotificationPermissionStatus,
    val provider: String,
)

internal data class NativeNotificationCapabilities(
    val actions: Boolean,
    val replies: Boolean,
    val replacement: Boolean,
    val timeout: Boolean,
    val activation: Boolean,
)

internal data class NativeNotificationRequest(
    val id: String,
    val tag: String?,
    val title: String,
    val body: String,
    val icon: KWebNotificationIcon,
    val urgency: KWebNotificationUrgency,
    val timeout: KWebNotificationTimeout,
    val actions: List<KWebNotificationAction>,
)

internal enum class NativeNotificationEventKind { ACTION, CLOSED, FAILED }

internal data class NativeNotificationEvent(
    val id: String,
    val kind: NativeNotificationEventKind,
    val actionId: String? = null,
    val reply: String? = null,
    val closeReason: KWebNotificationCloseReason? = null,
    val code: String? = null,
)

internal class NativeKWebNotifications(
    private val native: NotificationNativeExecutor,
    private val applicationId: String,
    private val activationRouter: KWebNotificationActivationRouter,
) : KWebNotifications {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleState = MutableStateFlow(KWebLifecycleState.OPEN)
    private val mutableEvents = MutableSharedFlow<KWebNotificationEvent>(
        replay = KWEB_NOTIFICATION_EVENT_CAPACITY,
        extraBufferCapacity = 0,
    )
    private val active = linkedMapOf<String, ActiveNotification>()
    private val showTimes = ArrayDeque<Long>()
    private val eventSequence = Sequence()
    private val closed = AtomicBoolean(false)
    private val providerCapabilities = native.capabilities()
    private val monitorJob = scope.launch { monitorNativeEvents() }
    private var closeFailure: KWebNativeException? = null

    override val descriptor = KWebNotifications.DESCRIPTOR
    override val lifecycle: StateFlow<KWebLifecycleState> = lifecycleState.asStateFlow()
    override val events: Flow<KWebNotificationEvent> = mutableEvents.asSharedFlow()

    override suspend fun permission(): KWebNotificationPermission = withContext(Dispatchers.IO) {
        lock.withLock {
            requireOpen("permission")
            try {
                native.permission().toPublic()
            } catch (error: NotificationNativeFailure) {
                throw mapFailure("permission", error)
            }
        }
    }

    override suspend fun requestPermission(): KWebNotificationPermission = withContext(Dispatchers.IO) {
        lock.withLock {
            requireOpen("request-permission")
            try {
                native.requestPermission().toPublic()
            } catch (error: NotificationNativeFailure) {
                throw mapFailure("request-permission", error)
            }
        }
    }

    override suspend fun capabilities(): KWebNotificationCapabilities = lock.withLock {
        requireOpen("capabilities")
        providerCapabilities.toPublic()
    }

    override suspend fun show(request: KWebNotificationRequest): KWebNotificationShowResult = withContext(Dispatchers.IO) {
        val replacement: ActiveNotification?
        val shownSequence: ULong
        lock.withLock {
            requireOpen("show")
            enforceCapabilities(request)
            val now = System.currentTimeMillis()
            while (showTimes.isNotEmpty() && now - showTimes.first() >= 60_000L) showTimes.removeFirst()
            if (showTimes.size >= KWEB_NOTIFICATION_MAX_SHOWS_PER_MINUTE) {
                throw failure(KWebNotificationErrorCode.RATE_LIMITED, "The notification show rate limit was exceeded.", "show")
            }
            if (active.containsKey(request.id.value) && request.tag == null) {
                throw failure(KWebNotificationErrorCode.REPLACEMENT_CONFLICT, "A notification id is already active.", "show")
            }
            replacement = request.tag?.let { tag -> active.values.singleOrNull { it.request.tag == tag } }
            if (active.size - (if (replacement != null) 1 else 0) >= KWEB_NOTIFICATION_MAX_ACTIVE) {
                throw failure(KWebNotificationErrorCode.RATE_LIMITED, "The active notification limit was exceeded.", "show")
            }
            try {
                replacement?.let { native.close(it.request.id.value) }
                native.show(request.toNative())
            } catch (error: NotificationNativeFailure) {
                throw mapFailure("show", error)
            }
            replacement?.let { active.remove(it.request.id.value) }
            if (replacement != null) {
                publishLocked(
                    KWebNotificationEvent.Closed(
                        sequence = eventSequence.next(),
                        id = KWebNotificationId(replacement.request.id.value),
                        reason = KWebNotificationCloseReason.REPLACED,
                    ),
                )
            }
            val entry = ActiveNotification(request, scheduleTimeout(request))
            active[request.id.value] = entry
            showTimes.addLast(now)
            shownSequence = eventSequence.next()
            publishLocked(
                KWebNotificationEvent.Shown(
                    sequence = shownSequence,
                    id = request.id,
                    tag = request.tag,
                    replacedId = replacement?.request?.id,
                ),
            )
        }
        KWebNotificationShowResult(
            id = request.id,
            outcome = if (replacement == null) KWebNotificationShowOutcome.SHOWN else KWebNotificationShowOutcome.REPLACED,
            replacedId = replacement?.request?.id,
            sequence = shownSequence,
        )
    }

    override suspend fun close(id: KWebNotificationId): KWebNotificationCloseResult = withContext(Dispatchers.IO) {
        lock.withLock { closeLocked(id.value, KWebNotificationCloseReason.PROGRAMMATIC) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            closeFailure?.let { throw it }
            return
        }
        val failure = runCatching {
            runBlocking(Dispatchers.IO) {
                val entries = lock.withLock {
                    lifecycleState.value = KWebLifecycleState.CLOSING
                    val snapshot = active.values.toList()
                    active.clear()
                    snapshot
                }
                entries.forEach { it.timeoutJob?.cancel() }
                monitorJob.cancel()
                entries.forEach { entry -> runCatching { native.close(entry.request.id.value) } }
                native.close()
                lock.withLock {
                    entries.forEach { entry ->
                        publishLocked(
                            KWebNotificationEvent.Closed(
                                eventSequence.next(),
                                KWebNotificationId(entry.request.id.value),
                                KWebNotificationCloseReason.OWNER_CLOSED,
                            ),
                        )
                    }
                    lifecycleState.value = KWebLifecycleState.CLOSED
                }
                scope.cancel()
            }
        }.exceptionOrNull()
        if (failure != null) {
            val mapped = if (failure is KWebNativeException) failure else nativeFailure(
                KWebNotificationErrorCode.NATIVE_FAILED,
                "The notification provider could not close.",
                "close",
                failure,
            )
            closeFailure = mapped
            lifecycleState.value = KWebLifecycleState.FAILED
            throw mapped
        }
    }

    private suspend fun monitorNativeEvents() {
        while (scope.coroutineContext.isActive) {
            val event = runCatching { native.pollEvent() }.getOrElse { error ->
                System.err.println("notifications monitor poll failed: ${error::class.simpleName}")
                if (error is NotificationNativeFailure) publishFailure(error)
                null
            }
            if (event != null) {
                System.err.println("notifications monitor event: ${event.kind} ${event.id}")
                processNativeEvent(event)
            }
            delay(50)
        }
    }

    private suspend fun processNativeEvent(event: NativeNotificationEvent) {
        when (event.kind) {
            NativeNotificationEventKind.ACTION -> {
                val entry = lock.withLock { active[event.id] } ?: return
                val actionId = event.actionId ?: return
                val activation = KWebNotificationActivation(
                    applicationId = applicationId,
                    notificationId = KWebNotificationId(entry.request.id.value),
                    tag = entry.request.tag,
                    actionId = actionId,
                    reply = event.reply,
                )
                lock.withLock {
                    publishLocked(KWebNotificationEvent.Action(eventSequence.next(), activation.notificationId, actionId, event.reply))
                }
                try {
                    activationRouter.route(activation)
                } catch (_: Throwable) {
                    lock.withLock {
                        publishLocked(
                            KWebNotificationEvent.Failed(
                                eventSequence.next(),
                                activation.notificationId,
                                KWebNotificationErrorCode.ACTIVATION_ROUTE_FAILED,
                            ),
                        )
                    }
                }
            }

            NativeNotificationEventKind.CLOSED -> {
                lock.withLock {
                    val entry = active.remove(event.id) ?: return
                    entry.timeoutJob?.cancel()
                    publishLocked(
                        KWebNotificationEvent.Closed(
                            eventSequence.next(),
                            KWebNotificationId(event.id),
                            event.closeReason ?: KWebNotificationCloseReason.NATIVE,
                        ),
                    )
                }
            }

            NativeNotificationEventKind.FAILED -> publishFailure(
                NotificationNativeFailure(
                    code = event.code ?: KWebNotificationErrorCode.NATIVE_FAILED,
                    message = "The notification provider reported a failure.",
                ),
            )
        }
    }

    private suspend fun closeLocked(id: String, reason: KWebNotificationCloseReason): KWebNotificationCloseResult {
        requireOpen("close")
        val entry = active.remove(id) ?: throw failure(KWebNotificationErrorCode.NOT_FOUND, "The notification is not active.", "close")
        entry.timeoutJob?.cancel()
        try {
            native.close(id)
        } catch (error: NotificationNativeFailure) {
            active[id] = entry
            throw mapFailure("close", error)
        }
        val sequence = eventSequence.next()
        publishLocked(KWebNotificationEvent.Closed(sequence, KWebNotificationId(id), reason))
        return KWebNotificationCloseResult(KWebNotificationId(id), sequence)
    }

    private fun scheduleTimeout(request: KWebNotificationRequest): Job? = when (request.timeout) {
        KWebNotificationTimeout.SYSTEM,
        KWebNotificationTimeout.PERSISTENT,
        -> null
        KWebNotificationTimeout.SHORT -> scope.launch { delay(5_000); expire(request.id.value) }
        KWebNotificationTimeout.LONG -> scope.launch { delay(30_000); expire(request.id.value) }
    }

    private suspend fun expire(id: String) {
        runCatching { lock.withLock { if (active.containsKey(id)) closeLocked(id, KWebNotificationCloseReason.EXPIRED) } }
    }

    private fun enforceCapabilities(request: KWebNotificationRequest) {
        if (request.actions.isNotEmpty() && !providerCapabilities.actions) {
            throw failure(KWebNotificationErrorCode.ACTIONS_UNSUPPORTED, "The notification provider does not support actions.", "show")
        }
        if (request.actions.any { it.kind == KWebNotificationActionKind.REPLY } && !providerCapabilities.replies) {
            throw failure(KWebNotificationErrorCode.REPLY_UNSUPPORTED, "The notification provider does not support replies.", "show")
        }
        if (request.timeout != KWebNotificationTimeout.SYSTEM && !providerCapabilities.timeout) {
            throw failure(KWebNotificationErrorCode.TIMEOUT_UNSUPPORTED, "The notification provider does not support this timeout policy.", "show")
        }
        if (!providerCapabilities.replacement && request.tag != null) {
            throw failure(KWebNotificationErrorCode.ACTIONS_UNSUPPORTED, "The notification provider does not support replacement.", "show")
        }
    }

    private fun publishLocked(event: KWebNotificationEvent) {
        mutableEvents.tryEmit(event)
    }

    private fun publishFailure(error: NotificationNativeFailure) {
        scope.launch {
            lock.withLock {
                val id = active.keys.firstOrNull() ?: return@withLock
                publishLocked(KWebNotificationEvent.Failed(eventSequence.next(), KWebNotificationId(id), error.code))
            }
        }
    }

    private fun requireOpen(operation: String) {
        if (lifecycleState.value != KWebLifecycleState.OPEN) {
            throw failure(KWebServiceErrorCode.OWNER_CLOSED, "The notification service is not open.", operation)
        }
    }

    private fun mapFailure(operation: String, error: NotificationNativeFailure): KWebNativeException =
        nativeFailure(error.code, error.message ?: "The notification provider failed.", operation, error)

    private data class ActiveNotification(
        val request: KWebNotificationRequest,
        val timeoutJob: Job?,
    )

    private class Sequence {
        private var value = 0uL
        fun next(): ULong {
            value += 1uL
            return value
        }
    }
}

internal class NotificationNativeFailure(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

private fun KWebNotificationRequest.toNative(): NativeNotificationRequest = NativeNotificationRequest(
    id = id.value,
    tag = tag,
    title = title,
    body = body,
    icon = icon,
    urgency = urgency,
    timeout = timeout,
    actions = actions,
)

private fun NativeNotificationPermission.toPublic(): KWebNotificationPermission = KWebNotificationPermission(status, provider)
private fun NativeNotificationCapabilities.toPublic(): KWebNotificationCapabilities =
    KWebNotificationCapabilities(actions, replies, replacement, timeout, activation)

private fun failure(code: String, message: String, operation: String): KWebNativeException = nativeFailure(code, message, operation)

private fun nativeFailure(code: String, message: String, operation: String, cause: Throwable? = null): KWebNativeException =
    KWebNativeException(
        code = code,
        details = mapOf("service" to KWebNotifications.DESCRIPTOR.id, "operation" to operation),
        message = message,
        cause = cause,
    )
