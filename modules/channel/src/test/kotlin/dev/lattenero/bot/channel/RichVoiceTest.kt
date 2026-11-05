package dev.lattenero.bot.channel

import dev.lattenero.bot.transport.TgConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The violet voice's pure builders — the wire JSON is pinned here. */
class RichVoiceTest {

    private fun blocksOf(vararg types: String) = types.toList()

    @Test
    fun proseBecomesPullquotesAndCodeBecomesPre() {
        val blocks = RichVoice.proseBlocks(
            "El meter es real: 55.337 tokens.\n```kotlin\nval x = 1\n```\nCapturo el frame.",
        )
        assertEquals(listOf("pullquote", "pre", "pullquote"), blocks.map { it["type"]!!.jsonPrimitive.content })
        assertTrue("55.337" in blocks[0]["text"]!!.jsonPrimitive.content)
        assertEquals("kotlin", blocks[1]["language"]!!.jsonPrimitive.content)
        assertTrue("val x = 1" in blocks[1]["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun theTextIsLiteralNeverHtmlEscaped() {
        // the spec's law: block text is literal RichText — <b> stays <b>
        val blocks = RichVoice.proseBlocks("comparo a < b y x > y")
        assertTrue("a < b y x > y" in blocks[0]["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun anUnclosedFenceEndsAsCodeAndBlankProseStillSpeaks() {
        val unclosed = RichVoice.proseBlocks("```\nsin cerrar")
        assertEquals(listOf("pre"), unclosed.map { it["type"]!!.jsonPrimitive.content })
        val plain = RichVoice.proseBlocks("solo palabras")
        assertEquals(listOf("pullquote"), plain.map { it["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun theThinkingBlockIsTheNativeExpandable() {
        val block = RichVoice.thinkingBlock("p".repeat(4000))
        assertEquals("expandable_blockquote", block["type"]!!.jsonPrimitive.content)
        // the cap speaks: 3500 cp, never a wall beyond the client's render
        assertEquals(3500, block["text"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun composeCarriesChatSilenceAndOptionalMarkup() {
        val cfg = TgConfig(token = "t", chatId = JsonPrimitive(42), ownerUserId = 7L)
        val wire = RichVoice.compose(cfg, RichVoice.proseBlocks("hola"))
        assertEquals(42, wire["chat_id"]!!.jsonPrimitive.content.toInt())
        assertEquals("true", wire["disable_notification"]!!.jsonPrimitive.content)
        val rich = wire["rich_message"]!!.jsonObject
        assertEquals(1, rich["blocks"]!!.jsonArray.size)
    }
}
