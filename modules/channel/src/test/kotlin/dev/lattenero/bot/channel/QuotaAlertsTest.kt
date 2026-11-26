package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE QUOTA WATCHER'S LAWS (audit item 5): announce a crossing ONCE, re-arm
 * only after real recovery, never nag about a plan nobody uses, and treat a
 * provider's refusal as one alert per episode.
 */
class QuotaAlertsTest {

    private fun seen(vararg pcts: Int?): PlanQuota.Mark =
        PlanQuota.Mark.Seen(pcts.map { PlanQuota.Window("⏱", it) }, PlanQuota.Rule.GATES)

    private fun snapshot(
        marks: Map<String, PlanQuota.Mark>,
        plan: String? = "p1",
        labels: Map<String, String> = marks.keys.associateWith { it.uppercase() },
    ) = QuotaSnapshot(marks = marks, labels = labels, plan = plan, model = "m")

    @Test
    fun aTightWindowIsAnnouncedOnce() {
        val snap = snapshot(mapOf("p1" to seen(8, 60)))
        val (first, state) = QuotaAlerts.decide(snap, null, QuotaAlertState(), thresholdPct = 10)
        assertEquals(1, first.size)
        assertTrue("p1".uppercase() in first.first() && "8%" in first.first(), first.first())
        val (second, _) = QuotaAlerts.decide(snap, null, state, thresholdPct = 10)
        assertTrue(second.isEmpty(), "the same window never nags twice: $second")
    }

    @Test
    fun recoveryRearmsOnlyPastTheMargin() {
        val low = snapshot(mapOf("p1" to seen(8)))
        val (_, armed) = QuotaAlerts.decide(low, null, QuotaAlertState(), thresholdPct = 10)
        // 15% is above the line but INSIDE the margin: still armed, silent
        val (inside, state2) = QuotaAlerts.decide(snapshot(mapOf("p1" to seen(15))), null, armed, thresholdPct = 10)
        assertTrue(inside.isEmpty(), inside.toString())
        // 25% is past the margin: re-armed…
        val (_, rearmed) = QuotaAlerts.decide(snapshot(mapOf("p1" to seen(25))), null, state2, thresholdPct = 10)
        // …so the next crossing speaks again
        val (again, _) = QuotaAlerts.decide(low, null, rearmed, thresholdPct = 10)
        assertEquals(1, again.size, again.toString())
    }

    @Test
    fun exhaustedInForceIsAnnouncedOnceAndOnlyForThatPlan() {
        val inForce = snapshot(mapOf("p1" to seen(0), "p2" to seen(50)))
        val (first, state) = QuotaAlerts.decide(inForce, null, QuotaAlertState(), thresholdPct = 10)
        assertTrue(first.any { "ran out of tokens" in it }, first.toString())
        assertEquals(2, first.size, "the tight window AND the exhausted plan speak: $first")
        val (second, _) = QuotaAlerts.decide(inForce, null, state, thresholdPct = 10)
        assertTrue(second.isEmpty(), second.toString())

        // p2 dried up but is NOT in force: its tight window still speaks ONCE
        // (the ladder's own data), but the exhausted-in-force line never does
        val elsewhere = snapshot(mapOf("p2" to seen(0)), plan = "p1")
        val (quiet, _) = QuotaAlerts.decide(elsewhere, null, QuotaAlertState(), thresholdPct = 10)
        assertEquals(1, quiet.size, "one tight notice: $quiet")
        assertTrue(quiet.none { "ran out of tokens" in it }, "no in-force page for a plan nobody uses: $quiet")
    }

    @Test
    fun aProviderLimitIsOneAlertPerEpisode() {
        val snap = snapshot(mapOf("p1" to seen(80)))
        val reason = "[1310][Weekly/Monthly Limit Exhausted]"
        val (first, state) = QuotaAlerts.decide(snap, reason, QuotaAlertState(), thresholdPct = 10)
        assertEquals(1, first.size, first.toString())
        assertTrue("1310" in first.first(), first.first())
        // the tracker repeats the same reason every tick: one alert, not one per tick
        val (second, state2) = QuotaAlerts.decide(snap, reason, state, thresholdPct = 10)
        assertTrue(second.isEmpty(), second.toString())
        // a DIFFERENT refusal is a new episode
        val (third, _) = QuotaAlerts.decide(snap, "[429] rate limited", state2, thresholdPct = 10)
        assertEquals(1, third.size, third.toString())
    }

    @Test
    fun aRecoveredWindowClimbsBackDownTheScale() {
        val (_, state) = QuotaAlerts.decide(snapshot(mapOf("p1" to seen(9))), null, QuotaAlertState(), thresholdPct = 10)
        assertEquals(listOf("p1"), state.tight)
        val (alerts, recovered) = QuotaAlerts.decide(snapshot(mapOf("p1" to seen(90))), null, state, thresholdPct = 10)
        assertTrue(alerts.isEmpty())
        assertTrue(recovered.tight.isEmpty(), "90% is past the margin: disarmed")
    }
}
