package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The router's DECISION TABLE — [planRoute] is pure, so every row of
 * the owner's 29/10 matrix is one frozen fact set: the cutover's live
 * failure (bound task deleted → tombstone → accidental new
 * conversation), Python's only_active_task busy path, the rebind
 * refusal, and the honest refusals.
 */
class RoutingTest {

    private fun facts(
        reachable: Boolean = true,
        present: Boolean = true,
        active: String? = "task-77",
        alive: Boolean = true,
        running: Set<String> = emptySet(),
        open: String? = "task-77",
    ) = RouteFacts(
        contextReachable = reachable,
        contextPresent = present,
        workspacePath = "/ws",
        activeTaskId = active,
        activeAlive = alive,
        running = running,
        openSid = open,
        openTitle = open?.let { "title-$it" },
    )

    @Test
    fun degradedChannelRefusesWithoutTouchingAnything() {
        val plan = planRoute(RouteFacts.DEGRADED, newIntent = false)
        val refuse = assertIs<RoutePlan.Refuse>(plan)
        assertEquals(RouteTexts.CHANNEL_DOWN, refuse.reason)
    }

    @Test
    fun noContextRefuses() {
        val plan = planRoute(facts(reachable = true, present = false), newIntent = false)
        val refuse = assertIs<RoutePlan.Refuse>(plan)
        assertEquals(RouteTexts.NO_CONTEXT, refuse.reason)
    }

    @Test
    fun aimedAtTheOpenConversationFiresWithoutBind() {
        val plan = planRoute(facts(), newIntent = false)
        val fire = assertIs<RoutePlan.Fire>(plan)
        assertEquals("task-77", fire.sid)
        assertEquals(false, fire.bind)
    }

    @Test
    fun openConversationMovedRebindsBeforeTheText() {
        val plan = planRoute(facts(active = "task-02", open = "task-77"), newIntent = false)
        val fire = assertIs<RoutePlan.Fire>(plan)
        assertEquals("task-77", fire.sid)
        assertEquals(true, fire.bind)
    }

    @Test
    fun midTurnOpenConversationParksTheText() {
        val plan = planRoute(facts(running = setOf("task-77")), newIntent = false)
        val enqueue = assertIs<RoutePlan.Enqueue>(plan)
        assertEquals("task-77", enqueue.sid)
    }

    @Test
    fun runningOldTaskBlocksTheReAimOnlyActiveTaskFallback() {
        val plan = planRoute(
            facts(active = "task-02", open = "task-77", running = setOf("task-02")),
            newIntent = false,
        )
        val busy = assertIs<RoutePlan.BusyRebind>(plan)
        assertEquals("task-02", busy.active)
        assertEquals("task-77", busy.wantedSid)
    }

    @Test
    fun deletedBindPlusOpenConversationIsTheTombstoneRebind() {
        // the LIVE failure of 29/10: «probando probando» created a fresh
        // conversation because the bound one was deleted
        val plan = planRoute(facts(alive = false, open = "task-open"), newIntent = false)
        val fire = assertIs<RoutePlan.Fire>(plan)
        assertEquals("task-open", fire.sid)
        assertEquals(true, fire.bind)
        assertTrue(fire.reason.startsWith("tombstone"))
    }

    @Test
    fun deletedBindWithNothingOpenRefusesNeverCreates() {
        val plan = planRoute(facts(alive = false, open = null), newIntent = false)
        val refuse = assertIs<RoutePlan.Refuse>(plan)
        assertEquals(RouteTexts.BOUND_GONE_NO_OPEN, refuse.reason)
    }

    @Test
    fun deletedButRunningTrustsTheSessionsIndex() {
        // a mid-turn task is never deleted — the index lags, park instead
        val plan = planRoute(
            facts(alive = false, running = setOf("task-77"), open = null),
            newIntent = false,
        )
        val enqueue = assertIs<RoutePlan.Enqueue>(plan)
        assertEquals("task-77", enqueue.sid)
    }

    @Test
    fun draftContextBindsToTheOpenConversation() {
        val plan = planRoute(facts(active = null), newIntent = false)
        val fire = assertIs<RoutePlan.Fire>(plan)
        assertEquals("task-77", fire.sid)
        assertEquals(true, fire.bind)
    }

    @Test
    fun draftContextWithNothingOpenRefusesLikePython() {
        val plan = planRoute(facts(active = null, open = null), newIntent = false)
        val refuse = assertIs<RoutePlan.Refuse>(plan)
        assertEquals(RouteTexts.NO_ACTIVE_CONVERSATION, refuse.reason)
    }

    @Test
    fun newIntentFiresIntoTheDraftNoBind() {
        val plan = planRoute(facts(active = null, open = "task-77"), newIntent = true)
        val fire = assertIs<RoutePlan.Fire>(plan)
        assertEquals(DRAFT_TARGET, fire.sid)
        assertEquals(false, fire.bind)
    }

    @Test
    fun noIndexFallsBackToTrustingTheLiveBind() {
        // freshest=null with a live bound task: fire at what is bound —
        // the bind is the server's own truth, the index is a hint
        val plan = planRoute(facts(open = null), newIntent = false)
        val fire = assertIs<RoutePlan.Fire>(plan)
        assertEquals("task-77", fire.sid)
        assertEquals(false, fire.bind)
    }
}
