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
 * The registry survives a restart ([QuestionBoardStore], injected — the
 * daemon owns the FILE, this module never touches java.io): the old cards
 * keep working and the fork's re-announcements dedupe into them — never a
 * dead card, never a misrouted tap.
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
    /**
     * THE TASK'S OWN TITLE (14/11, the any-task law): a question may arrive
     * from a conversation the owner is NOT reading — the card must say
     * which one it is. null = unknown (the stream event carries no title;
     * the watcher's index pass fills it).
     */
    val taskTitle: String? = null,
    /**
     * THE SUBAGENT'S ORIGIN (the fork's delegating broker: `origin.kind =
     * "subagent"` rewrites the child's blocking request onto the PARENT
     * session) — «agentType · description», so the owner knows the question
     * comes from a dispatch lane, not from the main agent.
     */
    val originLabel: String? = null,
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
    val origin = originLabelOf(event)
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
                originLabel = origin,
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
                    originLabel = origin,
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
                    originLabel = origin,
                )
            }
        }

        else -> null
    }
}

/**
 * The fork's `origin` when a SUBAGENT asked (the delegating broker's
 * metadata): «agentType · description» — a small, honest tag for the card.
 * Any other origin shape (absent, a future kind) = null (the additive law).
 */
private fun originLabelOf(event: JsonObject): String? {
    val origin = event.obj("origin")?.takeIf { it.str("kind") == "subagent" } ?: return null
    val type = origin.str("agentType")?.takeIf { it.isNotBlank() }
    val description = origin.str("description")?.takeIf { it.isNotBlank() }
    return listOfNotNull(type, description).joinToString(" · ").ifBlank { null }
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
 * the head, WHICH conversation (the task title) and WHICH subagent (the
 * origin tag) asked, the body, the plan's verbatim clip. User-facing
 * Spanish. A multi-question form says WHICH question is on screen
 * («pregunta N de M»).
 */
internal fun questionText(q: PendingQuestion): String = buildString {
    append("<b>").append(PyText.esc(q.header)).append("</b>")
    q.taskTitle?.takeIf { it.isNotBlank() }?.let { title ->
        append("\n<i>📋 ").append(PyText.esc(PyText.take(title, QuestionBoard.QUESTION_TASK_CP))).append("</i>")
    }
    q.originLabel?.takeIf { it.isNotBlank() }?.let { label ->
        append("\n<i>🤖 subagente: ")
            .append(PyText.esc(PyText.take(label, QuestionBoard.QUESTION_ORIGIN_CP)))
            .append("</i>")
    }
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
    /** The persistence seam (null = the pure in-memory board — the suite's default). */
    private val store: QuestionBoardStore? = null,
) {

    private val lock = Any()
    private val byShortId = LinkedHashMap<String, PendingQuestion>()
    private val shortIdByRequestId = HashMap<String, String>()
    private val counter = AtomicInteger(0)

    init {
        // THE RESTARTED BOARD (measured 4/11): the store's entries come back
        // EXACTLY as they left — the old card keeps its short id AND its
        // message id, its taps resolve through the EXISTING paths, and the
        // fork's re-announcement dedupes into it (the old card stays the
        // live one — never a second card, never a re-issued q1). A key that
        // does not parse only costs its counter seed; nextShortId's
        // collision loop keeps the ids unique regardless.
        store?.load()?.forEach { (sid, question) ->
            byShortId[sid] = question
            shortIdByRequestId[question.requestId] = sid
            sid.removePrefix(SHORT_ID_PREFIX).toIntOrNull()?.let { n ->
                if (n > counter.get()) counter.set(n)
            }
        }
    }

    /** The next short id — collision-free even against the LOADED entries (a restart never re-issues q1). */
    private fun nextShortId(): String =
        generateSequence { "$SHORT_ID_PREFIX${counter.incrementAndGet()}" }
            .first { it !in byShortId }

    /** The disk shadow of every mutation — the write is the store's own (it degrades, never throws). */
    private fun persist() {
        store?.save(synchronized(lock) { byShortId.toMap() })
    }

    /**
     * The stream's entry: parse, dedupe by requestId (the fork's recovery
     * re-emits — a duplicate question must NOT re-message the owner),
     * register under a fresh short id, and send the dedicated message. The
     * send seam answers the message id → remembered for the resolve edit.
     * Returns the question when a NEW one went out (null = not a question
     * or already pending).
     */
    /** One insertion's under-lock outcome: the new short id + the cards the cap retired. */
    private class Registration(val shortId: String, val evicted: List<PendingQuestion>)

    @Suppress("ReturnCount") // the dedupe's guards ARE the law
    suspend fun onStreamEvent(frame: ServerFrame.StreamEvent, taskTitle: String? = null): PendingQuestion? {
        val parsed = parseQuestionEvent(frame.event)?.copy(taskTitle = taskTitle) ?: return null
        val registered = synchronized(lock) {
            shortIdByRequestId[parsed.requestId]?.let { return null }
            val sid = nextShortId()
            shortIdByRequestId[parsed.requestId] = sid
            byShortId[sid] = parsed
            val evicted = mutableListOf<PendingQuestion>()
            while (byShortId.size > REGISTRY_CAP) {
                val eldest = byShortId.keys.first()
                byShortId.remove(eldest)?.let { dropped ->
                    shortIdByRequestId.remove(dropped.requestId)
                    evicted.add(dropped)
                }
            }
            Registration(sid, evicted)
        }
        persist() // the insert + the evictions are committed BEFORE the send flies
        // THE EVICTION'S HONESTY (measured 4/11): a retired card keeps armed
        // buttons whose taps now answer «ya no está pendiente» — a dead
        // button pretending to be live. The seal edit flies OUTSIDE the lock
        // (the AnswerStep law: decisions under the monitor, effects after).
        registered.evicted.forEach { q ->
            if (q.mid > 0) editQuestion(q.mid, EVICTION_TEXT, null)
        }
        val mid = sendQuestion(questionText(parsed), questionKeyboard(registered.shortId, parsed))
        if (mid != null && mid > 0) {
            synchronized(lock) {
                byShortId[registered.shortId]?.let { byShortId[registered.shortId] = it.copy(mid = mid) }
            }
            persist() // the mid is part of the persisted state (the seal's edit target)
        }
        return parsed
    }

    /** The tap's lookup — null after a resolve (a second tap fails honestly). */
    fun byShortId(shortId: String): PendingQuestion? = synchronized(lock) { byShortId[shortId] }

    /**
     * THE QUESTIONS' TASKS (14/11, the any-task law): every taskId with a
     * question still pending — the watcher keeps those subscriptions alive
     * even after the task falls out of the activity window, so an app-side
     * answer still seals the phone's card.
     */
    fun pendingTaskIds(): Set<String> = synchronized(lock) { byShortId.values.map { it.taskId }.toSet() }

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
                persist() // the ADVANCED copy is the stored state — the disk follows
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
        val question = synchronized(lock) {
            val removed = byShortId.remove(shortId)
            removed?.let { shortIdByRequestId.remove(it.requestId) }
            removed
        } ?: return
        persist() // the seal empties the registry — the store follows (one lock pass, both maps)
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

        /** The short ids' shape (`q1`, `q2`, …) — the tap wire's key AND the reload's counter seed. */
        const val SHORT_ID_PREFIX = "q"

        /** The evicted card's honest seal (4/11: an armed dead button's tap answers «ya no está pendiente»). */
        const val EVICTION_TEXT =
            "🗑 Pregunta retirada — llegaron $REGISTRY_CAP pendientes; mandá la respuesta por texto si sigue vigente"

        /** The buttons' cap per question (Telegram's keyboard sanity; ✍️ always remains). */
        const val QUESTION_OPTIONS_CAP = 8

        /** The plan's verbatim clip in the question message (code points). */
        const val QUESTION_PLAN_CP = 1600

        /** The task title's clip on the card's context line (code points). */
        const val QUESTION_TASK_CP = 80

        /** The subagent origin tag's clip on the card (code points). */
        const val QUESTION_ORIGIN_CP = 80

        /** An option's label cap on the button (Telegram's row sanity). */
        const val QUESTION_LABEL_CAP = 30
    }
}
