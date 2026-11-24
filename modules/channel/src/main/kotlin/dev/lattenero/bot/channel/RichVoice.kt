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
 * THE VOICE OF THE LLM (the owner, 5/11: «yo se que el texto que está en
 * ese fondo pertenece a ese canal y es el llm hablando»): every segment of
 * the model's prose — the mid-turn talk, the final summary, the thinking —
 * travels as a Bot API 10.x RICH MESSAGE, its quote styling the violet
 * channel's look. Pure: text in → wire JSON out; the send leg lives in
 * [LiveTelegram.sendRich].
 *
 * The spec's laws honored here (core.telegram.org/bots/api 10.3):
 * - InputRichMessage takes exactly ONE of html/markdown/blocks — this
 *   builder speaks BLOCKS (the quote identity is not negotiable).
 * - Block text is LITERAL RichText: no HTML escaping, ever (the spec's
 *   own warning) — the escaper's law does not apply to rich text.
 * - Prose rides `pullquote` (the styled quote = the violet), code rides
 *   `pre` with its fence language, thinking rides `expandable_blockquote`
 *   (10.3: collapses/expands NATIVELY — no custom toggle button).
 */
object RichVoice {

    /** One violet message's wire params (sendRichMessage) — silent, like the card. */
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
     * phone shows real columns), everything else becomes `pullquote` blocks
     * — the violet. One block per shape; each capped at [BLOCK_CP].
     */
    fun proseBlocks(text: String): List<JsonObject> = splitFences(text)
        .flatMap { (isCode, language, chunk) -> blocksOf(isCode, language, chunk) }
        .ifEmpty { listOf(pullquote(PyText.take(text, BLOCK_CP))) }

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
        val segments = NuTable.parse(body) ?: return listOf(pullquote(PyText.take(body, BLOCK_CP)))
        return segments.mapNotNull { segment ->
            when (segment) {
                is NuTable.Segment.Prose -> segment.lines.joinToString("\n")
                    .takeIf { it.isNotBlank() }
                    ?.let { pullquote(PyText.take(it, BLOCK_CP)) }

                is NuTable.Segment.Table -> tableOrProse(segment)
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
            return pullquote(PyText.take(flat, BLOCK_CP))
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

    /** The thinking block: native expand/collapse, the whole text behind one tap. */
    fun thinkingBlock(text: String): JsonObject = buildJsonObject {
        put("type", "expandable_blockquote")
        put("text", inline(PyText.take(text, BLOCK_CP)))
    }

    private fun pullquote(text: String): JsonObject = buildJsonObject {
        put("type", "pullquote")
        put("text", inline(text))
    }

    /**
     * INLINE MARKDOWN (24/11): block text is LITERAL RichText, so a model's
     * `` `code` `` and `**bold**` arrived as raw markers on the phone. This
     * tiny converter turns balanced single-backtick spans into `code` runs
     * and `**…**` into `bold` runs — everything else stays verbatim.
     * Conservative by design: a marker with no pair is left as text.
     */
    internal fun inline(markdown: String): JsonElement {
        val out = mutableListOf<JsonElement>()
        var last = 0
        var matched = false
        for (m in INLINE.findAll(markdown)) {
            matched = true
            if (m.range.first > last) out += JsonPrimitive(markdown.substring(last, m.range.first))
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
        if (!matched) return JsonPrimitive(markdown)
        if (last < markdown.length) out += JsonPrimitive(markdown.substring(last))
        return JsonArray(out)
    }

    private val INLINE = Regex("`([^`\\n]+)`|\\*\\*([^*\\n]+)\\*\\*")

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
