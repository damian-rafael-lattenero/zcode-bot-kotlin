package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE /plan ROWS' QUOTA (the owner's 11/11 order): both entitlement shapes
 * parse into remaining-percents, the direct rungs' typed snapshots map to
 * the same vocabulary, the compact button suffix and the card line speak
 * the SAME numbers, and a missing read is the tiny honest error — never an
 * invented number.
 */
class PlanQuotaTest {

    @Test
    fun theCreditLimitShapeParsesIntoRemainingPercents() {
        val json = """
        {"quota":{"limits":[
            {"type":"CREDIT_LIMIT","number":5,"currentValue":31,"usage":120,"percentage":26,"nextResetTime":1767225600000},
            {"type":"CREDIT_LIMIT","number":1,"currentValue":10,"usage":100,"percentage":10,"nextResetTime":1767250000000}
        ]}}
        """.trimIndent()
        val mark = PlanQuota.fromChannel(json) as PlanQuota.Mark.Seen
        assertEquals(2, mark.windows.size)
        assertEquals("⏱", mark.windows[0].glyph, "5h window")
        assertEquals(74, mark.windows[0].remainingPct, "100 - 26 used")
        assertEquals("📅", mark.windows[1].glyph, "weekly window")
        assertEquals(90, mark.windows[1].remainingPct)
        assertEquals(1767225600000L, mark.windows[0].resetMs)
    }

    @Test
    fun theStartPlanBucketsShapeParsesIntoRemainingPercents() {
        val json = """
        {"quota":{"limits":[{"unit":1000,"usage":680,"period":"5 hours","nextResetTime":1767225600000}]}}
        """.trimIndent()
        val mark = PlanQuota.fromChannel(json) as PlanQuota.Mark.Seen
        val window = mark.windows.single()
        assertEquals(32, window.remainingPct, "(1000-680)/1000")
        assertEquals("⏱", window.glyph, "«5 hours» names the short window")
    }

    @Test
    fun aPeriodWithoutAKnownNameRidesItsOtherGlyph() {
        val json = """
        {"quota":{"limits":[{"unit":100,"usage":20,"period":"quarterly"}]}}
        """.trimIndent()
        val mark = PlanQuota.fromChannel(json) as PlanQuota.Mark.Seen
        assertEquals("🔒", mark.windows.single().glyph)
    }

    @Test
    fun anUnavailableReasonIsTheMissingMark() {
        val mark = PlanQuota.fromChannel("""{"unavailableReason":"not_configured"}""")!!
        assertEquals(PlanQuota.Mark.Missing("not_configured"), mark)
        assertFalse(PlanQuota.exhausted(mark), "an unreadable plan is NOT proven empty")
        assertTrue(PlanQuota.usable(mark), "unknown is the ladder's last resort, never «empty»")
    }

    @Test
    fun aForeignShapeParsesNullAndGarbageNumbersNeverRide() {
        assertNull(PlanQuota.fromChannel("not json"))
        assertNull(PlanQuota.fromChannel("""{"quota":{}}"""))
        // a quoted percentage is NOT a number — the row rides with an UNKNOWN pct, never a parsed 0
        val quoted = PlanQuota.fromChannel(
            """{"quota":{"limits":[{"type":"CREDIT_LIMIT","number":5,"percentage":"26"}]}}""",
        ) as? PlanQuota.Mark.Seen
        assertEquals(1, quoted?.windows?.size)
        assertNull(quoted?.windows?.first()?.remainingPct)
    }

    @Test
    fun thePercentsStayClampedBetweenZeroAndOneHundred() {
        val json = """
        {"quota":{"limits":[
            {"type":"CREDIT_LIMIT","number":5,"percentage":130},
            {"type":"CREDIT_LIMIT","number":1,"percentage":0}
        ]}}
        """.trimIndent()
        val mark = PlanQuota.fromChannel(json) as PlanQuota.Mark.Seen
        assertEquals(0, mark.windows[0].remainingPct)
        assertEquals(100, mark.windows[1].remainingPct)
    }

    @Test
    fun opencodeWindowsMapToTheSameVocabulary() {
        val usage = OpencodeQuota.parse(
            """{"usage":{"rolling":{"status":"ok","percent":0,"resetsAt":"2026-11-11T22:00:00Z"},
                       "weekly":{"status":"ok","percent":13},
                       "monthly":{"status":"ok","percent":8}}}""",
        )!!
        val mark = PlanQuota.fromOpencode(usage) as PlanQuota.Mark.Seen
        assertEquals(3, mark.windows.size)
        assertEquals("⏱", mark.windows[0].glyph)
        assertEquals(100, mark.windows[0].remainingPct)
        assertEquals("📅", mark.windows[1].glyph)
        assertEquals(87, mark.windows[1].remainingPct)
        assertEquals("🗓", mark.windows[2].glyph, "monthly")
    }

    @Test
    fun zaiMonitorWindowsMapToTheSameVocabulary() {
        val snapshot = QuotaDirect.parseQuotaResponse(
            """{"data":{"level":"pro","limits":[
                {"type":"CREDIT_LIMIT","number":5,"currentValue":31,"usage":120,"percentage":26,"nextResetTime":1767225600000},
                {"type":"CREDIT_LIMIT","number":1,"currentValue":10,"usage":100,"percentage":90,"nextResetTime":0}
            ]}}""",
        )!!
        val mark = PlanQuota.fromZai(snapshot) as PlanQuota.Mark.Seen
        assertEquals(74, mark.windows[0].remainingPct)
        assertEquals(10, mark.windows[1].remainingPct)
    }

    // ── the renders ───────────────────────────────────────────────────────

    @Test
    fun theCompactSuffixSitsBesideTheNameWithOneLibre() {
        val seen = PlanQuota.Mark.Seen(
            listOf(PlanQuota.Window("⏱", 32), PlanQuota.Window("📅", 78)),
        )
        assertEquals(" — ⏱ 32% · 📅 78% libre", PlanQuota.compact(seen))
        assertEquals(" — ❔", PlanQuota.compact(PlanQuota.Mark.Missing("timeout")))
        assertEquals(" — ❔", PlanQuota.compact(null))
    }

    @Test
    fun theCompactSuffixSpeaksTheState() {
        val exhausted = PlanQuota.Mark.Seen(listOf(PlanQuota.Window("⏱", 0), PlanQuota.Window("📅", 0)))
        assertTrue("⛔" in PlanQuota.compact(exhausted))
        val tight = PlanQuota.Mark.Seen(listOf(PlanQuota.Window("⏱", 12), PlanQuota.Window("📅", 90)))
        assertTrue("⚠️" in PlanQuota.compact(tight), "the tightest window under 25% remaining")
        val unknown = PlanQuota.Mark.Seen(listOf(PlanQuota.Window("⏱", null)))
        assertTrue("⏱ ?%" in PlanQuota.compact(unknown), "an unreadable window shows ?, never 0")
    }

    @Test
    fun theLineCarriesTheResetsAndTheTinyError() {
        val seen = PlanQuota.Mark.Seen(listOf(PlanQuota.Window("⏱", 32, resetMs = 1_760_000_000_000L)))
        val line = PlanQuota.line("Start Plan", seen, nowMs = 1_000L)
        assertTrue("<b>Start Plan</b>" in line, line)
        assertTrue("⏱ 32% libre" in line, line)
        assertTrue("resets" in line, line)
        val missing = PlanQuota.line("Start Plan", PlanQuota.Mark.Missing("el canal no respondió"), 1_000L)
        assertTrue("❔ sin datos (el canal no respondió)" in missing, missing)
        assertTrue("reintentá /plan más tarde" in missing, missing)
    }
}
