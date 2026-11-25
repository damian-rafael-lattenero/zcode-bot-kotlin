package dev.lattenero.bot.channel

import dev.lattenero.bot.transport.TgConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The voice's pure builders — the wire JSON is pinned here. Since 24/11 the
 * prose (talk AND thinking) is plain `paragraph` blocks: normal HTML text,
 * left-aligned, formatted — no quote container.
 */
class RichVoiceTest {

    @Test
    fun proseBecomesParagraphsAndCodeBecomesPre() {
        val blocks = RichVoice.proseBlocks(
            "El meter es real: 55.337 tokens.\n```kotlin\nval x = 1\n```\nCapturo el frame.",
        )
        assertEquals(listOf("paragraph", "pre", "paragraph"), blocks.map { it["type"]!!.jsonPrimitive.content })
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
        assertEquals(listOf("paragraph"), plain.map { it["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun aBlankLineSplitsTheTalkIntoParagraphs() {
        val blocks = RichVoice.proseBlocks("uno\n\ndos\n\ntres")
        assertEquals(3, blocks.size, blocks.toString())
        assertTrue(blocks.all { it["type"]!!.jsonPrimitive.content == "paragraph" })
    }

    @Test
    fun theThinkingIsAPinkPullquoteCapped() {
        val blocks = RichVoice.thinkingBlocks("p".repeat(4000))
        assertEquals(1, blocks.size)
        val block = blocks.single()
        assertEquals("pullquote", block["type"]!!.jsonPrimitive.content)
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

    @Test
    fun aBoxTableBecomesANativeTableBlock() {
        val text = "antes\n│ # │ leg  │\n├───┼──────┤\n│ 0 │ boot │\ndespués"
        val blocks = RichVoice.proseBlocks(text)
        assertEquals(
            listOf("paragraph", "table", "paragraph"),
            blocks.map { it["type"]!!.jsonPrimitive.content },
            blocks.toString(),
        )
        val cells = blocks[1]["cells"]!!.jsonArray
        assertEquals(2, cells.size)
        val header = cells[0].jsonArray
        assertEquals("left", header[0].jsonObject["align"]!!.jsonPrimitive.content)
        assertEquals("top", header[0].jsonObject["valign"]!!.jsonPrimitive.content)
        assertEquals("true", header[0].jsonObject["is_header"]!!.jsonPrimitive.content)
        assertEquals("boot", cells[1].jsonArray[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun aTableOverTwentyColumnsDegradesToProseNeverToARejectedBlock() {
        fun row(value: (Int) -> String) = (0 until 21).joinToString("") { "│ ${value(it)} " } + "│"
        val text = row { "c$it" } + "\n" + row { "$it" }
        val blocks = RichVoice.proseBlocks(text)
        assertEquals(listOf("paragraph"), blocks.map { it["type"]!!.jsonPrimitive.content }, blocks.toString())
    }

    // THE INLINE LAW (24/11): block text is literal, so a model's `code` and
    // **bold** used to arrive as raw markers; balanced pairs become runs.
    @Test
    fun inlineMarkdownBecomesRichRuns() {
        val block = RichVoice.thinkingBlocks("mirá `/tasks` y **esto** literal").single()
        val text = block["text"]!!.jsonArray
        assertEquals(5, text.size, text.toString())
        assertEquals("code", text[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("/tasks", text[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("bold", text[3].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("esto", text[3].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun plainTextStaysAStringAndAnUnpairedMarkerIsLeftVerbatim() {
        assertTrue(RichVoice.thinkingBlocks("sin marcadores").single()["text"] is JsonPrimitive)
        val lone = RichVoice.thinkingBlocks("un `backtick suelto").single()
        assertTrue(lone["text"] is JsonPrimitive, "no pair, no invention: ${lone["text"]}")
    }

    // THE LINE SHAPES (24/11): a bullet the model wrote as `- ` reads as `•`,
    // and a heading becomes a bold run — the CoT/answer look formatted, not raw.
    @Test
    fun bulletsAndHeadingsReadLikeThePhone() {
        val block = RichVoice.thinkingBlocks("## Plan\n- uno\n- dos").single()
        val runs = block["text"]!!.jsonArray
        val flat = runs.joinToString("") { run ->
            if (run is JsonPrimitive) run.content else run.jsonObject["text"]!!.jsonPrimitive.content
        }
        assertEquals("Plan\n• uno\n• dos", flat)
        assertEquals("bold", runs[0].jsonObject["type"]!!.jsonPrimitive.content)
    }
}
