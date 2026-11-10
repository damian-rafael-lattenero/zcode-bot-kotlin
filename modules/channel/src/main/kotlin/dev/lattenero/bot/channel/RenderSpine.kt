package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kap.Backpressure
import kap.Kap
import kap.KapStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.serialization.json.JsonObject

/**
 * U5a's EVENT SPINE (docs/u5-render-design.md, law 1): the channel's
 * `ServerFrame` stream classified into the render's OWN vocabulary — a
 * sealed [RenderEvent] ADT — and folded by a pure reducer into the
 * [RenderState]. The imperative fold this replaces lived as a mutable
 * cell mutated by frame callbacks ([LiveHub] before U5a); the spine makes
 * the state a VALUE: every event in, one new state out, no mutation
 * anywhere but the fold's own step.
 *
 * The classifier ([classify], wrapped by [mapKap]) and the reducer are
 * PURE: no clock of their own (`nowMs` is injected — the events carry no
 * clock), no effects, no I/O. The socket stays [ChannelClient]'s, the
 * phone stays [LivePublisher]'s; this file is the thing in between.
 */
sealed interface RenderEvent {

    /**
     * A frame-carried event: the fork's seq and BOTH clocks ride along.
     * `atMs` is the fork's own stamp (0 = absent — the receive clock wins,
     * the honest fallback the reducer has always used); `nowMs` is the
     * host's receive clock (the events carry no clock of their own).
     */
    sealed interface FromFrame : RenderEvent {
        val taskId: String
        val seq: Long
        val atMs: Long
        val nowMs: Long
    }

    /**
     * `task_run_started` — a fresh turn's first signal. [startedAtMs] is the
     * PAYLOAD's own stamp (zcode's task start, epoch ms; 0 = absent — the
     * delivery clock keeps its fallback): the header's time is zcode's,
     * mirrored, never the bus's delivery moment.
     */
    data class Started(
        override val taskId: String,
        override val seq: Long,
        override val atMs: Long,
        override val nowMs: Long,
        val startedAtMs: Long = 0,
    ) : FromFrame

    /**
     * `agent_thought_chunk` — a CoT char chunk. THE STAGE THAT CONFLATES
     * (the design's law 3: only the latest matters; the fold's counter
     * says so) — one delta of the thinking stream, main lane or subagent
     * lane by [parentToolUseId].
     */
    data class Thought(
        override val taskId: String,
        override val seq: Long,
        override val atMs: Long,
        override val nowMs: Long,
        val delta: String,
        val parentToolUseId: String?,
    ) : FromFrame

    /** `agent_message_chunk` — the answer's own char stage. */
    data class Answering(
        override val taskId: String,
        override val seq: Long,
        override val atMs: Long,
        override val nowMs: Long,
        val delta: String,
    ) : FromFrame

    /**
     * A tool lane opens or its input streams: the final `tool_call` (the
     * authority input) or the `tool_call_update`/pending partial. EXACT
     * stage — a line counter that drops is a lie.
     */
    data class ToolOpen(
        override val taskId: String,
        override val seq: Long,
        override val atMs: Long,
        override val nowMs: Long,
        val tool: ToolCall,
        val final: Boolean,
    ) : FromFrame

    /** `tool_call_update` with a terminal status — the lane's verdict. EXACT stage. */
    data class ToolClose(
        override val taskId: String,
        override val seq: Long,
        override val atMs: Long,
        override val nowMs: Long,
        val tool: ToolCall,
    ) : FromFrame

    /**
     * `tool_call_update` with `status:"in_progress"` — the fork's OWN
     * «this action is alive» signal (v2 §3, folded, never dropped to the
     * additive Frame anymore). Touches the lane's clock and restores the
     * Writing phase on the lane's OWN anchor (the reducer's law); the
     * ToolCall rides so a never-opened lane still gets its real name.
     */
    data class ToolRunning(
        override val taskId: String,
        override val seq: Long,
        override val atMs: Long,
        override val nowMs: Long,
        val tool: ToolCall,
    ) : FromFrame

    /**
     * `task_complete` / `task_error` — THE SEAL (the design's `Seal`): the
     * turn's terminal, the typed error riding when the fork sent one. The
     * 🔔 notification and the `bot_turns_sealed_*` metric hang off this
     * event's fold.
     */
    data class Sealed(
        override val taskId: String,
        override val seq: Long,
        override val atMs: Long,
        override val nowMs: Long,
        val stopReason: String,
        val usageTokens: Long?,
        val error: TypedErrorPill?,
    ) : FromFrame

    /** `permission_request` / `elicitation_request` — the turn blocks on the owner. */
    data class InteractionAsked(
        override val taskId: String,
        override val seq: Long,
        override val atMs: Long,
        override val nowMs: Long,
        val kind: String,
        val title: String?,
    ) : FromFrame

    /** The interaction's own response — the 🔐/❓ lift. */
    data class InteractionResolved(
        override val taskId: String,
        override val seq: Long,
        override val atMs: Long,
        override val nowMs: Long,
    ) : FromFrame

    /**
     * `session_info_update` — the fork's pill patch (absent = untouched,
     * null = clear) and the turn's REAL title when the session reports one.
     */
    data class SessionInfo(
        override val taskId: String,
        override val seq: Long,
        override val atMs: Long,
        override val nowMs: Long,
        val title: String?,
        val retry: ApiRetryText.ApiRetryPatch,
    ) : FromFrame

    /** `task_network_debug_status` — the raw status's own pill patch. */
    data class NetworkDebug(
        override val taskId: String,
        override val seq: Long,
        override val atMs: Long,
        override val nowMs: Long,
        val retry: ApiRetryText.ApiRetryPatch,
    ) : FromFrame

    /**
     * THE ADDITIVE FRAME (the design's `Frame*`): a stream event the fold
     * folds for FRESHNESS only — `usage_update`, the union's unknown types,
     * an in-progress tool update. Nothing visible changes; the card's age
     * does (silence is data, and this frame is signal).
     */
    data class Frame(
        override val taskId: String,
        override val seq: Long,
        override val atMs: Long,
        override val nowMs: Long,
        val type: String?,
    ) : FromFrame

    /**
     * THE STALENESS TICK (the design's law 2): the ticker's event, folded
     * like any other — the card's age is STATE, not a side channel. It
     * moves NOTHING the frames own (lastSeq/lastEventAtMs never move on a
     * tick: the ticker proves nothing about the model).
     */
    data class Tick(val nowMs: Long) : RenderEvent

    /** The REAL title, resolved OUTSIDE the stream (the history's tasks-index). */
    data class Retitle(val taskId: String, val title: String) : RenderEvent

    /** A reconnect's resync — the lost events stay lost, the state re-baselines. */
    data class Resync(val taskId: String) : RenderEvent

    /**
     * THE WATCHDOG'S RE-ARM (10/11): the stream watchdog re-subscribed a
     * live turn and the fork's subscribe burst re-states the in-flight
     * streamed content — the live answer windows die BEFORE the burst
     * lands, or the re-stated deltas double onto the retained text (the
     * duplicated seams the owner read: «MiMi edición… La elimino:ino:»).
     * Everything else stands: the phase is true (a turn IS running), the
     * lanes and the sealed blocks are real, the spent prose is history.
     */
    data class StreamRebase(val taskId: String) : RenderEvent

    /**
     * The stream heartbeat's seq accounting — NOTHING else moves. The
     * keepalive consumes the connection's seq counter (the fork's own law)
     * and proves the BUS, never the model.
     */
    data class Keepalive(val taskId: String, val seq: Long) : RenderEvent

    /** A sa: attach — the card speaks ONLY that subagent's stream from here. */
    data class ScopeLane(val laneId: String) : RenderEvent

    /** Back to the whole task's stream (a t: attach clears any lane scope). */
    data object WatchTask : RenderEvent

    /**
     * THE ATTACH'S INSTANT SWITCH (4/11): a `t:` tap points the watched turn
     * at the tapped task NOW — an idle task never receives a frame, so
     * without this the fold (and the 🎯 mark that reads it) stayed on the
     * previous turn until its next event. The fold builds the same fresh seed
     * a foreign task's first frame would. [nowMs] stamps the seed's clock
     * (the 5/11 blank card: an unstamped seed read `now - 0` and the
     * watchdog barked at 1.79 billion seconds of «silence») and arms the
     * ATTACH'S AWAIT — an idle session's honest card, born silent no more.
     */
    data class SwitchTask(val taskId: String, val nowMs: Long = 0L) : RenderEvent

    /**
     * A COLLAPSED OUTPUT BOX was born (U5b): the feed spoke the collapsed
     * header with its one 📜 button and the send FLEW — the born fact (the
     * message id included) folds here. The event is the FACT, not the
     * request: a send that never flew folds nothing (there is no message
     * to edit, the tap on it is an honest miss).
     */
    data class BoxBorn(
        val taskId: String,
        val boxId: Int,
        val header: String,
        val pages: List<String>,
        val messageId: Long,
        /** The pages' fence language (v2 §5) — `bash` for a shell's output. */
        val language: String? = null,
    ) : RenderEvent

    /**
     * THE PAGER'S TAP (U5b): a `bp:`/`bf:` button's edit LANDED — the box's
     * message shows page [page] now (the fold-back is [BoxLive.FOLDED]) —
     * and the fold mirrors it. The event is the fact, not the request: a
     * throttled edit folds nothing (the message keeps its page, the state
     * keeps its truth). The tap rides the existing callback dispatch (the
     * 64-byte wire law untouched: `bp:<id>:<page>`).
     */
    data class Page(val boxId: Int, val page: Int) : RenderEvent

    /** The turn is spent (the seal's cleanup, a ☢️ nuclear): the fold's reset. */
    data object Clear : RenderEvent
}

/**
 * One tool frame's extraction — what ONE frame carries about a lane (the
 * merge with the lane's existing state is the REDUCER's law, which is where
 * the `?: existing` fallbacks live; the classifier invents nothing).
 */
data class ToolCall(
    val toolId: String?,
    /** The frame's own name; null = it did not name the tool (the lane keeps its own). */
    val name: String? = null,
    val title: String? = null,
    val parentToolUseId: String? = null,
    val subagentAgentId: String? = null,
    val input: JsonObject? = null,
    /** Lines written so far per THIS frame's input (content/new_string); 0 when nothing streamed. */
    val streamedLines: Int,
    /** raw.streamingRawInputLength — the chars the model actually emitted; null = absent. */
    val rawChars: Int? = null,
    val isMcp: Boolean,
    /** The close's terminal status (completed/failed/denied/stopped); null on an open. */
    val status: String? = null,
    /** The close's output in lines — string content only; null = structured payload invents no count. */
    val outputLines: Int? = null,
    /** The close's output VERBATIM (the result update's `content`); null = none/structured. */
    val output: String? = null,
)

/**
 * THE PURE CLASSIFIER (the design's law 1): one channel frame → its
 * [RenderEvent], or null when the frame is not render material at all
 * (hello/pong/acks — the additive rule says the reader ignores what it
 * does not understand). Extracts EXACTLY what the fold reads — the same
 * readers [StreamEvents] has always used — and nothing more.
 */
// one branch per wire kind — the union IS the shape (the reducer's own table)
fun classify(frame: ServerFrame, nowMs: Long): RenderEvent? = when (frame) {
    is ServerFrame.KeepAlive -> RenderEvent.Keepalive(frame.taskId, frame.seq)

    is ServerFrame.StreamEvent -> classifyFrame(frame, nowMs)

    else -> null // hello/pong/acks: not render material — the additive rule's own drop
}

/**
 * The stream event's own classification — TOTAL (a StreamEvent is always
 * render material, even if only for freshness). This is the fold-edge's
 * entry: [LiveTurn.reduce] classifies through here.
 */
fun classifyFrame(frame: ServerFrame.StreamEvent, nowMs: Long): RenderEvent = classifyStream(frame, nowMs)

// one branch per wire type — the union IS the shape (the reducer's own table)
@Suppress("CyclomaticComplexMethod")
private fun classifyStream(frame: ServerFrame.StreamEvent, nowMs: Long): RenderEvent {
    val event = frame.event
    val taskId = frame.taskId
    val seq = frame.seq
    val atMs = frame.at
    return when (StreamEvents.type(event)) {
        StreamEvents.RUN_STARTED -> RenderEvent.Started(taskId, seq, atMs, nowMs, StreamEvents.startedAt(event))

        StreamEvents.THOUGHT -> RenderEvent.Thought(
            taskId = taskId,
            seq = seq,
            atMs = atMs,
            nowMs = nowMs,
            delta = StreamEvents.content(event),
            parentToolUseId = StreamEvents.parentToolUseId(event),
        )

        StreamEvents.MESSAGE -> RenderEvent.Answering(taskId, seq, atMs, nowMs, StreamEvents.content(event))

        StreamEvents.TOOL_CALL, StreamEvents.TOOL_UPDATE -> classifyTool(frame, nowMs)

        StreamEvents.COMPLETE, StreamEvents.ERROR -> classifyTerminal(frame, nowMs)

        StreamEvents.PERMISSION -> RenderEvent.InteractionAsked(
            taskId,
            seq,
            atMs,
            nowMs,
            kind = "permission",
            title = StreamEvents.title(event),
        )

        StreamEvents.ELICITATION -> RenderEvent.InteractionAsked(
            taskId,
            seq,
            atMs,
            nowMs,
            kind = "elicitation",
            title = StreamEvents.title(event),
        )

        StreamEvents.PERMISSION_RESPONSE, StreamEvents.ELICITATION_RESPONSE ->
            RenderEvent.InteractionResolved(taskId, seq, atMs, nowMs)

        StreamEvents.SESSION_INFO -> RenderEvent.SessionInfo(
            taskId = taskId,
            seq = seq,
            atMs = atMs,
            nowMs = nowMs,
            title = event.str("title")?.takeIf { it.isNotEmpty() },
            retry = ApiRetryText.fromPatch(event),
        )

        StreamEvents.NETWORK_DEBUG -> RenderEvent.NetworkDebug(
            taskId = taskId,
            seq = seq,
            atMs = atMs,
            nowMs = nowMs,
            retry = ApiRetryText.fromNetworkDebug(event, atMs.takeIf { it > 0 } ?: nowMs),
        )

        else -> RenderEvent.Frame(taskId, seq, atMs, nowMs, StreamEvents.type(event))
    }
}

/** The tool ladder: the open/stream/close shapes of `tool_call` + `tool_call_update`. */
private fun classifyTool(frame: ServerFrame.StreamEvent, nowMs: Long): RenderEvent {
    val taskId = frame.taskId
    val seq = frame.seq
    val atMs = frame.at
    val type = StreamEvents.type(frame.event)
    return when (type) {
        StreamEvents.TOOL_CALL ->
            RenderEvent.ToolOpen(taskId, seq, atMs, nowMs, openToolCall(frame.event), final = true)

        StreamEvents.TOOL_UPDATE -> when (StreamEvents.status(frame.event)) {
            "pending" -> RenderEvent.ToolOpen(taskId, seq, atMs, nowMs, openToolCall(frame.event), final = false)

            // the fork's own «the action is alive» — a REAL fold, never the
            // additive frame (v2 §3): the lane's clock and the phase move
            "in_progress" -> RenderEvent.ToolRunning(taskId, seq, atMs, nowMs, openToolCall(frame.event))

            "completed", "failed", "denied", "stopped" ->
                RenderEvent.ToolClose(taskId, seq, atMs, nowMs, closeToolCall(frame.event))

            else -> RenderEvent.Frame(taskId, seq, atMs, nowMs, type)
        }

        else -> RenderEvent.Frame(taskId, seq, atMs, nowMs, type)
    }
}

/** The turn's terminal: the clean seal's stop reason + usage, the error's typed pill. */
private fun classifyTerminal(frame: ServerFrame.StreamEvent, nowMs: Long): RenderEvent {
    val event = frame.event
    return when (StreamEvents.type(event)) {
        StreamEvents.COMPLETE -> RenderEvent.Sealed(
            taskId = frame.taskId,
            seq = frame.seq,
            atMs = frame.at,
            nowMs = nowMs,
            stopReason = StreamEvents.stopReason(event),
            usageTokens = StreamEvents.usageTokens(event),
            error = null,
        )

        else -> {
            val pill = StreamEvents.typedErrorOf(event)
            RenderEvent.Sealed(
                frame.taskId,
                frame.seq,
                frame.at,
                nowMs,
                stopReason = pill.error,
                usageTokens = null,
                error = pill,
            )
        }
    }
}

/** The OPEN side's extraction (a final `tool_call` or a pending update). */
private fun openToolCall(event: JsonObject): ToolCall {
    val name = event.str("toolName")
    return ToolCall(
        toolId = StreamEvents.toolId(event),
        name = name,
        title = StreamEvents.title(event),
        parentToolUseId = StreamEvents.parentToolUseId(event),
        subagentAgentId = StreamEvents.subagentAgentId(event),
        input = StreamEvents.input(event),
        streamedLines = StreamEvents.writtenLines(event),
        rawChars = StreamEvents.streamingRawInputLength(event),
        // the frame's own markers only — the lane's existing isMcp wins in the fold
        isMcp = StreamEvents.isMcp(event, name ?: ""),
    )
}

/** The CLOSE side's extraction (the terminal `tool_call_update`). */
private fun closeToolCall(event: JsonObject): ToolCall = ToolCall(
    toolId = StreamEvents.toolId(event),
    name = event.str("toolName"),
    title = null,
    parentToolUseId = null,
    subagentAgentId = null,
    input = StreamEvents.input(event),
    streamedLines = 0,
    rawChars = null,
    isMcp = false,
    status = StreamEvents.status(event),
    outputLines = StreamEvents.outputLines(event),
    output = StreamEvents.outputText(event),
)

/**
 * THE SPINE'S HEAD (the design's law 1, verbatim shape): the channel's
 * `ServerFrame` stream lifted into KapStream and classified by the pure
 * [classify] through the stream's OWN mapKap stage. The classifier's null
 * (not render material) leaves the spine here — the additive rule's drop.
 *
 * `frames` must be multicast-safe (a SharedFlow — the channel client's
 * stream is one): the spine's stages below each collect it.
 */
fun mapKap(frames: Flow<ServerFrame>, nowMs: () -> Long): KapStream<RenderEvent> {
    val classified = KapStream.from(frames)
        // EXACT at this stage by definition: classify is pure and total —
        // a frame's event exists before the stage is even named.
        .mapKap { f -> Kap { classify(f, nowMs()) } }
    return KapStream.from(classified.asFlow().mapNotNull { it })
}

/** The spine's first named stage: the classified stream with slack, exact. */
internal fun KapStream<RenderEvent>.withStageSlack(slots: Int = SPINE_SLOTS): KapStream<RenderEvent> =
    withBackpressure(Backpressure.Buffer(slots))

/** The spine's default slack — bursts ride, the exact law never bends. */
internal const val SPINE_SLOTS = 64


// ---- the spine's composition -----------------------------------------------

/**
 * THE SPINE, END TO END (the design's laws 1–3): the channel's frames →
 * [mapKap] → the named backpressure stages → the pure fold's
 * `scan(RenderState.empty, ::reduce)` → the states, one per event.
 *
 * THE STAGES — backpressure named per stage, the honesty law:
 *
 * - **THE CoT CHAR STAGE — `conflate()`.** What may drop: an older
 *   buffered CoT delta when the fold lags behind a thinking burst — only
 *   the latest matters; the fold's char counter says so (it becomes a
 *   lower bound). What never: a tool line, a seal, a pill, a tick — they
 *   ride the exact lane.
 * - **THE EXACT LANE — [withStageSlack].** Tool-input lines EXACT: a line
 *   counter that drops is a lie. The lane has slack for bursts and the
 *   producer suspends when it fills — backpressure flows upstream, and
 *   the verdicts (the +N/−M, the outputs, the 🔔's facts) lose NOTHING.
 * - **THE STALENESS TICKER — `dropOldest(1)`.** An old tick is worthless:
 *   only the freshest survives a backlog, and a tick drops no truth (it
 *   moves no event clock).
 *
 * The two event lanes merge by readiness; within one burst the phase may
 * reflect a tool line one event late and converges at the next event (the
 * exact lane's OWN order and completeness are the upstream's, always).
 * `frames` must be multicast-safe (a SharedFlow): each lane collects it,
 * and the classifier is pure so a re-run is the same event.
 */
fun spine(
    frames: Flow<ServerFrame>,
    ticker: Flow<Long>,
    nowMs: () -> Long,
    differ: LineDiffer = SimpleLineDiffer,
): Flow<RenderState?> {
    val events = mapKap(frames, nowMs)
    val thoughts = events
        .filterKap { Kap { it is RenderEvent.Thought } }
        .conflate() // THE CoT CHAR STAGE — the conflating lane
    val exact = events
        .filterKap { Kap { it !is RenderEvent.Thought } }
        .withStageSlack() // THE EXACT LANE — tool lines, seals, pills: nothing drops
    val ticks = KapStream.from(ticker)
        .mapKap { ms -> Kap { RenderEvent.Tick(ms) } }
        .dropOldest(1) // THE STALENESS TICKER — the freshest tick is the only one
    return KapStream.from(merge(thoughts.asFlow(), exact.asFlow(), ticks.asFlow()))
        .let { merged ->
            KapStream.from(
                merged.asFlow()
                    // the scan's seed emission (the unborn state) is NOT an
                    // event's state — the consumers' contract is ONE STATE
                    // PER EVENT; the seed is the accumulator, never output
                    .scan<RenderEvent, RenderState?>(RenderState.empty) { acc, e -> reduce(acc, e, differ) }
                    .drop(1),
            )
        }
        .asFlow()
}
