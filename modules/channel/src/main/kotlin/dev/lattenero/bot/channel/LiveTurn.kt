package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * B3b's LIVE VIEW — the never-radio-silence contract, PURE. The owner's
 * MVP (30/10): «si es writing que aumente las líneas, si es thinking
 * que aumente los chars, todos con su timer» + spinner + visible
 * heartbeat. This file is the event→state reduction and the state→text
 * render; the socket, the clock and Telegram are effects that live
 * elsewhere ([LivePublisher] ticks the render, [ChannelClient] feeds
 * the events).
 *
 * The turn's state ADT (per the MVP, one spelling):
 * - [TurnPhase.Idle] — subscribed, nothing running;
 * - [TurnPhase.Thinking] — the model reasons: chars grow;
 * - [TurnPhase.Writing] — a tool's input streams (lines grow) or the
 *   answer itself is being written (tool == null);
 * - [TurnPhase.Done] — task_complete/task_error sealed the turn.
 */
sealed interface TurnPhase {
    data object Idle : TurnPhase

    data class Thinking(val chars: Int, val sinceMs: Long) : TurnPhase

    data class Writing(val tool: String?, val lines: Int, val rawChars: Int, val sinceMs: Long) : TurnPhase

    data class Done(val stopReason: String, val atMs: Long) : TurnPhase
}

/** One tool call in flight — the spec's §3.3 table, one lane per toolId. */
data class ToolLive(
    val toolId: String,
    val name: String,
    val title: String? = null,
    val parentToolUseId: String? = null,
    val openedAtMs: Long,
    val lines: Int = 0,
    val rawChars: Int = 0,
    val input: JsonObject? = null,
    /** null = open; completed/failed/denied/stopped close the lane. */
    val status: String? = null,
)

/** A permission/elicitation the turn is blocked on (the 🔐/❓ of the MVP). */
data class InteractionPending(val kind: String, val title: String?) {
    val emoji: String get() = if (kind == "permission") "🔐" else "❓"
}

/** The whole visible turn, one immutable value per event. */
data class TurnLive(
    val taskId: String,
    val title: String,
    val startedAtMs: Long? = null,
    val phase: TurnPhase = TurnPhase.Idle,
    val thoughtChars: Int = 0,
    val answerChars: Int = 0,
    /** The live CoT tail (capped at [COT_TAIL_CP] code points, Python's narration cap). */
    val cotTail: String = "",
    val tools: List<ToolLive> = emptyList(),
    /** Subagent lanes: parentToolUseId → thought chars (the stream is ONE stream). */
    val subagentThought: Map<String, Int> = emptyMap(),
    val doneTools: Int = 0,
    val failedTools: Int = 0,
    val interaction: InteractionPending? = null,
    /** The fork's own retry pill (session_info_update.apiRetry / task_network_debug_status). */
    val apiRetry: ApiRetryPill? = null,
    val lastSeq: Long? = null,
    val lastEventAtMs: Long? = null,
    val usageTokens: Long? = null,
)

/** The projection of one ZCodeStreamEvent — what the reducer reads out of the raw JSON. */
@Suppress("TooManyFunctions") // one reader per field of the union — the projection shape
internal object StreamEvents {
    const val RUN_STARTED = "task_run_started"
    const val THOUGHT = "agent_thought_chunk"
    const val MESSAGE = "agent_message_chunk"
    const val TOOL_CALL = "tool_call"
    const val TOOL_UPDATE = "tool_call_update"
    const val COMPLETE = "task_complete"
    const val ERROR = "task_error"
    const val PERMISSION = "permission_request"
    const val ELICITATION = "elicitation_request"
    const val PERMISSION_RESPONSE = "permission_response"
    const val ELICITATION_RESPONSE = "elicitation_response"
    const val SESSION_INFO = "session_info_update"
    const val NETWORK_DEBUG = "task_network_debug_status"

    fun type(event: JsonObject): String? = event.str("type")

    fun parentToolUseId(event: JsonObject): String? =
        (event["parentToolUseId"] as? JsonPrimitive)?.content?.takeIf { it != "null" && it.isNotEmpty() }

    fun content(event: JsonObject): String = (event["content"] as? JsonPrimitive)?.content ?: ""

    fun toolId(event: JsonObject): String? = event.str("toolId")

    fun toolName(event: JsonObject, tool: ToolLive?): String =
        event.str("toolName") ?: tool?.name ?: "tool"

    fun title(event: JsonObject): String? = event.str("title")

    fun status(event: JsonObject): String? = event.str("status")

    fun input(event: JsonObject): JsonObject? = event.obj("input")

    /** raw.streamingRawInputLength — the chars the model actually emitted for the input. */
    fun streamingRawInputLength(event: JsonObject): Int? =
        event.obj("raw")?.int("streamingRawInputLength")

    fun stopReason(event: JsonObject): String = event.str("stopReason") ?: "unknown"

    fun errorText(event: JsonObject): String = event.str("error") ?: "task error"

    fun usageTokens(event: JsonObject): Long? {
        val usage = event.obj("usage") ?: return null
        return (usage.long("totalTokens") ?: usage.long("inputTokens") ?: 0L) +
            (usage.long("outputTokens") ?: 0L)
    }

    /**
     * Lines written so far: the line count of the tool's streamed input —
     * `content` for writes, `new_string` for edits (§3.3's table). The
     * pending projection parses best-effort; the final tool_call replaces
     * it wholesale.
     */
    @Suppress("ReturnCount") // one guard per fallback — writtenLines exact shape
    fun writtenLines(event: JsonObject): Int {
        val partial = input(event) ?: return 0
        val text = partial.str("content") ?: partial.str("new_string") ?: return 0
        return lineCount(text)
    }

    /** Python's str line sense: empty text is 0 lines, otherwise breaks+1. */
    fun lineCount(text: String): Int = when (text.isEmpty()) {
        true -> 0
        false -> text.count { it == '\n' } + 1
    }
}

@Suppress("TooManyFunctions") // reduce + render + the timers: one vocabulary per concern
object LiveTurn {

    /** The live CoT tail's cap — Python's intermediate narration cap (2500 code points). */
    const val COT_TAIL_CP = 2500

    /** How much of the tail the STATUS message shows (the rest waits for the flush). */
    const val COT_PREVIEW_CP = 1100

    /**
     * One stream event folded into the turn's state. PURE: `now` is
     * injected (the host's `at` when present — the events carry no clock
     * of their own), the seq gap check is the caller's (the client
     * resyncs by taskSnapshot; the fold itself never fabricates events).
     */
    // one branch per event type — the union IS the shape (BusEvents precedent)
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun reduce(state: TurnLive?, frame: ServerFrame.StreamEvent, nowMs: Long): TurnLive {
        val event = frame.event
        val base = state ?: TurnLive(
            taskId = frame.taskId,
            title = frame.taskId.takeLast(TITLE_TAIL),
            startedAtMs = openedAt(frame, nowMs),
        )
        val refreshed = base.copy(
            lastSeq = frame.seq,
            lastEventAtMs = if (frame.at > 0) frame.at else nowMs,
        )
        val type = StreamEvents.type(event) ?: return refreshed
        return when (type) {
            StreamEvents.RUN_STARTED -> refreshed.copy(startedAtMs = frame.at.takeIf { it > 0 } ?: nowMs)

            StreamEvents.THOUGHT -> reduceThought(refreshed, event, nowMs)

            StreamEvents.MESSAGE -> {
                val delta = StreamEvents.content(event)
                val chars = refreshed.answerChars + delta.length
                refreshed.copy(
                    answerChars = chars,
                    phase = when (val phase = refreshed.phase) {
                        is TurnPhase.Done -> phase
                        else -> {
                            val since = phaseSince(phase, refreshed, nowMs)
                            TurnPhase.Writing(tool = null, lines = 0, rawChars = chars, sinceMs = since)
                        }
                    },
                    // real progress clears the retry pill (reasoning/text deltas)
                    apiRetry = if (delta.isNotEmpty()) null else refreshed.apiRetry,
                )
            }

            StreamEvents.TOOL_CALL -> reduceToolArrival(refreshed, event, nowMs, final = true)

            StreamEvents.TOOL_UPDATE -> when (StreamEvents.status(event)) {
                "pending" -> reduceToolArrival(refreshed, event, nowMs, final = false)

                "completed", "failed", "denied", "stopped" -> reduceToolClose(refreshed, event, nowMs)

                else -> refreshed // in_progress and friends: nothing visible changes
            }

            StreamEvents.COMPLETE ->
                refreshed.copy(
                    phase = TurnPhase.Done(stopReason = StreamEvents.stopReason(event), atMs = nowMs),
                    usageTokens = StreamEvents.usageTokens(event) ?: refreshed.usageTokens,
                    interaction = null,
                )

            StreamEvents.ERROR -> refreshed.copy(
                phase = TurnPhase.Done(stopReason = "error: ${StreamEvents.errorText(event)}", atMs = nowMs),
                interaction = null,
            )

            StreamEvents.PERMISSION -> refreshed.copy(
                interaction = InteractionPending("permission", StreamEvents.title(event)),
            )

            StreamEvents.ELICITATION -> refreshed.copy(
                interaction = InteractionPending("elicitation", StreamEvents.title(event)),
            )

            StreamEvents.PERMISSION_RESPONSE, StreamEvents.ELICITATION_RESPONSE ->
                refreshed.copy(interaction = null)

            StreamEvents.SESSION_INFO -> {
                // the fork's pill patch (absent = untouched, null = clear) + the
                // turn's REAL title when the session reports one
                val titled = event.str("title")?.takeIf { it.isNotEmpty() }
                    ?.let { refreshed.copy(title = it) } ?: refreshed
                when (val patch = ApiRetryText.fromPatch(event)) {
                    ApiRetryText.ApiRetryPatch.Untouched -> titled
                    ApiRetryText.ApiRetryPatch.Clear -> titled.copy(apiRetry = null)
                    is ApiRetryText.ApiRetryPatch.Set -> titled.copy(apiRetry = patch.pill)
                }
            }

            StreamEvents.NETWORK_DEBUG -> when (
                val patch = ApiRetryText.fromNetworkDebug(event, frame.at.takeIf { it > 0 } ?: nowMs)
            ) {
                ApiRetryText.ApiRetryPatch.Untouched -> refreshed
                ApiRetryText.ApiRetryPatch.Clear -> refreshed.copy(apiRetry = null)
                is ApiRetryText.ApiRetryPatch.Set -> refreshed.copy(apiRetry = patch.pill)
            }

            else -> refreshed // usage_update and the rest of the union: additive rule
        }
    }

    private fun openedAt(frame: ServerFrame.StreamEvent, nowMs: Long): Long =
        if (frame.at > 0) frame.at else nowMs

    /** agent_thought_chunk: main agent grows the tail; subagents grow their own lane. */
    private fun reduceThought(state: TurnLive, event: JsonObject, nowMs: Long): TurnLive {
        val delta = StreamEvents.content(event)
        val parent = StreamEvents.parentToolUseId(event)
        return if (parent == null) {
            val chars = state.thoughtChars + delta.length
            state.copy(
                thoughtChars = chars,
                cotTail = takeLastCodePoints(state.cotTail + delta, COT_TAIL_CP),
                phase = TurnPhase.Thinking(chars = chars, sinceMs = phaseSince(state.phase, state, nowMs)),
                // REAL progress clears the retry pill (the fork's own recovery rule)
                apiRetry = if (delta.isNotEmpty()) null else state.apiRetry,
            )
        } else {
            state.copy(
                subagentThought = state.subagentThought +
                    (parent to (state.subagentThought[parent] ?: 0) + delta.length),
            )
        }
    }

    /**
     * tool_call (final, input complete) opens-or-fills the lane with the
     * authority input; tool_call_update/pending streams the partial input
     * (lines from content/new_string, raw chars from raw.streamingRawInputLength).
     */
    @Suppress("CyclomaticComplexMethod") // the tool lane lifecycle, one guard per field
    private fun reduceToolArrival(
        state: TurnLive,
        event: JsonObject,
        nowMs: Long,
        final: Boolean,
    ): TurnLive {
        val toolId = StreamEvents.toolId(event) ?: return state
        val existing = state.tools.firstOrNull { it.toolId == toolId }
        val name = StreamEvents.toolName(event, existing)
        val streamed = StreamEvents.writtenLines(event)
        // final tool_call: the input IS the authority. pending update: the
        // partial projection only ever GROWS the count (sampling may stutter)
        val lines = when {
            final -> if (streamed > 0 || existing == null) streamed else existing.lines
            streamed > 0 -> streamed
            else -> existing?.lines ?: 0
        }
        val lane = ToolLive(
            toolId = toolId,
            name = name,
            title = StreamEvents.title(event) ?: existing?.title,
            parentToolUseId = StreamEvents.parentToolUseId(event) ?: existing?.parentToolUseId,
            openedAtMs = existing?.openedAtMs ?: nowMs,
            lines = lines,
            rawChars = StreamEvents.streamingRawInputLength(event) ?: existing?.rawChars ?: 0,
            input = StreamEvents.input(event) ?: existing?.input,
            status = existing?.status, // a closed lane does not reopen
        )
        val tools = state.tools.filterNot { it.toolId == toolId } + lane
        return state.copy(
            tools = tools,
            phase = when (state.phase) {
                is TurnPhase.Done -> state.phase
                else -> TurnPhase.Writing(
                    tool = name,
                    lines = lane.lines,
                    rawChars = lane.rawChars,
                    sinceMs = existing?.openedAtMs ?: nowMs,
                )
            },
        )
    }

    @Suppress("ReturnCount") // the close ladder: one guard per early exit
    private fun reduceToolClose(state: TurnLive, event: JsonObject, nowMs: Long): TurnLive {
        val toolId = StreamEvents.toolId(event) ?: return state
        val existing = state.tools.firstOrNull { it.toolId == toolId } ?: return state
        val closed = existing.copy(status = StreamEvents.status(event))
        val failed = closed.status == "failed"
        // the next visible thing re-opens the phase; until then keep showing
        // the sealed lane with its own timer frozen
        return state.copy(
            tools = state.tools.map { if (it.toolId == toolId) closed else it },
            doneTools = state.doneTools + 1,
            failedTools = state.failedTools + if (failed) 1 else 0,
            phase = when (state.phase) {
                is TurnPhase.Done -> state.phase
                else -> TurnPhase.Thinking(chars = state.thoughtChars, sinceMs = nowMs)
            },
        )
    }

    /** A phase's timer starts when the phase is ENTERED — not re-armed per event. */
    private fun phaseSince(phase: TurnPhase, state: TurnLive, nowMs: Long): Long = when (phase) {
        is TurnPhase.Thinking -> phase.sinceMs
        is TurnPhase.Writing -> phase.sinceMs
        is TurnPhase.Done -> phase.atMs
        TurnPhase.Idle -> state.lastEventAtMs ?: state.startedAtMs ?: nowMs
    }

    // ---- render ------------------------------------------------------------

    /**
     * The status message's TEXT (pure state→string): spinner, phase,
     * counters with their timers, the live CoT preview and the visible
     * heartbeat. The blink is F80's: Telegram does not animate emojis,
     * the leading pair toggles per edit so the line is VISIBLY alive.
     */
    @Suppress("CyclomaticComplexMethod") // one branch per phase/lane — the message IS the state machine
    fun render(state: TurnLive, nowMs: Long, spin: Int, channelAlive: Boolean): String {
        val brain = if (spin % 2 == 0) "🧠" else "💭"
        val gear = if (spin % 2 == 0) "⚙️" else "📝"
        val elapsed = state.startedAtMs?.let { fmtDur(nowMs - it) } ?: "—"
        val head = "${PyText.esc(state.title)} · $elapsed"
        val lines = mutableListOf<String>()
        when (val phase = state.phase) {
            is TurnPhase.Idle -> lines.add("💻 <b>«$head»</b> — subscribed, nothing running")

            is TurnPhase.Thinking -> {
                lines.add("$brain <b>«$head»</b>")
                if (phase.chars > 0) {
                    lines.add("<b>thinking</b> · ${phase.chars} chars · ${fmtDur(nowMs - phase.sinceMs)}")
                } else {
                    lines.add("thinking…")
                }
            }

            is TurnPhase.Writing -> {
                lines.add("$gear <b>«$head»</b>")
                if (phase.tool != null) {
                    lines.add(
                        "🔧 <b>${PyText.esc(phase.tool)}</b> · ${phase.lines} lines" +
                            (if (phase.rawChars > 0) " · ${phase.rawChars} chars raw" else "") +
                            " · ${fmtDur(nowMs - phase.sinceMs)}",
                    )
                } else {
                    val since = fmtDur(nowMs - phase.sinceMs)
                    lines.add("✍️ writing the answer · ${phase.rawChars} chars · $since")
                }
            }

            is TurnPhase.Done -> lines.add(
                "${if (state.failedTools > 0) "⚠️" else "✅"} <b>«$head»</b>" +
                    " — done: ${PyText.esc(phase.stopReason)}",
            )
        }
        // open tool lanes (the live action beyond the phase line)
        state.tools.asSequence().filter { it.status == null }.take(OPEN_LANES).forEach {
            val tag = if (it.parentToolUseId != null) "🤖" else "🔧"
            lines.add(
                "$tag ${PyText.esc(it.name)} · ${it.lines} lines" +
                    (if (it.rawChars > 0) " · ${it.rawChars} raw" else "") +
                    " · ${fmtDur(nowMs - it.openedAtMs)}",
            )
        }
        state.subagentThought.forEach { (parent, chars) ->
            lines.add("🤖 subagent ${parent.takeLast(SUBAGENT_ID_TAIL)} · $chars chars thought")
        }
        state.interaction?.let { lines.add("${it.emoji} waiting: ${PyText.esc(it.title ?: it.kind)}") }
        // the fork's own pill — the honest difference between «z.ai down» and «I typed too much»
        state.apiRetry?.let { pill ->
            val suffix = pill.errorStatus?.let { " (HTTP $it)" } ?: ""
            lines.add("⚠️ ${PyText.esc(ApiRetryText.line(pill, nowMs))}$suffix")
        }
        // the live CoT preview: what the model is actually saying right now
        if (state.cotTail.isNotEmpty() && state.phase is TurnPhase.Thinking) {
            lines.add("<pre>${PyText.esc(takeLastCodePoints(state.cotTail, COT_PREVIEW_CP))}</pre>")
        }
        val beat = if (channelAlive) "💓" else "📡"
        val lastSeen = state.lastEventAtMs?.let { fmtDur(nowMs - it) } ?: "—"
        lines.add("$beat alive · last event $lastSeen ago · ${state.doneTools} done")
        return lines.joinToString("\n")
    }

    /** The message born at the turn's start (before any edit). */
    fun birthText(state: TurnLive): String =
        "🖥️ <b>«${PyText.esc(state.title)}»</b> — turn live (channel)\n${PyText.esc(state.taskId)}"

    /** The seal of the status message at Done — one line, the record of the turn. */
    fun sealText(state: TurnLive, nowMs: Long): String {
        val phase = state.phase as? TurnPhase.Done ?: return render(state, nowMs, 0, true)
        val total = state.startedAtMs?.let { fmtDur(nowMs - it) } ?: "?"
        return "✅ <b>«${PyText.esc(state.title)}»</b> — ${PyText.esc(phase.stopReason)} in $total · " +
            "🧠 ${state.thoughtChars} chars · 🔧 ${state.doneTools} tools" +
            (if (state.usageTokens != null) " · ${state.usageTokens} tok" else "")
    }

    /** Seconds → «42s» / «3m12s» / «1h02m» — the timers' one spelling. */
    fun fmtDur(ms: Long): String {
        val totalSeconds = ms / 1000
        return when {
            totalSeconds < SECONDS_PER_MINUTE -> "${totalSeconds}s"
            totalSeconds < SECONDS_PER_HOUR ->
                "${totalSeconds / SECONDS_PER_MINUTE}m${pad2(totalSeconds % SECONDS_PER_MINUTE)}s"
            else ->
                "${totalSeconds / SECONDS_PER_HOUR}h${pad2((totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE)}m"
        }
    }

    /** tail by CODE POINTS (Python str semantics — the spectator's lesson). */
    fun takeLastCodePoints(text: String, n: Int): String {
        val cps = text.codePointCount(0, text.length)
        if (cps <= n) return text
        val from = text.offsetByCodePoints(0, cps - n)
        return text.substring(from)
    }

    private fun pad2(n: Long): String = if (n < TEN) "0$n" else "$n"

    private const val OPEN_LANES = 3
    private const val TEN = 10L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val TITLE_TAIL = 12
    private const val SUBAGENT_ID_TAIL = 6
}
