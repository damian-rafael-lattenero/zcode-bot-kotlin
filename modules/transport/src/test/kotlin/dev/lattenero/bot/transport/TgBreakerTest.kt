package dev.lattenero.bot.transport

import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The sync transport's breaker — the port of telegram/circuit_breaker.py
 * SyncCircuitBreaker (F79e), case by case: 5 consecutive failures open
 * it, an OPEN past its timeout reads HALF_OPEN and the next call is the
 * probe, 2 successes close it. Time is injected — tests move the clock
 * instead of sleeping (Python's time_fn).
 */
class TgBreakerTest {

    private var clock = 100.0
    private val breaker = TgBreaker(time = { clock })

    private suspend fun fail(n: Int) {
        repeat(n) {
            assertFailsWith<IOException> { breaker.call { throw IOException("down") } }
        }
    }

    @Test
    fun fiveConsecutiveFailuresOpenIt() = runTest {
        fail(4)
        assertEquals("CLOSED", breaker.state, "four is not five")
        fail(1)
        assertEquals("OPEN", breaker.state)
    }

    @Test
    fun aSuccessResetsTheConsecutiveCount() = runTest {
        fail(4)
        breaker.call { "ok" }
        fail(4)
        assertEquals("CLOSED", breaker.state, "the streak restarted at the success")
        fail(1)
        assertEquals("OPEN", breaker.state)
    }

    @Test
    fun openRejectsWithTheRemainingWindow() = runTest {
        fail(5)
        clock += 5.0
        val e = assertFailsWith<CircuitBreakerOpen> { breaker.call { "never runs" } }
        assertEquals("tg-sync", e.name)
        assertEquals(25.0, e.retryAfter, "timeout 30 minus the 5 already waited")
        assertEquals("Circuit breaker 'tg-sync' OPEN, retry after 25.0s", e.message)
    }

    @Test
    fun openPastItsTimeoutReadsHalfOpenAndTheNextCallProbes() = runTest {
        fail(5)
        clock += TgBreaker.BREAKER_OPEN_S
        assertEquals("HALF_OPEN", breaker.state, "the observational state: the next call is the probe")
        breaker.call { "probe ok" }
        assertEquals("HALF_OPEN", breaker.state, "one success is not two — success_threshold=2")
        breaker.call { "probe ok 2" }
        assertEquals("CLOSED", breaker.state)
    }

    @Test
    fun aFailedProbeReopensItImmediately() = runTest {
        fail(5)
        clock += TgBreaker.BREAKER_OPEN_S
        assertFailsWith<IOException> { breaker.call { throw IOException("still down") } }
        assertEquals("OPEN", breaker.state, "a failed probe is a new trip, not a count")
    }

    @Test
    fun kotlinSideCancellationNeverCountsAsATelegramFailure() = runTest {
        fail(4)
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            breaker.call { throw kotlinx.coroutines.CancellationException("caller's business") }
        }
        assertEquals("CLOSED", breaker.state, "cancellation passed through untouched")
        fail(1)
        assertEquals("OPEN", breaker.state, "still only five REAL failures in the streak")
    }

    @Test
    fun theThresholdsAreTheConfig() {
        assertEquals(5, TgBreaker.DEFAULT_FAILURE_THRESHOLD)
        assertEquals(2, TgBreaker.DEFAULT_SUCCESS_THRESHOLD)
        assertEquals(30.0, TgBreaker.BREAKER_OPEN_S)
        assertTrue(CircuitBreakerOpen("x", 1.0) is IOException)
    }
}
