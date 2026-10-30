package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * B3b's LIVE VIEW — HONEST, PURE. The owner's two hard rules (29/10):
 *
 * A. «el cartel de thinking que hay ahora es falso … yo quiero que sea
 *    real, no un cartel de adorno» — every live state derives from REAL
 *    channel events with a staleness watchdog: generic Thinking is only
 *    valid WHILE thought chunks keep arriving; 20s of silence degrades
 *    honestly («(30s sin eventos)»); a socket drop renders the RECONNECT
 *    state, never a fake ongoing turn; a typed error frame resolves the
 *    turn with its error visible (quota exhaustion is a pill, not a
 *    hanging spinner). Silence is data.
 *
 * B. «cada thinking debe ser distinto: el thinking general, el thinking
 *    por read, por write, y por cada accion que tenga zcode, mcps, shell
 *    etc» — the phase is keyed by what is ACTUALLY happening, each with
 *    its own verb (🧠 general thought, 📖 reading, ✏️ writing/editing,
 *    ⚡ shell, 🔌 mcp …) via the injected [ToolVerbifier] — the wiring
 *    passes the SPECTATOR's classification map, never a duplicate.
 *
 * The reducer and the render stay pure (`now`, the verbifier and the
 * line differ are injected); the socket, the clock and Telegram are
 * effects elsewhere ([LivePublisher] ticks, [ChannelClient] feeds).
 */
sealed interface TurnPhase {
    data object Idle : TurnPhase

    data class Thinking(val chars: Int, val sinceMs: Long) : TurnPhase

    /**
     * A tool's input streams (lines grow) or the answer itself is being
     * written (tool == null). [toolId] finds the lane at render time for
     * the verb + detail; [tool] stays the NAME (logs, /status).
     */
    data class Writing(
        val tool: String?,
        val toolId: String?,
        val lines: Int,
        val rawChars: Int,
        val sinceMs: Long,
    ) : TurnPhase

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
    /** The MCP flag (pluginMetadata.component == "mcp" or an mcp__ name) — the 🔌 verb. */
    val isMcp: Boolean = false,
    /** An edit's final +N/−M (the SequenceMatcher differ's verdict, at close). */
    val added: Int? = null,
    val removed: Int? = null,
)

/**
 * The fork's TYPED terminal error (ZCodeTaskError: error, code?, detail?,
 * attribution?{source, reason, statusCode, providerErrorCode, retryable}) —
 * surfaced as its own pill, the honest end of a dead turn.
 */
data class TypedErrorPill(
    val error: String,
    val code: String? = null,
    val detail: String? = null,
    val source: String? = null,
    val statusCode: Int? = null,
    val providerErrorCode: String? = null,
    val retryable: Boolean? = null,
) {
    /** The quota family — the owner's exact scenario: tokens ran out mid-turn. */
    val isQuota: Boolean get() = QUOTA_MARKERS.any { m ->
        m in (code ?: "").lowercase() || m in (detail ?: "").lowercase() || m in (providerErrorCode ?: "").lowercase()
    }

    /** One honest line: the words, the code, the status — nothing invented. */
    fun line(): String = buildString {
        append(PyText.esc(error))
        code?.let { append(" · <code>${PyText.esc(it)}</code>") }
        statusCode?.let { append(" · HTTP $it") }
        providerErrorCode?.let { append(" · ${PyText.esc(it)}") }
        source?.let { append(" · ${PyText.esc(it)}") }
    }

    private companion object {
        val QUOTA_MARKERS = listOf("quota", "credit", "insufficient", "usage_cap", "exhaust")
    }
}

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
    /** The live CoT tail (capped at [LiveTurn.COT_TAIL_CP] code points, Python's narration cap). */
    val cotTail: String = "",
    /** The cajita's fold state — the cot:/cotr: taps flip it (a VIEW flag riding the turn value). */
    val cotExpanded: Boolean = false,
    val tools: List<ToolLive> = emptyList(),
    /** Subagent lanes: parentToolUseId → thought chars (the stream is ONE stream). */
    val subagentThought: Map<String, Int> = emptyMap(),
    /**
     * The lane this card is SCOPED to (a sa: attach — subagent implements
     * task: the identical card, fed only that lane's stream plus its root
     * tool's lifecycle). Null = the whole task's stream.
     */
    val watchedLane: String? = null,
    val doneTools: Int = 0,
    val failedTools: Int = 0,
    val interaction: InteractionPending? = null,
    /** The fork's own retry pill (session_info_update.apiRetry / task_network_debug_status). */
    val apiRetry: ApiRetryPill? = null,
    /** The fork's typed terminal error — the honest seal of a dead turn. */
    val typedError: TypedErrorPill? = null,
    val lastSeq: Long? = null,
    val lastEventAtMs: Long? = null,
    val usageTokens: Long? = null,
)

/**
 * One tool's live verb — the distinct-per-action contract. The channel
 * module ships [ToolVerbs.MINIMAL]; the daemon injects the SPECTATOR's
 * classification map (READ/WRITE/EDIT families + the file-path reader).
 */
data class ToolVerb(val emoji: String, val verb: String, val detail: String = "") {
    /** The phase/lane line's head: "⚡ shell" / "📖 reading" / "🔌 mcp". */
    fun head(): String = "$emoji <b>${PyText.esc(verb)}</b>"
}

fun interface ToolVerbifier {
    fun of(name: String, input: JsonObject?, isMcp: Boolean): ToolVerb
}

/** The fallback classifier (tests, unwired daemons) — MCP-aware, tool-honest. */
object ToolVerbs {
    val MINIMAL: ToolVerbifier = ToolVerbifier { name, _, isMcp ->
        when {
            isMcp || name.startsWith("mcp__") ->
                ToolVerb(emoji = "🔌", verb = "mcp", detail = name.substringAfter("mcp__", name))

            else -> ToolVerb(emoji = "🔧", verb = name)
        }
    }
}

/**
 * The edit's +N/−M: old vs new text → (added, removed) lines. The daemon
 * injects the SequenceMatcher port (byte-exact); the default is the
 * honest prefix/suffix approximation.
 */
fun interface LineDiffer {
    fun of(oldText: String, newText: String): Pair<Int, Int>
}

/** The unwired default: strip the common prefix/suffix, count both sides. */
object SimpleLineDiffer : LineDiffer {
    override fun of(oldText: String, newText: String): Pair<Int, Int> {
        val a = oldText.lines()
        val b = newText.lines()
        var head = 0
        while (head < a.size && head < b.size && a[head] == b[head]) head++
        var tail = 0
        while (tail < a.size - head && tail < b.size - head && a[a.size - 1 - tail] == b[b.size - 1 - tail]) tail++
        return (b.size - head - tail) to (a.size - head - tail)
    }
}

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

    /** pluginMetadata.component == "mcp" — the fork's own MCP marker. */
    fun isMcp(event: JsonObject, name: String): Boolean =
        event.obj("pluginMetadata")?.str("component") == "mcp" || name.startsWith("mcp__")

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

    /** task_error's typed fields → the pill (attribution is the fork's own typing). */
    fun typedErrorOf(event: JsonObject): TypedErrorPill {
        val attr = event.obj("attribution")
        return TypedErrorPill(
            error = errorText(event),
            code = event.str("code"),
            detail = event.str("detail"),
            source = attr?.str("source"),
            statusCode = attr?.int("statusCode"),
            providerErrorCode = attr?.str("providerErrorCode"),
            retryable = attr?.bool("retryable"),
        )
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

    /** How much of the tail the STATUS message shows while it fits (the cajita's window). */
    const val COT_PREVIEW_CP = 1100

    /** The cajita COLLAPSES to a «cot 1.2K» button at this size (the owner's ~3k contract). */
    const val COT_COLLAPSE_CP = 3000

    /** The expanded cajita's window (Telegram's 4096-char message law minus the rest of the line). */
    const val COT_EXPANDED_CP = 3400

    /** Thinking degrades honestly after this silence — «(30s sin eventos)». */
    const val STALE_SOFT_MS = 20_000L

    /** After this the line says the stream is probably CUT — still honest, never fabricated. */
    const val STALE_HARD_MS = 90_000L

    /**
     * One stream event folded into the turn's state. PURE: `now` is
     * injected (the host's `at` when present — the events carry no clock
     * of their own), the `differ` computes an edit's final +N/−M (the
     * daemon passes the SequenceMatcher port), and the seq gap check is
     * the caller's.
     */
    // one branch per event type — the union IS the shape (BusEvents precedent)
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun reduce(
        state: TurnLive?,
        frame: ServerFrame.StreamEvent,
        nowMs: Long,
        differ: LineDiffer = SimpleLineDiffer,
    ): TurnLive {
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
                        else -> TurnPhase.Writing(
                            tool = null,
                            toolId = null,
                            lines = 0,
                            rawChars = chars,
                            sinceMs = phaseSince(phase, refreshed, nowMs),
                        )
                    },
                    // real progress clears the retry pill (reasoning/text deltas)
                    apiRetry = if (delta.isNotEmpty()) null else refreshed.apiRetry,
                )
            }

            StreamEvents.TOOL_CALL -> reduceToolArrival(refreshed, event, nowMs, final = true)

            StreamEvents.TOOL_UPDATE -> when (StreamEvents.status(event)) {
                "pending" -> reduceToolArrival(refreshed, event, nowMs, final = false)

                "completed", "failed", "denied", "stopped" -> reduceToolClose(refreshed, event, nowMs, differ)

                else -> refreshed // in_progress and friends: nothing visible changes
            }

            StreamEvents.COMPLETE ->
                refreshed.copy(
                    phase = TurnPhase.Done(stopReason = StreamEvents.stopReason(event), atMs = nowMs),
                    usageTokens = StreamEvents.usageTokens(event) ?: refreshed.usageTokens,
                    interaction = null,
                )

            StreamEvents.ERROR -> {
                val pill = StreamEvents.typedErrorOf(event)
                refreshed.copy(
                    phase = TurnPhase.Done(stopReason = pill.error, atMs = nowMs),
                    typedError = pill,
                    interaction = null,
                )
            }

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

    /** agent_thought_chunk: main agent grows the tail; subagents grow their own lane —
     *  EXCEPT on a lane-scoped card, where the watched lane's thoughts ARE the
     *  main narration (subagent implements task: the identical UI). */
    private fun reduceThought(state: TurnLive, event: JsonObject, nowMs: Long): TurnLive {
        val delta = StreamEvents.content(event)
        val parent = StreamEvents.parentToolUseId(event)
        val isMain = parent == null || parent == state.watchedLane
        return if (isMain) {
            // Replay guard (measured live 30/10: the fork's stream recovery
            // can re-emit the same chunk; the tail already ends with it — a
            // duplicate would double-print the sentence in the cajita).
            // Length-gated: tiny legitimate repeats (separators, short tokens)
            // must pass — only sentence-sized re-emissions are replays.
            if (delta.length >= REPLAY_GUARD_MIN_CP && state.cotTail.endsWith(delta)) return state
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
            isMcp = existing?.isMcp ?: StreamEvents.isMcp(event, name),
        )
        val tools = state.tools.filterNot { it.toolId == toolId } + lane
        return state.copy(
            tools = tools,
            phase = when (state.phase) {
                is TurnPhase.Done -> state.phase
                else -> TurnPhase.Writing(
                    tool = name,
                    toolId = toolId,
                    lines = lane.lines,
                    rawChars = lane.rawChars,
                    sinceMs = existing?.openedAtMs ?: nowMs,
                )
            },
        )
    }

    @Suppress("ReturnCount") // the close ladder: one guard per early exit
    private fun reduceToolClose(
        state: TurnLive,
        event: JsonObject,
        nowMs: Long,
        differ: LineDiffer,
    ): TurnLive {
        val toolId = StreamEvents.toolId(event) ?: return state
        val existing = state.tools.firstOrNull { it.toolId == toolId } ?: return state
        val diff = editDiffOf(existing, event, differ)
        val closed = existing.copy(status = StreamEvents.status(event), added = diff?.first, removed = diff?.second)
        val failed = closed.status == "failed"
        // a lane-scoped card's OWN root tool closed: the watched subagent is
        // over — the honest Done seal (its next event never comes; a frozen
        // "thinking" would be the lie the owner forbade)
        val laneOver = state.watchedLane != null && toolId == state.watchedLane
        // the next visible thing re-opens the phase; until then keep showing
        // the sealed lane with its own timer frozen
        return state.copy(
            tools = state.tools.map { if (it.toolId == toolId) closed else it },
            doneTools = state.doneTools + 1,
            failedTools = state.failedTools + if (failed) 1 else 0,
            phase = when {
                laneOver -> TurnPhase.Done(
                    stopReason = "subagent ${closed.status ?: "ended"}",
                    atMs = nowMs,
                )
                state.phase is TurnPhase.Done -> state.phase
                else -> TurnPhase.Thinking(chars = state.thoughtChars, sinceMs = nowMs)
            },
        )
    }

    /** An edit's final +N/−M: the lane's own old_string/new_string through the differ. */
    @Suppress("ReturnCount") // one guard per absent side of the edit pair
    private fun editDiffOf(lane: ToolLive, event: JsonObject, differ: LineDiffer): Pair<Int, Int>? {
        val input = lane.input ?: StreamEvents.input(event) ?: return null
        val old = input.str("old_string") ?: return null
        val new = input.str("new_string") ?: return null
        return differ.of(old, new)
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
     * The status message's TEXT (pure state→string): the phase keyed by
     * what ACTUALLY happens (the verbifier's vocabulary), the staleness
     * watchdog, the typed pills, the CoT cajita and the visible
     * heartbeat. The blink is F80's: the leading pair toggles per edit.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // one branch per phase/lane — the message IS the state machine
    fun render(
        state: TurnLive,
        nowMs: Long,
        spin: Int,
        channelAlive: Boolean,
        verbs: ToolVerbifier = ToolVerbs.MINIMAL,
    ): String {
        val brain = if (spin % 2 == 0) "🧠" else "💭"
        // THE OWNER'S RULE («los segundos no deben correr»): when reality
        // is not advancing — silence, a dead channel, an error pill — every
        // clock in this render derives from the LAST REAL EVENT, never from
        // the present. A ticking timer during an outage is the lie class
        // he has rejected three times.
        val silence = silenceMs(state, nowMs)
        val frozen = !channelAlive ||
            silence >= STALE_SOFT_MS ||
            state.typedError != null ||
            state.apiRetry != null
        val clockNow = if (frozen) (state.lastEventAtMs ?: nowMs) else nowMs
        val elapsed = state.startedAtMs?.let { fmtDur(clockNow - it) } ?: "—"
        val head = "${PyText.esc(state.title)} · $elapsed"
        val lines = mutableListOf<String>()
        when (val phase = state.phase) {
            is TurnPhase.Idle -> lines.add("💻 <b>«$head»</b> — suscripto, nada corriendo")

            is TurnPhase.Thinking -> {
                lines.add("$brain <b>«$head»</b>")
                val stale = stalenessWord(silence, channelAlive)
                lines.add(
                    "<b>pensando</b> · ${phase.chars} caracteres · ${fmtDur(clockNow - phase.sinceMs)}$stale",
                )
            }

            is TurnPhase.Writing -> {
                val lane = phase.toolId?.let { id -> state.tools.firstOrNull { it.toolId == id } }
                val verb = verbs.of(lane?.name ?: phase.tool ?: "", lane?.input, lane?.isMcp ?: false)
                if (phase.tool == null) {
                    lines.add("✍️ <b>«$head»</b>")
                    lines.add(
                        "✍️ escribiendo la respuesta · ${phase.rawChars} caracteres · " +
                            fmtDur(clockNow - phase.sinceMs),
                    )
                } else {
                    lines.add("${verb.emoji} <b>«$head»</b>")
                    lines.add(
                        verbLine(verb, lane, phase, nowMs, silence, channelAlive),
                    )
                }
            }

            is TurnPhase.Done -> lines.add(
                "${if (state.failedTools > 0) "⚠️" else "✅"} <b>«$head»</b>" +
                    " — listo: ${PyText.esc(phase.stopReason)}",
            )
        }
        // the socket drop is a STATE, not a background detail: the phase line
        // must never pretend a turn is alive while the channel reconnects
        if (!channelAlive && state.phase !is TurnPhase.Done) {
            lines.add("📡 canal caído — reconectando… (última señal hace ${fmtDur(silence)})")
        }
        state.typedError?.let { pill ->
            val quota = if (pill.isQuota) " · 💳 CUOTA" else ""
            lines.add("💀 <b>error:</b> ${pill.line()}$quota")
        }
        // open tool lanes (the live action beyond the phase line)
        state.tools.asSequence().filter { it.status == null }.take(OPEN_LANES).forEach {
            val verb = verbs.of(it.name, it.input, it.isMcp)
            val counts = "${it.lines} líneas"
            lines.add(
                "${verb.emoji} ${PyText.esc(it.name)} · $counts · ${fmtDur(nowMs - it.openedAtMs)}",
            )
        }
        // sealed edits carry their +N/−M (the SequenceMatcher verdict)
        state.tools.filter { it.status != null && (it.added != null || it.removed != null) }
            .takeLast(EDIT_STAMPS)
            .forEach { lane ->
                lines.add(
                    "✏️ ${PyText.esc(lane.name)}: 🟢 +${lane.added ?: 0} · 🔴 −${lane.removed ?: 0}",
                )
            }
        state.subagentThought.forEach { (parent, chars) ->
            lines.add("🤖 subagente ${parent.takeLast(SUBAGENT_ID_TAIL)} · $chars caracteres pensando")
        }
        state.interaction?.let { lines.add("${it.emoji} esperando: ${PyText.esc(it.title ?: it.kind)}") }
        // the fork's own pill — the honest difference between «z.ai down» and «I typed too much»
        state.apiRetry?.let { pill ->
            val suffix = pill.errorStatus?.let { " (HTTP $it)" } ?: ""
            lines.add("⚠️ ${PyText.esc(ApiRetryText.line(pill, nowMs))}$suffix")
        }
        cotBlock(state)?.let(lines::add)
        val beat = if (channelAlive) "💓" else "📡"
        lines.add("$beat en vivo · último evento hace ${fmtDur(silence)} · ${state.doneTools} listos")
        return lines.joinToString("\n")
    }

    /** The lane's line: verb + detail + honest counters + the staleness word. */
    @Suppress("CyclomaticComplexMethod") // one shape per verb family
    private fun verbLine(
        verb: ToolVerb,
        lane: ToolLive?,
        phase: TurnPhase.Writing,
        nowMs: Long,
        silence: Long,
        channelAlive: Boolean,
    ): String {
        val clipped = PyText.preview(verb.detail, DETAIL_CP)
        val detail = if (clipped.isNotEmpty()) " <code>${PyText.esc(clipped)}</code>" else ""
        val stale = stalenessWord(silence, channelAlive)
        val timer = fmtDur(nowMs - phase.sinceMs)
        return when {
            lane != null && (lane.added != null || lane.removed != null) ->
                "${verb.head()}$detail · 🟢 +${lane.added ?: 0} · 🔴 −${lane.removed ?: 0} · $timer"

            phase.lines > 0 -> "${verb.head()}$detail · ${phase.lines} líneas · $timer$stale"

            else -> "${verb.head()}$detail · $timer$stale"
        }
    }

    /** The honest staleness word — silence is data, never a hidden spinner. */
    internal fun stalenessWord(silenceMs: Long, channelAlive: Boolean): String = when {
        !channelAlive -> "" // the reconnect line already speaks
        silenceMs >= STALE_HARD_MS ->
            " (${fmtDur(silenceMs)} sin eventos — stream cortado?)"
        silenceMs >= STALE_SOFT_MS -> " (${fmtDur(silenceMs)} sin eventos)"
        else -> ""
    }

    private fun silenceMs(state: TurnLive, nowMs: Long): Long =
        nowMs - (state.lastEventAtMs ?: state.startedAtMs ?: nowMs)

    /**
     * The CoT cajita: the tail as a quote block while the cot fits; at
     * [COT_COLLAPSE_CP] of TOTAL cot it collapses to the «cot 1.2K»
     * button ([cotMarkup] — the text only shows the hint; the tail cap
     * alone must never trigger this, the tail is just the window).
     */
    @Suppress("ReturnCount") // one shape per cajita state: absent, folded, open
    internal fun cotBlock(state: TurnLive): String? {
        if (state.phase !is TurnPhase.Thinking || state.cotTail.isEmpty()) return null
        if (!state.cotExpanded && cotSize(state) >= COT_COLLAPSE_CP) {
            return "🧠 cot ${fmtK(cotSize(state))} chars — tocá el botón para abrirlo"
        }
        val cap = if (state.cotExpanded) COT_EXPANDED_CP else COT_PREVIEW_CP
        return "<blockquote>${PyText.esc(takeLastCodePoints(state.cotTail, cap))}</blockquote>"
    }

    /** The status message's keyboard: the cajita's open/fold button when it matters. */
    fun cotMarkup(state: TurnLive): kotlinx.serialization.json.JsonObject? {
        if (state.phase !is TurnPhase.Thinking) return null
        val size = cotSize(state)
        return when {
            state.cotExpanded -> Keyboards.cotButtons(expanded = true)
            size >= COT_COLLAPSE_CP -> Keyboards.cotButtons(expanded = false)
            else -> null
        }
    }

    /** The cajita's size is the WHOLE cot so far (the tail is just the memory window). */
    private fun cotSize(state: TurnLive): Int = state.thoughtChars

    /** Python's «cot 1.2K» — thousands with one decimal. */
    internal fun fmtK(n: Int): String = when {
        n >= K_FROM -> {
            val text = String.format(java.util.Locale.ROOT, "%.1f", n / K_DIVISOR)
            text.removeSuffix(".0") + "K"
        }

        else -> n.toString()
    }

    /** The message born at the turn's start (before any edit). */
    fun birthText(state: TurnLive): String =
        "🖥️ <b>«${PyText.esc(state.title)}»</b> — turn live (channel)\n${PyText.esc(state.taskId)}"

    /** The seal of the status message at Done — one line, the record of the turn. */
    fun sealText(state: TurnLive, nowMs: Long): String {
        val phase = state.phase as? TurnPhase.Done ?: return render(state, nowMs, 0, true)
        val total = state.startedAtMs?.let { fmtDur(nowMs - it) } ?: "?"
        val error = state.typedError?.let { "\n💀 ${it.line()}" + if (it.isQuota) " · 💳 CUOTA" else "" } ?: ""
        return "✅ <b>«${PyText.esc(state.title)}»</b> — ${PyText.esc(phase.stopReason)} in $total · " +
            "🧠 ${state.thoughtChars} chars · 🔧 ${state.doneTools} tools" +
            (if (state.usageTokens != null) " · ${state.usageTokens} tok" else "") +
            error
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
    private const val EDIT_STAMPS = 2
    private const val DETAIL_CP = 60

    /**
     * The replay guard's floor: only sentence-sized re-emissions count as
     * replays — legitimate small repeats (separators, short tokens) pass.
     */
    internal const val REPLAY_GUARD_MIN_CP = 24
    private const val TEN = 10L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val TITLE_TAIL = 12
    private const val SUBAGENT_ID_TAIL = 6
    private const val K_FROM = 1000
    private const val K_DIVISOR = 1000.0
}
