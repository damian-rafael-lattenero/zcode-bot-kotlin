package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * M2's tick nudge: a tap wakes the publisher NOW; silence keeps the old
 * cadence. The beat is the attach's first-frame guarantee — no waiting out
 * the 3 s clock.
 */
class LiveHubNudgeTest {

    @Test
    fun aNudgeWakesTheBeatImmediately() = runBlocking {
        val hub = LiveHub()
        hub.requestTick()
        val startedAt = System.nanoTime()
        hub.awaitBeat(5.seconds)
        val elapsedMs = (System.nanoTime() - startedAt) / NANOS_PER_MS
        assertTrue(elapsedMs < 500, "a pending nudge must wake the loop, waited ${elapsedMs}ms")
    }

    @Test
    fun withoutANudgeTheBeatWaitsItsTimeout() = runBlocking {
        val hub = LiveHub()
        val startedAt = System.nanoTime()
        hub.awaitBeat(100.milliseconds)
        val elapsedMs = (System.nanoTime() - startedAt) / NANOS_PER_MS
        assertTrue(elapsedMs >= 90, "an empty beat waits its full window, waited ${elapsedMs}ms")
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
