package dev.lattenero.bot.daemon.metrics

import dev.lattenero.bot.channel.BrainReply
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The facade's laws: counters count the real events they are named after,
 * the gauges read their supplier at SCRAPE time (two scrapes, two values),
 * the timers time the actual call and return its value untouched, and the
 * no-op runs the block bare measuring nothing.
 */
class MetricsTest {

    private fun registry() = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    /** The value line of [metric] in a scrape (`name{tags} value`), null when the family is absent. */
    private fun valueOf(scrape: String, metric: String): Double? =
        scrape.lineSequence()
            .firstOrNull { it.startsWith(metric) }
            ?.substringAfterLast(' ')
            ?.toDoubleOrNull()

    @Test
    fun countersCountTheRealEvents() {
        val reg = registry()
        val m = MicrometerMetrics(reg)
        m.turnStarted()
        m.turnStarted()
        m.turnSealed(TurnOutcome.COMPLETED, 1_500)
        m.turnSealed(TurnOutcome.FAILED, 300)
        m.memoryInjected()
        m.memoryInjectFailed()
        m.brainFailed()
        m.pollerCycle()
        m.pollerInbound()
        m.pollerCallback()
        m.zconnTransition(down = true)
        m.zconnTransition(down = false)
        m.zconnTransition(down = false)
        val scrape = reg.scrape()
        assertEquals(2.0, valueOf(scrape, "bot_turns_started_total"))
        assertEquals(1.0, valueOf(scrape, """bot_turns_sealed_total{outcome="completed"}"""))
        assertEquals(1.0, valueOf(scrape, """bot_turns_sealed_total{outcome="failed"}"""))
        assertEquals(1.0, valueOf(scrape, "bot_memory_injected_total"))
        assertEquals(1.0, valueOf(scrape, "bot_memory_inject_failures_total"))
        assertEquals(1.0, valueOf(scrape, "bot_brain_failures_total"))
        assertEquals(1.0, valueOf(scrape, "bot_poller_cycles_total"))
        assertEquals(1.0, valueOf(scrape, "bot_poller_inbound_total"))
        assertEquals(1.0, valueOf(scrape, "bot_poller_callbacks_total"))
        assertEquals(1.0, valueOf(scrape, """bot_zconn_transitions_total{direction="down"}"""))
        assertEquals(2.0, valueOf(scrape, """bot_zconn_transitions_total{direction="up"}"""))
    }

    @Test
    fun theTurnTimerRecordsOnlyWitnessedDurations() {
        val reg = registry()
        val m = MicrometerMetrics(reg)
        m.turnSealed(TurnOutcome.COMPLETED, 1_500)
        m.turnSealed(TurnOutcome.FAILED, 300)
        m.turnSealed(TurnOutcome.FAILED, null) // witnessed only at its end: counter yes, timer no
        val scrape = reg.scrape()
        assertEquals(1.0, valueOf(scrape, """bot_turn_duration_seconds_count{outcome="completed"}"""))
        assertEquals(1.0, valueOf(scrape, """bot_turn_duration_seconds_count{outcome="failed"}"""))
        assertEquals(1.5, valueOf(scrape, """bot_turn_duration_seconds_sum{outcome="completed"}"""))
    }

    @Test
    fun theGaugesReadTheRealValueAtScrapeTime() {
        val reg = registry()
        val m = MicrometerMetrics(reg)
        var written = 0L
        var failures = 0L
        var depth = 3
        var down = false
        m.busGauges(written = { written }, failures = { failures })
        m.outboxDepth(Lane.MAIN) { depth }
        m.cooldownActive(Lane.FIREHOSE) { true }
        m.zconnDown { down }

        val first = reg.scrape()
        assertEquals(0.0, valueOf(first, "bot_bus_written"))
        assertEquals(0.0, valueOf(first, "bot_bus_failures"))
        assertEquals(3.0, valueOf(first, """bot_outbox_depth{lane="main"}"""))
        assertEquals(1.0, valueOf(first, """bot_cooldown_active{lane="firehose"}"""))
        assertEquals(0.0, valueOf(first, "bot_zconn_down"))

        // the world moved; the NEXT scrape sees it — never a cached value
        written = 41
        failures = 2
        depth = 7
        down = true
        val second = reg.scrape()
        assertEquals(41.0, valueOf(second, "bot_bus_written"))
        assertEquals(2.0, valueOf(second, "bot_bus_failures"))
        assertEquals(7.0, valueOf(second, """bot_outbox_depth{lane="main"}"""))
        assertEquals(1.0, valueOf(second, "bot_zconn_down"))
    }

    @Test
    fun theTimersTimeTheRealCallAndReturnItsValue() = runBlocking {
        val reg = registry()
        val m = MicrometerMetrics(reg)
        val block = "el bloque de memoria"
        assertEquals(block, m.memoryFetch { block })
        val reply = m.brainRequest { BrainReply.Response(200, "{}") }
        assertIs<BrainReply.Response>(reply)
        assertEquals(1.0, valueOf(reg.scrape(), "bot_memory_fetch_seconds_count"))
        assertEquals(1.0, valueOf(reg.scrape(), "bot_brain_request_seconds_count"))
    }

    @Test
    fun aCancelledFetchStillTimesAndStillPropagates() = runBlocking {
        val reg = registry()
        val m = MicrometerMetrics(reg)
        try {
            m.memoryFetch { throw IllegalStateException("the caller cancelled") }
            assertTrue(false, "the exception must propagate")
        } catch (_: IllegalStateException) {
            // the caller's cancellation is its own — the timer never swallows it
        }
        assertEquals(1.0, valueOf(reg.scrape(), "bot_memory_fetch_seconds_count"))
    }

    @Test
    fun theNoopRunsTheBlockBareAndMeasuresNothing() = runBlocking {
        val m: Metrics = NoopMetrics
        assertFalse(m.measures, "OFF is OFF: the wiring builds no stateful tap")
        assertEquals("x", m.memoryFetch { "x" })
        assertEquals(2, m.brainRequest { 1 + 1 })
        m.turnStarted()
        m.turnSealed(TurnOutcome.FAILED, null)
        m.busGauges({ 1 }, { 2 })
        m.memoryInjected()
        m.memoryInjectFailed()
        m.brainFailed()
        m.pollerCycle()
        m.pollerInbound()
        m.pollerCallback()
        m.outboxDepth(Lane.MAIN) { 3 }
        m.cooldownActive(Lane.MAIN) { true }
        m.zconnTransition(down = true)
        m.zconnDown { true }
    }

    @Test
    fun theTagsAreTheFacadeSEnums() {
        assertEquals("completed", TurnOutcome.COMPLETED.tag)
        assertEquals("failed", TurnOutcome.FAILED.tag)
        assertEquals("main", Lane.MAIN.tag)
        assertEquals("firehose", Lane.FIREHOSE.tag)
    }
}
