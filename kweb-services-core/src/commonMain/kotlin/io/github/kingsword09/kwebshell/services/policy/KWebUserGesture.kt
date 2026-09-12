package io.github.kingsword09.kwebshell.services.policy

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * One user-gesture binding. A gesture token is minted only from the
 * browser/native input event path for a main frame of one exact origin inside
 * one Engine/Profile/Page. Renderers never mint or present tokens.
 */
public data class KWebGestureBinding(
    public val engineId: String,
    public val profileId: String,
    public val pageId: String,
    public val origin: String,
) {
    init {
        if (engineId.isBlank() || profileId.isBlank() || pageId.isBlank() || origin.isBlank()) {
            throw KWebConfigurationException(
                code = "service.gesture.binding-invalid",
                details = mapOf(
                    "engine" to engineId,
                    "profile" to profileId,
                    "page" to pageId,
                ),
                message = "A gesture binding must identify the engine, profile, page, and origin.",
            )
        }
    }
}

/** Opaque single-use gesture token. Only the issuer that minted it can consume it. */
public class KWebUserGestureToken internal constructor(
    public val id: String,
    public val binding: KWebGestureBinding,
    internal val expiresAt: TimeMark,
) {
    override fun toString(): String = "KWebUserGestureToken(${id.take(8)}...)"
}

public enum class KWebGestureConsumeResult {
    CONSUMED,
    NOT_MINTED,
    REPLAYED,
    EXPIRED,
    WRONG_ORIGIN,
    WRONG_PAGE,
    INVALIDATED_BY_NAVIGATION,
    OWNER_CLOSED,
}

/**
 * Mints and consumes native-verified user gestures. The only production minting
 * path is the real browser/native input event pipeline; synthetic renderer
 * events can never reach it. Consumed once, rejected after navigation, owner
 * close, or expiry.
 */
public interface KWebUserGestureIssuer {
    public fun mint(binding: KWebGestureBinding): KWebUserGestureToken

    /**
     * Atomically consumes the latest minted token for one page binding and
     * reports the precise outcome: [KWebGestureConsumeResult.CONSUMED] on
     * success, otherwise [KWebGestureConsumeResult.NOT_MINTED], `REPLAYED`,
     * `EXPIRED`, `WRONG_ORIGIN`, `INVALIDATED_BY_NAVIGATION`, or `OWNER_CLOSED`.
     */
    public fun consumeLatest(binding: KWebGestureBinding): KWebGestureConsumeResult

    /** The current unconsumed, unexpired token for one page binding, if any. */
    public fun current(binding: KWebGestureBinding): KWebUserGestureToken?

    /** Invalidates every outstanding token after one main-frame navigation. */
    public fun invalidateNavigation(pageId: String)

    /** Invalidates and drops every token for a terminal page owner. */
    public fun invalidatePage(pageId: String)
}

/**
 * Bounded gesture registry. At most one outstanding token per page (the latest
 * mint wins) and a bounded closed-page set, so pages can never accumulate
 * unbounded gesture state.
 */
public class KWebUserGestureRegistry(
    private val expiry: Duration = DEFAULT_EXPIRY,
    private val clock: TimeSource = TimeSource.Monotonic,
    private val tokenIds: () -> String = { nextGestureTokenId() },
) : KWebUserGestureIssuer {
    private class PageGesture(
        val token: KWebUserGestureToken,
        var consumed: Boolean,
        var invalidatedByNavigation: Boolean,
    )

    private val lock = Any()
    private val outstanding = linkedMapOf<String, PageGesture>()
    private val closedPages = linkedSetOf<String>()

    init {
        if (expiry <= Duration.ZERO) {
            throw KWebConfigurationException(
                code = "service.gesture.expiry-invalid",
                details = mapOf(),
                message = "A gesture token expiry must be positive.",
            )
        }
    }

    override fun mint(binding: KWebGestureBinding): KWebUserGestureToken {
        synchronized(lock) {
            if (binding.pageId in closedPages) {
                throw KWebConfigurationException(
                    code = "service.gesture.owner-closed",
                    details = mapOf("page" to binding.pageId),
                    message = "A gesture cannot be minted after its owner closed.",
                )
            }
            val token = KWebUserGestureToken(tokenIds(), binding, clock.markNow() + expiry)
            outstanding[binding.pageId] = PageGesture(token, consumed = false, invalidatedByNavigation = false)
            if (outstanding.size > MAX_OUTSTANDING_PAGES) {
                outstanding.remove(outstanding.keys.first())
            }
            return token
        }
    }

    override fun consumeLatest(binding: KWebGestureBinding): KWebGestureConsumeResult {
        synchronized(lock) {
            if (binding.pageId in closedPages) return KWebGestureConsumeResult.OWNER_CLOSED
            val gesture = outstanding[binding.pageId]
                ?: return KWebGestureConsumeResult.NOT_MINTED
            if (gesture.token.binding != binding) return KWebGestureConsumeResult.WRONG_ORIGIN
            if (gesture.invalidatedByNavigation) return KWebGestureConsumeResult.INVALIDATED_BY_NAVIGATION
            if (gesture.consumed) return KWebGestureConsumeResult.REPLAYED
            if (!gesture.token.expiresAt.hasNotPassedNow()) {
                gesture.consumed = true
                return KWebGestureConsumeResult.EXPIRED
            }
            gesture.consumed = true
            return KWebGestureConsumeResult.CONSUMED
        }
    }

    override fun current(binding: KWebGestureBinding): KWebUserGestureToken? {
        synchronized(lock) {
            val gesture = outstanding[binding.pageId] ?: return null
            if (gesture.consumed || gesture.invalidatedByNavigation) return null
            if (!gesture.token.expiresAt.hasNotPassedNow()) return null
            return gesture.token.takeIf { it.binding == binding }
        }
    }

    override fun invalidateNavigation(pageId: String) {
        synchronized(lock) {
            outstanding[pageId]?.invalidatedByNavigation = true
        }
    }

    override fun invalidatePage(pageId: String) {
        synchronized(lock) {
            outstanding.remove(pageId)
            closedPages += pageId
            if (closedPages.size > MAX_CLOSED_PAGES) {
                closedPages.remove(closedPages.first())
            }
        }
    }

    public companion object {
        public val DEFAULT_EXPIRY: Duration = 30.seconds
        internal const val MAX_OUTSTANDING_PAGES: Int = 64
        internal const val MAX_CLOSED_PAGES: Int = 256

        private fun nextGestureTokenId(): String =
            Random.Default.nextBytes(16).joinToString("") { "%02x".format(it) }
    }
}
