package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.JsonObject

// ---- the pure fold ----------------------------------------------------------

/**
 * THE FOLD'S STATE — the whole visible turn. The card's own name is
 * [TurnLive]; the spine's name for it is [RenderState]: one type, the two
 * vocabularies of the design (the state IS the render's input, byte for
 * byte — U5a's zero-visual-diff law).
 */
typealias RenderState = TurnLive

/**
 * THE PURE FOLD (the design's law 2): `RenderState =
 * events.scan(RenderState.empty, ::reduce)`. One [RenderEvent] in, one new
 * state out — NO mutation, NO clock, NO I/O. The reducer absorbs everything
 * the imperative fold did — the phases, the CoT blocks, the tool lanes, the
 * counters, the pills — and, since U5a, the staleness tick (the card's age
 * is state, not a side channel) and the hub's own verbs (retitle / resync /
 * keepalive / lane scope), which are events like any other now.
 *
 * The [differ] computes an edit's +N/−M (the daemon passes the
 * SequenceMatcher port); the seq gap check stays the EDGE's (the wiring's
 * feedAndResync logs it and folds a [RenderEvent.Resync]).
 */
// one branch per event — the ADT IS the shape (BusEvents precedent)
@Suppress("CyclomaticComplexMethod", "LongMethod")
fun reduce(
    state: RenderState?,
    event: RenderEvent,
    differ: LineDiffer = SimpleLineDiffer,
): RenderState? = when (event) {
    is RenderEvent.FromFrame -> reduceFrame(state, event, differ)

    is RenderEvent.Tick ->
        // THE STALENESS TICK (law 2): the card's age folds into the state —
        // and moves NOTHING the frames own (the ticker proves nothing about
        // the model; lastEventAtMs never moves on a tick).
        state?.copy(staleMs = event.nowMs - (state.lastEventAtMs ?: 0L))

    // the task-scoped commands IGNORE a foreign task (the old hub's law: the
    // watched turn's state stays, never a null that would spend the card)
    is RenderEvent.Retitle -> when {
        state?.taskId == event.taskId -> state.copy(title = event.title)
        else -> state
    }

    is RenderEvent.Resync -> when {
        // the resync re-baselines the LIVE windows (the tail dies) but the
        // sealed blocks are complete real text — a gap cuts the seam, never
        // a sealed box (and the feed already spoke them)
        state?.taskId == event.taskId -> state.copy(
            cotTail = "",
            cotPending = "",
            phase = TurnPhase.Idle,
        )

        else -> state
    }

    is RenderEvent.Keepalive -> when {
        // THE HEARTBEAT'S SEQ ACCOUNTING: the keepalive consumes the
        // connection's counter; lastEventAtMs NEVER moves (the heartbeat
        // proves the BUS, not the model — a quiet model stays honestly quiet)
        state?.taskId != event.taskId -> state
        state.lastSeq == null || event.seq <= state.lastSeq -> state
        else -> state.copy(lastSeq = event.seq)
    }

    is RenderEvent.ScopeLane -> state?.copy(watchedLane = event.laneId)

    RenderEvent.WatchTask -> state?.copy(watchedLane = null)

    RenderEvent.Clear -> null // the turn is spent: the fold's own reset
}

/**
 * One frame-carried event folded in. The TASK SWITCH is absorbed here (it
 * lived in the old hub's apply): a new task's event replaces the watched
 * turn — and a task switch is also a lane-switch, the scope dying with the
 * task (the fresh seed carries no watchedLane).
 */
// one branch per frame event — the union IS the shape (the reducer's own table)
@Suppress("CyclomaticComplexMethod")
private fun reduceFrame(state: RenderState?, event: RenderEvent.FromFrame, differ: LineDiffer): RenderState {
    val base = when {
        state == null || state.taskId != event.taskId -> RenderState.empty.copy(
            taskId = event.taskId,
            title = event.taskId.takeLast(LiveTurn.TITLE_TAIL),
            startedAtMs = if (event.atMs > 0) event.atMs else event.nowMs,
        )

        else -> state
    }
    val refreshed = base.copy(
        lastSeq = event.seq,
        lastEventAtMs = if (event.atMs > 0) event.atMs else event.nowMs,
    )
    return when (event) {
        is RenderEvent.Started -> refreshed.copy(startedAtMs = event.atMs.takeIf { it > 0 } ?: event.nowMs)

        is RenderEvent.Thought -> reduceThought(refreshed, event)

        is RenderEvent.Answering -> reduceAnswer(refreshed, event)

        is RenderEvent.ToolOpen -> reduceToolArrival(refreshed, event, differ)

        is RenderEvent.ToolClose -> reduceToolClose(refreshed, event, differ)

        is RenderEvent.Sealed -> refreshed.copy(
            phase = TurnPhase.Done(stopReason = event.stopReason, atMs = event.nowMs),
            usageTokens = event.usageTokens ?: refreshed.usageTokens,
            interaction = null,
            // a clean complete keeps an earlier pill untouched; the error's own seal sets it
            typedError = event.error ?: refreshed.typedError,
        )

        is RenderEvent.InteractionAsked -> refreshed.copy(
            interaction = InteractionPending(kind = event.kind, title = event.title),
        )

        is RenderEvent.InteractionResolved -> refreshed.copy(interaction = null)

        is RenderEvent.SessionInfo -> reduceSessionInfo(refreshed, event)

        is RenderEvent.NetworkDebug -> when (val patch = event.retry) {
            ApiRetryText.ApiRetryPatch.Untouched -> refreshed
            ApiRetryText.ApiRetryPatch.Clear -> refreshed.copy(apiRetry = null)
            is ApiRetryText.ApiRetryPatch.Set -> refreshed.copy(apiRetry = patch.pill)
        }

        is RenderEvent.Frame -> refreshed // usage_update and the rest of the union: additive rule
    }
}

/** agent_thought_chunk: main agent grows the tail; subagents grow their own lane —
 *  EXCEPT on a lane-scoped card, where the watched lane's thoughts ARE the
 *  main narration (subagent implements task: the identical UI). */
private fun reduceThought(state: RenderState, event: RenderEvent.Thought): RenderState {
    val delta = event.delta
    val parent = event.parentToolUseId
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
        while (PyText.cpLength(pending) >= LiveTurn.COT_BLOCK_CP) {
            val cut = pending.offsetByCodePoints(0, LiveTurn.COT_BLOCK_CP)
            blocks = blocks + pending.substring(0, cut)
            pending = pending.substring(cut)
        }
        state.copy(
            thoughtChars = chars,
            cotBlocks = blocks,
            cotPending = pending,
            cotTail = LiveTurn.takeLastCodePoints(state.cotTail + delta, LiveTurn.COT_TAIL_CP),
            phase = TurnPhase.Thinking(chars = chars, sinceMs = phaseSince(state.phase, state, event.nowMs)),
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

/** agent_message_chunk: the answer's chars grow; real progress clears the retry pill. */
private fun reduceAnswer(state: RenderState, event: RenderEvent.Answering): RenderState {
    val chars = state.answerChars + event.delta.length
    return state.copy(
        answerChars = chars,
        phase = when (val phase = state.phase) {
            is TurnPhase.Done -> phase
            else -> TurnPhase.Writing(
                tool = null,
                toolId = null,
                lines = 0,
                rawChars = chars,
                sinceMs = phaseSince(phase, state, event.nowMs),
            )
        },
        apiRetry = if (event.delta.isNotEmpty()) null else state.apiRetry,
    )
}

/**
 * tool_call (final, input complete) opens-or-fills the lane with the
 * authority input; tool_call_update/pending streams the partial input
 * (lines from content/new_string, raw chars from raw.streamingRawInputLength).
 * The +N/−M breathe: the differ runs per delta on the growing old/new
 * pair; the close recomputes it on the complete input (the authority).
 */
@Suppress("CyclomaticComplexMethod") // the tool lane lifecycle, one guard per field
private fun reduceToolArrival(state: RenderState, event: RenderEvent.ToolOpen, differ: LineDiffer): RenderState {
    val toolId = event.tool.toolId ?: return state
    val existing = state.tools.firstOrNull { it.toolId == toolId }
    val name = event.tool.name ?: existing?.name ?: "tool"
    val streamed = event.tool.streamedLines
    // final tool_call: the input IS the authority. pending update: the
    // partial projection only ever GROWS the count (sampling may stutter)
    val lines = when {
        event.final -> if (streamed > 0 || existing == null) streamed else existing.lines
        streamed > 0 -> streamed
        else -> existing?.lines ?: 0
    }
    val input = event.tool.input ?: existing?.input
    val liveDiff = liveEditDiff(input, differ)
    val lane = ToolLive(
        toolId = toolId,
        name = name,
        title = event.tool.title ?: existing?.title,
        parentToolUseId = event.tool.parentToolUseId ?: existing?.parentToolUseId,
        subagentAgentId = event.tool.subagentAgentId ?: existing?.subagentAgentId,
        openedAtMs = existing?.openedAtMs ?: event.nowMs,
        lines = lines,
        rawChars = event.tool.rawChars ?: existing?.rawChars ?: 0,
        input = input,
        status = existing?.status, // a closed lane does not reopen
        isMcp = existing?.isMcp ?: event.tool.isMcp,
        added = liveDiff?.first,
        removed = liveDiff?.second,
        lastUpdateAtMs = event.nowMs,
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
                sinceMs = existing?.openedAtMs ?: event.nowMs,
            )
        },
    )
}

// the close ladder: one guard per early exit, one branch per close shape — the reducer's own table
@Suppress("ReturnCount", "CyclomaticComplexMethod")
private fun reduceToolClose(state: RenderState, event: RenderEvent.ToolClose, differ: LineDiffer): RenderState {
    val toolId = event.tool.toolId ?: return state
    val existing = state.tools.firstOrNull { it.toolId == toolId } ?: return state
    val diff = liveEditDiff(existing.input ?: event.tool.input, differ)
    val closed = existing.copy(
        status = event.tool.status,
        added = diff?.first,
        removed = diff?.second,
        outputLines = event.tool.outputLines ?: existing.outputLines,
        output = event.tool.output ?: existing.output,
        lastUpdateAtMs = event.nowMs,
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
                atMs = event.nowMs,
            )
            state.phase is TurnPhase.Done -> state.phase
            else -> TurnPhase.Thinking(chars = state.thoughtChars, sinceMs = event.nowMs)
        },
    )
}

/** session_info_update: the REAL title when the session reports one + the pill patch. */
private fun reduceSessionInfo(state: RenderState, event: RenderEvent.SessionInfo): RenderState {
    val titled = event.title?.let { state.copy(title = it) } ?: state
    return when (val patch = event.retry) {
        ApiRetryText.ApiRetryPatch.Untouched -> titled
        ApiRetryText.ApiRetryPatch.Clear -> titled.copy(apiRetry = null)
        is ApiRetryText.ApiRetryPatch.Set -> titled.copy(apiRetry = patch.pill)
    }
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
private fun phaseSince(phase: TurnPhase, state: RenderState, nowMs: Long): Long = when (phase) {
    is TurnPhase.Thinking -> phase.sinceMs
    is TurnPhase.Writing -> phase.sinceMs
    is TurnPhase.Done -> phase.atMs
    TurnPhase.Idle -> state.lastEventAtMs ?: state.startedAtMs ?: nowMs
}

/**
 * The replay guard's floor: only sentence-sized re-emissions count as
 * replays — legitimate small repeats (separators, short tokens) pass.
 */
internal const val REPLAY_GUARD_MIN_CP = 24

/**
 * The dispatch tool names (the channel-side law — the same set the
 * /tasks keyboard filters by): closing one folds its inner lanes away.
 */
private val DISPATCH_TOOLS = setOf("Task", "Agent")
