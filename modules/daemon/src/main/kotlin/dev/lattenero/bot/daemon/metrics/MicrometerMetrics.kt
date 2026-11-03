package dev.lattenero.bot.daemon.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * The ON implementation: a Micrometer [PrometheusMeterRegistry] whose
 * scrape IS the /metrics body. The names use Micrometer's dot form and
 * the registry renders them Prometheus-style: `bot.turns.started` becomes
 * `bot_turns_started_total`, the timers become `*_seconds_count/_sum/_max`,
 * the gauges stay verbatim with their one tag (`lane`, `outcome`,
 * `direction`).
 *
 * Counters are looked up per event (Micrometer dedupes meters by
 * name+tags); the gauges register their supplier with a STRONG reference —
 * Micrometer holds gauge state weakly and an unreferenced supplier reads
 * NaN after a GC (the documented pitfall).
 */
// the inventory IS the facade: one method per real surface
@Suppress("TooManyFunctions")
class MicrometerMetrics(private val registry: PrometheusMeterRegistry) : Metrics {

    override val measures: Boolean = true

    override fun turnStarted() {
        counter(TURN_STARTED).increment()
    }

    override fun turnSealed(outcome: TurnOutcome, durationMs: Long?) {
        counter(TURN_SEALED, TAG_OUTCOME, outcome.tag).increment()
        durationMs?.let { registry.timer(TURN_DURATION, TAG_OUTCOME, outcome.tag).record(it, TimeUnit.MILLISECONDS) }
    }

    override fun busGauges(written: () -> Long, failures: () -> Long) {
        gauge(BUS_WRITTEN) { written().toDouble() }
        gauge(BUS_FAILURES) { failures().toDouble() }
    }

    override fun memoryInjected() {
        counter(MEMORY_INJECTED).increment()
    }

    override fun memoryInjectFailed() {
        counter(MEMORY_INJECT_FAILURES).increment()
    }

    override suspend fun <T> memoryFetch(block: suspend () -> T): T = timed(MEMORY_FETCH, block)

    override suspend fun <T> brainRequest(block: suspend () -> T): T = timed(BRAIN_REQUEST, block)

    override fun brainFailed() {
        counter(BRAIN_FAILURES).increment()
    }

    override fun pollerCycle() {
        counter(POLLER_CYCLES).increment()
    }

    override fun pollerInbound() {
        counter(POLLER_INBOUND).increment()
    }

    override fun pollerCallback() {
        counter(POLLER_CALLBACKS).increment()
    }

    override fun outboxDepth(lane: Lane, depth: () -> Int) {
        gauge(OUTBOX_DEPTH, TAG_LANE, lane.tag) { depth().toDouble() }
    }

    override fun cooldownActive(lane: Lane, active: () -> Boolean) {
        gauge(COOLDOWN_ACTIVE, TAG_LANE, lane.tag) { if (active()) 1.0 else 0.0 }
    }

    override fun zconnTransition(down: Boolean) {
        counter(ZCONN_TRANSITIONS, TAG_DIRECTION, if (down) "down" else "up").increment()
    }

    override fun zconnDown(down: () -> Boolean) {
        gauge(ZCONN_DOWN) { if (down()) 1.0 else 0.0 }
    }

    /**
     * The timer around the call itself: the elapsed time is real even for
     * a call the caller cancels (the inject's deadline) — the finally
     * records it and the cancellation still propagates.
     */
    private suspend fun <T> timed(name: String, block: suspend () -> T): T {
        val timer = registry.timer(name)
        val startedAt = System.nanoTime()
        try {
            return block()
        } finally {
            timer.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
        }
    }

    private fun counter(name: String, tagKey: String? = null, tagValue: String? = null) =
        if (tagKey == null || tagValue == null) {
            registry.counter(name)
        } else {
            registry.counter(name, tagKey, tagValue)
        }

    private fun gauge(name: String, supplier: () -> Double) {
        Gauge.builder(name, Supplier { supplier() })
            .strongReference(true)
            .register(registry)
    }

    private fun gauge(name: String, tagKey: String, tagValue: String, supplier: () -> Double) {
        Gauge.builder(name, Supplier { supplier() })
            .tag(tagKey, tagValue)
            .strongReference(true)
            .register(registry)
    }

    private companion object {
        const val TAG_OUTCOME = "outcome"
        const val TAG_LANE = "lane"
        const val TAG_DIRECTION = "direction"

        const val TURN_STARTED = "bot.turns.started"
        const val TURN_SEALED = "bot.turns.sealed"
        const val TURN_DURATION = "bot.turn.duration"
        const val BUS_WRITTEN = "bot.bus.written"
        const val BUS_FAILURES = "bot.bus.failures"
        const val MEMORY_INJECTED = "bot.memory.injected"
        const val MEMORY_INJECT_FAILURES = "bot.memory.inject.failures"
        const val MEMORY_FETCH = "bot.memory.fetch"
        const val BRAIN_REQUEST = "bot.brain.request"
        const val BRAIN_FAILURES = "bot.brain.failures"
        const val POLLER_CYCLES = "bot.poller.cycles"
        const val POLLER_INBOUND = "bot.poller.inbound"
        const val POLLER_CALLBACKS = "bot.poller.callbacks"
        const val OUTBOX_DEPTH = "bot.outbox.depth"
        const val COOLDOWN_ACTIVE = "bot.cooldown.active"
        const val ZCONN_TRANSITIONS = "bot.zconn.transitions"
        const val ZCONN_DOWN = "bot.zconn.down"
    }
}
