package dev.lattenero.bot.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Raised when the breaker is OPEN and rejects the call — the caller
 * parks to the outbox instead of hammering.
 */
class CircuitBreakerOpen(val name: String, val retryAfter: Double) :
    IOException("Circuit breaker '$name' OPEN, retry after ${"%.1f".format(retryAfter)}s")

private enum class BreakerRaw { CLOSED, OPEN, HALF_OPEN }

/**
 * The sync transport's breaker — an exact port of telegram/circuit_breaker.py
 * SyncCircuitBreaker (F79e): 5 consecutive failed calls open it (sends go
 * straight to the outbox, no hammering), getUpdates is the natural
 * HALF-OPEN probe, 2 successes close it. The clock is injectable (tests
 * move time instead of sleeping); the Mutex mirrors the async sibling's
 * lock shape — the guarded call itself always runs OUTSIDE the lock.
 */
class TgBreaker(
    val name: String = "tg-sync",
    val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    val successThreshold: Int = DEFAULT_SUCCESS_THRESHOLD,
    val timeoutS: Double = BREAKER_OPEN_S,
    private val time: () -> Double = { System.nanoTime() / NANOS_PER_SECOND },
) {
    private var raw = BreakerRaw.CLOSED
    private var failureCount = 0
    private var successCount = 0
    private var lastFailureTime: Double? = null
    private val mutex = Mutex()

    /**
     * The observational state (the healer's health line): an OPEN past
     * its timeout reads HALF_OPEN — the next call is the probe.
     */
    val state: String
        get() = if (raw == BreakerRaw.OPEN && shouldAttemptReset()) BreakerRaw.HALF_OPEN.name else raw.name

    @Suppress("TooGenericExceptionCaught") // Python's `except Exception`: the breaker counts EVERY failure
    suspend fun <T> call(block: suspend () -> T): T {
        mutex.withLock {
            if (raw == BreakerRaw.OPEN) {
                if (!shouldAttemptReset()) {
                    val waited = time() - (lastFailureTime ?: 0.0)
                    throw CircuitBreakerOpen(name, maxOf(0.0, timeoutS - waited))
                }
                raw = BreakerRaw.HALF_OPEN // time moved: this call is the probe
                successCount = 0
            }
        }
        val result = try {
            block()
        } catch (e: Throwable) {
            // Kotlin-side note: coroutine cancellation is the caller's
            // business, not Telegram failing — it never counts as a failure
            if (e !is CancellationException) onFailure()
            throw e
        }
        onSuccess()
        return result
    }

    private suspend fun onSuccess() {
        mutex.withLock {
            failureCount = 0
            if (raw == BreakerRaw.HALF_OPEN) {
                successCount++
                if (successCount >= successThreshold) {
                    raw = BreakerRaw.CLOSED
                    successCount = 0
                }
            }
        }
    }

    private suspend fun onFailure() {
        mutex.withLock {
            failureCount++
            lastFailureTime = time()
            if (raw == BreakerRaw.HALF_OPEN) {
                raw = BreakerRaw.OPEN
                successCount = 0
            } else if (raw == BreakerRaw.CLOSED && failureCount >= failureThreshold) {
                raw = BreakerRaw.OPEN
            }
        }
    }

    private fun shouldAttemptReset(): Boolean =
        lastFailureTime?.let { time() - it >= timeoutS } ?: true

    /** Manual reset (for tests). */
    suspend fun reset() {
        mutex.withLock {
            raw = BreakerRaw.CLOSED
            failureCount = 0
            successCount = 0
            lastFailureTime = null
        }
    }

    companion object {
        const val BREAKER_OPEN_S = 30.0
        const val DEFAULT_FAILURE_THRESHOLD = 5
        const val DEFAULT_SUCCESS_THRESHOLD = 2
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
