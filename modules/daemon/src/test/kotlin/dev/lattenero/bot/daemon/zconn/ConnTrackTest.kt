package dev.lattenero.bot.daemon.zconn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The episode machine's incident cases, ported from the Python suite
 * (tests/test_f85_zconn.py) — each assertion is a story that already
 * happened once in production.
 */
class ConnTrackTest {

    private val defaultPill = ZconnText.pillVocab(null)

    private fun feed(
        track: ConnTrack,
        ts: Double,
        event: String,
        reason: String = "",
        pill: String = defaultPill,
    ) = ConnTrackMachine.feed(track, ts, event, reason, pill)

    @Test
    fun oneDownPerEpisodeThenOneUp() {
        var t = ConnTrack()
        val first = feed(t, 10.0, "model.network.failed", "ETIMEDOUT")
        assertEquals(listOf(ZconnNotice.Down("ETIMEDOUT", 10.0, defaultPill)), first.second)
        t = first.first

        // the warn PAIR (request.failed) is not a second episode
        val pair = feed(t, 10.1, "model.request.failed", "x")
        assertEquals(emptyList(), pair.second)
        t = pair.first

        // retries keep quiet; the recovery SPEAKS (42s cut)
        val retry = feed(t, 14.0, "model.network.failed", "502")
        assertEquals(emptyList(), retry.second)
        t = retry.first

        val up = feed(t, 52.0, "model.network.completed")
        assertEquals(listOf(ZconnNotice.Up(10.0, 52.0)), up.second)
        t = up.first

        // the second completed is nothing new
        assertEquals(emptyList(), feed(t, 53.0, "model.network.completed").second)

        // ...and it speaks again only on the NEXT fresh episode
        val again = feed(t, 600.0, "model.network.failed", "down")
        assertEquals(listOf(ZconnNotice.Down("down", 600.0, defaultPill)), again.second)
    }

    @Test
    fun upReportsTheDuration() {
        var t = ConnTrack()
        t = feed(t, 100.0, "model.network.failed", "no route").first
        assertEquals(listOf(ZconnNotice.Up(100.0, 142.0)), feed(t, 142.0, "model.network.completed").second)
    }

    @Test
    fun sputtersAreOneEpisode() {
        var t = ConnTrack()
        t = feed(t, 100.0, "model.network.failed", "first").first
        t = feed(t, 130.0, "model.network.completed").first
        // the SAME sick wire fails again 10s later: quiet (one story)
        val refail = feed(t, 140.0, "model.network.failed", "again")
        assertEquals(emptyList(), refail.second)
        t = refail.first
        // the final recovery reports the WHOLE episode (100 -> 200)
        assertEquals(listOf(ZconnNotice.Up(100.0, 200.0)), feed(t, 200.0, "model.network.completed").second)
    }

    @Test
    fun theEpisodeKeepsItsFirstReason() {
        // the 13:00 incident: the escalation had rewritten the story with
        // the consequence («Model request was cancelled»); the FIRST reason
        // is the diagnosis.
        var t = ConnTrack()
        t = feed(t, 100.0, "model.network.failed", "Cannot connect to API: getaddrinfo ENOTFOUND").first
        t = feed(t, 140.0, "model.network.failed", "Model request was cancelled.").first
        assertEquals("Cannot connect to API: getaddrinfo ENOTFOUND", t.reason)
        assertEquals(emptyList(), feed(t, 141.0, "model.request.failed", "x").second)
    }

    @Test
    fun anEmptyReasonFallsBackToTheDefault() {
        val (t, n) = feed(ConnTrack(), 5.0, "model.network.failed", "")
        assertTrue(t.down)
        assertEquals("connection failed", (n.single() as ZconnNotice.Down).reason)
    }

    @Test
    fun unknownEventsChangeNothing() {
        val t = ConnTrack(down = true, firstDown = 1.0, reason = "x")
        val (next, notices) = feed(t, 2.0, "model.tokens.counted")
        assertEquals(emptyList(), notices)
        assertEquals(t, next)
    }

    @Test
    fun anUpWithoutADownOnlyMovesLastUp() {
        val (next, notices) = feed(ConnTrack(), 7.0, "model.network.completed")
        assertEquals(emptyList(), notices)
        assertEquals(7.0, next.lastUp)
        assertTrue(!next.down)
    }
}
