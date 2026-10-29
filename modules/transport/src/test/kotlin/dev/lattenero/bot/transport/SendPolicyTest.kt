package dev.lattenero.bot.transport

import kap.Kap
import kap.evalGraph
import kap.retry
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * utils/retry.retry_call's frozen send contract as schedule algebra,
 * pinned BEHAVIORALLY through Kap.retry (the schedule is a value, not a
 * poked internal): backoffs (0, 1, 3) — the waits before attempts 2 and
 * 3 are 1 s and 3 s; 4xx≠429 stops the loop NOW; a 429 parks (its
 * window is the cooldown's business). The dormant retry_after override
 * (ra + 0.5, capped) is kept for retry_call parity.
 */
class SendPolicyTest {

    private val net = IOException("connection reset")

    @Test
    fun theBackoffTableIsZeroOneThree() = runTest {
        var attempts = 0
        val result = Kap {
            attempts++
            if (attempts < 3) throw net else "sent"
        }.retry(SendPolicy.schedule()).evalGraph()
        assertEquals("sent", result)
        assertEquals(3, attempts)
        assertEquals(
            4_000L,
            testScheduler.currentTime,
            "1 s after the first failure, 3 s after the second — delays (0,1,3), delay BEFORE attempt i+1",
        )
    }

    @Test
    fun a4xxThatIsNotA429FailsFast() = runTest {
        var attempts = 0
        assertFailsWith<TgHttpException> {
            Kap {
                attempts++
                throw TgHttpException(404, """{"description": "not found"}""")
            }.retry(SendPolicy.schedule()).evalGraph()
        }
        assertEquals(1, attempts, "retrying a bad request is hammering with extra steps")
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun a429ParksInsteadOfRetrying() = runTest {
        var attempts = 0
        assertFailsWith<TgHttpException> {
            Kap {
                attempts++
                throw TgHttpException(429, """{"parameters": {"retry_after": 7}}""")
            }.retry(SendPolicy.schedule()).evalGraph()
        }
        assertEquals(1, attempts, "the server NAMED the window — the cooldown holds it, the schedule never sleeps it out")
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun a5xxTakesTheTableLikeANetworkError() = runTest {
        var attempts = 0
        val result = Kap {
            attempts++
            if (attempts < 2) throw TgHttpException(502, "bad gateway") else "sent"
        }.retry(SendPolicy.schedule()).evalGraph()
        assertEquals("sent", result)
        assertEquals(2, attempts)
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun theRetryableMatrixIsRetryCalls() {
        assertFalse(SendPolicy.tgRetryable(TgHttpException(400, "{}")))
        assertFalse(SendPolicy.tgRetryable(TgHttpException(499, "{}")))
        assertFalse(SendPolicy.tgRetryable(TgHttpException(429, """{"parameters": {"retry_after": 1}}""")))
        assertTrue(SendPolicy.tgRetryable(TgHttpException(500, "{}")))
        assertTrue(SendPolicy.tgRetryable(net))
        assertEquals(60.0, SendPolicy.DEFAULT_RETRY_AFTER_CAP_S)
    }

    @Test
    fun theDormantRetryAfterOverrideIsRaPlusHalfCapped() = runTest {
        // reachable only when shouldRetry lets a 429 continue — retry_call
        // parity; the send path never takes it (shouldRetry is false then)
        var attempts = 0
        val result = Kap {
            attempts++
            if (attempts == 1) throw TgHttpException(429, """{"parameters": {"retry_after": 7}}""") else "sent"
        }.retry(SendPolicy.schedule(shouldRetry = { true })).evalGraph()
        assertEquals("sent", result)
        assertEquals(7_500L, testScheduler.currentTime, "ra + 0.5 replaces the table delay")

        attempts = 0
        val before = testScheduler.currentTime
        Kap {
            attempts++
            if (attempts == 1) throw TgHttpException(429, """{"parameters": {"retry_after": 120}}""") else "sent"
        }.retry(SendPolicy.schedule(shouldRetry = { true })).evalGraph()
        assertEquals(60_500L, testScheduler.currentTime - before, "retry_after_cap=60 binds the override")
    }

    @Test
    fun exhaustionReraisesTheLastError() = runTest {
        var attempts = 0
        val last = assertFailsWith<IOException> {
            Kap {
                attempts++
                throw net
            }.retry(SendPolicy.schedule()).evalGraph()
        }
        assertEquals(3, attempts)
        assertEquals("connection reset", last.message)
    }
}
