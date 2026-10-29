package dev.lattenero.bot.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure engine: exactly what each pass must emit, deterministically. */
class SnapshotEngineTest {

    private fun fact(name: String, sha: String = "$name-1", mtime: Long = 1_000) =
        FileFact(name, size = 10, mtimeMs = mtime, sha256 = sha)

    private fun sample(f: FileFact, text: String? = f.name) = FileSample(f, text, null, overCap = false)

    @Test
    fun `first pass emits the pass line plus every file, sorted`() {
        val lines = SnapshotEngine.pass(
            "t0",
            previous = emptyMap(),
            current = mapOf("b.json" to sample(fact("b.json")), "a.json" to sample(fact("a.json"))),
        )
        assertEquals(listOf("pass", "file", "file"), lines.map { it.t })
        assertEquals(listOf("a.json", "b.json"), lines.drop(1).map { it.name })
        assertEquals(2, lines.first().changed)
        assertEquals(0, lines.first().gone)
    }

    @Test
    fun `an unchanged pass emits only the pass line`() {
        val prev = mapOf("a" to fact("a"), "b" to fact("b"))
        val cur = prev.mapValues { sample(it.value) }
        val lines = SnapshotEngine.pass("t1", prev, cur)
        assertEquals(1, lines.size)
        assertEquals("pass", lines[0].t)
        assertEquals(0, lines[0].changed)
    }

    @Test
    fun `a sha change is a delta, a disappearance is a gone line`() {
        val prev = mapOf("a" to fact("a"), "gone" to fact("gone"))
        val cur = mapOf("a" to sample(fact("a", sha = "a-2")))
        val lines = SnapshotEngine.pass("t1", prev, cur)
        assertEquals(listOf("pass", "file", "gone"), lines.map { it.t })
        assertEquals("a", lines[1].name)
        assertEquals("gone", lines[2].name)
        assertEquals(1, lines[0].changed)
        assertEquals(1, lines[0].gone)
    }

    @Test
    fun `over-cap files are recorded hash-only, binary falls back to base64`() {
        val over = FileSample(fact("big"), null, null, overCap = true)
        val bin = FileSample(fact("bin"), null, "aGVsbG8=", overCap = false)
        val lines = SnapshotEngine.pass("t", emptyMap(), mapOf("big" to over, "bin" to bin))
        val bigLine = lines.first { it.name == "big" }
        val binLine = lines.first { it.name == "bin" }
        assertTrue(bigLine.truncated)
        assertEquals(null, bigLine.content)
        assertEquals("aGVsbG8=", binLine.contentB64)
    }

    @Test
    fun `trace lines are compact JSON with no null noise`() {
        val line = TraceLine(t = "pass", ts = "t")
        assertEquals("""{"t":"pass","ts":"t"}""" + "\n", line.toJsonLine())
    }
}
