package dev.lattenero.bot.daemon.otel

import dev.lattenero.bot.channel.ServerFrame
import dev.lattenero.bot.contracts.BusWire
import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * E3's span tracker — the PURE core that folds the stream frames the
 * daemon already witnesses into the span tree's lifecycle. The SAME real
 * surfaces the bus producer reads (ADR-019's lifecycle), tapped beside it
 * in the daemon's own wiring (one owner per tap point — the channel
 * module never learns a span exists); this one only shapes spans:
 *
 * - one ROOT `bot.turn` per real turn, born on `task_run_started` and
 *   sealed on `task_complete`/`task_error` — the span ends when the real
 *   thing ends;
 * - one CHILD `bot.tool` per tool call, born on `tool_call` and closed on
 *   the terminal `tool_call_update` — the span ends when the tool ends.
 *
 * The bus producer's own laws port verbatim (each one tested):
 * - a resubscribe burst re-emits the LIVE turn's frames — the same fork
 *   turn or the same startedAt is the same turn, spanned once;
 * - a burst replaying an ALREADY SEALED turn spans nothing;
 * - a turn first WITNESSED mid-flight still gets its root span, starting
 *   at the witnessing moment — the documented choice: no start timestamp
 *   is invented (never backdated from a terminal frame), the midFlight
 *   mark says the duration is the observation window, not the turn's;
 * - a tool the daemon never saw OPEN gets no span (its start would be
 *   invented); a tool still open at the seal ends AT the seal with the
 *   verdict honestly unknown ([SpanTapEvent.ToolEnd.unclosed]).
 *
 * Zero I/O: the taps are in-memory calls (the recorder, or the OTel SDK's
 * span start/end — the export rides the SDK's own batch thread). The
 * clock is read only where a real stamp is missing.
 *
 * THE TAP INVARIANT (pinned by the concurrency audit): the taps fire UNDER
 * the tracker's monitor (each `on*` leg's `synchronized` section — state
 * and event are one linearized step). That is only safe while the taps
 * stay pump-owned, exception-guarded and IO-free — compute under lock,
 * emit after, or the lock becomes a latency stall. Concretely: (a)
 * PUMP-OWNED — [onFrame] runs on the channel pump's single
 * `onStreamEvent` coroutine, never fanned out to a pool; (b)
 * EXCEPTION-GUARDED — [GuardedSpanTaps] wraps the edge, so no tap failure
 * escapes the monitor; (c) IO-FREE — no tap ever waits on network or disk
 * (the export is the SDK's batch thread's business, see [OtelSpanTaps]).
 */
// one private reader per frame kind + the small helpers — the class IS the
// lifecycle's dispatch (the bus producer's own shape)
@Suppress("TooManyFunctions")
class TurnSpanTracker(
    private val taps: SpanTaps,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    /** A tool call opened and not yet closed — the child span's pending facts. */
    private data class OpenTool(val tool: String)

    /** One live turn's span state — what the dedupe and the seal need. */
    private class TurnState(
        val startedAtMs: Long,
        var forkTurn: String?,
        /** True when the daemon never saw the birth: the span starts at the witness moment. */
        val midFlight: Boolean,
    ) {
        /** Ids ever OPENED — a replay of a closed tool's `tool_call` opens nothing (the bus's `calls`). */
        val calls = HashSet<String>()

        /** Ids ever CLOSED — a duplicated terminal update closes nothing twice (the bus's `results`). */
        val results = HashSet<String>()

        /** Currently open tools: id → its facts. The seal ends every survivor. */
        val open = HashMap<String, OpenTool>()
    }

    /** A turn this tracker already sealed — the burst's replay must span nothing. */
    private data class SealMark(val startedAtMs: Long, val forkTurn: String?)

    private val lock = Any()
    private val live = HashMap<String, TurnState>()
    private val sealed = LinkedHashMap<String, SealMark>()

    /** One stream frame — the whole span lifecycle rides the frames the bus taps read. */
    fun onFrame(frame: ServerFrame.StreamEvent) {
        when (str(frame.event, "type")) {
            RUN_STARTED -> onRunStarted(frame)
            TOOL_CALL -> onToolCall(frame)
            TOOL_UPDATE -> onToolUpdate(frame)
            SESSION_INFO -> onSessionInfo(frame)
            COMPLETE -> onSealed(frame, OUTCOME_COMPLETED, error = null)
            ERROR -> onSealed(frame, OUTCOME_FAILED, errorText(frame))
            else -> Unit // the rest of the union is not span material (additive rule)
        }
    }

    // the honesty ladder: live replay, mid-flight adoption, sealed replay — one exit each
    @Suppress("ReturnCount")
    private fun onRunStarted(frame: ServerFrame.StreamEvent) {
        val eventStart = long(frame.event, "startedAt")?.takeIf { it > 0 }
        val forkTurn = str(frame.event, "turnId")
        synchronized(lock) {
            val existing = live[frame.taskId]
            if (existing != null) {
                when {
                    // a resubscribe burst re-emits the live turn's birth — the same
                    // fork turn or the same startedAt is the same turn, spanned once
                    sameTurn(existing.startedAtMs, existing.forkTurn, eventStart, forkTurn) -> return

                    // the birth of a turn the tracker LAZILY opened mid-flight: the
                    // SAME turn's belated announcement — one span stays, only the
                    // dedupe marks refresh (a second span would lie twice)
                    existing.midFlight -> {
                        existing.forkTurn = forkTurn
                        return
                    }

                    // a DIFFERENT turn's birth while one is live: the previous turn
                    // never sealed (a seq gap swallowed its terminal) — the next
                    // birth is the honest proof it is over, no outcome claimed
                    else -> taps.on(
                        SpanTapEvent.TurnEnd(
                            sid = frame.taskId,
                            outcome = null,
                            error = null,
                            endAtMs = frame.at.takeIf { it > 0 } ?: clockMs(),
                        ),
                    )
                }
            } else {
                // a burst replaying an ALREADY SEALED turn spans nothing
                val mark = sealed[frame.taskId]
                if (mark != null && sameTurn(mark.startedAtMs, mark.forkTurn, eventStart, forkTurn)) return
            }
            val startedAtMs = eventStart ?: frame.at.takeIf { it > 0 } ?: clockMs()
            live[frame.taskId] = TurnState(startedAtMs, forkTurn, midFlight = false)
            taps.on(SpanTapEvent.TurnStart(frame.taskId, startedAtMs, forkTurn, midFlight = false))
        }
    }

    // the honesty ladder: no id, sealed replay, duplicate open — one exit each
    @Suppress("ReturnCount")
    private fun onToolCall(frame: ServerFrame.StreamEvent) {
        val event = frame.event
        val id = toolCallId(event) ?: return // no id: no closable span — never invented
        val name = str(event, "toolName") ?: ""
        synchronized(lock) {
            if (live[frame.taskId] == null) {
                // a tool frame for a turn this tracker already sealed is a replay:
                // the stream is ordered, a post-seal tool call cannot be real
                if (sealed.containsKey(frame.taskId)) return
                lazyTurnLocked(frame) // the mid-flight attach's honest partial record
            }
            val state = live.getValue(frame.taskId)
            if (!state.calls.add(id)) return // the bus's own dedupe: the same id opens once
            state.open[id] = OpenTool(name)
            val atMs = frame.at.takeIf { it > 0 } ?: clockMs()
            taps.on(SpanTapEvent.ToolStart(frame.taskId, id, name, atMs))
        }
    }

    // the honesty ladder: not lifecycle, no id, sealed replay, duplicate close,
    // close-without-open — one exit each, each honest
    @Suppress("ReturnCount")
    private fun onToolUpdate(frame: ServerFrame.StreamEvent) {
        val event = frame.event
        val status = str(event, "status") ?: return
        if (status !in TOOL_CLOSED) return // pending/streaming updates are not lifecycle
        val id = toolCallId(event) ?: return // no id: the open cannot be correlated — no span
        synchronized(lock) {
            if (live[frame.taskId] == null) {
                // a terminal update for a sealed turn is a replay; for an unseen
                // turn it is the mid-flight attach's first witnessed moment — the
                // ROOT opens (the tool itself gets no span: its start is lost)
                if (sealed.containsKey(frame.taskId)) return
                lazyTurnLocked(frame)
            }
            val state = live.getValue(frame.taskId)
            if (!state.results.add(id)) return // the bus's own dedupe: the same id closes once
            if (state.open.remove(id) == null) return // close without open: no span, no lie
            val atMs = frame.at.takeIf { it > 0 } ?: clockMs()
            taps.on(
                SpanTapEvent.ToolEnd(
                    sid = frame.taskId,
                    toolCallId = id,
                    ok = status == TOOL_COMPLETED,
                    chars = chars(event),
                    atMs = atMs,
                    unclosed = false,
                ),
            )
        }
    }

    /** The fork reported the turn's real title: set on the LIVE span only, never invented. */
    private fun onSessionInfo(frame: ServerFrame.StreamEvent) {
        val title = str(frame.event, "title")?.takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            if (live.containsKey(frame.taskId)) taps.on(SpanTapEvent.TurnTitled(frame.taskId, BusWire.clip(title)))
        }
    }

    // the honesty ladder: sealed replay — one exit
    @Suppress("ReturnCount")
    private fun onSealed(frame: ServerFrame.StreamEvent, outcome: String, error: String?) {
        synchronized(lock) {
            val state = live.remove(frame.taskId)
            // a terminal frame with NO live turn on an already-sealed task is a
            // burst replay — silence (a second seal would lie twice)
            if (state == null && sealed.containsKey(frame.taskId)) return
            // a turn first WITNESSED at its own end: the honest span without a
            // start — it opens at this witnessing moment and closes now
            val turn = state ?: lazyTurnNoLock(frame)
            val endAtMs = frame.at.takeIf { it > 0 } ?: clockMs()
            // the seal ended every still-open tool for real: each child ends AT
            // the seal, its verdict honestly unknown (never guessed)
            turn.open.keys.forEach { id ->
                taps.on(
                    SpanTapEvent.ToolEnd(
                        sid = frame.taskId,
                        toolCallId = id,
                        ok = null,
                        chars = null,
                        atMs = endAtMs,
                        unclosed = true,
                    ),
                )
            }
            // the seal mark's dedupe quality matches the metrics tap's: the
            // frame's own startedAt counts for IDENTITY, never for the span's start
            sealed[frame.taskId] = SealMark(
                startedAtMs = long(frame.event, "startedAt")?.takeIf { it > 0 } ?: turn.startedAtMs,
                forkTurn = str(frame.event, "turnId") ?: turn.forkTurn,
            )
            while (sealed.size > SEAL_CAP) sealed.remove(sealed.keys.first())
            taps.on(SpanTapEvent.TurnEnd(frame.taskId, outcome, error, endAtMs))
        }
    }

    /** Lazily opens the mid-flight root AND registers it — the first witnessed moment of the turn. */
    private fun lazyTurnLocked(frame: ServerFrame.StreamEvent): TurnState =
        lazyTurnNoLock(frame).also { live[frame.taskId] = it }

    /**
     * The mid-flight attach's honest partial record: the span opens at the
     * WITNESSING moment (the frame's reception stamp, else the clock) —
     * never backdated from a terminal frame's startedAt (E2's nothing-
     * invented law; that stamp's turn-vs-task semantics are unverified) —
     * and [SpanTapEvent.TurnStart.midFlight] tells the reader.
     */
    private fun lazyTurnNoLock(frame: ServerFrame.StreamEvent): TurnState =
        TurnState(
            startedAtMs = frame.at.takeIf { it > 0 } ?: clockMs(),
            forkTurn = null, // tool/terminal frames name no fork turn — never invented
            midFlight = true,
        ).also { taps.on(SpanTapEvent.TurnStart(frame.taskId, it.startedAtMs, it.forkTurn, midFlight = true)) }

    /** The bus's own turn-identity rule: the fork turn when present, else the start stamp. */
    private fun sameTurn(existingStart: Long, existingFork: String?, eventStart: Long?, forkTurn: String?): Boolean =
        (forkTurn != null && forkTurn == existingFork) ||
            (eventStart != null && eventStart == existingStart)

    /**
     * The tool call's id — the fork's field arrives as `toolCallId` (the
     * bus's dedupe key) or `toolId` (the render's lane key): the same
     * stamp, read whichever came. A blank id is no id: a span without a
     * key could never be closed honestly.
     */
    private fun toolCallId(event: JsonObject): String? =
        str(event, "toolCallId")?.takeIf { it.isNotEmpty() }
            ?: str(event, "toolId")?.takeIf { it.isNotEmpty() }

    /** The failed seal's error text — only what the frame itself said, clipped (the bus's cap). */
    private fun errorText(frame: ServerFrame.StreamEvent): String? =
        str(frame.event, "error")?.takeIf { it.isNotBlank() }?.let { BusWire.clip(it) }

    /** The close's content length in code points — the bus's own char law (0 when none). */
    private fun chars(event: JsonObject): Int =
        PyText.cpLength((event["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "")

    private fun str(event: JsonObject, key: String): String? = (event[key] as? JsonPrimitive)?.content

    private fun long(event: JsonObject, key: String): Long? =
        (event[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()

    private companion object {
        const val RUN_STARTED = "task_run_started"
        const val TOOL_CALL = "tool_call"
        const val TOOL_UPDATE = "tool_call_update"
        const val SESSION_INFO = "session_info_update"
        const val COMPLETE = "task_complete"
        const val ERROR = "task_error"

        const val TOOL_COMPLETED = "completed"
        val TOOL_CLOSED = setOf("completed", "failed", "denied", "stopped")

        const val OUTCOME_COMPLETED = "completed"
        const val OUTCOME_FAILED = "failed"

        /** The sealed-turn memory — pruned FIFO, never unbounded (the house's own cap). */
        const val SEAL_CAP = 512
    }
}
