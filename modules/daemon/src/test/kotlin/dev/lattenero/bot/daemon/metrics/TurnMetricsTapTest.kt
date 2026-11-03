package dev.lattenero.bot.daemon.metrics

import dev.lattenero.bot.channel.ServerFrame
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The turn tap: the bus producer's dedupe laws, counted. A simulated
 * conversation (resubscribe bursts included) yields exactly one start and
 * one seal per real turn, with the witnessed duration on the timer and
 * the mid-flight attach sealing without one.
 */
class TurnMetricsTapTest {

    private val reg = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    private val m = MicrometerMetrics(reg)
    private val tap = TurnMetricsTap(m)

    private fun valueOf(scrape: String, metric: String): Double? =
        reg.scrape().lineSequence()
            .filter { it.startsWith(metric) }
            .firstOrNull()
            ?.substringAfterLast(' ')
            ?.toDoubleOrNull()

    private fun started(taskId: String, at: Long, forkTurn: String? = null): ServerFrame.StreamEvent =
        ServerFrame.StreamEvent(
            taskId,
            seq = 1,
            at = at,
            event = buildJsonObject {
                put("type", "task_run_started")
                put("startedAt", at)
                forkTurn?.let { put("turnId", it) }
            },
        )

    private fun completed(taskId: String, at: Long, seq: Long = 9): ServerFrame.StreamEvent =
        ServerFrame.StreamEvent(taskId, seq, at, buildJsonObject { put("type", "task_complete") })

    private fun failed(taskId: String, at: Long): ServerFrame.StreamEvent =
        ServerFrame.StreamEvent(taskId, seq = 9, at = at, buildJsonObject { put("type", "task_error") })

    @Test
    fun aFullTurnCountsStartSealAndTheRealDuration() {
        tap.onFrame(started("conv-1", at = 1_000))
        tap.onFrame(completed("conv-1", at = 4_500))
        val scrape = reg.scrape()
        assertEquals(1.0, valueOf(scrape, "bot_turns_started_total"))
        assertEquals(1.0, valueOf(scrape, """bot_turns_sealed_total{outcome="completed"}"""))
        assertEquals(3.5, valueOf(scrape, """bot_turn_duration_seconds_sum{outcome="completed"}"""))
    }

    @Test
    fun aFailedTurnSealsAsFailed() {
        tap.onFrame(started("conv-1", at = 1_000))
        tap.onFrame(failed("conv-1", at = 2_000))
        val scrape = reg.scrape()
        assertEquals(1.0, valueOf(scrape, """bot_turns_sealed_total{outcome="failed"}"""))
        assertEquals(1.0, valueOf(scrape, """bot_turn_duration_seconds_count{outcome="failed"}"""))
    }

    @Test
    fun aResubscribeBurstCountsTheLiveTurnOnce() {
        tap.onFrame(started("conv-1", at = 1_000, forkTurn = "t7"))
        tap.onFrame(started("conv-1", at = 1_000, forkTurn = "t7")) // the burst's replay
        tap.onFrame(started("conv-1", at = 1_000, forkTurn = "t7"))
        tap.onFrame(completed("conv-1", at = 4_000))
        val scrape = reg.scrape()
        assertEquals(1.0, valueOf(scrape, "bot_turns_started_total"))
        assertEquals(1.0, valueOf(scrape, """bot_turns_sealed_total{outcome="completed"}"""))
    }

    @Test
    fun aBurstReplayingAnAlreadySealedTurnPublishesNothing() {
        tap.onFrame(started("conv-1", at = 1_000, forkTurn = "t7"))
        tap.onFrame(completed("conv-1", at = 4_000))
        // the reconnect arrives late and replays the WHOLE turn
        tap.onFrame(started("conv-1", at = 1_000, forkTurn = "t7"))
        tap.onFrame(completed("conv-1", at = 4_000))
        val scrape = reg.scrape()
        assertEquals(1.0, valueOf(scrape, "bot_turns_started_total"))
        assertEquals(1.0, valueOf(scrape, """bot_turns_sealed_total{outcome="completed"}"""))
        assertEquals(3.0, valueOf(scrape, """bot_turn_duration_seconds_sum{outcome="completed"}"""))
    }

    @Test
    fun aTurnWitnessedOnlyAtItsEndSealsWithoutADuration() {
        // a subscription attached mid-flight: the terminal frame arrives with no live turn
        tap.onFrame(completed("conv-1", at = 9_000))
        val scrape = reg.scrape()
        assertEquals(0.0, valueOf(scrape, "bot_turns_started_total") ?: 0.0)
        assertEquals(1.0, valueOf(scrape, """bot_turns_sealed_total{outcome="completed"}"""))
        assertEquals(null, valueOf(scrape, """bot_turn_duration_seconds_count{outcome="completed"}"""))
    }

    @Test
    fun aSecondTurnInTheSameConversationCountsNormally() {
        tap.onFrame(started("conv-1", at = 1_000, forkTurn = "t1"))
        tap.onFrame(completed("conv-1", at = 2_000))
        tap.onFrame(started("conv-1", at = 5_000, forkTurn = "t2"))
        tap.onFrame(completed("conv-1", at = 6_500))
        val scrape = reg.scrape()
        assertEquals(2.0, valueOf(scrape, "bot_turns_started_total"))
        assertEquals(2.0, valueOf(scrape, """bot_turns_sealed_total{outcome="completed"}"""))
        assertEquals(2.5, valueOf(scrape, """bot_turn_duration_seconds_sum{outcome="completed"}"""))
    }

    @Test
    fun differentConversationsAreIndependent() {
        tap.onFrame(started("conv-1", at = 1_000))
        tap.onFrame(started("conv-2", at = 1_100))
        tap.onFrame(completed("conv-2", at = 3_100))
        assertEquals(2.0, valueOf(reg.scrape(), "bot_turns_started_total"))
        assertEquals(1.0, valueOf(reg.scrape(), """bot_turns_sealed_total{outcome="completed"}"""))
    }
}
