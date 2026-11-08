package dev.lattenero.bot.daemon.otel

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import java.util.concurrent.TimeUnit

/**
 * E3's OTel edge: the [SpanTaps] consumer that turns the pure tracker's
 * typed events into REAL spans. Lives ONLY in the daemon module — the
 * channel never learns a span exists (the tap is the injected callback).
 *
 * The span tree (each attribute HONEST — present only when a real frame
 * carried the value):
 *
 * - ROOT `bot.turn`: `bot.sid` (always — the frame's own task id),
 *   `bot.turn.fork_turn` when the birth named it, `bot.turn.title` when
 *   the stream reported it, `bot.turn.midflight=true` when the daemon
 *   never saw the birth (the span's start is the witnessing moment — the
 *   duration is the observation window, never a backdated guess),
 *   `bot.turn.outcome` at the seal, `bot.turn.error` when the failed
 *   seal said so, and the failed seal marks the span's status ERROR.
 *   NOTE: no `model` attribute — the stream frames never name the model
 *   (the bus's own comment: never invented); the cli-db read that could
 *   resolve it is a per-turn query E3 refuses — the honest absence.
 * - CHILD `bot.tool` (parent = its turn's span, same trace): `bot.sid`,
 *   `bot.tool.name` when the frame named the tool, `bot.tool.ok` with the
 *   close's own verdict (absent when the verdict is honestly unknown),
 *   `bot.tool.chars` with the close's content length, `bot.tool.unclosed=
 *   true` when the seal ended the tool before any verdict arrived — and
 *   the span status ERROR only on a real failed verdict.
 *
 * The timestamps are the events' own stamps (the fork's or the
 * reception's): the span's duration IS the real thing's duration.
 *
 * THE TAP INVARIANT ([TurnSpanTracker]'s law, mirrored at the edge): these
 * handlers fire UNDER the tracker's monitor — they must stay pump-owned,
 * exception-guarded ([GuardedSpanTaps] below is the edge's guard) and
 * IO-free — compute under lock, emit after, or the lock becomes a latency
 * stall. Span start/end are in-memory SDK calls; the EXPORT is async, it
 * rides the OTel SDK's own batch thread — the reason this class may sit
 * inside a lock at all: nothing here ever waits on network or disk.
 */
// one private handler per event kind — the class IS the edge's mapping
@Suppress("TooManyFunctions")
class OtelSpanTaps(private val tracer: Tracer) : SpanTaps {

    /** The live root span plus the context its children parent to. */
    private data class RootSpan(val span: Span, val context: Context)

    private val lock = Any()
    private val turns = HashMap<String, RootSpan>()
    private val tools = HashMap<String, Span>()

    override fun on(event: SpanTapEvent) {
        when (event) {
            is SpanTapEvent.TurnStart -> onTurnStart(event)
            is SpanTapEvent.TurnTitled -> onTurnTitled(event)
            is SpanTapEvent.ToolStart -> onToolStart(event)
            is SpanTapEvent.ToolEnd -> onToolEnd(event)
            is SpanTapEvent.TurnEnd -> onTurnEnd(event)
        }
    }

    private fun onTurnStart(event: SpanTapEvent.TurnStart) {
        val builder = tracer.spanBuilder(SPAN_TURN)
            .setStartTimestamp(event.startedAtMs, TimeUnit.MILLISECONDS)
            .setAttribute(ATTR_SID, event.sid)
        event.forkTurn?.let { builder.setAttribute(ATTR_FORK_TURN, it) }
        if (event.midFlight) builder.setAttribute(ATTR_MIDFLIGHT, true)
        val span = builder.startSpan()
        synchronized(lock) { turns[event.sid] = RootSpan(span, Context.root().with(span)) }
    }

    /** The fork's own title, set on the live span (attributes may move until the end). */
    private fun onTurnTitled(event: SpanTapEvent.TurnTitled) {
        val root = synchronized(lock) { turns[event.sid] } ?: return
        root.span.setAttribute(ATTR_TITLE, event.title)
    }

    private fun onToolStart(event: SpanTapEvent.ToolStart) {
        val root = synchronized(lock) { turns[event.sid] } ?: return // a child needs its root — never orphaned
        val builder = tracer.spanBuilder(SPAN_TOOL)
            .setParent(root.context)
            .setStartTimestamp(event.atMs, TimeUnit.MILLISECONDS)
            .setAttribute(ATTR_SID, event.sid)
        if (event.tool.isNotEmpty()) builder.setAttribute(ATTR_TOOL_NAME, event.tool)
        val span = builder.startSpan()
        synchronized(lock) { tools[event.toolCallId] = span }
    }

    private fun onToolEnd(event: SpanTapEvent.ToolEnd) {
        val span = synchronized(lock) { tools.remove(event.toolCallId) } ?: return // never opened here: no lie
        event.ok?.let { span.setAttribute(ATTR_TOOL_OK, it) }
        if (event.ok == false) span.setStatus(StatusCode.ERROR)
        event.chars?.let { span.setAttribute(ATTR_TOOL_CHARS, it.toLong()) }
        if (event.unclosed) span.setAttribute(ATTR_TOOL_UNCLOSED, true)
        span.end(event.atMs, TimeUnit.MILLISECONDS)
    }

    private fun onTurnEnd(event: SpanTapEvent.TurnEnd) {
        val root = synchronized(lock) { turns.remove(event.sid) } ?: return // never opened here: no lie
        event.outcome?.let { root.span.setAttribute(ATTR_OUTCOME, it) }
        event.error?.let { root.span.setAttribute(ATTR_ERROR, it) }
        if (event.outcome == OUTCOME_FAILED) root.span.setStatus(StatusCode.ERROR)
        root.span.end(event.endAtMs, TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val SPAN_TURN = "bot.turn"
        const val SPAN_TOOL = "bot.tool"
        const val OUTCOME_FAILED = "failed"

        val ATTR_SID: AttributeKey<String> = AttributeKey.stringKey("bot.sid")
        val ATTR_FORK_TURN: AttributeKey<String> = AttributeKey.stringKey("bot.turn.fork_turn")
        val ATTR_TITLE: AttributeKey<String> = AttributeKey.stringKey("bot.turn.title")
        val ATTR_MIDFLIGHT: AttributeKey<Boolean> = AttributeKey.booleanKey("bot.turn.midflight")
        val ATTR_OUTCOME: AttributeKey<String> = AttributeKey.stringKey("bot.turn.outcome")
        val ATTR_ERROR: AttributeKey<String> = AttributeKey.stringKey("bot.turn.error")
        val ATTR_TOOL_NAME: AttributeKey<String> = AttributeKey.stringKey("bot.tool.name")
        val ATTR_TOOL_OK: AttributeKey<Boolean> = AttributeKey.booleanKey("bot.tool.ok")
        val ATTR_TOOL_CHARS: AttributeKey<Long> = AttributeKey.longKey("bot.tool.chars")
        val ATTR_TOOL_UNCLOSED: AttributeKey<Boolean> = AttributeKey.booleanKey("bot.tool.unclosed")
    }
}

/**
 * The live-io invariant's guard around the OTel edge: telemetry NEVER
 * breaks a turn. Every tap failure is ONE log line — the event is lost,
 * the turn is not (the bus producer's own `guard`, shaped for taps).
 */
class GuardedSpanTaps(
    private val delegate: SpanTaps,
    private val log: (String) -> Unit,
) : SpanTaps {

    @Suppress("TooGenericExceptionCaught") // no tap failure may ever reach a turn — the bus's invariant
    override fun on(event: SpanTapEvent) = try {
        delegate.on(event)
    } catch (e: Exception) {
        log(
            "otel tap '${event::class.simpleName}' failed (${e::class.simpleName}: ${e.message}) — " +
                "the turn is unaffected",
        )
    }
}
