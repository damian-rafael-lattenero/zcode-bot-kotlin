package dev.lattenero.bot.transport

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * THE RICH ENVELOPE (Bot API 10.1, 24/11): `sendRichMessage` and
 * `editMessageText` accept `rich_message` — a structured body that renders
 * tables, code blocks and headings NATIVELY on the phone. The bot already
 * composes HTML everywhere (SendRequest, EditRequest, NuScriptRunner,
 * NotifText, ToolBox), and the rich HTML mode supports the same tags plus
 * `<table bordered>`, so the whole bot upgrades by changing the ENVELOPE,
 * not the producers. `parse_mode` is gone: rich HTML carries its own markup.
 *
 * Two laws measured on the owner's phone:
 *  - rich HTML folds single newlines into spaces, exactly like markdown, so
 *    [htmlRich] turns them into `<br>` OUTSIDE `<pre>`/`<code>` (a code
 *    block keeps its literal lines);
 *  - the per-message ceiling is 32,768 code points and 500 blocks — we keep
 *    a phone-sane [RICH_CP] ceiling well below it, with `htmlTruncate`'s
 *    tag-aware cut so a long answer never loses its open tags.
 */
object RichRequest {

    /**
     * The rich body's cap: ~3x the legacy 3900, still a screenful of scroll
     * on a phone. The 32,768 ceiling exists; this is the house budget.
     */
    const val RICH_CP: Int = 12_000

    /**
     * The `sendRichMessage` params: one rich HTML body, the same loudness
     * contract as [SendRequest.compose], the markup as a JSON string.
     */
    fun composeSend(
        cfg: TgConfig,
        html: String,
        loud: Boolean? = null,
        markup: JsonObject? = null,
    ): JsonObject {
        val notify = loud ?: cfg.loudAll
        val base = buildJsonObject {
            put("chat_id", cfg.chatId)
            put("rich_message", buildJsonObject { put("html", htmlRich(html)) })
            put("disable_notification", !notify)
        }
        return withMarkup(base, markup)
    }

    /**
     * The `editMessageText` params for a rich body: pass `rich_message`
     * instead of `text` (the 10.1 field), keep the markup semantics of
     * [EditRequest] — absent clears the keyboard.
     */
    fun composeEdit(
        cfg: TgConfig,
        messageId: Long,
        html: String,
        markup: JsonObject? = null,
    ): JsonObject {
        val base = buildJsonObject {
            put("chat_id", cfg.chatId)
            put("message_id", messageId)
            put("rich_message", buildJsonObject { put("html", htmlRich(html)) })
        }
        return withMarkup(base, markup)
    }

    /**
     * HTML for a rich body: newlines become `<br>` except inside pre/code,
     * then the tag-aware cap. A blank line keeps its own rank (two `<br>`),
     * so the terminal shape survives the trip.
     */
    fun htmlRich(html: String): String =
        htmlTruncate(preserveLines(html.replace("\r\n", "\n")), RICH_CP)

    private fun withMarkup(base: JsonObject, markup: JsonObject?): JsonObject =
        if (markup == null) {
            base
        } else {
            JsonObject(base + ("reply_markup" to JsonPrimitive(PyText.jsonDumps(markup))))
        }

    private val TAG_RE = Regex("<[^>]+>")

    /** Replace `\n` with `<br>` only while no `<pre>`/`<code>` is open. */
    private fun preserveLines(html: String): String {
        val sb = StringBuilder(html.length + LINE_PADDING)
        var codeDepth = 0
        var last = 0
        for (m in TAG_RE.findAll(html)) {
            sb.append(if (codeDepth > 0) html.substring(last, m.range.first) else breaks(html, last, m.range.first))
            val tag = m.value
            if (isCodeTag(tag)) codeDepth = (codeDepth + if (tag.startsWith("</")) -1 else 1).coerceAtLeast(0)
            sb.append(tag)
            last = m.range.last + 1
        }
        sb.append(if (codeDepth > 0) html.substring(last) else breaks(html, last, html.length))
        return sb.toString()
    }

    private fun breaks(html: String, from: Int, to: Int): String =
        html.substring(from, to).replace("\n", "<br>")

    /** `<pre>`/`<code>` and their closers; a self-closing tag never opens a span. */
    private fun isCodeTag(tag: String): Boolean {
        val name = tag.removePrefix("</").removePrefix("<").substringBefore('>')
            .substringBefore(' ').substringBefore('/').lowercase()
        return (name == "pre" || name == "code") && !tag.endsWith("/>")
    }

    private const val LINE_PADDING = 64
}
