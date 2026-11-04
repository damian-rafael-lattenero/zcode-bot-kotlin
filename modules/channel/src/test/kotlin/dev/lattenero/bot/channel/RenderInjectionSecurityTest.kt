package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * U9 · V1 — THIRD-PARTY CONTENT NEVER BECOMES TELEGRAM MARKUP.
 *
 * The bot renders text it did not write: tool outputs, file paths, task
 * titles, RAG citations and memories (all of them can quote a file, a web
 * page or a model answer). Every such piece must reach Telegram's HTML
 * parser ESCAPED — otherwise a payload either forges markup (`<a href>`
 * turns a filename into a link) or breaks the message with a 400
 * «Unclosed tag» and the owner simply never hears that turn.
 *
 * These tests pin the escape at the RENDERER, not at the send: a raw `<`
 * in the output can only be one of the bot's own tags.
 */
class RenderInjectionSecurityTest {

    /** The payload: markup that Telegram WOULD interpret if it arrived raw. */
    private val payload = "<b>bold</b> & <a href=\"https://evil.example\">click</a>"

    private fun assertFullyEscaped(rendered: String, where: String) {
        assertFalse(rendered.contains("<b>bold"), "$where leaked a raw <b>: $rendered")
        assertFalse(rendered.contains("https://evil.example\">click</a>"), "$where leaked a raw <a>: $rendered")
        assertTrue(rendered.contains("&lt;b&gt;bold&lt;/b&gt;"), "$where must escape the tag body: $rendered")
        assertTrue(rendered.contains("&amp;"), "$where must escape the ampersand: $rendered")
    }

    @Test
    fun aCollapsedCotBlockIsEscapedWhole() {
        val rendered = TranscriptFeed.expandedBlock(1, PyText.cpLength(payload), payload)
        assertTrue(rendered.startsWith("📖 <b>CoT 1</b>"))
        assertFullyEscaped(rendered.substringAfter("<blockquote>"), "the CoT blockquote")
        assertTrue(rendered.endsWith("</blockquote>"))
    }

    @Test
    fun aCollapsedToolOutputPageIsEscapedInsideItsPre() {
        val rendered = ToolOutputPages.renderPage("<b>header</b>", listOf(payload), 0)
        assertFullyEscaped(rendered.substringAfter("<pre>").substringBefore("</pre>"), "the output page")
        // the header is a pass-through (the publisher built it escaped) — the body is the data
        assertTrue(rendered.startsWith("<b>header</b>"))
    }

    @Test
    fun theOutputHeaderEscapesTheCommandItNames() {
        val rendered = ToolOutputPages.headerOf("⚡ <b>Bash</b>", payload, 3)
        assertFullyEscaped(rendered.substringAfter("<code>").substringBefore("</code>"), "the header detail")
    }

    @Test
    fun aRagCitationFromTheIndexIsEscaped() {
        val rendered = BrainTexts.ragAnswer(
            payload,
            listOf(BrainHit(uri = payload, title = payload, heading = payload, score = 0.42)),
        )
        assertFullyEscaped(rendered.substringAfter("— «").substringBefore("»"), "the query echo")
        assertFullyEscaped(rendered.substringAfter("1. "), "the citation line")
        assertEquals(1, Regex("<b>").findAll(rendered).count(), "only the bot's own <b>brain</b>")
    }

    @Test
    fun aMemoryFromTheBrainIsEscaped() {
        val rendered = BrainTexts.memoryAnswer(
            "q",
            listOf(BrainMemory(id = 7, kind = payload, content = payload, createdAt = null, score = 0.5)),
        )
        assertFullyEscaped(rendered, "the memory line")
    }

    @Test
    fun aBrainStatusFieldIsEscaped() {
        val rendered = BrainTexts.memoryStatus(
            BrainMemoryStatus(
                pendingTurns = 0, processedTurns = 0, memories = 1,
                byKind = mapOf(payload to 1), quarantined = 0,
                breaker = payload, chatModel = payload, embedModel = payload,
            ),
        )
        assertFullyEscaped(rendered, "the status card")
    }

    @Test
    fun aTaskTitleFromTheSessionStoreIsEscaped() {
        val rendered = BotCommands.renderTaskList(
            rows = listOf(TaskRow(sid = "ses_abc", title = payload, ws = "/x/$payload")),
            running = emptySet(),
            activeSid = null,
            activeWs = "/other",
            all = true,
        )
        assertFullyEscaped(rendered, "the /tasks row")
    }

    /**
     * The complement: an escaped payload can never leave a tag open. If a
     * raw `<` slipped through inside a <pre>, Telegram would reject the
     * WHOLE message (400 «Unclosed tag») — radio silence, the failure the
     * house rules forbid above all.
     */
    @Test
    fun anEscapedPayloadLeavesNoOpenTag() {
        val rendered = TranscriptFeed.expandedBlock(null, 3, payload)
        val opens = Regex("<(blockquote|pre|code)>").findAll(rendered).count()
        val closes = Regex("</(blockquote|pre|code)>").findAll(rendered).count()
        assertEquals(opens, closes, "balanced markup: $rendered")
    }
}
