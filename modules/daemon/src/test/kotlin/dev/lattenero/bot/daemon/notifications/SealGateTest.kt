package dev.lattenero.bot.daemon.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The 60 s fold: the card already showed it — the notification is not an echo. */
class SealGateTest {

    @Test
    fun theFirstSealOfATaskSpeaks() {
        val gate = SealGate(clockMs = { 100_000L })
        assertTrue(gate.tryAnnounce("t"))
    }

    @Test
    fun aSealInsideTheWindowFoldsIntoSilence() {
        var now = 100_000L
        val gate = SealGate(clockMs = { now })
        assertTrue(gate.tryAnnounce("t"))
        now += SealGate.SEAL_GAP_MS - 1
        assertFalse(gate.tryAnnounce("t"), "59 s later: the card already showed it")
    }

    @Test
    fun aSealPastTheWindowSpeaksAgain() {
        var now = 100_000L
        val gate = SealGate(clockMs = { now })
        assertTrue(gate.tryAnnounce("t"))
        now += SealGate.SEAL_GAP_MS
        assertTrue(gate.tryAnnounce("t"), "60 s later: a new turn's seal is its own notification")
    }

    @Test
    fun aFoldDoesNotMoveTheClock() {
        var now = 100_000L
        val gate = SealGate(clockMs = { now })
        assertTrue(gate.tryAnnounce("t"))
        now += 10_000L
        assertFalse(gate.tryAnnounce("t"))
        now += SealGate.SEAL_GAP_MS // 70 s after the FIRST announce, 60 s after the fold
        assertTrue(gate.tryAnnounce("t"), "the fold recorded nothing — the window ran from the announce")
    }

    @Test
    fun tasksAreIndependent() {
        val gate = SealGate(clockMs = { 100_000L })
        assertTrue(gate.tryAnnounce("a"))
        assertTrue(gate.tryAnnounce("b"), "another conversation's seal is not an echo of this one")
    }

    @Test
    fun theMapPrunesInsteadOfBallooning() {
        var now = 100_000L
        val gate = SealGate(clockMs = { now })
        repeat(400) { gate.tryAnnounce("task-$it") }
        assertEquals(SealGate.LAST_CAP, gate.underlyingSize(), "the cap holds")
        now += SealGate.PRUNE_MS + 1_000L
        gate.tryAnnounce("task-late")
        assertTrue(gate.underlyingSize() < SealGate.LAST_CAP, "the prune dropped the stale verdicts")
    }
}
