package dev.lattenero.bot.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * /ram_clean's pure half: the plan's mode table (bare = report, --clean =
 * the drill, anything else = the usage), the second lock (clean is
 * owner-gated, fail-closed), and the render (escaped `<pre>`, the head+tail
 * cut with the log named — never a silent truncation).
 */
class RamCleanCommandsTest {

    // ---- the plan ------------------------------------------------------------

    @Test
    fun bareRestPlansTheReport() {
        assertEquals(RamCleanCommands.Plan.Report, RamCleanCommands.plan(""))
        assertEquals(RamCleanCommands.Plan.Report, RamCleanCommands.plan("   "))
    }

    @Test
    fun theCleanWordPlansTheDrill() {
        assertEquals(RamCleanCommands.Plan.Clean, RamCleanCommands.plan("--clean"))
        assertEquals(RamCleanCommands.Plan.Clean, RamCleanCommands.plan("clean"))
        assertEquals(listOf("--clean"), RamCleanCommands.plan(" --clean ").argv)
    }

    @Test
    fun theTerminalOnlyFlagsRefuseWithTheUsage() {
        for (rest in listOf("--trend", "--leak-run-mb 5", "todo")) {
            val plan = RamCleanCommands.plan(rest)
            assertTrue(plan is RamCleanCommands.Plan.Refused, rest)
            assertTrue("/ram_clean" in plan.reason, plan.reason)
        }
    }

    // ---- the second lock -----------------------------------------------------

    @Test
    fun cleanRefusesStrangersAndFailCloses() {
        val gated = RamCleanCommands.gate(RamCleanCommands.Plan.Clean, fromId = 42L, owner = 7L)
        assertTrue(gated is RamCleanCommands.Plan.Refused, gated.toString())
        assertTrue("Solo el dueño" in gated.reason, gated.reason)
        // fail-closed: a null sender or an unwired owner never passes
        assertTrue(RamCleanCommands.gate(RamCleanCommands.Plan.Clean, null, 7L) is RamCleanCommands.Plan.Refused)
        assertTrue(RamCleanCommands.gate(RamCleanCommands.Plan.Clean, 7L, null) is RamCleanCommands.Plan.Refused)
    }

    @Test
    fun cleanPassesForTheOwnerAndTheReportNeedsNoLock() {
        assertEquals(RamCleanCommands.Plan.Clean, RamCleanCommands.gate(RamCleanCommands.Plan.Clean, 7L, 7L))
        assertEquals(
            RamCleanCommands.Plan.Report,
            RamCleanCommands.gate(RamCleanCommands.Plan.Report, fromId = 42L, owner = 7L),
        )
    }

    // ---- the render ----------------------------------------------------------

    @Test
    fun theScriptWordsRideEscapedInsideOnePre() {
        val reply = RamCleanCommands.render(
            clean = false,
            exit = 0,
            tookMs = 297,
            out = "== ram-clean ==\njava <591> & co",
            logPath = "/tmp/ram-clean.log",
        )
        assertTrue(reply.startsWith("🧹 <b>ram-clean</b> (report) · 0s"), reply)
        assertTrue("<pre>== ram-clean ==\njava &lt;591&gt; &amp; co</pre>" in reply, reply)
        assertFalse("✂️" in reply, reply) // it fit — no cut line
    }

    @Test
    fun theHeaderSpeaksSecondsAndTheExitCode() {
        val ok = RamCleanCommands.render(true, 0, 2_950, "out", "/tmp/l.log")
        assertTrue("(clean) · 2s" in ok, ok)
        val failed = RamCleanCommands.render(false, 3, 100, "boom", "/tmp/l.log")
        assertTrue("código 3" in failed, failed)
    }

    @Test
    fun anOverlongBodyKeepsHeadAndTailAndNamesTheLog() {
        val head = "== leak suspects ==\n"
        val tail = "== after: free 71% ==\n"
        val filler = (0 until 600).joinToString("") { "line $it of the middle tables\n" }
        val reply = RamCleanCommands.render(true, 0, 5_000, head + filler + tail, "/logs/ram-clean.log")
        assertTrue("== leak suspects ==" in reply, reply)
        assertTrue("free 71%" in reply, reply) // the tail (the clean receipts) survives
        assertTrue("…✂️…" in reply, reply)
        assertTrue("/logs/ram-clean.log" in reply, reply)
        assertTrue("/file lo trae" in reply, reply)
        // the middle is what got cut — a filler line is provably gone
        assertFalse("line 300 of the middle tables" in reply, reply)
    }
}
