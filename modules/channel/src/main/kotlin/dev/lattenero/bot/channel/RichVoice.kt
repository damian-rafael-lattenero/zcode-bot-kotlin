package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import dev.lattenero.bot.transport.TgConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * THE VOICE OF THE LLM (the owner, 5/11: «I know that the text on that
 * background belongs to that channel and it is the llm talking» [translated]): every segment of
 * the model's prose — the mid-turn talk, the final summary, the thinking —
 * travels as a Bot API 10.x RICH MESSAGE, its quote styling the violet
 * channel's look. Pure: text in → wire JSON out; the send leg lives in
 * [LiveTelegram.sendRich].
 *
 * The spec's laws honored here (core.telegram.org/bots/api 10.3):
 * - InputRichMessage takes exactly ONE of html/markdown/blocks — this
 *   builder speaks BLOCKS.
 * - Block text is LITERAL RichText: no HTML escaping, ever (the spec's
 *   own warning) — the escaper's law does not apply to rich text.
 * - Prose (the model's talk AND its thinking) rides plain `paragraph`
 *   blocks — normal left-aligned formatted text, the owner's 24/11 call;
 *   a paragraph per blank-line chunk. Code rides `pre` with its fence
 *   language.
 */
// The voice's whole surface (wire, prose/blocks/table/inline/paragraphs) —
// the count is the shape, not a grab bag.
@Suppress("TooManyFunctions")
object RichVoice {

    /** One voice message's wire params (sendRichMessage) — silent, like the card. */
    fun compose(
        cfg: TgConfig,
        blocks: List<JsonObject>,
        markup: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        put("chat_id", cfg.chatId)
        put("rich_message", buildJsonObject {
            put("blocks", buildJsonArray { blocks.forEach { add(it) } })
        })
        put("disable_notification", true)
        if (markup != null) put("reply_markup", JsonPrimitive(PyText.jsonDumps(markup)))
    }

    /**
     * The prose's blocks: fenced code becomes `pre` blocks (the fence's
     * language rides), a nu BOX TABLE becomes a native `table` block (the
     * phone shows real columns), everything else becomes `paragraph` blocks
     * — one per blank-line chunk, each capped at [BLOCK_CP].
     */
    fun proseBlocks(text: String): List<JsonObject> = splitFences(text)
        .flatMap { (isCode, language, chunk) -> blocksOf(isCode, language, chunk) }
        .ifEmpty { paragraphsOf(text) }

    private fun blocksOf(isCode: Boolean, language: String?, chunk: String): List<JsonObject> {
        val body = chunk.trim('\n')
        if (body.isBlank()) return emptyList()
        return if (isCode) {
            listOf(
                buildJsonObject {
                    put("type", "pre")
                    put("text", PyText.take(body, BLOCK_CP))
                    // kt → kotlin, sh → bash…: the phone only highlights a name it knows
                    CodeLang.normalize(language)?.let { put("language", it) }
                },
            )
        } else {
            textBlocks(body)
        }
    }

    /** Prose runs and tables, in order: the parser is [NuTable]'s, shared with the fleet's HTML. */
    private fun textBlocks(body: String): List<JsonObject> {
        val segments = NuTable.parse(body) ?: return paragraphsOf(body)
        return segments.flatMap { segment ->
            when (segment) {
                is NuTable.Segment.Prose -> paragraphsOf(segment.lines.joinToString("\n"))
                is NuTable.Segment.Table -> listOf(tableOrProse(segment))
            }
        }
    }

    /**
     * The native table block (`RichBlockTable`): cells need align + valign
     * and the header row carries `is_header`. A table over the API's 20
     * columns is NOT invented into a block (Telegram would reject it) — it
     * stays honest prose.
     */
    private fun tableOrProse(table: NuTable.Segment.Table): JsonObject {
        val columns = table.rows.firstOrNull()?.size ?: 0
        if (columns == 0 || columns > MAX_TABLE_COLUMNS) {
            val flat = table.rows.joinToString("\n") { row -> row.joinToString(" · ") }
            return paragraph(flat)
        }
        return buildJsonObject {
            put("type", "table")
            put("is_bordered", true)
            put("cells", buildJsonArray {
                table.rows.forEachIndexed { index, row ->
                    add(buildJsonArray {
                        row.forEach { cell ->
                            add(
                                buildJsonObject {
                                    put("align", "left")
                                    put("valign", "top")
                                    put("text", cell)
                                    if (index == 0) put("is_header", true)
                                },
                            )
                        }
                    })
                }
            })
        }
    }

    /**
     * THE THINKING (24/11, the owner; re-shaped 26/11 for legibility): the
     * block opens with a bold `📖 CoT N` line and the reasoning rides a
     * BLOCKQUOTE. The pink pullquote stayed the reasoning's identity for two
     * days, but 2K characters of large italic were hard to read on a phone —
     * the quote bar keeps the voice, the smaller upright text keeps the speed.
     */
    fun thinkingBlocks(text: String, number: Int? = null): List<JsonObject> = listOf(
        paragraph("**📖 CoT" + (number?.let { " $it" } ?: "") + "**"),
        buildJsonObject {
            put("type", "blockquote")
            put("text", inline(PyText.take(text, BLOCK_CP)))
        },
    )

    /** Blank-line-separated chunks as paragraph blocks, each inline-formatted and capped. */
    internal fun paragraphsOf(text: String): List<JsonObject> = text
        .split(PARAGRAPH_BREAK)
        .mapNotNull { chunk -> chunk.trim().takeIf { it.isNotEmpty() } }
        .map { paragraph(it) }
        .ifEmpty { listOf(paragraph(text)) }

    private fun paragraph(text: String): JsonObject = buildJsonObject {
        put("type", "paragraph")
        put("text", inline(PyText.take(text, BLOCK_CP)))
    }

    /** The pink quote: the thinking's own background. */
    private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")

    /**
     * INLINE MARKDOWN (24/11): block text is LITERAL RichText, so a model's
     * `` `code` `` and `**bold**` arrived as raw markers on the phone. This
     * tiny converter turns balanced single-backtick spans into `code` runs
     * and `**…**` into `bold` runs, and normalizes the line shapes a phone
     * reads at a glance: `- item` becomes `• item` and `## Title` becomes a
     * bold run. Everything else stays verbatim — a marker with no pair is
     * left as text (no invention).
     */
    internal fun inline(markdown: String): JsonElement {
        val shaped = shapeLines(markdown)
        val out = mutableListOf<JsonElement>()
        var last = 0
        var matched = false
        for (m in INLINE.findAll(shaped)) {
            matched = true
            if (m.range.first > last) out += JsonPrimitive(shaped.substring(last, m.range.first))
            val code = m.groupValues[1]
            out += buildJsonObject {
                if (code.isNotEmpty()) {
                    put("type", "code")
                    put("text", code)
                } else {
                    put("type", "bold")
                    put("text", m.groupValues[2])
                }
            }
            last = m.range.last + 1
        }
        if (!matched) return JsonPrimitive(shaped)
        if (last < shaped.length) out += JsonPrimitive(shaped.substring(last))
        return JsonArray(out)
    }

    /**
     * The SAME inline shaping as HTML (the classic lane's prose): escapes
     * everything, then emits `<code>`/`<b>` for the balanced pairs and `•` /
     * bold headings. A line break is the caller's (it decides `<br>`).
     */
    fun html(markdown: String): String {
        val shaped = shapeLines(markdown)
        val sb = StringBuilder()
        var last = 0
        for (m in INLINE.findAll(shaped)) {
            sb.append(PyText.esc(shaped.substring(last, m.range.first)))
            val code = m.groupValues[1]
            if (code.isNotEmpty()) {
                sb.append("<code>").append(PyText.esc(code)).append("</code>")
            } else {
                sb.append("<b>").append(PyText.esc(m.groupValues[2])).append("</b>")
            }
            last = m.range.last + 1
        }
        sb.append(PyText.esc(shaped.substring(last)))
        return sb.toString()
    }

    /** Bullets and headings as the phone reads them; everything else verbatim. */
    private fun shapeLines(markdown: String): String = markdown.lines().joinToString("\n") { line ->
        val trimmed = line.trimStart()
        when {
            HEADING.matches(trimmed) -> "**" + trimmed.dropWhile { it == '#' || it == ' ' } + "**"
            BULLET.matches(trimmed) -> "• " + trimmed.drop(1).trimStart()
            else -> line
        }
    }

    private val INLINE = Regex("`([^`\\n]+)`|\\*\\*([^*\\n]+)\\*\\*")
    private val HEADING = Regex("^#{1,6}\\s+\\S.*$")
    private val BULLET = Regex("^[-*]\\s+\\S.*$")

    /**
     * The fence split: (isCode, language, chunk) in order. A ``` line
     * toggles code mode; its language word (if any) rides the pre. An
     * unclosed fence ends the text as code (honest render, no invention).
     */
    internal fun splitFences(text: String): List<Triple<Boolean, String?, String>> {
        val out = mutableListOf<Triple<Boolean, String?, String>>()
        var inCode = false
        var language: String? = null
        val current = StringBuilder()
        for (line in PyText.splitLines(text)) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) {
                if (current.isNotBlank()) out += Triple(inCode, language.takeIf { inCode }, current.toString())
                current.clear()
                inCode = !inCode
                language = trimmed.removePrefix("```").trim().takeIf { it.isNotEmpty() }
            } else {
                current.append(line).append('\n')
            }
        }
        if (current.isNotBlank()) out += Triple(inCode, language.takeIf { inCode }, current.toString())
        return out
    }

    /** The violet block ceiling — a phone's screenful, well under any message cap. */
    const val BLOCK_CP = 3500

    /** The Bot API's table ceiling: a wider table degrades to prose, never to a rejected block. */
    const val MAX_TABLE_COLUMNS = 20
}
