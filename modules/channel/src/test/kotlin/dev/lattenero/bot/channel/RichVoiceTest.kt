package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import dev.lattenero.bot.transport.TgConfig
import kotlinx.serialization.json.JsonObject
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

    /** The thinking's LAST block is the quote; the first is its bold header. */
    private fun quote(text: String): JsonObject = RichVoice.thinkingBlocks(text).last()

    @Test
    fun theThinkingIsABoldHeaderPlusACappedQuote() {
        // 26/11, the owner: the pink italic pullquote was hard to read — the
        // header names the block and the reasoning rides an upright quote
        val blocks = RichVoice.thinkingBlocks("p".repeat(4000), number = 3)
        assertEquals(2, blocks.size, blocks.toString())
        assertEquals("paragraph", blocks[0]["type"]!!.jsonPrimitive.content)
        assertTrue(blocks[0].toString().contains("CoT 3"), "the header carries the number: ${blocks[0]}")
        assertEquals("blockquote", blocks[1]["type"]!!.jsonPrimitive.content)
        // the cap speaks: 3500 CODE POINTS (the emoji's two UTF-16 units
        // must not steal a character), never a wall beyond the client's render
        assertEquals(3500, PyText.cpLength(blocks[1]["text"]!!.jsonPrimitive.content))
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
        val block = quote("mirá `/tasks` y **esto** literal")
        val text = block["text"]!!.jsonArray
        assertEquals(5, text.size, text.toString())
        assertEquals("code", text[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("/tasks", text[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("bold", text[3].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("esto", text[3].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun plainTextStaysAStringAndAnUnpairedMarkerIsLeftVerbatim() {
        assertTrue(quote("sin marcadores")["text"] is JsonPrimitive)
        val lone = quote("un `backtick suelto")
        assertTrue(lone["text"] is JsonPrimitive, "no pair, no invention: ${lone["text"]}")
    }

    // THE LINE SHAPES (24/11): a bullet the model wrote as `- ` reads as `•`,
    // and a heading becomes a bold run — the CoT/answer look formatted, not raw.
    @Test
    fun bulletsAndHeadingsReadLikeThePhone() {
        val block = quote("## Plan\n- uno\n- dos")
        val runs = block["text"]!!.jsonArray
        val flat = runs.joinToString("") { run ->
            if (run is JsonPrimitive) run.content else run.jsonObject["text"]!!.jsonPrimitive.content
        }
        assertEquals("Plan\n• uno\n• dos", flat, "the quote carries the shaped lines, bare")
        assertEquals("bold", runs[0].jsonObject["type"]!!.jsonPrimitive.content, "the heading is the first bold run")
    }

    // THE CLASSIC LANE (24/11): the same shaping as HTML — escaped text with
    // real <code>/<b> runs, bullets and headings shaped, no raw markers. The
    // line break stays the caller's (the classic lane keeps REAL newlines —
    // parse_mode=HTML rejects <br>).
    @Test
    fun htmlShapesInlineRunsAndEscapesEverythingElse() {
        val html = RichVoice.html("## Plan\n- mirá `/tasks` y **esto** <b>")
        assertEquals("<b>Plan</b>\n• mirá <code>/tasks</code> y <b>esto</b> &lt;b&gt;", html)
    }

    @Test
    fun plainProseIsJustEscaped() {
        assertEquals("a &lt; b", RichVoice.html("a < b"))
    }
}
