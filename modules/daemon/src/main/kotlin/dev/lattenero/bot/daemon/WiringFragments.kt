package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.BotCommands
import dev.lattenero.bot.channel.ChannelQuery
import dev.lattenero.bot.channel.ModeMenu
import dev.lattenero.bot.channel.QueryAnswer
import dev.lattenero.bot.contracts.PyText
import dev.lattenero.bot.channel.ToolLive
import dev.lattenero.bot.channel.ToolOutputPages
import dev.lattenero.bot.daemon.spectator.ToolBox
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.sql.DriverManager
import java.util.Properties

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
 * U9/F1's owner-facing sentence: the poller consumed a backlog it never
 * dispatched because the cursor was absent/corrupt. The number is what was
 * dropped; the reason is why dropping beat replaying; the last line is the
 * remedy the owner actually has.
 */
internal fun cursorLostText(skipped: Int): String =
    "🕳️ Se perdió el cursor de Telegram (archivo ausente o ilegible): descarté $skipped update(s) " +
        "viejos SIN re-ejecutarlos — un replay volvería a correr /nuclear o /deploy de antes. " +
        "Si mandaste algo hace un rato y no hubo respuesta, mandalo de nuevo."

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
        // the tail's lines carry their REAL numbers (the owner, 3/11) —
        // numbering happens BEFORE the cap, so a clipped tail still says
        // which lines it is showing
        val numbered = ToolOutputPages.pages(out, pageCp = OUTPUT_TAIL_CP).first()
        "\n↳ <pre>${PyText.esc(numbered)}</pre>"
    }
    return box + tail
}

/** The output tail's cap — the box's own output budget (ToolBox.OUTPUT_CAP's sense). */
private const val OUTPUT_TAIL_CP = 800

/** taskSnapshot → the last ASSISTANT message (the turn's answer, from the store). */
internal fun snapshotAnswerOf(resultJson: String): String? = BotCommands.snapshotLastAnswer(resultJson)

/** The final-answer window: two bounded retries after the first miss (the CLI's persist race, a reconnect window). */
internal const val FINAL_ANSWER_ATTEMPTS = 3
internal const val FINAL_ANSWER_RETRY_MS = 600L

/**
 * taskSnapshot → the turn's answer, asked with the task's OWN workspace and
 * a ONE-MESSAGE limit.
 *
 * Measured 2026-11-03, three bugs deep:
 * 1. both consumers (the live view's seal and the bus producer's
 *    turn.completed) queried TaskSnapshot WITHOUT a workspacePath, and the
 *    fork throws "workspacePath is required when the daemon bot has no
 *    active context" — the QueryAnswer came back non-Ok, the answer
 *    silently became null, and every seal logged `answer=0` for hours.
 * 2. with the workspace fixed the fork still answered
 *    `Failed(code=timeout, message=no answer in 15s)`: this conversation
 *    is gigabytes long, and a limit-less snapshot projects EVERY message
 *    of it. The fork's own contextWindow path uses `messageLimit: 1` with
 *    the comment "1 mensaje mantiene el paquete liviano" — and the fork's
 *    limit keeps the TAIL (`messages.slice(-limit)`), exactly the last
 *    assistant message this resolver reads. The parser only ever needed
 *    one message; asking for all of them was the timeout.
 * 3. the loud warn on EVERY non-Ok is what exposed both — silence here is
 *    what hid the first bug for hours.
 */
internal suspend fun finalAnswerOf(
    taskId: String,
    query: suspend (ChannelQuery) -> QueryAnswer,
    workspaceOf: suspend (String) -> String?,
    delayMs: suspend (Long) -> Unit = { delay(it) },
    log: (String) -> Unit,
): String? {
    val ws = runCatching { workspaceOf(taskId) }.getOrNull()
    repeat(FINAL_ANSWER_ATTEMPTS) { attempt ->
        val attemptNo = attempt + 1
        when (val answer = query(ChannelQuery.TaskSnapshot(taskId, ws, messageLimit = 1))) {
            is QueryAnswer.Ok -> {
                val text = snapshotAnswerOf(answer.resultJson)
                if (text != null) return text
                log(
                    "taskSnapshot($taskId, ws=${ws != null}) has no assistant message yet — " +
                        "the CLI has not persisted the answer (attempt $attemptNo/$FINAL_ANSWER_ATTEMPTS)",
                )
            }
            else -> log(
                "taskSnapshot($taskId, ws=${ws != null}) answered $answer — " +
                    "the seal's answer stays empty (attempt $attemptNo/$FINAL_ANSWER_ATTEMPTS)",
            )
        }
        if (attemptNo < FINAL_ANSWER_ATTEMPTS) delayMs(FINAL_ANSWER_RETRY_MS)
    }
    return null
}

/**
 * THE DEFAULT-MODE ENFORCEMENT (the owner's law, 4/11: «por defecto
 * siempre Full access»): on `task_run_started` for a watched task, read
 * the task's snapshot `meta.mode` (the SAME one-message query the /mode
 * card uses) — absent → fire `SetMode(taskId, "yolo")` ONCE per task.
 * The [guard] set is the wiring's in-memory one: it dies with the
 * process, so the fork's own truth is RE-CHECKED after every restart —
 * an owner-chosen mode IS in meta.mode and is never stomped. A failed
 * write frees the guard (the next turn retries); a failed READ never
 * latches (honest best-effort, nothing fired on a dead channel). The
 * honest caveat (the log + the /mode card's copy): the very first turn
 * of a brand-new conversation runs the app's own default — yolo lands
 * from the next turn.
 */
internal suspend fun enforceDefaultModeOnRunStart(
    taskId: String,
    guard: MutableSet<String>,
    query: suspend (ChannelQuery) -> QueryAnswer,
    workspaceOf: suspend (String) -> String?,
    log: (String) -> Unit,
) {
    val ws = runCatching { workspaceOf(taskId) }.getOrNull()
    val snapshot = query(ChannelQuery.TaskSnapshot(taskId, ws, messageLimit = 1))
    val current = (snapshot as? QueryAnswer.Ok)?.let { ModeMenu.currentFrom(it.resultJson) }
    if (snapshot !is QueryAnswer.Ok || current != null) {
        log(
            "default-mode: ...${taskId.takeLast(MODE_LOG_TAIL)} carries meta.mode=$current " +
                "(read ${if (snapshot is QueryAnswer.Ok) "ok" else "FAILED"}) — nothing enforced " +
                "(an owner-chosen mode is never stomped)",
        )
        return
    }
    if (!guard.add(taskId)) return // once per task, per process
    log(
        "default-mode: ...${taskId.takeLast(MODE_LOG_TAIL)} has NO meta.mode — firing SetMode(yolo) " +
            "(the DEFAULT is Full access; the very first turn ran the app's own default, yolo lands next turn)",
    )
    when (query(ChannelQuery.SetMode(taskId, ModeMenu.DEFAULT_MODE_ID))) {
        is QueryAnswer.Ok -> Unit
        else -> {
            guard.remove(taskId) // the write never landed: the next run_started retries
            log("default-mode: SetMode(yolo) FAILED for ...${taskId.takeLast(MODE_LOG_TAIL)} — the next turn retries")
        }
    }
}

/** The enforcement's log tail (the full sid is noise in the daemon log). */
private const val MODE_LOG_TAIL = 8

/**
 * The index's own workspace for a task — the same read-only, short-lived
 * xerial pattern as every other read here (mode=ro, zero writes).
 */
internal fun workspacePathOf(baseDir: File, sid: String): String? {
    val index = File(baseDir, "v2").resolve("tasks-index.sqlite")
    if (!index.exists()) return null
    return runCatching {
        DriverManager.getConnection("jdbc:sqlite:${index.absolutePath}", indexRoProps()).use { conn ->
            conn.prepareStatement("SELECT workspace_path FROM tasks WHERE task_id=?").use { ps ->
                ps.setString(1, sid)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1)?.takeIf { it.isNotBlank() } else null }
            }
        }
    }.getOrNull()
}

private fun indexRoProps(): Properties = Properties().apply { put("open_mode", "1") } // SQLITE_READONLY
