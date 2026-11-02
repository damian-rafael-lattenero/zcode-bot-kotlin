package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * THE QUESTION, end to end (the owner's phone answers everything — 30/10):
 * the stream carries `permission_request` / `elicitation_request` events;
 * the card renders «🔐/❓ esperando» and the turn hangs forever. This file
 * closes the loop:
 *
 * 1. [parseQuestionEvent] reads the REAL event (the permission options ride
 *    with their `response` objects; the AskUserQuestion options ride the
 *    event; plan approval = schema.interaction "plan_approval") — pure.
 * 2. [QuestionBoard.onStreamEvent] registers the question under a SHORT
 *    generated id (the 64-byte callback_data law: the requestId can be
 *    arbitrarily long — the board keys the taps, like the queue's pattern),
 *    renders the DEDICATED MESSAGE (never the card, NEVER gated by
 *    verbosity — a question must reach the owner even in summary mode) and
 *    sends it through the injected seam, remembering the message id.
 * 3. The tap (`qa:<sid>:<i>`) answers the CURRENT question: a single-question
 *    form flies the fork's `respond` frame to the SAME service API the desktop
 *    calls (the ACK edits the message honestly; a double tap or a stale
 *    question fails with the fork's own error — never a silent tap). A
 *    MULTI-question form (the fork's `questions[]`, `questionCount > 1`)
 *    advances IN PLACE: the answer is recorded, the SAME Telegram message is
 *    edited with the next question («pregunta 2 de N»), and only after the
 *    LAST answer does ONE `respond` frame fly carrying every answer in the
 *    fork's exact shape ([elicitationContentAll]).
 *
 * The free-text answer (`qf:<sid>` + [elicitationContent]) reuses the
 * ArmedOrder's one-shot mechanics: the next text IS the answer (of the
 * question on screen).
 *
 * The fork's multi-answer contract (verified in the fork's own sources,
 * packages/services/src/bots/botsService.ts `buildBotElicitationContent`):
 * `content.answers` keyed by each QUESTION's text (missing key = skipped,
 * never an empty string), `content.answer_N` per answered index, and the
 * singular `content.answer` ONLY for a one-question form. The daemon host
 * passes the content verbatim to the CLI's interaction broker, which reads
 * `answers[qText] ?? answer_i ?? answer` per question (blank = skipped).
 */

/** One answer choice. Permission options carry the event's own `response` — echoed back verbatim. */
data class QuestionOption(
    /** permission: the event's optionId; elicitation: the option's `value`. */
    val optionId: String,
    val label: String,
    /** permission only: the option's response object ({decision, …}) — the client's echo. */
    val response: JsonObject? = null,
    /** The ❌ mark: the fork's deny option or a deny decision. */
    val isDeny: Boolean = false,
)

/**
 * ONE question of the questionnaire (the fork's ZCodeElicitationQuestion):
 * the text is the `answers` map's key on the wire, the options are the
 * question's OWN choices (an empty list = the ✍️ free-text arm only).
 */
data class QuestionItem(
    val header: String,
    val text: String,
    val options: List<QuestionOption>,
)

/**
 * One pending question parsed from the stream. [kind] is the fork's respond
 * kind: "permission" | "elicitation" | "plan" (ExitPlanMode — the approval
 * rides the elicitation door). [mid] is the Telegram message that carries
 * the buttons (0 = not sent / send seam declined).
 *
 * The multi-question state rides IMMUTABLY (FP-first, §7): [questions] is
 * the FULL questionnaire (the fork's `questions[]` — empty = the plain
 * single shape), [questionIndex] the question on screen, [answers] the
 * collected answers so far (index → value). [header]/[title]/[options] are
 * ALWAYS the current question's data — advancing is a `copy` that moves all
 * three, so every renderer and tap stays on the question at hand.
 */
data class PendingQuestion(
    val taskId: String,
    val requestId: String,
    val kind: String,
    /** The message's head — «🔐 Permiso» / the AskUserQuestion header / the plan's title. */
    val header: String,
    /** The question's body text (the description / the question itself). */
    val title: String,
    val options: List<QuestionOption>,
    /** plan approval's plan text, clipped at render. */
    val plan: String? = null,
    /** AskUserQuestion's multi-question count (1 = the plain shape). */
    val questionCount: Int = 1,
    val mid: Long = 0,
    /** The FULL questionnaire — empty for permission/plan and the old single shape. */
    val questions: List<QuestionItem> = emptyList(),
    /** The question currently on screen (0-based). */
    val questionIndex: Int = 0,
    /** The answers collected so far: question index → the answer's value. */
    val answers: Map<Int, String> = emptyMap(),
)

/**
 * Pure: the stream event → the question, or null (not a question / no
 * requestId — the additive rule). Reads the shapes the fork itself emits:
 * ZCodePermissionRequest (options with optionId+name+response),
 * ZCodeElicitationRequest (questions[] or message+options, value+label),
 * plan approval by schema.interaction.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount") // one branch per wire shape — the parser's own map
internal fun parseQuestionEvent(event: JsonObject): PendingQuestion? {
    val type = event.str("type") ?: return null
    val requestId = event.str("requestId")?.takeIf { it.isNotEmpty() } ?: return null
    val taskId = event.str("taskId") ?: return null
    return when (type) {
        "permission_request" -> {
            val raw = event["options"] as? JsonArray
            val options = raw.orEmpty()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { option ->
                    val id = option.str("optionId")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val response = option.obj("response")
                    QuestionOption(
                        optionId = id,
                        label = option.str("name")?.takeIf { it.isNotBlank() } ?: id,
                        response = response,
                        isDeny = response?.str("decision") == "deny" || id == "deny",
                    )
                }
                .take(QuestionBoard.QUESTION_OPTIONS_CAP)
            // an event with no options: the honest synthetic pair (the same
            // allow/deny the fork's own deny command answers with)
            val opts = options.ifEmpty {
                listOf(
                    QuestionOption("allow", "Permitir", response = buildJsonObject { put("decision", "allow") }),
                    QuestionOption(
                        "deny",
                        "Denegar",
                        response = buildJsonObject { put("decision", "deny") },
                        isDeny = true,
                    ),
                )
            }
            PendingQuestion(
                taskId = taskId,
                requestId = requestId,
                kind = "permission",
                header = "🔐 Permiso solicitado",
                title = event.str("description")?.takeIf { it.isNotBlank() }
                    ?: event.str("title")?.takeIf { it.isNotBlank() }
                    ?: "El agente pide permiso",
                options = opts,
            )
        }

        "elicitation_request" -> {
            val schema = event.obj("schema")
            if (schema?.str("interaction") == "plan_approval") {
                PendingQuestion(
                    taskId = taskId,
                    requestId = requestId,
                    kind = "plan",
                    header = "📋 Plan por aprobar",
                    title = event.str("message")?.takeIf { it.isNotBlank() } ?: "¿Apruebo este plan?",
                    options = listOf(
                        QuestionOption("approve", "✅ Aprobar el plan"),
                        QuestionOption("decline", "❌ Rechazar"),
                    ),
                    plan = schema.str("plan"),
                )
            } else {
                val questions = event["questions"] as? JsonArray
                val eventHeader = event.str("header")?.takeIf { it.isNotBlank() } ?: "❓ Pregunta"
                val eventOptions = event["options"] as? JsonArray
                // The FULL questionnaire, index-aligned with the fork's wire
                // (`answer_N` answers question N — never drop an item, a
                // blank text only renders empty). The first item falls back
                // to the event's own header/options — the old single shape.
                val items = questions.orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .mapIndexed { i, q ->
                        QuestionItem(
                            header = q.str("header")?.takeIf { it.isNotBlank() } ?: eventHeader,
                            text = q.str("question")?.takeIf { it.isNotBlank() } ?: "",
                            options = elicitationOptions(
                                q["options"] as? JsonArray ?: eventOptions.takeIf { i == 0 },
                            ),
                        )
                    }
                    .ifEmpty {
                        // No questions[] at all: the event's own message+options ARE the question.
                        listOf(
                            QuestionItem(
                                header = eventHeader,
                                text = event.str("message")?.takeIf { it.isNotBlank() } ?: "",
                                options = elicitationOptions(eventOptions),
                            ),
                        )
                    }
                val title = items.first().text.takeIf { it.isNotBlank() }
                    ?: return null
                PendingQuestion(
                    taskId = taskId,
                    requestId = requestId,
                    kind = "elicitation",
                    header = items.first().header,
                    title = title,
                    options = items.first().options,
                    questionCount = items.size,
                    questions = items,
                )
            }
        }

        else -> null
    }
}

/** The elicitation's option rows (`value`+`label`), capped like every keyboard. */
private fun elicitationOptions(raw: JsonArray?): List<QuestionOption> = raw.orEmpty()
    .mapNotNull { it as? JsonObject }
    .mapNotNull { option ->
        val value = option.str("value")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        QuestionOption(
            optionId = value,
            label = option.str("label")?.takeIf { it.isNotBlank() } ?: value,
        )
    }
    .take(QuestionBoard.QUESTION_OPTIONS_CAP)

/**
 * The question message's TEXT — the dedicated message (never the card):
 * the head, the body, the plan's verbatim clip. User-facing Spanish. A
 * multi-question form says WHICH question is on screen («pregunta N de M»).
 */
internal fun questionText(q: PendingQuestion): String = buildString {
    append("<b>").append(PyText.esc(q.header)).append("</b>")
    append("\n").append(PyText.esc(q.title))
    if (q.questionCount > 1) {
        append("\n<i>pregunta ").append(q.questionIndex + 1).append(" de ").append(q.questionCount).append("</i>")
    }
    q.plan?.takeIf { it.isNotBlank() }?.let { plan ->
        append("\n\n<blockquote>").append(PyText.esc(PyText.take(plan, QuestionBoard.QUESTION_PLAN_CP)))
        if (PyText.cpLength(plan) > QuestionBoard.QUESTION_PLAN_CP) append(" …")
        append("</blockquote>")
    }
}

/**
 * The tap's keyboard: one button per option (`qa:<sid>:<i>` — the short id
 * keeps the 64-byte law asleep), permission rows pre-marked ✅/❌, and the
 * elicitation's «✍️ Responder» one-shot arm (`qf:<sid>`).
 */
fun questionKeyboard(shortId: String, q: PendingQuestion): JsonObject = buildJsonObject {
    put("inline_keyboard", buildQuestionRows(shortId, q))
}

private fun buildQuestionRows(shortId: String, q: PendingQuestion): JsonArray {
    val rows = buildJsonArray {
        q.options.forEachIndexed { i, option ->
            val mark = when {
                q.kind != "permission" -> ""
                option.isDeny -> "❌ "
                else -> "✅ "
            }
            add(
                buildJsonArray {
                    add(
                        Keyboards.button(
                            "$mark${option.label.take(QuestionBoard.QUESTION_LABEL_CAP)}",
                            "qa:$shortId:$i",
                        ),
                    )
                },
            )
        }
        if (q.kind == "elicitation") {
            add(buildJsonArray { add(Keyboards.button("✍️ Responder", "qf:$shortId")) })
        }
    }
    return rows
}

/**
 * The elicitation answer's CONTENT — the fork's own buildBotElicitationContent
 * shape (packages/services/src/bots/botsService.ts:4439): `answers` keyed by
 * each QUESTION's text (missing key = skipped, never an empty string),
 * `answer_N` per answered index, and `answer` only when the whole
 * questionnaire is one question. [entries] is the questionnaire in order:
 * (question text, answer) pairs.
 */
fun elicitationContentAll(entries: List<Pair<String, String>>, questionCount: Int): JsonObject =
    buildJsonObject {
        putJsonObject("answers") { entries.forEach { (text, value) -> put(text, value) } }
        entries.forEachIndexed { index, (_, value) -> put("answer_$index", value) }
        if (questionCount <= 1) entries.firstOrNull()?.let { (_, value) -> put("answer", value) }
    }

/**
 * The SINGLE answered question's content — the one-question case of
 * [elicitationContentAll] (byte-identical to the shape the board has always
 * flown: `answers` + `answer_0` + `answer`).
 */
fun elicitationContent(questionText: String, value: String, questionCount: Int = 1): JsonObject =
    elicitationContentAll(listOf(questionText to value), questionCount)

/**
 * The questionnaire's (question text, answer) pairs IN ORDER — the
 * [elicitationContentAll] input for a completed form. Unanswered questions
 * carry a blank value (the fork's broker drops blanks = skipped).
 */
fun PendingQuestion.answerEntries(): List<Pair<String, String>> =
    if (questions.isEmpty()) {
        listOf(title to answers[0].orEmpty())
    } else {
        questions.mapIndexed { index, item -> item.text to answers[index].orEmpty() }
    }

/**
 * One recorded answer's step (the multi-question round-trip's algebra):
 * [Advanced] — the answer was stored, the SAME message now shows the NEXT
 * question (the edit already flew; no respond frame yet); [Completed] — the
 * LAST question was answered and the caller flies the ONE `respond` frame
 * with [Completed.question] carrying every answer ([PendingQuestion.answers]).
 */
sealed class QuestionFlow {
    data class Advanced(val question: PendingQuestion) : QuestionFlow()
    data class Completed(val question: PendingQuestion) : QuestionFlow()
}

/**
 * The pending questions' registry — the short-id map (the queue's pattern:
 * a short generated key stands in for an unbounded wire id) + the two
 * effects (send the dedicated message, edit it with the outcome), both
 * injected so the suite pins the flow without a socket.
 */
class QuestionBoard(
    /** The dedicated question send — answers the message id (null = not sent). */
    private val sendQuestion: suspend (text: String, markup: JsonObject) -> Long? = { _, _ -> null },
    /** The honest seal's edit: the outcome replaces the question, the buttons die. */
    private val editQuestion: suspend (mid: Long, text: String, markup: JsonObject?) -> Unit = { _, _, _ -> },
) {

    private val lock = Any()
    private val byShortId = LinkedHashMap<String, PendingQuestion>()
    private val shortIdByRequestId = HashMap<String, String>()
    private val counter = AtomicInteger(0)

    /**
     * The stream's entry: parse, dedupe by requestId (the fork's recovery
     * re-emits — a duplicate question must NOT re-message the owner),
     * register under a fresh short id, and send the dedicated message. The
     * send seam answers the message id → remembered for the resolve edit.
     * Returns the question when a NEW one went out (null = not a question
     * or already pending).
     */
    @Suppress("ReturnCount") // the dedupe's guards ARE the law
    suspend fun onStreamEvent(frame: ServerFrame.StreamEvent): PendingQuestion? {
        val parsed = parseQuestionEvent(frame.event) ?: return null
        val shortId = synchronized(lock) {
            shortIdByRequestId[parsed.requestId]?.let { return null }
            val sid = "q${counter.incrementAndGet()}"
            shortIdByRequestId[parsed.requestId] = sid
            byShortId[sid] = parsed
            while (byShortId.size > REGISTRY_CAP) {
                val eldest = byShortId.keys.first()
                val dropped = byShortId.remove(eldest)
                dropped?.let { shortIdByRequestId.remove(it.requestId) }
            }
            sid
        }
        val mid = sendQuestion(questionText(parsed), questionKeyboard(shortId, parsed))
        if (mid != null && mid > 0) {
            synchronized(lock) { byShortId[shortId]?.let { byShortId[shortId] = it.copy(mid = mid) } }
        }
        return parsed
    }

    /** The tap's lookup — null after a resolve (a second tap fails honestly). */
    fun byShortId(shortId: String): PendingQuestion? = synchronized(lock) { byShortId[shortId] }

    /**
     * One recorded answer (a `qa:` tap's option value or the ✍️ arm's free
     * text — the caller routed [shortId] to an elicitation). The pure state
     * machine under the lock: the answer joins [PendingQuestion.answers];
     * with a next question the ADVANCED copy is stored and the SAME message
     * is edited in place with the next question's keyboard (the rate law:
     * the edit only flies because the state changed — never a second
     * message); without one the COMPLETED copy rides OUT (never stored —
     * the frame's flight owns it, an Unavailable retry re-answers the same
     * last question honestly). Null = the question is gone (stale/double
     * tap) or not an elicitation — the caller's honest failure.
     */
    @Suppress("ReturnCount") // the guards ARE the law (the same honest shapes as onStreamEvent's dedupe)
    suspend fun answer(shortId: String, value: String): QuestionFlow? {
        val step = synchronized(lock) {
            val current = byShortId[shortId] ?: return null
            if (current.kind != "elicitation") return null
            val answers = current.answers + (current.questionIndex to value)
            val next = current.questions.getOrNull(current.questionIndex + 1)
            if (next == null) {
                AnswerStep.Flies(current.copy(answers = answers))
            } else {
                val advanced = current.copy(
                    questionIndex = current.questionIndex + 1,
                    answers = answers,
                    header = next.header,
                    title = next.text,
                    options = next.options,
                )
                byShortId[shortId] = advanced
                AnswerStep.Stored(advanced)
            }
        }
        return when (step) {
            is AnswerStep.Stored -> {
                if (step.question.mid > 0) {
                    val onScreen = step.question
                    editQuestion(onScreen.mid, questionText(onScreen), questionKeyboard(shortId, onScreen))
                }
                QuestionFlow.Advanced(step.question)
            }

            is AnswerStep.Flies -> QuestionFlow.Completed(step.question)
        }
    }

    /** The answer's under-lock decision, effects applied outside the monitor. */
    private sealed class AnswerStep {
        data class Stored(val question: PendingQuestion) : AnswerStep()
        data class Flies(val question: PendingQuestion) : AnswerStep()
    }

    /** The honest seal: edit the question message with the outcome, drop the buttons, drop the entry. */
    suspend fun resolve(shortId: String, outcome: String) {
        val question = synchronized(lock) { byShortId.remove(shortId) } ?: return
        synchronized(lock) { shortIdByRequestId.remove(question.requestId) }
        if (question.mid > 0) editQuestion(question.mid, outcome, null)
    }

    /** The response event's guard: answered from the app → seal the phone's message too. */
    suspend fun resolveByRequestId(requestId: String, outcome: String) {
        val shortId = synchronized(lock) { shortIdByRequestId[requestId] } ?: return
        resolve(shortId, outcome)
    }

    /** The stale guard: the turn sealed/died → every pending question of that task is honestly dead. */
    suspend fun resolveTask(taskId: String, outcome: String) {
        val ids = synchronized(lock) { byShortId.filterValues { it.taskId == taskId }.keys.toList() }
        ids.forEach { resolve(it, outcome) }
    }

    companion object {
        /** The registry's cap — the oldest question evicts (the stream's replay cannot grow it unbounded). */
        const val REGISTRY_CAP = 32

        /** The buttons' cap per question (Telegram's keyboard sanity; ✍️ always remains). */
        const val QUESTION_OPTIONS_CAP = 8

        /** The plan's verbatim clip in the question message (code points). */
        const val QUESTION_PLAN_CP = 1600

        /** An option's label cap on the button (Telegram's row sanity). */
        const val QUESTION_LABEL_CAP = 30
    }
}
