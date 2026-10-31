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
 * 3. The tap (`qa:<sid>:<i>`) flies as the fork's `respond` frame to the
 *    SAME service API the desktop calls; the ACK edits the question message
 *    honestly and removes the buttons; a double tap or a stale question
 *    fails with the fork's own error — never a silent tap.
 *
 * The free-text answer (`qf:<sid>` + [elicitationContent]) reuses the
 * ArmedOrder's one-shot mechanics: the next text IS the answer.
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
 * One pending question parsed from the stream. [kind] is the fork's respond
 * kind: "permission" | "elicitation" | "plan" (ExitPlanMode — the approval
 * rides the elicitation door). [mid] is the Telegram message that carries
 * the buttons (0 = not sent / send seam declined).
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
)

/**
 * Pure: the stream event → the question, or null (not a question / no
 * requestId — the additive rule). Reads the shapes the fork itself emits:
 * ZCodePermissionRequest (options with optionId+name+response),
 * ZCodeElicitationRequest (questions[] or message+options, value+label),
 * plan approval by schema.interaction.
 */
// one branch per event shape — the union IS the shape (BusEvents precedent)
@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
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
                    QuestionOption(
                        "allow",
                        "Permitir",
                        response = buildJsonObject { put("decision", "allow") },
                    ),
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
                val first = questions?.firstOrNull() as? JsonObject
                val title = first?.str("question")?.takeIf { it.isNotBlank() }
                    ?: event.str("message")?.takeIf { it.isNotBlank() }
                    ?: return null
                val header = first?.str("header")?.takeIf { it.isNotBlank() }
                    ?: event.str("header")?.takeIf { it.isNotBlank() }
                    ?: "❓ Pregunta"
                val raw = first?.get("options") as? JsonArray ?: event["options"] as? JsonArray
                val options = raw.orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .mapNotNull { option ->
                        val value = option.str("value")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        QuestionOption(
                            optionId = value,
                            label = option.str("label")?.takeIf { it.isNotBlank() } ?: value,
                        )
                    }
                    .take(QuestionBoard.QUESTION_OPTIONS_CAP)
                PendingQuestion(
                    taskId = taskId,
                    requestId = requestId,
                    kind = "elicitation",
                    header = header,
                    title = title,
                    options = options,
                    questionCount = questions?.size ?: 1,
                )
            }
        }

        else -> null
    }
}

/**
 * The question message's TEXT — the dedicated message (never the card):
 * the head, the body, the plan's verbatim clip. User-facing Spanish.
 */
internal fun questionText(q: PendingQuestion): String = buildString {
    append("<b>").append(PyText.esc(q.header)).append("</b>")
    append("\n").append(PyText.esc(q.title))
    if (q.questionCount > 1) append("\n<i>pregunta 1 de ").append(q.questionCount).append("</i>")
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
 * shape for a single answered question: `answers` keyed by the QUESTION text
 * (missing key = skipped, never an empty string), `answer_N`, and `answer`
 * only when the whole questionnaire is one question.
 */
fun elicitationContent(questionText: String, value: String, questionCount: Int = 1): JsonObject =
    buildJsonObject {
        putJsonObject("answers") { put(questionText, value) }
        put("answer_0", value)
        if (questionCount <= 1) put("answer", value)
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
    // one guard per early exit (unparseable, duplicate) — the registry's own shape
    @Suppress("ReturnCount")
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
