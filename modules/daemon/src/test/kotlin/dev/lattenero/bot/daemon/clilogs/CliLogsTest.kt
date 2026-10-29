package dev.lattenero.bot.daemon.clilogs

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.longOrNull

class CliLogParserTest {

    private val needles = setOf("model.network.failed", "model.network.completed")

    @Test
    fun parsesTheRealCompletedShape() {
        val line =
            """{"timestamp":"2026-10-29T13:06:57.233Z","level":"info","event":"model.network.completed",""" +
                """"module":"core.runtime","message":"Model network request completed","sessionId":"session_4cf"}"""
        val ev = CliLogParser.parseLine(line, needles, 0.0)
        assertEquals("model.network.completed", ev?.event)
        assertEquals("session_4cf", ev?.sid)
        assertEquals(Instant.parse("2026-10-29T13:06:57.233Z").toEpochMilli() / 1000.0, ev?.ts)
        assertNull(ev?.context)
    }

    @Test
    fun extractsTheContextAndItsStatusMessage() {
        val line =
            """{"timestamp":"2026-10-29T13:00:01.000Z","event":"model.network.failed","sessionId":"s1",""" +
                """"context":{"statusMessage":"Cannot connect to API: read ETIMEDOUT","attempt":3,"maxAttempts":11}}"""
        val ev = CliLogParser.parseLine(line, needles, 0.0)
        assertEquals("model.network.failed", ev?.event)
        assertEquals(3L, (ev?.context?.get("attempt") as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull)
    }

    @Test
    fun aNeedleMentionedElsewhereIsNotAnEvent() {
        val line =
            """{"timestamp":"2026-10-29T13:00:01.000Z","event":"model.network.log","sessionId":"s1",""" +
                """"message":"saw model.network.failed in a trace"}"""
        assertNull(CliLogParser.parseLine(line, needles, 0.0))
    }

    @Test
    fun subagentSessionsAreSkipped() {
        val line =
            """{"timestamp":"2026-10-29T13:00:01.000Z","event":"model.network.completed",""" +
                """"sessionId":"sess_subagent_agent_1"}"""
        assertNull(CliLogParser.parseLine(line, needles, 0.0))
    }

    @Test
    fun theSinceFloorKeepsItsOneSecondTolerance() {
        val ts = Instant.parse("2026-10-29T13:00:01.000Z").toEpochMilli() / 1000.0
        val line = """{"timestamp":"2026-10-29T13:00:01.000Z","event":"model.network.completed","sessionId":"s"}"""
        assertEquals("model.network.completed", CliLogParser.parseLine(line, needles, ts + 1.0)?.event)
        assertNull(CliLogParser.parseLine(line, needles, ts + 2.5))
    }

    @Test
    fun junkLinesAreSkippedNotThrown() {
        assertNull(CliLogParser.parseLine("not json", needles, 0.0))
        assertNull(CliLogParser.parseLine("""{"event":"model.network.failed"}""", needles, 0.0))
        assertTrue(CliLogParser.parseChunk("\n\n$line1\n\n", needles, 0.0).size == 1)
    }

    private val line1 = """{"timestamp":"2026-10-29T13:00:01.000Z","event":"model.network.failed","sessionId":"s"}"""
}

class CliLogTailerTest {

    @TempDir
    lateinit var base: File

    private val needles = setOf("model.network.failed", "model.network.completed")

    private fun logDir(): File = File(File(base, "cli"), "log").apply { mkdirs() }

    private fun line(ts: String, event: String, sid: String = "session_x"): String =
        """{"timestamp":"$ts","event":"$event","sessionId":"$sid"}""" + "\n"

    @Test
    fun aPartialTrailingLineWaitsForItsNewline() {
        val f = File(logDir(), "zcode-2026-10-29.jsonl")
        f.writeText(
            line("2026-10-29T13:00:01.000Z", "model.network.failed") +
                line("2026-10-29T13:00:02.000Z", "model.network.completed").dropLast(1),
        )
        val tailer = CliLogTailer(base)
        val r1 = tailer.read(null, needles, 0.0)
        assertEquals(1, r1.events.size)
        f.appendText("\n")
        val r2 = tailer.read(r1.cursor, needles, 0.0)
        assertEquals(listOf("model.network.completed"), r2.events.map { it.event })
    }

    @Test
    fun quietPassesSeeNothingAndHoldTheCursor() {
        val f = File(logDir(), "zcode-2026-10-29.jsonl")
        f.writeText(line("2026-10-29T13:00:01.000Z", "model.network.failed"))
        val tailer = CliLogTailer(base)
        val r1 = tailer.read(null, needles, 0.0)
        val r2 = tailer.read(r1.cursor, needles, 0.0)
        assertEquals(0, r2.events.size)
        assertEquals(r1.cursor, r2.cursor)
    }

    @Test
    fun afterTheRolloverOnlyTheNewestFileIsRead_TheMirroredWart() {
        // Python's iter_events(last_files=1) drops the previous day's tail
        // after midnight; the shadow mirrors the wart because diverging
        // here would fail the byte-parity gate (ADR-023).
        val old = File(logDir(), "zcode-2026-10-28.jsonl")
        old.writeText(line("2026-10-28T23:59:00.000Z", "model.network.failed"))
        val tailer = CliLogTailer(base)
        val r1 = tailer.read(null, needles, 0.0)
        assertEquals(1, r1.events.size)

        val newFile = File(logDir(), "zcode-2026-10-29.jsonl")
        newFile.writeText(line("2026-10-29T00:00:10.000Z", "model.network.completed"))
        old.appendText(line("2026-10-28T23:59:59.000Z", "model.network.failed"))

        val r2 = tailer.read(r1.cursor, needles, 0.0)
        assertEquals(listOf("model.network.completed"), r2.events.map { it.event })
    }

    @Test
    fun noLogsAtAllIsQuietNotAnError() {
        val r = CliLogTailer(base).read(null, needles, 0.0)
        assertEquals(0, r.events.size)
    }
}
