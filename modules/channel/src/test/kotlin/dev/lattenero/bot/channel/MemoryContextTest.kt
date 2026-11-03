package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-020's injected block: bounded at 1500 code points, one bullet per
 * memory, UTF-8 never split — and silence (an empty block) when there is
 * nothing to say.
 */
class MemoryContextTest {

    private fun searchJson(vararg memories: String): String =
        """{"query":"x","embedModel":"m","memories":[${memories.joinToString(",")}]}"""

    private fun memory(id: Long, kind: String, content: String): String =
        "{\"id\":$id,\"kind\":${jsonString(kind)},\"content\":${jsonString(content)},\"score\":0.8}"

    private fun jsonString(value: String): String =
        Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))

    @Test
    fun aBlockStartsWithTheSpanishHeaderAndOneBulletPerMemory() {
        val block = MemoryContext.block(
            searchJson(
                memory(42, "preference", "commits chicos"),
                memory(41, "fact", "el daemon usa launchd"),
            ),
        )
        val lines = block.split("\n")
        assertEquals(MemoryContext.HEADER, lines[0])
        assertEquals(2, lines.size - 1)
        assertTrue(lines[1].startsWith("- [preference #42] "))
        assertTrue("commits chicos" in lines[1])
        assertTrue(lines[2].startsWith("- [fact #41] "))
    }

    @Test
    fun theBlockNeverExceedsTheCap() {
        val huge = "🧠".repeat(5_000)
        val many = (1..12).map { memory(it.toLong(), "fact", huge) }
        val block = MemoryContext.block(searchJson(*many.toTypedArray()))
        assertTrue(PyText.cpLength(block) <= MemoryContext.BLOCK_CAP, "block was ${PyText.cpLength(block)}")
        assertTrue(block.startsWith(MemoryContext.HEADER))
        assertTrue(block.endsWith("…"), "a truncated memory carries the ellipsis")
    }

    @Test
    fun theCutNeverBreaksAUtf8Sequence() {
        // every memory is emoji + accents: the cap must land between code points
        val many = (1..12).map { memory(it.toLong(), "fact", "ñandú 🧠🎯 ".repeat(200)) }
        val block = MemoryContext.block(searchJson(*many.toTypedArray()))
        val roundTrip = block.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)
        assertEquals(block, roundTrip, "the block must survive a UTF-8 round trip")
        assertTrue(PyText.cpLength(block) <= MemoryContext.BLOCK_CAP)
        assertTrue(!block.last().isHighSurrogate(), "a split surrogate pair would end the block")
    }

    @Test
    fun oneMemoryGetsItsOwnCapSoALongOneCannotEatTheBlock() {
        val memories = (1..3).map { memory(it.toLong(), "fact", "x".repeat(1_200)) }
        val block = MemoryContext.block(searchJson(*memories.toTypedArray()))
        val lines = block.split("\n").drop(1)
        assertTrue(lines.size >= 2, "the per-item cap keeps room for more memories: $lines")
        lines.forEach { assertTrue(PyText.cpLength(it) <= MemoryContext.ITEM_CAP) }
    }

    @Test
    fun noMemoriesIsAnEmptyBlock() {
        assertEquals("", MemoryContext.block("""{"query":"x","embedModel":"m","memories":[]}"""))
        assertEquals("", MemoryContext.block("not json"))
        assertEquals("", MemoryContext.block("""{"memories":[{"id":1,"kind":"fact","content":"   "}]}"""))
    }

    @Test
    fun prependPutsTheBlockBeforeTheOwnersWords() {
        val text = "arreglá el login"
        val composed = MemoryContext.prepend(text, "Contexto de memoria (agent-brain):\n- [fact #1] x")
        assertTrue(composed.startsWith("Contexto de memoria (agent-brain):"))
        assertTrue(composed.endsWith("\n\n$text"))
        // no block = the SAME text, byte for byte
        assertEquals(text, MemoryContext.prepend(text, ""))
    }
}
