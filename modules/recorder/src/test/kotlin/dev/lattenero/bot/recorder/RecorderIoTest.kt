package dev.lattenero.bot.recorder

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The IO shell against a real temp directory: day file, atomic state, cap. */
class RecorderIoTest {

    private fun tempDir(): File = createTempDirectory("bot-recorder-t").toFile()

    private fun parseLines(day: File): List<TraceLine> =
        day.readLines().filter { it.isNotBlank() }.map {
            RecorderJson.json.decodeFromString(TraceLine.serializer(), it)
        }

    @Test
    fun `two passes - first records everything, an unchanged second records only the pass line`() {
        val state = tempDir()
        val out = tempDir()
        try {
            File(state, "bot-session.json").writeText("""{"sid":"s","title":"t","ws":"w"}""")
            File(state, "tg-offset").writeText("123")

            val r1 = Recorder(state, out, capBytes = 4096)
            assertEquals(3, r1.runPass()) // pass + 2 files
            val day = out.listFiles()!!.first { it.name.startsWith("replay-") }
            val first = parseLines(day)
            assertEquals(listOf("pass", "file", "file"), first.map { it.t })
            assertTrue(first[1].content!!.contains("\"sid\":\"s\""))

            assertEquals(1, r1.runPass()) // nothing changed
            val second = parseLines(day)
            assertEquals("pass", second.last().t)
            assertEquals(0, second.last().changed)
        } finally {
            state.deleteRecursively()
            out.deleteRecursively()
        }
    }

    @Test
    fun `a change and a delete are captured on the next pass`() {
        val state = tempDir()
        val out = tempDir()
        try {
            val a = File(state, "a.json").apply { writeText("1") }
            File(state, "b.json").apply { writeText("2") }
            val r = Recorder(state, out)
            r.runPass()

            a.writeText("changed")
            File(state, "b.json").delete()
            r.runPass()

            val day = out.listFiles()!!.first { it.name.startsWith("replay-") }
            val lines = parseLines(day)
            assertTrue(lines.any { it.t == "file" && it.name == "a.json" && it.content == "changed" })
            assertTrue(lines.any { it.t == "gone" && it.name == "b.json" })
        } finally {
            state.deleteRecursively()
            out.deleteRecursively()
        }
    }

    @Test
    fun `over-cap files are hash-only and binary falls back to base64`() {
        val state = tempDir()
        val out = tempDir()
        try {
            File(state, "big.bin").writeBytes(ByteArray(64) { it.toByte() })
            val r = Recorder(state, out, capBytes = 8)
            r.runPass()
            val day = out.listFiles()!!.first { it.name.startsWith("replay-") }
            val line = parseLines(day).first { it.name == "big.bin" }
            assertTrue(line.truncated)
            assertEquals(null, line.content)
            assertEquals(64, line.sha256!!.length) // sha256 hex
        } finally {
            state.deleteRecursively()
            out.deleteRecursively()
        }
    }

    @Test
    fun `recorder state is written atomically and parses back`() {
        val state = tempDir()
        val out = tempDir()
        try {
            File(state, "x").writeText("x")
            Recorder(state, out).runPass()
            val prev = File(out, "recorder-state.json")
            assertTrue(prev.exists())
            assertFalse(File(out, "recorder-state.json.tmp").exists())
            assertTrue(prev.readText().contains("\"x\""))
        } finally {
            state.deleteRecursively()
            out.deleteRecursively()
        }
    }
}
