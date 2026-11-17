package dev.lattenero.bot.daemon.metrics

/**
 * The stall line: a pump callback longer than this held the channel's only
 * reader while real work waited behind it. One second is already felt on
 * the phone; the stabilization target is zero of these.
 */
const val STALL_LINE_MS: Long = 1_000L

/**
 * E2's typed metrics facade — the ONLY metrics surface the rest of the
 * daemon sees. Counters and gauges are explicit methods, never a Map of
 * strings: the compiler owns the inventory, and every method's KDoc names
 * the REAL event that feeds it (the honest-indicators law — nothing here
 * is decorative).
 *
 * The wiring decides ONCE at boot behind the `metrics` knob: [NoopMetrics]
 * when OFF (every method a no-op; the daemon byte-for-byte the pre-E2 one)
 * or [MicrometerMetrics] when ON. OFF is strict by SHAPE:
 *
 * - the request timer takes the CALL itself (`brainRequest`), so the no-op
 *   runs the block bare — not one clock read on a hot path;
 * - the gauges take suppliers that are read at SCRAPE time (a gauge must
 *   read a real value when Prometheus asks, never a cached one) — with
 *   the no-op they are simply never registered;
 * - the one stateful tap ([TurnMetricsTap]) is built by the wiring only
 *   when [measures] is true (the busTaps pattern).
 */
// the inventory IS the facade: one method per real surface, each with its
// own KDoc naming the event that feeds it
@Suppress("TooManyFunctions")
interface Metrics {

    /** True when this facade actually measures — the wiring builds its stateful taps only then. */
    val measures: Boolean

    // ── turns (the stream frames the daemon witnesses) ───────────────────

    /** A turn's birth: one `task_run_started` this daemon first-witnessed. */
    fun turnStarted()

    /**
     * A turn's seal: `task_complete` (completed) or `task_error` (failed).
     * [durationMs] is the witnessed start→seal duration; null when the
     * daemon never saw the turn begin (a subscription attached
     * mid-flight) — the counter counts the seal, the timer does not
     * (nothing invented).
     */
    fun turnSealed(outcome: TurnOutcome, durationMs: Long?)

    // ── the bus (ADR-019; the sink is the single owner of its counts) ────

    /**
     * Registers the bus sink's own counters as gauges — NO second count
     * anywhere: [dev.lattenero.bot.daemon.bus.BusSink.written] and
     * `.failures` are the one truth, read at scrape time.
     */
    fun busGauges(written: () -> Long, failures: () -> Long)

    // ── the brain HTTP leg (agent-brain over localhost) ──────────────────

    /** Every brain request's latency, timed around the call itself. */
    suspend fun <T> brainRequest(block: suspend () -> T): T

    /** A brain request that produced no usable answer: timeout, dead brain, non-2xx. */
    fun brainFailed()

    // ── the poller (the single getUpdates reader) ────────────────────────

    /** One completed pass of the poller process — whatever it fetched (0 when the breaker ate the cycle). */
    fun pollerCycle()

    /** An owner update accepted through the poller: a text message or an attachment. */
    fun pollerInbound()

    /** A button tap accepted through the poller. */
    fun pollerCallback()

    // ── the stream pump + the tap's latency (the stabilization baseline) ──

    /**
     * One `onStreamEvent` callback's own duration: how long the ONE reader
     * of the channel was held by a frame. The owner's freezes are this
     * number; nothing else moves while it grows.
     */
    fun streamCallback(durationMs: Long)

    /**
     * A pump callback that held the reader past the stall line
     * ([STALL_LINE_MS]): the freeze is REAL, not a slow frame — the one
     * counter the stabilization work must drive to zero.
     */
    fun streamStall()

    /**
     * Tap → the FIRST stream frame folded for that very task: the attach's
     * honest latency (a subscription with no burst never records — nothing
     * invented).
     */
    fun attachFirstFrame(durationMs: Long)

    /** One dispatched Telegram update's handler duration, tagged by verb (`/tasks`, `t:`, `text`, `attachment`). */
    fun handler(verb: String, durationMs: Long)

    /**
     * The channel's remembered subscriptions, read at scrape time. The main
     * view never unsubscribed the task it left (measured in the audit) —
     * this gauge is the leak's witness.
     */
    fun subscriptions(size: () -> Int)

    // ── the outbox + cooldown (LIVE mode's two throats) ──────────────────

    /** The lane's parked depth, read at scrape time from the outbox file itself. */
    fun outboxDepth(lane: Lane, depth: () -> Int)

    /** Whether the lane's cooldown window is active at scrape time (1/0). */
    fun cooldownActive(lane: Lane, active: () -> Boolean)

    // ── zconn (the episode machine) ──────────────────────────────────────

    /** A spoken transition: the wire cut (down) or healed (up). */
    fun zconnTransition(down: Boolean)

    /** The episode machine's current state, read at scrape time from its persisted truth. */
    fun zconnDown(down: () -> Boolean)
}

/** How a turn ended — the duration timer's one tag. */
enum class TurnOutcome(val tag: String) {
    COMPLETED("completed"),
    FAILED("failed"),
}

/** The send lanes (LIVE mode's two throats) — the outbox/cooldown gauges' one tag. */
enum class Lane(val tag: String) {
    MAIN("main"),
    FIREHOSE("firehose"),
}

/**
 * The OFF implementation: every tap a no-op, both timers run the block
 * bare (not one clock read), every gauge registration forgotten. A daemon
 * wired with this facade is byte-for-byte the pre-E2 one.
 */
// the empty overrides ARE the point: the whole object is the honest no-op
@Suppress("TooManyFunctions", "EmptyFunctionBlock")
object NoopMetrics : Metrics {

    override val measures: Boolean = false

    override fun turnStarted() {}

    override fun turnSealed(outcome: TurnOutcome, durationMs: Long?) {}

    override fun busGauges(written: () -> Long, failures: () -> Long) {}

    override suspend fun <T> brainRequest(block: suspend () -> T): T = block()

    override fun brainFailed() {}

    override fun pollerCycle() {}

    override fun pollerInbound() {}

    override fun pollerCallback() {}

    override fun streamCallback(durationMs: Long) {}

    override fun streamStall() {}

    override fun attachFirstFrame(durationMs: Long) {}

    override fun handler(verb: String, durationMs: Long) {}

    override fun subscriptions(size: () -> Int) {}

    override fun outboxDepth(lane: Lane, depth: () -> Int) {}

    override fun cooldownActive(lane: Lane, active: () -> Boolean) {}

    override fun zconnTransition(down: Boolean) {}

    override fun zconnDown(down: () -> Boolean) {}
}
