package dev.lattenero.bot.daemon.otel

/**
 * E3's span lifecycle, typed: ONE sealed event per span point the pure
 * tracker reports. The union IS the shape — the compiler owns the
 * inventory (a new event type fails every consumer's `when`), and every
 * record carries only what the REAL stream frames carried (the
 * honest-attributes law: nothing invented, nothing padded).
 *
 * The tap is [SpanTaps] — a single callback fed these events. The daemon
 * wiring builds exactly one consumer per boot: the in-memory
 * [RecordingSpanTaps] in tests, the OTel edge ([OtelSpanTaps], E3's
 * wiring) when the `otel` knob is on. The channel module never sees any
 * of this — the tap rides the daemon's own stream callback, beside the
 * bus taps, one owner per tap point (no double-instrument).
 */
sealed interface SpanTapEvent {

    /**
     * The ROOT span `bot.turn` opens. [startedAtMs] is the frame's own
     * start stamp when the birth carried one, else the reception stamp —
     * a real moment, never a backdated guess. [midFlight] marks the turn
     * the daemon never saw born (a subscription attached mid-flight): the
     * span starts at the WITNESSING moment — no start timestamp invented —
     * and the attribute tells the reader the duration is the observation
     * window, not the turn's.
     */
    data class TurnStart(
        val sid: String,
        val startedAtMs: Long,
        val forkTurn: String?,
        val midFlight: Boolean,
    ) : SpanTapEvent

    /**
     * The fork reported the turn's real title (`session_info_update`) —
     * set when the stream says so, never resolved from anywhere else.
     */
    data class TurnTitled(val sid: String, val title: String) : SpanTapEvent

    /** A CHILD span `bot.tool` opens: a tool call, witnessed from its open. */
    data class ToolStart(
        val sid: String,
        val toolCallId: String,
        val tool: String,
        val atMs: Long,
    ) : SpanTapEvent

    /**
     * The child span ends at the tool's real close. [ok] is the frame's
     * own verdict (`completed` vs everything else — the bus's law); null
     * only for a tool the seal ended before its verdict arrived
     * ([unclosed] — the span must not dangle, the verdict is honestly
     * unknown). [chars] is the close's content length in code points;
     * null when no close was ever seen.
     */
    data class ToolEnd(
        val sid: String,
        val toolCallId: String,
        val ok: Boolean?,
        val chars: Int?,
        val atMs: Long,
        val unclosed: Boolean,
    ) : SpanTapEvent

    /**
     * The ROOT span ends: the turn's seal (`task_complete` / `task_error`)
     * at the host's reception stamp. [outcome] is "completed" or "failed"
     * when the seal was witnessed; null only when a NEXT turn's birth
     * proved the previous one over without a witnessed seal (a seq gap) —
     * the span ends at that proof's moment, no outcome claimed. [error]
     * rides only what the `task_error` frame itself said.
     */
    data class TurnEnd(
        val sid: String,
        val outcome: String?,
        val error: String?,
        val endAtMs: Long,
    ) : SpanTapEvent
}

/** The tap the span tracker reports to: one sealed event per lifecycle point. */
fun interface SpanTaps {

    fun on(event: SpanTapEvent)
}

/**
 * The in-memory span recorder — the pure core's test trap (no mock
 * framework): every tap appends its typed event, the tests assert on the
 * log. Also the shape witness of what ONE turn's real frames become.
 */
class RecordingSpanTaps : SpanTaps {

    private val received = mutableListOf<SpanTapEvent>()

    /** The event log so far, in arrival order (a defensive copy per read). */
    val events: List<SpanTapEvent> get() = received.toList()

    override fun on(event: SpanTapEvent) {
        received.add(event)
    }

    /** The events of one type, in order — the tests' eye. */
    inline fun <reified T : SpanTapEvent> ofType(): List<T> = events.filterIsInstance<T>()

    fun clear() {
        received.clear()
    }
}
