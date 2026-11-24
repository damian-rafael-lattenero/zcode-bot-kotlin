package dev.lattenero.bot.transport

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The rich envelope's wire, its line-preservation law and its cap — pure, no network. */
class RichRequestTest {

    private val cfg = TgConfig(token = "T", chatId = JsonPrimitive(42))

    private fun JsonObject.richHtml(): String =
        this["rich_message"]!!.jsonObject["html"]!!.jsonPrimitive.content

    @Test
    fun sendCarriesTheRichBodyAndTheLoudness() {
        val wire = RichRequest.composeSend(cfg, "<b>hola</b>", loud = false)
        assertEquals("42", wire["chat_id"]!!.jsonPrimitive.content)
        assertEquals("<b>hola</b>", wire.richHtml())
        assertEquals("true", wire["disable_notification"]!!.jsonPrimitive.content)
        assertFalse("parse_mode" in wire, "rich HTML carries its own markup")
        assertFalse("text" in wire, "the rich field replaces text")
    }

    @Test
    fun editCarriesTheMessageIdAndNeverText() {
        val wire = RichRequest.composeEdit(cfg, messageId = 7, html = "x")
        assertEquals("7", wire["message_id"]!!.jsonPrimitive.content)
        assertEquals("x", wire.richHtml())
        assertFalse("text" in wire)
    }

    @Test
    fun markupRidesAsAJsonStringLikeTheLegacyComposer() {
        val markup = buildJsonObject { put("inline_keyboard", "[]") }
        val wire = RichRequest.composeSend(cfg, "x", markup = markup)
        val raw = wire["reply_markup"] as JsonPrimitive
        assertTrue(raw.isString, "Telegram's reply_markup param is a JSON string")
        assertTrue("inline_keyboard" in raw.content)
    }

    @Test
    fun proseNewlinesBecomeBreaksButCodeKeepsItsLines() {
        val html = "uno\ndos\n<pre>a\nb</pre>\ntres\n<code>x\ny</code>\ncuatro"
        assertEquals(
            "uno<br>dos<br><pre>a\nb</pre><br>tres<br><code>x\ny</code><br>cuatro",
            RichRequest.htmlRich(html),
        )
    }

    @Test
    fun aBlankLineKeepsItsOwnRank() {
        assertEquals("a<br><br>b", RichRequest.htmlRich("a\n\nb"))
    }

    @Test
    fun theCapClosesTheOpenTags() {
        val long = "<pre>" + "x".repeat(RichRequest.RICH_CP + 500) + "</pre>"
        val out = RichRequest.htmlRich(long)
        assertTrue(PyText.cpLength(out) <= RichRequest.RICH_CP + 10, "the cap holds")
        assertTrue(out.endsWith("</pre>"), "htmlTruncate closes what the cut left open")
    }

    @Test
    fun crlfIsNormalizedBeforeTheBreakLaw() {
        assertEquals("a<br>b", RichRequest.htmlRich("a\r\nb"))
    }
}
