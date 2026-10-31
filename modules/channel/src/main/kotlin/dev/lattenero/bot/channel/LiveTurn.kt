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
        /**
         * A dispatch lane's launch ACK (Agent/Task tools): the child's
         * agentId — the fork's additive field; the session store's
         * `sess_subagent_<agentId>` convention turns it into the address
         * the sa: attach subscribes to.
         */
        val subagentAgentId: String? = null,
        val openedAtMs: Long,
    val lines: Int = 0,
    val rawChars: Int = 0,
    val input: JsonObject? = null,
    /** null = open; completed/failed/denied/stopped close the lane. */
    val status: String? = null,
    /** The MCP flag (pluginMetadata.component == "mcp" or an mcp__ name) — the 🔌 verb. */
    val isMcp: Boolean = false,
    /**
     * The edit's +N/−M — LIVE while the input streams (the differ runs per
     * delta on the growing old/new pair); the final close keeps the
     * authoritative verdict (the same differ on the complete input).
     */
    val added: Int? = null,
    val removed: Int? = null,
    /**
     * The tool's output at close, in lines. The fork streams NO live tool
     * output (the adapter's projection carries `content` only on the result
     * tool_call_update — zcodeTaskServiceAdapter's mapToolUpdated), so the
     * output lands HERE, counted once, never faked as live.
     */
    val outputLines: Int? = null,
    /**
     * The tool's output at close, VERBATIM (the result update's `content`
     * string). The transcript feed carries the real thing — a Bash's
     * output lines, a failed tool's error text; the count above is for the
     * card's stamp. Null = no string content arrived (a structured payload
     * invents nothing).
     */
    val output: String? = null,
    /** The lane's last real event — the frozen lane's clock derives from it. */
    val lastUpdateAtMs: Long? = null,
) {
    /** The moment the lane last moved — openedAtMs when nothing touched it since. */
    val touchedAtMs: Long get() = lastUpdateAtMs ?: openedAtMs
}

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
    /**
     * The SEALED CoT blocks (Python's CotSealer boxes, reasoning.py): one
     * per [LiveTurn.COT_BLOCK_CP] code points of REAL streamed thinking.
     * The full accumulation lives here — the tail above is just the live
     * preview window; a block's text is never summarized, never invented.
     */
    val cotBlocks: List<String> = emptyList(),
    /** The block currently filling: seals into [cotBlocks] at the cap. */
    val cotPending: String = "",
    /**
     * The EXPANDED block's 1-based index — the cb:<n> tap's VIEW flag (the
     * cotExpanded precedent): the block's full text opens IN the card,
     * above its button row; the same tap folds it. One at a time (the
     * single pointer); null = everything folded.
     */
    val cotExpandedBlock: Int? = null,
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

    /** The dispatch lane's launch ACK (the fork's additive field) — the lane→session link. */
    fun subagentAgentId(event: JsonObject): String? = event.str("subagentAgentId")

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

    /**
     * The tool's OUTPUT at close, in lines, from the result update's
     * `content`. Only string content counts — a structured payload's
     * compact JSON would be one meaningless line, so the lane invents no
     * count for it (the close boxes render the real thing).
     */
    fun outputLines(event: JsonObject): Int? {
        val content = event["content"] as? JsonPrimitive ?: return null
        return if (content.isString) lineCount(content.content) else null
    }

    /**
     * The tool's OUTPUT at close, VERBATIM — the same string `content`
     * [outputLines] counts, uncut (the feed's honest payload; the
     * renderer caps it under the message law). Null for structured
     * payloads and empty strings (nothing real to carry).
     */
    fun outputText(event: JsonObject): String? {
        val content = event["content"] as? JsonPrimitive ?: return null
        return content.content.takeIf { it.isNotEmpty() }
    }

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

    /**
     * The CoT BLOCK's cap — Python's COT_BOX_CAP (reasoning.py, the
     * owner's F43 model verbatim: «a box has a size; when the CoT reaches
     * the cap the box is full and is sent with its number»). A block seals
     * at 3000 CODE POINTS of streamed thinking and its button travels.
     */
    const val COT_BLOCK_CP = 3000

    /** How much of the tail the STATUS message shows while it fits (the cajita's window). */
    const val COT_PREVIEW_CP = 1100

    /** The expanded block's window (Telegram's 4096-char message law minus the rest of the line). */
    const val COT_EXPANDED_CP = 3400

    /**
     * The close shower's chunk size (RAW code points before esc()) — a
     * prose chunk escapes to ≈ itself, under the transport's CH_REPLY;
     * esc() is per-char, so a chunk-then-escape never splits an entity.
     */
    const val COT_SHOWER_CHUNK_CP = 3600

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

            StreamEvents.TOOL_CALL -> reduceToolArrival(refreshed, event, nowMs, differ, final = true)

            StreamEvents.TOOL_UPDATE -> when (StreamEvents.status(event)) {
                "pending" -> reduceToolArrival(refreshed, event, nowMs, differ, final = false)

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
            // THE BLOCKS (Python's CotSealer.feed, reasoning.py): the pending
            // block seals EXACTLY at COT_BLOCK_CP code points — a delta may
            // straddle the boundary, so the block closes AT it and the
            // remainder opens the next one. CODE POINTS, not chars
            // (PyText.cpLength is the house ruler); offsetByCodePoints never
            // splits a surrogate pair.
            var blocks = state.cotBlocks
            var pending = state.cotPending + delta
            while (PyText.cpLength(pending) >= COT_BLOCK_CP) {
                val cut = pending.offsetByCodePoints(0, COT_BLOCK_CP)
                blocks = blocks + pending.substring(0, cut)
                pending = pending.substring(cut)
            }
            state.copy(
                thoughtChars = chars,
                cotBlocks = blocks,
                cotPending = pending,
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
     * The +N/−M breathe: the differ runs per delta on the growing old/new
     * pair; the close recomputes it on the complete input (the authority).
     */
    @Suppress("CyclomaticComplexMethod") // the tool lane lifecycle, one guard per field
    private fun reduceToolArrival(
        state: TurnLive,
        event: JsonObject,
        nowMs: Long,
        differ: LineDiffer,
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
        val input = StreamEvents.input(event) ?: existing?.input
        val liveDiff = liveEditDiff(input, differ)
        val lane = ToolLive(
            toolId = toolId,
            name = name,
            title = StreamEvents.title(event) ?: existing?.title,
            parentToolUseId = StreamEvents.parentToolUseId(event) ?: existing?.parentToolUseId,
            subagentAgentId = StreamEvents.subagentAgentId(event) ?: existing?.subagentAgentId,
            openedAtMs = existing?.openedAtMs ?: nowMs,
            lines = lines,
            rawChars = StreamEvents.streamingRawInputLength(event) ?: existing?.rawChars ?: 0,
            input = input,
            status = existing?.status, // a closed lane does not reopen
            isMcp = existing?.isMcp ?: StreamEvents.isMcp(event, name),
            added = liveDiff?.first,
            removed = liveDiff?.second,
            lastUpdateAtMs = nowMs,
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

    // the close ladder: one guard per early exit, one branch per close shape — the reducer's own table
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun reduceToolClose(
        state: TurnLive,
        event: JsonObject,
        nowMs: Long,
        differ: LineDiffer,
    ): TurnLive {
        val toolId = StreamEvents.toolId(event) ?: return state
        val existing = state.tools.firstOrNull { it.toolId == toolId } ?: return state
        val diff = liveEditDiff(existing.input ?: StreamEvents.input(event), differ)
        val closed = existing.copy(
            status = StreamEvents.status(event),
            added = diff?.first,
            removed = diff?.second,
            outputLines = StreamEvents.outputLines(event) ?: existing.outputLines,
            output = StreamEvents.outputText(event) ?: existing.output,
            lastUpdateAtMs = nowMs,
        )
        val failed = closed.status == "failed"
        // a dispatch lane's close folds its world: the child's inner lanes
        // and its thought counter leave the card with it (the owner's 30/10
        // corpse card kept orphan lanes ticking forever)
        val dispatch = closed.name in DISPATCH_TOOLS
        val tools = (if (dispatch) state.tools.filterNot { it.parentToolUseId == toolId } else state.tools)
            .map { if (it.toolId == toolId) closed else it }
        // a lane-scoped card's OWN root tool closed: the watched subagent is
        // over — the honest Done seal (its next event never comes; a frozen
        // "thinking" would be the lie the owner forbade)
        val laneOver = state.watchedLane != null && toolId == state.watchedLane
        // the next visible thing re-opens the phase; until then keep showing
        // the sealed lane with its own timer frozen
        return state.copy(
            tools = tools,
            doneTools = state.doneTools + 1,
            failedTools = state.failedTools + if (failed) 1 else 0,
            subagentThought = if (dispatch) state.subagentThought - toolId else state.subagentThought,
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

    /**
     * An edit's +N/−M: the input's old_string/new_string through the
     * differ. Called per delta on the PARTIAL input (the counts grow live)
     * and once more at close on the complete input (the authority).
     */
    @Suppress("ReturnCount") // one guard per absent side of the edit pair
    private fun liveEditDiff(input: JsonObject?, differ: LineDiffer): Pair<Int, Int>? {
        val old = input?.str("old_string") ?: return null
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
                        verbLine(verb, lane, phase, clockNow, silence, channelAlive),
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
        // the lanes, BOUNDED (the owner's 30/10 corpse card: a dead
        // subagent's bash lane ticked «43m23s» forever): only the most
        // recent open lanes, plus the last verdict stamps. A lane with no
        // event for [LANE_FROZEN_MS] renders FROZEN — dimmed, its clock
        // stopped at its last real event («los segundos no deben correr»).
        state.tools.asSequence()
            .filter { it.status == null }
            .sortedByDescending { it.touchedAtMs }
            .take(LANE_OPEN_CAP)
            .forEach { lines.add(laneLine(it, verbs, clockNow, nowMs)) }
        // sealed lanes carry their verdict — the edit's +N/−M, the shell's
        // output lines (the fork's stream carries NO live tool output; the
        // output lands at close, counted once) — most recent last
        state.tools.filter { it.status != null && (it.added != null || it.removed != null || it.outputLines != null) }
            .takeLast(LANE_STAMP_CAP)
            .forEach { lines.add(stampLine(it, verbs)) }
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
        // the honest cap: the keyboard holds only the most recent blocks —
        // the promise the close's shower keeps (the dropped ones travel)
        cotOverflowNote(state)?.let(lines::add)
        // THE HEART IS DEAD (the owner's 30/10 verdict on the 💓): liveness
        // is the per-box counters themselves plus this sober line — «en
        // vivo» with the honest silence while the stream moves or waits;
        // «frozen» when the channel is down, the turn errored, the retry
        // pill holds or the stream is cut (the clocks above already froze).
        val live = channelAlive && state.typedError == null && state.apiRetry == null &&
            silence < STALE_HARD_MS
        lines.add(
            if (live) "en vivo · último evento hace ${fmtDur(silence)} · ${state.doneTools} listos"
            else "frozen · ${fmtDur(silence)} sin eventos · ${state.doneTools} listos",
        )
        // THE READING (the owner's 30/10 refinement: «el lugar para que
        // aparezca el texto ES EN EL BOTON del CoT»): the tapped block opens
        // WHOLE as the card's LAST body element — right above its button
        // row — and stays while the model keeps working (the blocks are
        // sealed; the card's noise keeps churning above, the reading doesn't)
        expandedBlockSection(state)?.let(lines::add)
        return lines.joinToString("\n")
    }

    /**
     * One open lane's line: the verb + its detail (the shell's command, the
     * file), the growing counts, the timer — FROZEN and dimmed when the
     * lane died. The fork streams no tool output while a tool runs (the
     * adapter's projection carries `content` only on the result update),
     * so the line shows the command + elapsed, never a fake output tail.
     */
    private fun laneLine(lane: ToolLive, verbs: ToolVerbifier, clockNow: Long, nowMs: Long): String {
        val verb = verbs.of(lane.name, lane.input, lane.isMcp)
        val clipped = PyText.preview(verb.detail, DETAIL_CP)
        val detail = if (clipped.isNotEmpty()) " <code>${PyText.esc(clipped)}</code>" else ""
        // a dispatch lane whose child was ACKed never freezes: the child runs
        // as its OWN session (the sa: attach's card is the honest view) and
        // the parent stream carries nothing until the dispatch result — a
        // frozen claim we cannot verify would be a lie in the other direction
        val frozenLane = lane.subagentAgentId == null && nowMs - lane.touchedAtMs >= LANE_FROZEN_MS
        return if (frozenLane) {
            "❄️ <i>${verb.head()}$detail${laneCounts(lane)} · ${fmtDur(lane.touchedAtMs - lane.openedAtMs)} · " +
                "frozen · ${fmtDur(nowMs - lane.touchedAtMs)} sin eventos</i>"
        } else {
            "${verb.head()}$detail${laneCounts(lane)} · ${fmtDur(clockNow - lane.openedAtMs)}"
        }
    }

    /**
     * The lane's counts: the live +N/−M for edits, the streamed lines
     * otherwise. «0 líneas» is an INVENTED number (the fork streams no
     * tool output while a tool runs) — when nothing streams the box says
     * honestly where the output will appear: at close.
     */
    private fun laneCounts(lane: ToolLive): String = when {
        lane.added != null || lane.removed != null -> " · 🟢 +${lane.added ?: 0} · 🔴 −${lane.removed ?: 0}"
        lane.lines > 0 -> " · ${lane.lines} líneas"
        else -> " · salida al cerrar"
    }

    /** One closed lane's verdict line: the edit's diff, the output's lines. */
    private fun stampLine(lane: ToolLive, verbs: ToolVerbifier): String {
        val mark = when (lane.status) {
            "failed" -> " ❌"
            "denied", "stopped" -> " ⏹️"
            else -> ""
        }
        val verdict = when {
            lane.added != null || lane.removed != null -> "🟢 +${lane.added ?: 0} · 🔴 −${lane.removed ?: 0}"
            lane.outputLines != null -> "${lane.outputLines} líneas de salida"
            else -> PyText.esc(lane.status ?: "listo")
        }
        return "${verbs.of(lane.name, lane.input, lane.isMcp).emoji} ${PyText.esc(lane.name)}: $verdict$mark"
    }

    /** The phase's line: verb + detail + honest counters + the staleness word.
     *  The timer runs on [clockNow] — the frozen clock, never the present. */
    @Suppress("CyclomaticComplexMethod") // one shape per verb family
    private fun verbLine(
        verb: ToolVerb,
        lane: ToolLive?,
        phase: TurnPhase.Writing,
        clockNow: Long,
        silence: Long,
        channelAlive: Boolean,
    ): String {
        val clipped = PyText.preview(verb.detail, DETAIL_CP)
        val detail = if (clipped.isNotEmpty()) " <code>${PyText.esc(clipped)}</code>" else ""
        val stale = stalenessWord(silence, channelAlive)
        val timer = fmtDur(clockNow - phase.sinceMs)
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
     * The CoT's LIVE preview: the tail as a quote block while the model
     * streams (what is being thought RIGHT NOW). A tapped block's reading
     * replaces it (one surface at a time — the budget is the block's). The
     * tail is a window, never the reading: the blocks' buttons carry that.
     */
    @Suppress("ReturnCount") // one shape per preview state: not-thinking, expanded, folded
    internal fun cotBlock(state: TurnLive): String? {
        if (state.phase !is TurnPhase.Thinking || state.cotTail.isEmpty()) return null
        if (state.cotExpandedBlock != null) return null
        return "<blockquote>${PyText.esc(takeLastCodePoints(state.cotTail, COT_PREVIEW_CP))}</blockquote>"
    }

    /**
     * The TAPPED block opens WHOLE (the owner reads the COMPLETE CoT, the
     * Python bot's F43c in-place edit reborn as the card's own edit): its
     * real streamed text, htmlClip'd only when esc() would inflate it past
     * the message law (the audit-20/10 lesson) — and the cut says so.
     * Out-of-range pointers render nothing (a stale tap after a resync
     * folds silently — the honest no-op).
     */
    @Suppress("ReturnCount") // one guard per early exit: no pointer, stale pointer, the whole block
    internal fun expandedBlockSection(state: TurnLive): String? {
        val n = state.cotExpandedBlock ?: return null
        val text = state.cotBlocks.getOrNull(n - 1) ?: return null
        val clipped = PyText.htmlClip(text, COT_EXPANDED_CP)
        val cut = when {
            PyText.cpLength(PyText.esc(text)) > COT_EXPANDED_CP ->
                "\n<i>… (bloque de ${PyText.cpLength(text)} chars, recortado para el presupuesto de Telegram)</i>"
            else -> ""
        }
        return "📖 <b>CoT $n</b> · ${PyText.cpLength(text)} chars\n" +
            "<blockquote>${PyText.esc(clipped)}</blockquote>$cut"
    }

    /**
     * The honest cap's note: the keyboard holds the most recent
     * [Keyboards.COT_BUTTONS_CAP] blocks; the older ones travel with the
     * close's shower ([overflowShower]) — the render says so, never
     * silently.
     */
    @Suppress("ReturnCount") // one shape per note state: absent, dropped
    internal fun cotOverflowNote(state: TurnLive): String? {
        val dropped = state.cotBlocks.size - Keyboards.COT_BUTTONS_CAP
        if (dropped <= 0) return null
        val plural = if (dropped == 1) "" else "s"
        return "🧠 hay $dropped bloque$plural CoT fuera del teclado — viajan con el cierre del turno"
    }

    /** The card's keyboard: one «📖 CoT N» per sealed block, the expanded one folded by the same tap. */
    fun cotMarkup(state: TurnLive): kotlinx.serialization.json.JsonObject? =
        Keyboards.cotBlocksButtons(state.cotBlocks.size, state.cotExpandedBlock)

    /**
     * The keyboard-dropped blocks' shower text — the promise
     * [cotOverflowNote] makes: blocks beyond the keyboard cap travel with
     * the close, chunked under the message law, the REAL streamed text
     * (never summarized). Empty when the keyboard held everything.
     */
    fun overflowShower(state: TurnLive): List<String> {
        val dropped = state.cotBlocks.size - Keyboards.COT_BUTTONS_CAP
        if (dropped <= 0) return emptyList()
        val joined = state.cotBlocks.take(dropped).joinToString("\n\n")
        val chunks = mutableListOf<String>()
        var rest = joined
        while (rest.isNotEmpty()) {
            val chunk = PyText.take(rest, COT_SHOWER_CHUNK_CP)
            chunks.add(chunk)
            rest = rest.substring(chunk.length)
        }
        return chunks.mapIndexed { i, chunk ->
            val head = if (i == 0) "🧠 <b>CoT</b> — los primeros $dropped bloque" +
                (if (dropped == 1) "" else "s") + " (fuera del teclado en vivo):\n" else ""
            head + PyText.esc(chunk)
        }
    }

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

    /**
     * True when the card still wears the fallback title — the sid's tail
     * («644150daf013», the owner's 30/10 verdict): the stream's
     * session_info_update title never arrives for some sessions, so the
     * history's tasks-index is resolved instead ([LivePublisher]).
     */
    fun isFallbackTitle(taskId: String, title: String): Boolean = title == taskId.takeLast(TITLE_TAIL)

    /** The seal of the status message at Done — one line, the record of the turn. */
    fun sealText(state: TurnLive, nowMs: Long): String {
        val phase = state.phase as? TurnPhase.Done ?: return render(state, nowMs, 0, true)
        val total = state.startedAtMs?.let { fmtDur(nowMs - it) } ?: "?"
        val error = state.typedError?.let { "\n💀 ${it.line()}" + if (it.isQuota) " · 💳 CUOTA" else "" } ?: ""
        val blocks = when {
            state.cotBlocks.isEmpty() -> ""
            else -> {
                val n = state.cotBlocks.size
                " · $n bloque${if (n == 1) "" else "s"} CoT"
            }
        }
        return "✅ <b>«${PyText.esc(state.title)}»</b> — ${PyText.esc(phase.stopReason)} in $total · " +
            "🧠 ${state.thoughtChars} chars · 🔧 ${state.doneTools} tools" +
            blocks +
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

    private const val LANE_OPEN_CAP = 5
    private const val LANE_STAMP_CAP = 2
    private const val DETAIL_CP = 60

    /**
     * A plain lane with no event for this long renders FROZEN — its clock
     * stops (the doctrine) and the line dims. 10 minutes: a pending
     * elicitation or a slow legit run stays live well under it; the
     * owner's corpse («Bash · 0 líneas · 43m23s») is far past it.
     */
    const val LANE_FROZEN_MS = 600_000L

    /**
     * The dispatch tool names (the channel-side law — the same set the
     * /tasks keyboard filters by): closing one folds its inner lanes away.
     */
    private val DISPATCH_TOOLS = setOf("Task", "Agent")

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
