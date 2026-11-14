package dev.lattenero.bot.channel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

/**
 * THE BOARD'S PERSISTENCE SEAM (measured 4/11: a daemon restart left every
 * old card armed-but-dead AND made the fork's re-announced question render
 * as a NEW card — the old card's `qa:q1:*` taps then misrouted onto the NEW
 * q1). The registry's essential state (shortId → the FULL question, its
 * Telegram message id included) survives the restart, so the old card stays
 * the live one and the re-announcement dedupes into it. The seam is INJECTED
 * like send/edit: the interface lives here, PURE — the File implementation
 * lives in the daemon (the wiring owns the path). An unreadable store
 * degrades to an empty board (the SendKnob law), never a crash.
 */
interface QuestionBoardStore {
    /** The stored registry (shortId → question), insertion order kept — empty when absent/unreadable. */
    fun load(): Map<String, PendingQuestion>

    /** The FULL snapshot write — the impl owns atomicity and its own failure (degrade, never throw). */
    fun save(entries: Map<String, PendingQuestion>)
}

/**
 * The persisted entry's wire shape: the shortId plus EVERY field — a tap
 * after the restart flies the REAL respond frame (kind + taskId + requestId
 * + the options' response echoes), so nothing may be left behind. Pure.
 */
fun PendingQuestion.storeEntry(shortId: String): JsonObject = buildJsonObject {
    put("shortId", JsonPrimitive(shortId))
    put("taskId", JsonPrimitive(taskId))
    put("requestId", JsonPrimitive(requestId))
    put("kind", JsonPrimitive(kind))
    put("header", JsonPrimitive(header))
    put("title", JsonPrimitive(title))
    putJsonArray("options") { options.forEach { add(it.storeJson()) } }
    plan?.let { put("plan", JsonPrimitive(it)) }
    taskTitle?.let { put("taskTitle", JsonPrimitive(it)) }
    originLabel?.let { put("originLabel", JsonPrimitive(it)) }
    put("mid", JsonPrimitive(mid))
    put("questionCount", JsonPrimitive(questionCount))
    putJsonArray("questions") { questions.forEach { add(it.storeJson()) } }
    put("questionIndex", JsonPrimitive(questionIndex))
    putJsonObject("answers") {
        answers.forEach { (index, value) -> put(index.toString(), JsonPrimitive(value)) }
    }
}

/**
 * The stored entry → the question, or null (torn: the respond frame's spine
 * — requestId/taskId/kind — is missing; a half entry would fly a garbage
 * frame). Everything else defaults (the queue's own read law). Pure.
 */
@Suppress("ReturnCount") // the torn-entry guards ARE the law (the parser's own shapes)
fun pendingQuestionOf(entry: JsonObject): PendingQuestion? {
    val requestId = entry.str("requestId")?.takeIf { it.isNotEmpty() } ?: return null
    val taskId = entry.str("taskId")?.takeIf { it.isNotEmpty() } ?: return null
    val kind = entry.str("kind")?.takeIf { it.isNotEmpty() } ?: return null
    return PendingQuestion(
        taskId = taskId,
        requestId = requestId,
        kind = kind,
        header = entry.str("header").orEmpty(),
        title = entry.str("title").orEmpty(),
        options = entry.array("options").mapNotNull { it as? JsonObject }.mapNotNull { optionOf(it) },
        plan = entry.str("plan"),
        mid = entry.long("mid") ?: 0L,
        questionCount = entry.int("questionCount") ?: 1,
        questions = entry.array("questions").mapNotNull { it as? JsonObject }.map { itemOf(it) },
        questionIndex = entry.int("questionIndex") ?: 0,
        answers = (entry["answers"] as? JsonObject).orEmpty()
            .mapNotNull { (index, value) ->
                index.toIntOrNull()?.let { it to (value as? JsonPrimitive)?.content.orEmpty() }
            }.toMap(),
        taskTitle = entry.str("taskTitle"),
        originLabel = entry.str("originLabel"),
    )
}

private fun QuestionOption.storeJson(): JsonObject = buildJsonObject {
    put("optionId", JsonPrimitive(optionId))
    put("label", JsonPrimitive(label))
    response?.let { put("response", it) }
    if (isDeny) put("isDeny", JsonPrimitive(true))
}

private fun QuestionItem.storeJson(): JsonObject = buildJsonObject {
    put("header", JsonPrimitive(header))
    put("text", JsonPrimitive(text))
    putJsonArray("options") { options.forEach { add(it.storeJson()) } }
}

private fun optionOf(option: JsonObject): QuestionOption? {
    val id = option.str("optionId")?.takeIf { it.isNotEmpty() } ?: return null
    return QuestionOption(
        optionId = id,
        label = option.str("label")?.takeIf { it.isNotBlank() } ?: id,
        response = option["response"] as? JsonObject,
        isDeny = option.bool("isDeny") ?: false,
    )
}

private fun itemOf(item: JsonObject): QuestionItem = QuestionItem(
    header = item.str("header").orEmpty(),
    text = item.str("text").orEmpty(),
    options = item.array("options").mapNotNull { it as? JsonObject }.mapNotNull { optionOf(it) },
)

private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
