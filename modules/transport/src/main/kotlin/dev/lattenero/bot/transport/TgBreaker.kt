package dev.lattenero.bot.transport

import kap.CircuitBreaker
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Raised when the breaker is OPEN and rejects the call — the caller
 * parks to the outbox instead of hammering.
 */
class CircuitBreakerOpen(val name: String, val retryAfter: Double) :
    IOException("Circuit breaker '$name' OPEN, retry after ${"%.1f".format(retryAfter)}s")

/**
 * The sync transport's breaker — the port of telegram/circuit_breaker.py
 * SyncCircuitBreaker (F79e): 5 consecutive failed calls open it (sends go
 * straight to the outbox, no hammering), getUpdates is the natural
 * HALF-OPEN probe, 2 successes close it.
 *
 * The breaker MECHANICS are kap's [CircuitBreaker] now (its 4.3
 * `successThreshold` was built for exactly this F79e law): the open
 * threshold, the open window, the two-success half-open, the
 * consecutive-failure streak, the single in-flight probe and the
 * reopen-on-failed-probe are all kap's, under the injected [TimeSource].
 * This class kept only the Telegram-specific shell that is NOT breaker
 * mechanics:
 * - [CircuitBreakerOpen] — the house exception: an IOException carrying
 *   the REMAINING window in seconds, which the poller logs and the send
 *   path parks on (kap's own CircuitBreakerOpenException says neither).
 * - [state] — the healer's health-line string (CLOSED/OPEN/HALF_OPEN),
 *   computed WITHOUT touching the breaker: an OPEN past its window reads
 *   HALF_OPEN observationally, before any probe has run (kap's own
 *   transition is lazy — it happens at the next admission).
 * - the injected clock seam — tests move time instead of sleeping.
 *
 * The guarded call always runs OUTSIDE the breaker's lock (kap's
 * tryAcquire/onSuccess/onFailure mutex API); a cancellation passes
 * through untouched — the caller's business, never a Telegram failure
 * (kap's own law too).
 *
 * One mechanic kap brought and the hand-rolled version lacked: the
 * half-open probe is SINGLE-FLIGHT — while a probe is in flight, a
 * concurrent call is rejected as OPEN (the homegrown one admitted every
 * concurrent call as a probe). Pinned in [TgBreakerTest].
 */
class TgBreaker(
    val name: String = "tg-sync",
    val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    val successThreshold: Int = DEFAULT_SUCCESS_THRESHOLD,
    val timeoutS: Double = BREAKER_OPEN_S,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    /**
     * When the breaker last went OPEN (this facade's own mark, captured
     * off kap's state change): the remaining-window word of
     * [CircuitBreakerOpen] and the observational [state] read from it.
     */
    private var openedMark: TimeMark? = null

    private val breaker = CircuitBreaker(
        maxFailures = failureThreshold,
        retryAfter = timeoutS.seconds,
        successThreshold = successThreshold,
        onStateChange = { _, new ->
            if (new == CircuitBreaker.State.Open) openedMark = timeSource.markNow()
        },
        timeSource = timeSource,
    )

    /**
     * The observational state (the healer's health line): an OPEN past
     * its timeout reads HALF_OPEN — the next call is the probe. A pure
     * read: unlike kap's lazy transition, this never mutates the breaker.
     */
    val state: String
        get() = when (breaker.currentState) {
            CircuitBreaker.State.Closed -> "CLOSED"
            CircuitBreaker.State.HalfOpen -> "HALF_OPEN"
            CircuitBreaker.State.Open -> if (windowElapsed()) "HALF_OPEN" else "OPEN"
        }

    /** The guarded call: rejected with [CircuitBreakerOpen] while OPEN. */
    // Three throws, like kap's own protect: the open rejection plus the two
    // passthrough rethrows (cancellation untouched, failure counted first).
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    suspend fun <T> call(block: suspend () -> T): T {
        if (!breaker.tryAcquire()) throw CircuitBreakerOpen(name, remainingWindowS())
        return try {
            block().also { breaker.onSuccess() }
        } catch (e: CancellationException) {
            // coroutine cancellation is the caller's business, not Telegram
            // failing — it never counts as a failure
            throw e
        } catch (e: Throwable) {
            breaker.onFailure()
            throw e
        }
    }

    private fun windowElapsed(): Boolean =
        openedMark?.elapsedNow()?.let { it >= timeoutS.seconds } == true

    /** The OPEN window's remainder in seconds, floored at 0 — the house exception's word. */
    private fun remainingWindowS(): Double =
        maxOf(Duration.ZERO, timeoutS.seconds - (openedMark?.elapsedNow() ?: Duration.ZERO))
            .toDouble(DurationUnit.SECONDS)

    companion object {
        const val BREAKER_OPEN_S = 30.0
        const val DEFAULT_FAILURE_THRESHOLD = 5
        const val DEFAULT_SUCCESS_THRESHOLD = 2
    }
}
