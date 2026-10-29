package dev.lattenero.bot.daemon.zconn

import dev.lattenero.bot.contracts.ShadowDecision
import dev.lattenero.bot.contracts.StateFiles
import dev.lattenero.bot.contracts.ZconnState
import dev.lattenero.bot.daemon.shadow.ShadowLog
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The watcher against a real directory tree: the cycle's whole behavior —
 * arming, transitions, state advancement, the since clamp, the quiet-pass
 * no-rewrite — is the Python `zconn.listen` suite ported to the IO shell.
 */
class ZconnShadowTest {

    @TempDir
    lateinit var base: File

    private val stateDir: File get() = File(base, "state")
    private val shadowDir: File get() = File(base, "shadow")

    private fun logFile(name: String = "zcode-2026-10-29.jsonl"): File =
        File(File(File(base, "cli"), "log"), name).apply { parentFile.mkdirs() }

    private fun downLine(
        ts: String,
        reason: String,
        attempt: Long? = null,
        event: String = "model.network.failed",
    ): String {
        val ctx = if (attempt == null) {
            "\"context\":{\"statusMessage\":\"$reason\"}"
        } else {
            "\"context\":{\"statusMessage\":\"$reason\",\"attempt\":$attempt,\"maxAttempts\":11}"
        }
        return """{"timestamp":"$ts","event":"$event","sessionId":"session_x",$ctx}""" + "\n"
    }

    private fun upLine(ts: String): String =
        """{"timestamp":"$ts","event":"model.network.completed","sessionId":"session_x"}""" + "\n"

    private fun shadow(now: () -> Double): ZconnShadow =
        ZconnShadow(base, stateDir, ShadowLog(shadowDir), now)

    private fun state(): ZconnState? =
        runCatching { File(stateDir, "zconn.json").readText() }.getOrNull()
            ?.let { StateFiles.parseObject(it) }
            ?.let(ZconnState::fromJson)

    private fun shadowLines(): List<ShadowDecision> =
        File(shadowDir, "zconn.jsonl").readLines().map { Json.decodeFromString<ShadowDecision>(it) }

    @Test
    fun firstRunArmsSilently_NoPastReplay() {
        logFile().writeText(downLine("2026-10-29T10:00:01.000Z", "old") + upLine("2026-10-29T10:00:02.000Z"))
        var nowVal = 100.0
        val z = shadow { nowVal }

        assertEquals(0, z.pass().decisions.size)
        val st = assertNotNull(state())
        assertEquals(100.0, st.since)
        assertTrue(!st.down)

        // quiet cycles neither advance since nor rewrite the file (its
        // mtime is a clock)
        val mtime = File(stateDir, "zconn.json").lastModified()
        nowVal = 160.0
        z.pass()
        assertEquals(100.0, assertNotNull(state()).since)
        assertEquals(mtime, File(stateDir, "zconn.json").lastModified())
    }

    @Test
    fun transitionsSpeakWithByteExactTextsAndStateAdvances() {
        val f = logFile()
        f.writeText("")
        var nowVal = 100.0
        val z = shadow { nowVal }
        z.pass() // arm at 100

        f.appendText(
            downLine("2026-10-29T10:00:41.200Z", "Cannot connect to API: read ETIMEDOUT", attempt = 3) +
                downLine("2026-10-29T10:00:41.400Z", "Cannot connect to API: read ETIMEDOUT", event = "model.request.failed"),
        )
        nowVal = 102.0
        val pass1 = z.pass()
        val d1 = pass1.decisions.single()
        assertEquals("down", d1.decision)
        assertEquals(
            "⚠️ <b>z.ai connection lost</b>\nCannot connect to API: read ETIMEDOUT\n(attempt 3/11, next retry 8s)",
            d1.text,
        )
        val st1 = assertNotNull(state())
        assertTrue(st1.down)
        assertEquals(minOf(ts("2026-10-29T10:00:41.400Z") + SINCE_ADVANCE_S, 102.0), st1.since)

        f.appendText(upLine("2026-10-29T10:01:10.200Z"))
        nowVal = 131.0
        val pass2 = z.pass()
        assertEquals("✅ <b>z.ai back</b> — the cut lasted 29s", pass2.decisions.single().text)
        assertTrue(!assertNotNull(state()).down)

        assertEquals(listOf("down", "up"), shadowLines().map { it.decision })
        assertEquals(ts("2026-10-29T10:00:41.200Z"), shadowLines()[0].eventTs)
    }

    @Test
    fun aDeadMacParksBothAndTheyLandTogether() {
        val f = logFile()
        f.writeText("")
        var nowVal = 1.0
        val z = shadow { nowVal }
        z.pass() // arm
        f.appendText(downLine("2026-10-29T10:02:00.000Z", "ENOTFOUND") + upLine("2026-10-29T10:02:45.000Z", ))
        nowVal = 48.0
        val out = z.pass()
        assertEquals(listOf("down", "up"), out.decisions.map { it.decision })
        assertEquals(2, shadowLines().size)
    }

    @Test
    fun sinceNeverCrossesIntoTheFuture() {
        val f = logFile()
        f.writeText("")
        var nowVal = 100.0
        val z = shadow { nowVal }
        z.pass()
        f.appendText(upLine("2026-10-29T23:59:59.000Z"))
        nowVal = 120.0 // the clock NOW, behind the event
        z.pass()
        assertEquals(120.0, assertNotNull(state()).since)
    }

    @Test
    fun aFreshProcessRereadsTheLogButNeverReannounces() {
        // A realistic clock matters here: the cross-restart protection is
        // the `since` floor (advanced to last event + 0.5), which drops the
        // episode's events except the closing UP — and that one finds the
        // track already closed. (A clock far BEHIND the events hits the
        // clamp and would re-announce; Python behaves the same there.)
        val tDown = ts("2026-10-29T10:00:00.000Z")
        val tUp = ts("2026-10-29T10:00:30.000Z")
        val f = logFile()
        f.writeText("")
        var nowVal = tDown - 10.0
        val z1 = shadow { nowVal }
        z1.pass() // arm
        f.appendText(downLine("2026-10-29T10:00:00.000Z", "first") + upLine("2026-10-29T10:00:30.000Z"))
        nowVal = tUp + 5.0
        assertEquals(2, z1.pass().decisions.size)
        assertEquals(tUp + SINCE_ADVANCE_S, assertNotNull(state()).since)

        // the daemon restarts: the cursor is gone, the whole file is
        // re-read, the since floor admits only the closing UP — silent.
        nowVal = tUp + 60.0
        val z2 = shadow { nowVal }
        assertEquals(0, z2.pass().decisions.size)
        assertEquals(2, shadowLines().size)
        assertEquals(tUp + SINCE_ADVANCE_S, assertNotNull(state()).since)
    }

    private fun ts(iso: String): Double = java.time.Instant.parse(iso).toEpochMilli() / 1000.0
}
