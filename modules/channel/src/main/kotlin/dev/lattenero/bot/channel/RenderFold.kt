@file:Suppress("TooManyFunctions") // the fold's reducers + the two boundary flushes — one cohesive switch

package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import java.security.MessageDigest
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
        // the resync re-baselines the LIVE windows (the CoT's AND the
        // answer's: the reconnect's catch-up burst re-states the in-flight
        // content, and windows that survived would double it) but the
        // sealed blocks are complete real text — a gap cuts the seam, never
        // a sealed box (and the feed already spoke them). THE SEQ FLOOR
        // RE-ARMS (11/11): the new connection's counter restarts — the
        // first post-resync frame arms the floor, and a re-delivery below
        // it folds nothing (the spent prose keys ride on regardless).
        state?.taskId == event.taskId -> state.copy(
            cotTail = "",
            cotPending = "",
            answerTail = "",
            prosePending = "",
            phase = TurnPhase.Idle,
        )

        else -> state
    }

    is RenderEvent.StreamRebase -> when {
        // THE WATCHDOG'S RE-ARM: the resubscribe's catch-up burst re-states
        // the in-flight streamed content — the live windows die BEFORE it
        // lands (the duplication law, see the event's doc). THE PENDING
        // THOUGHT RIDES THE SAME CUT: one Thought delta writes BOTH the
        // tail and the pending accumulator (reduceThought), so a re-stated
        // delta doubles whichever survives — the tail and the pending go
        // together or not at all. The sealed blocks are complete real text
        // (spoken already); the counters and the lanes stand. THE SEQ FLOOR
        // RE-ARMS with the windows (11/11): the burst must FOLD (it rebuilds
        // them), so the floor waits for the burst's first frame.
        state?.taskId == event.taskId -> state.copy(
            answerTail = "",
            prosePending = "",
            cotTail = "",
            cotPending = "",
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

    is RenderEvent.BoxBorn -> when {
        // the feed speaks only the watched turn's boxes: a foreign task's
        // birth is ignored (the Retitle law — the state stays, never a null
        // that would spend the card)
        state?.taskId == event.taskId -> state.copy(
            boxes = (
                state.boxes.filterNot { it.boxId == event.boxId } + // a reborn id never doubles
                    BoxLive(
                        boxId = event.boxId,
                        header = event.header,
                        pages = event.pages,
                        messageId = event.messageId,
                        language = event.language,
                    )
                ).takeLast(BOX_CAP), // the registry's own ceiling — the newest survive
        )

        else -> state
    }

    is RenderEvent.Page -> state?.let { s ->
        // THE PAGER'S MIRROR (U5b): the page the message actually shows.
        // The clamp is the reducer's own defense too (an out-of-range page
        // can only arrive as the tap edge's already-clamped index — the
        // fold re-clamps rather than trusting the wire).
        s.copy(
            boxes = s.boxes.map { box ->
                if (box.boxId == event.boxId) {
                    box.copy(page = event.page.coerceIn(BoxLive.FOLDED, box.pages.size - 1))
                } else {
                    box
                }
            },
        )
    }

    RenderEvent.Clear -> null // the turn is spent: the fold's own reset

    // THE ATTACH'S INSTANT SWITCH (measured 4/11): a `t:` tap on an IDLE task
    // never receives a frame, so the fold kept watching the previous turn and
    // the 🎯 stayed on the first row forever. The attach's switch is a REAL
    // task switch — the same fresh seed a frame for a foreign task would
    // build — just without waiting for traffic. Re-attaching the current task
    // changes nothing (WatchTask's lane-clear already ran). The 5/11 blank
    // card: the seed carries the attach's clock (an unstamped seed read
    // `now - 0` — the watchdog barked at 1.79 billion «seconds» of silence)
    // and arms [TurnLive.attachAwaited] — the attached-Idle session's honest
    // card, born silent no more.
    is RenderEvent.SwitchTask -> when {
        state == null || state.taskId != event.taskId -> RenderState.empty.copy(
            taskId = event.taskId,
            title = event.taskId.takeLast(LiveTurn.TITLE_TAIL),
            lastEventAtMs = event.nowMs.takeIf { it > 0 },
            attachAwaited = true,
        )

        else -> state // re-attaching the held task re-arms nothing — it already holds
    }
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
        is RenderEvent.Started -> {
            // THE HEADER'S CLOCK IS ZCODE'S, MIRRORED (v2 §1): the payload's
            // startedAt beats the delivery `at` (the bus's clock can be
            // minutes late after a recovery burst). MIN-GUARD: a re-emitted
            // task_run_started never restarts the clock — the time counts
            // from when the TASK started, not from the re-delivery.
            // THE AWAIT'S END (5/11): the turn the attach awaited has begun.
            val anchored = event.startedAtMs.takeIf { it > 0 }
                ?: event.atMs.takeIf { it > 0 }
                ?: event.nowMs
            refreshed.copy(
                startedAtMs = minOf(refreshed.startedAtMs ?: anchored, anchored),
                attachAwaited = false,
            )
        }

        is RenderEvent.Thought -> reduceThought(refreshed, event)

        is RenderEvent.Answering -> reduceAnswer(flushCot(refreshed), event)

        is RenderEvent.ToolOpen -> reduceToolArrival(flushProse(flushCot(refreshed)), event, differ)

        is RenderEvent.ToolRunning -> reduceToolRunning(refreshed, event, differ)

        is RenderEvent.ToolClose -> reduceToolClose(flushProse(refreshed), event, differ)

        is RenderEvent.Sealed -> {
            // THE SEAL OWNS THE ANSWER (11/11): the pending prose rides
            // [TurnLive.answerAtSeal] — the seal speaks the answer ONCE (the
            // snapshot's full violet text). It is NOT spent: the feed's
            // positional diff never sees it, so the double-speak (the seal
            // feed's truncated copy + the full finalAnswer, back to back)
            // dies. [answerAtSeal] is the never-lost fallback for the seal
            // moment the snapshot cannot answer.
            val pending = refreshed.prosePending.trim('\n')
            refreshed.copy(
                phase = TurnPhase.Done(stopReason = event.stopReason, atMs = event.nowMs),
                usageTokens = event.usageTokens ?: refreshed.usageTokens,
                interaction = null,
                // a clean complete keeps an earlier pill untouched; the error's own seal sets it
                typedError = event.error ?: refreshed.typedError,
                prosePending = "",
                answerAtSeal = pending.takeIf { it.isNotBlank() },
            )
        }

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
// one guard per early exit: the two replay gates answer before the fold
@Suppress("ReturnCount")
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
        // THE REBURST'S FIRST THOUGHT (11/11): the re-stated first thinking
        // emission (chars equal, tail equal) appends nothing — the fork
        // re-stamps `at` on a re-delivery, so the stamp proves nothing; a
        // genuine chunk carries MORE content and grows the counter. The
        // answer's twin guard is the same shape.
        if (state.thoughtChars == delta.length && state.cotTail == delta) return state
        val chars = state.thoughtChars + delta.length
        // THE BLOCKS (Python's CotSealer.feed, reasoning.py): the pending
        // block seals EXACTLY at COT_BLOCK_CP code points — the sealer owns
        // the boundary law AND the block content keys (a re-stated burst's
        // twin block is skipped, already spoken).
        val (blocks, keys, pending) = sealCotBlocks(state.cotBlocks, state.cotKeys, state.cotPending + delta)
        state.copy(
            thoughtChars = chars,
            cotBlocks = blocks,
            cotKeys = keys,
            cotPending = pending,
            cotTail = LiveTurn.takeLastCodePoints(state.cotTail + delta, LiveTurn.COT_TAIL_CP),
            // THE CoT'S FRESHNESS ANCHOR (v2 §2): only the main agent's real
            // deltas mark the thinking fresh — the «generando CoT vs
            // procesando…» split reads it, subagent lanes never touch it
            lastThoughtAtMs = event.atMs.takeIf { it > 0 } ?: event.nowMs,
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

/**
 * THE 3K SEALER (Python's CotSealer.feed, reasoning.py): the pending seals
 * EXACTLY at [LiveTurn.COT_BLOCK_CP] code points — a delta may straddle the
 * boundary, so the block closes AT it and the remainder opens the next one.
 * CODE POINTS, not chars (PyText.cpLength is the house ruler);
 * offsetByCodePoints never splits a surrogate pair.
 *
 * THE BLOCK'S CONTENT KEY (11/11): a re-stated burst re-fills the pending
 * with ALREADY-SPOKEN thought — at 3K it would seal a twin block and the
 * feed would speak it again (the owner read the first sentence twice). The
 * key says spoken: the block is skipped and the pending drains either way.
 */
private fun sealCotBlocks(
    blocks: List<String>,
    keys: Set<String>,
    pending: String,
): Triple<List<String>, Set<String>, String> {
    var out = blocks
    var outKeys = keys
    var rest = pending
    while (PyText.cpLength(rest) >= LiveTurn.COT_BLOCK_CP) {
        val cut = rest.offsetByCodePoints(0, LiveTurn.COT_BLOCK_CP)
        val block = rest.substring(0, cut)
        val key = proseKey(block)
        if (key !in outKeys) {
            out = out + block
            outKeys = (outKeys + key).let { if (it.size > COT_KEY_CAP) it.drop(it.size - COT_KEY_CAP).toSet() else it }
        }
        rest = rest.substring(cut)
    }
    return Triple(out, outKeys, rest)
}

/**
 * agent_message_chunk: the answer's chars grow AND its tail keeps the
 * words — the card renders the tail (Python's `text[-3200:]`), so the
 * owner reads the answer as it streams instead of a bare counter. Real
 * progress clears the retry pill. The replay guard mirrors the CoT's:
 * the fork's stream recovery can re-emit a sentence-sized chunk the tail
 * already ends with (measured 30/10), and short legitimate repeats
 * (separators, tokens) must pass.
 */
// one guard per early exit: the two replay gates answer before the fold
@Suppress("ReturnCount")
private fun reduceAnswer(state: RenderState, event: RenderEvent.Answering): RenderState {
    val delta = event.delta
    if (delta.length >= REPLAY_GUARD_MIN_CP && state.answerTail.endsWith(delta)) return state
    // THE REBURST'S FIRST WORD (11/11): the subscribe burst re-delivers the
    // answer's first emission — the whole streamed answer IS that delta
    // (chars equal, tail equal) — so appending it again doubled the first
    // word while the owner watched. One copy stays. A genuine chunk carries
    // MORE content and grows the counter, so it never matches; the thought's
    // twin guard is the same shape. (The fork re-stamps `at` on a
    // re-delivery — the stamp proved nothing in production; content only.)
    if (state.answerChars == delta.length && state.answerTail == delta) return state
    val chars = state.answerChars + delta.length
    return state.copy(
        answerChars = chars,
        answerTail = LiveTurn.takeLastCodePoints(state.answerTail + delta, LiveTurn.ANSWER_TAIL_CP),
        // THE VOICE'S SEGMENT (5/11): the prose accumulates for its violet
        // message — spoken whole when the model moves to a tool (or seals)
        prosePending = (state.prosePending + delta).takeLast(LiveTurn.ANSWER_TAIL_CP),
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
        apiRetry = if (delta.isNotEmpty()) null else state.apiRetry,
    )
}

/**
 * THE VOICE'S BOUNDARY (5/11): a tool's arrival/closing or the seal ends
 * the current prose segment — the pending words become a SPENT segment,
 * the feed speaks it as one violet message, in order. Blank pending =
 * nothing to spend (a tool after a tool).
 */
// one guard per early exit: the blank pending and the already-spent key
@Suppress("ReturnCount")
internal fun flushProse(state: RenderState): RenderState {
    val pending = state.prosePending.trim('\n')
    if (pending.isBlank()) return state
    // THE REPLAYED SEGMENT (11/11): a re-stated burst (the fork's subscribe
    // reburst after a Resync/StreamRebase) re-flushes prose the voice
    // already spoke — the content key says it. Nothing is spent, the feed's
    // positional diff never sees a new segment; the pending dies either way
    // (the boundary ended it).
    val key = proseKey(pending)
    if (key in state.proseKeys) return state.copy(prosePending = "")
    val keys: Set<String> = (state.proseKeys + key)
        .let { if (it.size > PROSE_KEY_CAP) it.drop(it.size - PROSE_KEY_CAP).toSet() else it }
    return state.copy(
        proseSpent = (state.proseSpent + pending).takeLast(PROSE_SPENT_CAP),
        proseKeys = keys,
        prosePending = "",
    )
}

/**
 * The spent segment's content key: SHA-256 hex — deterministic and pure
 * (no I/O, no clock; the digest of the text IS the identity), short enough
 * for the state to hold hundreds without a second thought.
 */
private fun proseKey(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** The content keys' ceiling — a turn spends far fewer segments than this. */
private const val PROSE_KEY_CAP = 128

/** The spent segments' ceiling — the feed's diff only needs the newest. */
private const val PROSE_SPENT_CAP = 24

/**
 * THE THINKING'S BOUNDARY (the owner, 10/11 — either the 3K cap or the
 * segment's end, never silence): a tool's arrival or the answer's first
 * word ends the current thinking segment — the pending tail (the block
 * UNDER the 3K seal) seals as a REAL block, the feed's diff speaks it
 * like any other sealed one (the «generado 2.7K» verdict, rich path
 * included). Blank pending = nothing to seal (a tool after a tool).
 * The seal's own [TranscriptFeed.pendingFlush] stays as the honest
 * safety net for a turn that dies mid-thought.
 */
// one guard per early exit: the blank pending and the already-spoken key
@Suppress("ReturnCount")
internal fun flushCot(state: RenderState): RenderState {
    val pending = state.cotPending
    if (pending.isBlank()) return state
    // THE BLOCK'S CONTENT KEY (11/11): the boundary-sealed tail carries the
    // same key law as the 3K blocks — a re-stated burst's tail was already
    // spoken (its twin block exists) and never speaks twice.
    val key = proseKey(pending)
    if (key in state.cotKeys) return state.copy(cotPending = "")
    val keys = (state.cotKeys + key).let { if (it.size > COT_KEY_CAP) it.drop(it.size - COT_KEY_CAP).toSet() else it }
    return state.copy(
        cotBlocks = state.cotBlocks + pending,
        cotKeys = keys,
        cotPending = "",
    )
}

/** The CoT blocks' content keys' ceiling — a turn seals far fewer than this. */
private const val COT_KEY_CAP = 128

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

/**
 * `tool_call_update` `in_progress` (v2 §3): the action IS alive — touch its
 * lane's clock and, if the phase drifted to Thinking (a sibling closed),
 * return to Writing on the lane's OWN `openedAtMs` anchor (the elapsed the
 * verbLine renders is the ACTION's, not the arrival's). A never-opened lane
 * opens here, anchored on the fork's own stamp. A closed lane never reopens.
 */
// the lane merge, one guard per field — the arrival's own shape (the ?: fallbacks ARE the table)
@Suppress("CyclomaticComplexMethod")
private fun reduceToolRunning(state: RenderState, event: RenderEvent.ToolRunning, differ: LineDiffer): RenderState {
    val toolId = event.tool.toolId ?: return state
    val existing = state.tools.firstOrNull { it.toolId == toolId }
    val name = event.tool.name ?: existing?.name ?: "tool"
    val streamed = event.tool.streamedLines
    // the partial projection only ever GROWS the count (sampling may stutter)
    val lines = when {
        streamed > 0 -> streamed
        else -> existing?.lines ?: 0
    }
    val input = event.tool.input ?: existing?.input
    val liveDiff = liveEditDiff(input, differ)
    val anchor = existing?.openedAtMs ?: event.atMs.takeIf { it > 0 } ?: event.nowMs
    val lane = ToolLive(
        toolId = toolId,
        name = name,
        title = event.tool.title ?: existing?.title,
        parentToolUseId = event.tool.parentToolUseId ?: existing?.parentToolUseId,
        subagentAgentId = event.tool.subagentAgentId ?: existing?.subagentAgentId,
        openedAtMs = anchor,
        lines = lines,
        rawChars = event.tool.rawChars ?: existing?.rawChars ?: 0,
        input = input,
        status = existing?.status, // a closed lane does not reopen
        isMcp = existing?.isMcp ?: event.tool.isMcp,
        added = liveDiff?.first ?: existing?.added,
        removed = liveDiff?.second ?: existing?.removed,
        lastUpdateAtMs = event.nowMs,
    )
    val tools = state.tools.filterNot { it.toolId == toolId } + lane
    return state.copy(
        tools = tools,
        phase = when {
            state.phase is TurnPhase.Done -> state.phase
            existing?.status != null -> state.phase // a closed lane's stale ping moves no phase
            else -> TurnPhase.Writing(
                tool = name,
                toolId = toolId,
                lines = lane.lines,
                rawChars = lane.rawChars,
                sinceMs = anchor, // the action's own anchor — the elapsed runs on it
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
 * The box ceiling (U5b) — the same 120-entry cap the registry kept (a
 * daemon running for weeks must not hoard every box): the fold keeps the
 * NEWEST [BOX_CAP] boxes; eviction takes the oldest (the lowest ids), so
 * the next id's max-read never regresses.
 */
internal const val BOX_CAP = 120

/**
 * The dispatch tool names (the channel-side law — the same set the
 * /tasks keyboard filters by): closing one folds its inner lanes away.
 */
private val DISPATCH_TOOLS = setOf("Task", "Agent")
