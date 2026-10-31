package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.BotCommands
import dev.lattenero.bot.contracts.PyText
import dev.lattenero.bot.channel.ToolLive
import dev.lattenero.bot.daemon.spectator.ToolBox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * ATTACHMENTS IN's gate: the Bot API's own download law — the bot's token
 * cannot fetch a file bigger than 20 MB (Telegram answers with «file is
 * too big»). The refusal is SPOKEN, never silent. Absent size (Telegram
 * omits file_size on some photos) passes: the honest failure then happens
 * at the download itself, where the truth is.
 */
internal fun attachmentRefusal(sizeBytes: Long?): String? =
    if (sizeBytes != null && sizeBytes > MAX_ATTACHMENT_BYTES) {
        "⚠️ «file is too big» — Telegram no deja bajar más de ${MAX_ATTACHMENT_BYTES / MB} MB " +
            "con el token del bot (${sizeBytes / MB} MB los tuyos). Pasalo por la Mac de otro modo."
    } else {
        null
    }

private val FILENAME_SAFE = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('.', '_', '-')

/**
 * The wire's filename reduced to a safe on-disk name (no paths, no
 * separators) — a name with no safe character at all («///») is no
 * filename: the honest fallback.
 */
internal fun attachmentFilename(filename: String): String =
    filename.map { c -> if (c in FILENAME_SAFE) c else '_' }
        .joinToString("")
        .take(FILENAME_CAP)
        .takeIf { mapped -> mapped.any { it.isLetterOrDigit() } } // a name with no alphanumeric is garbage («///»)
        ?: "attachment"

private const val MB = 1024L * 1024L
private const val MAX_ATTACHMENT_BYTES = 20L * MB
private const val FILENAME_CAP = 120

internal fun stoppedCountOf(resultJson: String): Int = runCatching {
    ((Json.parseToJsonElement(resultJson) as? JsonObject)?.get("stopped") as? JsonArray)?.size ?: 0
}.getOrDefault(0)

/**
 * The transcript feed's per-tool CLOSE box (the old finalBoxesOf, ONE tool
 * at a time — the feed speaks each close as its own message): the
 * spectator's renderer with the status mark inside the head, plus the
 * tool's VERBATIM output as the tail (the fork's result-update content —
 * the box's own output/result keys are the spectator-era shape and the
 * tail only rides when the content came OUT of band, never doubled).
 */
internal fun feedBoxOf(tool: ToolLive): String {
    val mark = when (tool.status) {
        "failed" -> " ❌"
        "denied", "stopped" -> " ⏹️"
        else -> " ✅"
    }
    val input = tool.input
    val box = if (input == null) "" else ToolBox.box(tool.name, input).replaceFirst("</b>", "</b>$mark")
    val alreadyShown = input != null && listOf("output", "result").any { k ->
        (input[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.isNotBlank() == true
    }
    val out = tool.output
    val tail = if (out.isNullOrBlank() || alreadyShown) {
        ""
    } else {
        "\n↳ <pre>${PyText.esc(PyText.htmlClip(out, OUTPUT_TAIL_CP))}</pre>"
    }
    return box + tail
}

/** The output tail's cap — the box's own output budget (ToolBox.OUTPUT_CAP's sense). */
private const val OUTPUT_TAIL_CP = 800

/** taskSnapshot → the last ASSISTANT message (the turn's answer, from the store). */
internal fun snapshotAnswerOf(resultJson: String): String? = BotCommands.snapshotLastAnswer(resultJson)
