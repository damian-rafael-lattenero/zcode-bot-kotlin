package dev.lattenero.bot.comparator

import dev.lattenero.bot.contracts.ShadowDecision
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/** The truth parsers against the REAL production formats (pinned 2026-10-29). */
class TruthLogsTest {

    @Test
    fun kbLogParsesTheTwoLineRecords() {
        val got = KbLog.parse(
            listOf(
                "[2026-10-28 13:00:38.086 -03] INFO zcode_bot.kb component=transport",
                "zconn down",
                "noise that is not a decision",
                "[2026-10-28 13:02:24.250 -03] INFO zcode_bot.kb component=transport",
                "zconn up",
            ),
        )
        assertEquals(listOf("down", "up"), got.map { it.kind })
        assertEquals("2026-10-28T16:00:38.086Z", Instant.ofEpochMilli((got[0].ts * 1000).toLong()).toString())
        assertEquals("2026-10-28T16:02:24.250Z", Instant.ofEpochMilli((got[1].ts * 1000).toLong()).toString())
    }

    @Test
    fun pwLogPairsPinnedWithEndAndKeepsOpenTurns() {
        val got = PwLog.parse(
            listOf(
                "[10-29 11:10:55] spectator: turn pinned turn_5056bb83-2137",
                "[10-29 11:10:56] spectator end: steps=0",
                "[10-29 11:17:56] spectator: turn pinned turn_b43afaa3-0bf1",
                "[10-29 11:30:54] spectator end: steps=37",
                "[10-29 11:36:49] spectator: turn pinned turn_open",
                "[10-29 11:40:00] something else entirely",
            ),
            year = 2026,
            zone = ZoneOffset.ofHours(-3),
        )
        assertEquals(3, got.size)
        assertEquals(0, got[0].steps)
        assertEquals("turn_b43afaa3-0bf1", got[1].turnPrefix)
        assertEquals(37, got[1].steps)
        assertEquals(null, got[2].steps, "a crashed spectator stays open-ended")
        assertEquals(null, got[2].endTs)
        assertEquals("2026-10-29T14:30:54Z", Instant.ofEpochMilli((got[1].endTs!! * 1000).toLong()).toString())
    }
}

/** The matchers: what PARITY, DIVERGENT and INSUFFICIENT mean, in cases. */
class ParityTest {

    private fun d(watcher: String, decision: String, ts: Double, text: String = "", sid: String? = null) =
        ShadowDecision(ts, "", watcher, decision, text, sid = sid)

    @Test
    fun zconnMatchesKindsWithinToleranceAndClipsCoverage() {
        val py = listOf(ZconnTruth(50.0, "down"), ZconnTruth(100.0, "down"), ZconnTruth(160.0, "up"))
        val shadow = listOf(d("zconn", "down", 105.0), d("zconn", "up", 163.0))
        val p = Parity.zconn(py, shadow, coverageStart = 90.0)
        assertEquals(2, p.pythonEvents)
        assertEquals(1, p.beforeCoverage, "the pre-coverage cut is history, not divergence")
        assertEquals(2, p.matched)
        assertEquals(0, p.pythonOnly)
        assertEquals(0, p.shadowOnly)
        assertEquals(5.0, p.maxDriftS)
    }

    @Test
    fun aKindMismatchIsDivergentBothWays() {
        val p = Parity.zconn(listOf(ZconnTruth(100.0, "down")), listOf(d("zconn", "up", 102.0)), 0.0)
        assertEquals(1, p.pythonOnly)
        assertEquals(1, p.shadowOnly)
        assertEquals("DIVERGENT", Parity.verdict(p, SpectatorParity(0, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun shadowTurnsRegroupByHeaderAndCountMaxStep() {
        val turns = Parity.shadowTurns(
            listOf(
                d("spectator", "message", 10.0, "🖥️ header — Mac turn live (spectator)", "A"),
                d("spectator", "message", 11.0, "🔧 Bash · <b>1</b>", "A"),
                d("spectator", "message", 12.0, "🔧 Bash · <b>2</b>", "A"),
                d("spectator", "message", 20.0, "🖥️ otra — Mac turn live (spectator)", "B"),
                d("spectator", "message", 21.0, "🔧 Bash · <b>5</b>", "B"),
            ),
        )
        assertEquals(2, turns.size)
        assertEquals(ShadowTurn("A", 10.0, 12.0, 2), turns[0])
        assertEquals(ShadowTurn("B", 20.0, 21.0, 5), turns[1])
    }

    @Test
    fun spectatorMatchesStepsSkipsSilentTurnsAndFlagsMismatches() {
        val py = listOf(
            SpectatorTurnTruth(90.0, "t0", 91.0, 0), // silent on both sides
            SpectatorTurnTruth(100.0, "t1", 130.0, 3),
            SpectatorTurnTruth(200.0, "t2", 260.0, 7),
        )
        val shadow = listOf(ShadowTurn("A", 105.0, 130.0, 3), ShadowTurn("B", 193.0, 255.0, 8))
        val p = Parity.spectator(py, shadow, coverageStart = 0.0)
        assertEquals(2, p.pythonTurns)
        assertEquals(1, p.zeroStepTurnsSkipped)
        assertEquals(1, p.matched)
        assertEquals(1, p.stepMismatches)
        assertEquals("DIVERGENT", Parity.verdict(ZconnParity(0, 0, 0, 0, 0, 0, null, null), p))
    }

    @Test
    fun noOverlappingEvidenceIsInsufficientNotDivergent() {
        val z = ZconnParity(0, 0, 0, 5, 0, 0, null, null)
        val s = SpectatorParity(0, 0, 3, 0, 0, 0, 0)
        assertEquals("INSUFFICIENT", Parity.verdict(z, s))
    }
}
