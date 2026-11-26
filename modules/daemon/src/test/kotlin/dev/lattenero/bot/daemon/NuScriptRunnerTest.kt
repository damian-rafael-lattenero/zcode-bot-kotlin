package dev.lattenero.bot.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared nu-subprocess runner's render battery: the header (mode ·
 * seconds, or the exit code when the script failed), the escaped `<pre>`,
 * and the head+tail cut with the log named — never a silent truncation.
 * The plan/gate batteries live per-command ([RamCleanCommandsTest],
 * [FleetHealthcheckCommandsTest]).
 */
class NuScriptRunnerTest {

    private fun render(out: String, exit: Int = 0) = NuScriptRunner.render(
        emoji = "🧹",
        label = "ram-clean",
        mode = "clean",
        exit = exit,
        tookMs = 297,
        out = out,
        logPath = "/logs/ram-clean.log",
    )

    @Test
    fun theScriptWordsRideEscapedInsideOnePre() {
        val reply = render("== ram-clean ==\njava <591> & co")
        assertTrue(reply.startsWith("🧹 <b>ram-clean</b> (clean) · 0s"), reply)
        assertTrue("<pre>== ram-clean ==\njava &lt;591&gt; &amp; co</pre>" in reply, reply)
        assertFalse("✂️" in reply, reply) // it fit — no cut line
    }

    @Test
    fun theHeaderSpeaksSecondsAndTheExitCode() {
        val ok = render("out")
        assertTrue("(clean) · 0s" in ok, ok)
        val failed = render("boom", exit = 3)
        assertTrue("exited with code 3" in failed, failed)
    }

    @Test
    fun anOverlongBodyKeepsHeadAndTailAndNamesTheLog() {
        val head = "== leak suspects ==\n"
        val tail = "== after: free 71% ==\n"
        val filler = (0 until 600).joinToString("") { "line $it of the middle tables\n" }
        val reply = render(head + filler + tail)
        assertTrue("== leak suspects ==" in reply, reply)
        assertTrue("free 71%" in reply, reply) // the tail (verdicts + receipts) survives
        assertTrue("…✂️…" in reply, reply)
        assertTrue("/logs/ram-clean.log" in reply, reply)
        assertTrue("/file brings it" in reply, reply)
        // the middle is what got cut — a filler line is provably gone
        assertFalse("line 300 of the middle tables" in reply, reply)
    }
}
