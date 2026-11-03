package dev.lattenero.bot.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The sync transport's breaker — the port of telegram/circuit_breaker.py
 * SyncCircuitBreaker (F79e), case by case: 5 consecutive failures open
 * it, an OPEN past its timeout reads HALF_OPEN and the next call is the
 * probe, 2 successes close it. Time is the injected [TestTimeSource] —
 * tests move the clock instead of sleeping. The mechanics under the
 * facade are kap's [kap.CircuitBreaker] (maxFailures=5, retryAfter=30s,
 * successThreshold=2): these tests pin that the F79e law survived the
 * swap, plus the one mechanic kap brought that the hand-rolled version
 * lacked — the single-flight half-open probe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TgBreakerTest {

    private val clock = TestTimeSource()
    private val breaker = TgBreaker(timeSource = clock)

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
        clock += 5.0.seconds
        val e = assertFailsWith<CircuitBreakerOpen> { breaker.call { "never runs" } }
        assertEquals("tg-sync", e.name)
        assertEquals(25.0, e.retryAfter, "timeout 30 minus the 5 already waited")
        assertEquals("Circuit breaker 'tg-sync' OPEN, retry after 25.0s", e.message)
    }

    @Test
    fun openPastItsTimeoutReadsHalfOpenAndTheNextCallProbes() = runTest {
        fail(5)
        clock += TgBreaker.BREAKER_OPEN_S.seconds
        assertEquals("HALF_OPEN", breaker.state, "the observational state: the next call is the probe")
        breaker.call { "probe ok" }
        assertEquals("HALF_OPEN", breaker.state, "one success is not two — success_threshold=2")
        breaker.call { "probe ok 2" }
        assertEquals("CLOSED", breaker.state)
    }

    @Test
    fun aFailedProbeReopensItImmediately() = runTest {
        fail(5)
        clock += TgBreaker.BREAKER_OPEN_S.seconds
        assertFailsWith<IOException> { breaker.call { throw IOException("still down") } }
        assertEquals("OPEN", breaker.state, "a failed probe is a new trip, not a count")
    }

    @Test
    fun aFailureBetweenTheTwoProbeSuccessesRestartsTheProbeCount() = runTest {
        // F79e's full law: the two closing successes must be CONSECUTIVE —
        // a failure in between is a fresh trip and the probe count starts
        // over (kap's recordFailure zeroes it), so ONE success after the
        // next window can never close it
        fail(5)
        clock += TgBreaker.BREAKER_OPEN_S.seconds
        breaker.call { "probe ok" }
        assertEquals("HALF_OPEN", breaker.state)
        assertFailsWith<IOException> { breaker.call { throw IOException("not yet") } }
        assertEquals("OPEN", breaker.state)
        clock += TgBreaker.BREAKER_OPEN_S.seconds
        breaker.call { "probe ok again" }
        assertEquals("HALF_OPEN", breaker.state, "the count restarted — one success is not two")
        breaker.call { "second consecutive success" }
        assertEquals("CLOSED", breaker.state)
    }

    @Test
    fun theHalfOpenProbeIsSingleFlight() = runTest {
        // kap's mechanic (new with the swap): while a half-open probe is
        // in flight, a concurrent call is rejected as OPEN — the hand-rolled
        // version admitted every concurrent call as a probe; kap admits one
        fail(5)
        clock += TgBreaker.BREAKER_OPEN_S.seconds
        val gate = CompletableDeferred<Unit>()
        var probeRan = false
        val probe = launch {
            breaker.call {
                probeRan = true
                gate.await() // the probe hangs in flight
            }
        }
        runCurrent() // the probe acquires (half-open admits it) and suspends on the gate
        assertEquals(true, probeRan)
        assertFailsWith<CircuitBreakerOpen> {
            breaker.call { "concurrent — rejected while the probe flies" }
        }
        gate.complete(Unit)
        probe.join()
        assertEquals("HALF_OPEN", breaker.state, "the probe's success still counts towards the two")
        breaker.call { "second success" }
        assertEquals("CLOSED", breaker.state)
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
        assertEquals(5, breaker.failureThreshold)
        assertEquals(2, breaker.successThreshold)
        assertEquals(30.0, breaker.timeoutS)
        assertTrue(CircuitBreakerOpen("x", 1.0) is IOException)
    }
}
