package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 2026-11-05 — the DEDICATED CONVERSATION's plan pinned: the pin IS the
 * destination (bind first — the context may aim anywhere), a mid-turn pin
 * parks (F17), a down channel refuses honestly, and the table never
 * consults the app's open conversation. [planRoutePinned] is the pure core;
 * BotCommands' ladder (armed order > /tasks new > pin > router) is the
 * wiring's layering.
 */
class RoutingPinnedTest {

    private fun facts(
        reachable: Boolean = true,
        running: Set<String> = emptySet(),
    ) = RouteFacts(
        contextReachable = reachable,
        contextPresent = true,
        workspacePath = "/ws",
        activeTaskId = "sess_other", // the context aims SOMEWHERE ELSE — the pin ignores it
        activeAlive = true,
        running = running,
        openSid = "sess_open",
        openTitle = "the app's open conversation",
    )

    @Test
    fun thePinIsTheDestinationEvenWhenTheContextAimsElsewhere() {
        val plan = planRoutePinned(facts(), "sess_bot")
        assertEquals(RoutePlan.Fire("sess_bot", bind = true, reason = "pinned conversation (bind_task_id)"), plan)
    }

    @Test
    fun aMidTurnPinParksTheTextNotReaims() {
        val plan = planRoutePinned(facts(running = setOf("sess_bot")), "sess_bot")
        assertEquals(RoutePlan.Enqueue("sess_bot", "pinned conversation is mid-turn (F17)"), plan)
    }

    @Test
    fun aDownChannelRefusesHonest() {
        val plan = planRoutePinned(facts(reachable = false), "sess_bot")
        assertTrue(plan is RoutePlan.Refuse)
        assertEquals(RouteTexts.CHANNEL_DOWN, (plan as RoutePlan.Refuse).reason)
    }

    @Test
    fun theOpenConversationNeverStealsThePin() {
        // the follow-the-open law's exact theft scenario: open != pin — the
        // pinned plan still fires AT THE PIN (the helper's facts already carry
        // activeTaskId=sess_other + openSid=sess_open, both != the pin)
        val plan = planRoutePinned(facts(), "sess_bot")
        assertEquals("sess_bot", (plan as RoutePlan.Fire).sid)
    }
}
