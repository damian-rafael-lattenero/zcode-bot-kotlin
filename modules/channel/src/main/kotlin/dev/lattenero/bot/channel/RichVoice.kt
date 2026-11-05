package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import dev.lattenero.bot.transport.TgConfig
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
     * language rides), everything else becomes `pullquote` blocks — the
     * violet. One block per fence boundary; each block capped at
     * [BLOCK_CP] (a phone's screen, under any per-message ceiling).
     */
    fun proseBlocks(text: String): List<JsonObject> = splitFences(text)
        .mapNotNull { (isCode, language, chunk) ->
            val body = chunk.trim('\n').takeIf { it.isNotBlank() } ?: return@mapNotNull null
            when {
                isCode -> buildJsonObject {
                    put("type", "pre")
                    put("text", body.take(BLOCK_CP))
                    language?.let { put("language", it) }
                }

                else -> buildJsonObject {
                    put("type", "pullquote")
                    put("text", body.take(BLOCK_CP))
                }
            }
        }
        .ifEmpty { listOf(pullquote(text.take(BLOCK_CP))) }

    /** The thinking block: native expand/collapse, the whole text behind one tap. */
    fun thinkingBlock(text: String): JsonObject = buildJsonObject {
        put("type", "expandable_blockquote")
        put("text", text.take(BLOCK_CP))
    }

    private fun pullquote(text: String): JsonObject = buildJsonObject {
        put("type", "pullquote")
        put("text", text)
    }

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
}
