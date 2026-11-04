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

/**
 * One COLLAPSED tool output's fold state (U5b): the pager's whole world as
 * immutable values — what the folded button knows (the header, the numbered
 * pages), the Telegram message it lives on, and WHICH page is showing. The
 * old registry's vars (its entries and per-box index) retired into here:
 * the taps render FROM the fold and the fold mirrors what the message shows.
 */
data class BoxLive(
    /**
     * The wire id (`bp:<id>:<page>`): turn-unique by construction — the
     * task's hash base + the birth order — so an old turn's button can
     * never alias a new turn's box (the old registry's global seq, without
     * the var).
     */
    val boxId: Int,
    /** The always-visible collapsed header — the fold-back's render. */
    val header: String,
    /** The numbered pages (ToolOutputPages.pages), born once, read-only. */
    val pages: List<String>,
    /** The Telegram message the box lives on — the tap's edit target. */
    val messageId: Long,
    /** The page the message shows: [FOLDED] = the header + its one button. */
    val page: Int = FOLDED,
) {
    companion object {
        /** The folded mark: the message shows its header, no page open. */
        const val FOLDED = -1
    }
}

/**
 * THE PARK'S FOLD STATE (U5c) — the owner's ⏸️ as a VALUE, not a machine
 * living beside the fold: the `bg:` tap folds a [RenderEvent.Park] and the
 * card is parked in the state itself. [atMs] is the tap's own clock (the
 * fact's stamp — the parked card renders NO timers on purpose); [landed] is
 * the ONE final edit's fact («▶️ sigue en background» is already on the
 * message), so a throttled tap retries and a landed one never re-edits.
 *
 * The park lives exactly as long as its state: the seal's [RenderEvent.Clear],
 * a task switch and a ☢️ nuclear take it with the world — a NEW turn for the
 * same task is therefore never parked, by construction, not by cleanup.
 */
data class ParkLive(
    /** The tap's own clock — the fact's stamp, never a rendered timer. */
    val atMs: Long,
    /** The ONE final edit landed: the card's last shape is the parked render. */
    val landed: Boolean = false,
)

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
     * The full accumulation lives here; a block's text is never
     * summarized, never invented. Each block travels as its own COLLAPSED
     * Telegram message (header + button — the owner decides whether to
     * read it, 2/11: «yo decido si los leo o no una vez que se llenó el
     * botón de los 3k»); the card carries the COUNT only.
     */
    val cotBlocks: List<String> = emptyList(),
    /** The block currently filling: seals into [cotBlocks] at the cap. */
    val cotPending: String = "",
    val tools: List<ToolLive> = emptyList(),
    /**
     * THE COLLAPSED OUTPUT BOXES, FOLD STATE (U5b): every collapsed box
     * message the feed spoke this turn, birth-ordered, capped (the reducer
     * keeps the newest [RenderFold.BOX_CAP]). The +N/−M and the output
     * lines ride the lanes above (the SEQUENCE_LINE_DIFFER port's verdict,
     * U5a); the numbered pages and the open page ride HERE.
     */
    val boxes: List<BoxLive> = emptyList(),
    /** Subagent lanes: parentToolUseId → thought chars (the stream is ONE stream). */
    val subagentThought: Map<String, Int> = emptyMap(),
    /**
     * The lane this card is SCOPED to (a sa: attach — subagent implements
     * task: the identical card, fed only that lane's stream plus its root
     * tool's lifecycle). Null = the whole task's stream.
     */
    val watchedLane: String? = null,
    /**
     * THE PARK (U5c, the owner's ⏸️, 3/11): non-null = the card is parked —
     * the publisher stops editing it (no redraw, no feed), the fold keeps
     * consuming the turn, and the seal still runs so the 🔔 carries the
     * conclusion exactly once. Null = the live card's edits flow.
     */
    val parked: ParkLive? = null,
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
    /**
     * THE CARD'S AGE, FOLD-REDUCED (U5a, the design's law 2): the staleness
     * tick's verdict — `tickNow - lastEventAtMs` — carried in the state
     * instead of computed at the edges. The [Tick] event moves NOTHING the
     * frames own: the ticker proves nothing about the model.
     */
    val staleMs: Long? = null,
) {
    companion object {
        /**
         * THE FOLD'S SEED — `RenderState.empty` (docs/u5-render-design.md,
         * law 2): the unborn turn, named by the first frame. The reducer's
         * task-switch law treats any state whose taskId is not the event's
         * as unborn, so the seed flows through the same branch.
         */
        val empty: TurnLive = TurnLive(taskId = "", title = "")
    }
}

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
     * at 3000 CODE POINTS of streamed thinking and the feed speaks it.
     */
    const val COT_BLOCK_CP = 3000

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
     *
     * U5a: THE FOLD LIVES ON THE SPINE — this is the frame edge's
     * convenience (the hub's and the tests' entry point): classify the
     * frame, then one pure step of [RenderSpine.reduce].
     */
    fun reduce(
        state: TurnLive?,
        frame: ServerFrame.StreamEvent,
        nowMs: Long,
        differ: LineDiffer = SimpleLineDiffer,
    ): TurnLive = checkNotNull(reduce(state, classifyFrame(frame, nowMs), differ)) {
        "a frame never clears the turn — the fold's own law"
    }

    // ---- render ------------------------------------------------------------

    /**
     * The status message's TEXT — THE PROCESS (the owner's ley raíz,
     * 2/11: «separar bien el dato del proceso»). The card's shape is the
     * desktop pill's mirror: the WORKING header (how long the turn has
     * run), the NOW line (what is executing RIGHT NOW — the verb, the
     * target, that tool's own elapsed; between tools the thinking line,
     * chars only), then the honest pills, the other lanes, the stamps and
     * the CoT row — and the staleness line, which always speaks. The
     * card is redrawn as the chat's LAST message per event; the DATA
     * (outputs, diffs, blocks' text) travels in the feed below it.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // one branch per phase/lane — the message IS the state machine
    fun render(
        state: TurnLive,
        nowMs: Long,
        channelAlive: Boolean,
        verbs: ToolVerbifier = ToolVerbs.MINIMAL,
        /**
         * The daemon's cached PRIZE line (GiftsMenu.workingLine) — the
         * owner's «pequeña sección premios»: it rides the process card
         * while the turn runs, one line, never the detail (/quota owns
         * that). Null = nothing claimable, or the read is not fresh.
         */
        giftsLine: String? = null,
    ): String {
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
        val lines = mutableListOf<String>()
        when (val phase = state.phase) {
            is TurnPhase.Idle -> lines.add("💻 <b>«${PyText.esc(state.title)}»</b> — suscripto, nada corriendo")

            is TurnPhase.Done -> lines.add(
                "${if (state.failedTools > 0) "⚠️" else "✅"} <b>«${PyText.esc(state.title)}»</b>" +
                    " — listo: ${PyText.esc(phase.stopReason)}",
            )

            is TurnPhase.Thinking -> {
                lines.add(workingHead(state.title, elapsed))
                val stale = stalenessWord(silence, channelAlive)
                // THE PENDING TAIL IS A ONE-LINER (the owner, 2/11: the CoT
                // never spills — he opens the collapsed blocks when he wants)
                lines.add(
                    "🧠 <b>pensando</b> · ${phase.chars} caracteres · ${fmtDur(clockNow - phase.sinceMs)}$stale",
                )
            }

            is TurnPhase.Writing -> {
                lines.add(workingHead(state.title, elapsed))
                if (phase.tool == null) {
                    lines.add(
                        "✍️ <b>escribiendo la respuesta</b> · ${phase.rawChars} caracteres · " +
                            fmtDur(clockNow - phase.sinceMs),
                    )
                } else {
                    val lane = phase.toolId?.let { id -> state.tools.firstOrNull { it.toolId == id } }
                    val verb = verbs.of(lane?.name ?: phase.tool ?: "", lane?.input, lane?.isMcp ?: false)
                    lines.add(verbLine(verb, lane, phase, clockNow, silence, channelAlive))
                }
            }
        }
        // the socket drop is a STATE, not a background detail: the phase line
        // must never pretend a turn is alive while the channel reconnects
        if (!channelAlive && state.phase !is TurnPhase.Done) {
            lines.add("📡 canal caído — reconectando… (última señal hace ${fmtDur(silence)})")
        }
        state.interaction?.let { lines.add("${it.emoji} esperando: ${PyText.esc(it.title ?: it.kind)}") }
        state.typedError?.let { pill ->
            val quota = if (pill.isQuota) " · 💳 CUOTA" else ""
            lines.add("💀 <b>error:</b> ${pill.line()}$quota")
        }
        // THE OWNER'S PRIZE SECTION (3/11): while the turn runs, the card
        // says a prize can be claimed — the WORKING states only (a Done
        // card is a verdict, not a shelf); /quota carries the detail.
        if (state.phase is TurnPhase.Thinking || state.phase is TurnPhase.Writing) {
            giftsLine?.takeIf { it.isNotBlank() }?.let(lines::add)
        }
        // the lanes, BOUNDED (the owner's 30/10 corpse card: a dead
        // subagent's bash lane ticked «43m23s» forever): only the most
        // recent open lanes — the NOW lane already spoke, first under the
        // header. A lane with no event for [LANE_FROZEN_MS] renders FROZEN
        // — dimmed, its clock stopped at its last real event.
        val nowLaneId = (state.phase as? TurnPhase.Writing)?.toolId
        state.tools.asSequence()
            .filter { it.status == null && it.toolId != nowLaneId }
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
        // the fork's own pill — the honest difference between «z.ai down» and «I typed too much»
        state.apiRetry?.let { pill ->
            val suffix = pill.errorStatus?.let { " (HTTP $it)" } ?: ""
            lines.add("⚠️ ${PyText.esc(ApiRetryText.line(pill, nowMs))}$suffix")
        }
        cotRow(state)?.let(lines::add)
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
        return lines.joinToString("\n")
    }

    /** THE WORKING HEADER — the desktop pill's mirror: «⏳ Working · 3m 51s — «título»». */
    private fun workingHead(title: String, elapsed: String): String =
        "⏳ <b>Working · $elapsed</b> — «${PyText.esc(title)}»"

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

    /** The NOW line for a tool: verb + target + the honest counters + the
     *  tool's OWN elapsed (the timer runs on [clockNow] — the frozen
     *  clock, never the present) + the staleness word. */
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
        val counts = when {
            lane != null && (lane.added != null || lane.removed != null) ->
                " · 🟢 +${lane.added ?: 0} · 🔴 −${lane.removed ?: 0}"

            phase.lines > 0 -> " · ${phase.lines} líneas"

            lane != null -> laneCounts(lane) // «salida al cerrar» — the fork streams no live output
            else -> ""
        }
        return "${verb.head()}$detail$counts · $timer$stale"
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
     * The CoT ROW — the card's one-liner for the thinking (the owner,
     * 2/11: «yo decido si los leo o no una vez que se llenó el botón de
     * los 3k»): the sealed blocks' count and the pending fill, CHARS
     * ONLY. The text itself lives in the collapsed block messages below
     * the card (the tap opens each one in place) — the tail never spills.
     */
    internal fun cotRow(state: TurnLive): String? {
        val blocks = state.cotBlocks.size
        val pending = PyText.cpLength(state.cotPending)
        val blocksWord = when {
            blocks == 0 -> null
            blocks == 1 -> "1 bloque plegado"
            else -> "$blocks bloques plegados"
        }
        val pendingWord = if (pending == 0) null else "${fmtK(pending)} en camino"
        return when {
            blocksWord == null && pendingWord == null -> null
            else -> "📖 CoT: " + listOfNotNull(blocksWord, pendingWord).joinToString(" · ")
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

    /**
     * True when the card still wears the fallback title — the sid's tail
     * («644150daf013», the owner's 30/10 verdict): the stream's
     * session_info_update title never arrives for some sessions, so the
     * history's tasks-index is resolved instead ([LivePublisher]).
     */
    fun isFallbackTitle(taskId: String, title: String): Boolean = title == taskId.takeLast(TITLE_TAIL)

    /**
     * The seal of the status message at Done — one line, the record of the
     * turn (the card's LAST shape: the process's one-line summary).
     */
    fun sealText(state: TurnLive, nowMs: Long): String {
        val phase = state.phase as? TurnPhase.Done ?: return render(state, nowMs, true)
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

    /**
     * THE PARKED CARD's render — the owner's ⏸️ (3/11): the process card
     * freezes here («▶️ sigue en background» as its LAST edit) and the
     * honest note names the promise the 🔔 keeps: the 🏁 arrives when the
     * turn seals. No timers on purpose — a parked card receives no more
     * edits, and a frozen «hace 3m» would read as a live clock that died.
     * The 🏁 lands as its own message right below this card, so the pair
     * reads as the promise and its keeping.
     */
    fun parkText(state: TurnLive): String =
        "⏸️ <b>«${PyText.esc(state.title)}»</b> — ▶️ sigue en background\n" +
            "🔔 te aviso al terminar: el 🏁 llega cuando el turno selle"

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

    private const val TEN = 10L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L

    /** The fallback title's length — the sid's tail («644150daf013»). */
    internal const val TITLE_TAIL = 12
    private const val SUBAGENT_ID_TAIL = 6
    private const val K_FROM = 1000
    private const val K_DIVISOR = 1000.0
}
