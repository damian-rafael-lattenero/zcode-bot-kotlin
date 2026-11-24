package dev.lattenero.bot.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The boot re-attach's decision (measured 24/11: a deploy mid-turn sealed the
 * phone's card and a RUNNING turn never re-births it — 46 minutes of silence).
 * The target comes from the bot's own status answer; the airing set from the
 * fork's ActiveTaskIds reply. Both parsers tear to "nothing" — a boot step
 * must never invent work.
 */
class BootReattachTest {

    private val status = """
        {"bot":{"id":"daemon-local","enabled":true},
         "context":{"workspacePath":"/tmp/ws","activeTaskId":"sess_abc","mode":"yolo"}}
    """.trimIndent()

    @Test
    fun theActiveConversationParsesTheStatusContext() {
        assertEquals(ReattachTarget("sess_abc", "/tmp/ws"), activeConversationOf(status))
    }

    @Test
    fun aStatusWithoutAnActiveConversationHasNothingToReattach() {
        assertNull(activeConversationOf("""{"context":{"workspacePath":"/tmp/ws"}}"""))
        assertNull(activeConversationOf("""{"context":{"activeTaskId":"","workspacePath":"/tmp/ws"}}"""))
        assertNull(activeConversationOf("""{"bot":{}}"""))
        assertNull(activeConversationOf("garbage"))
        assertNull(activeConversationOf(null))
    }

    @Test
    fun theAiringSetReadsTheForksArrayAndTearsToEmpty() {
        assertEquals(setOf("sess_abc", "sess_def"), runningTaskIdsOf("""["sess_abc","sess_def"]"""))
        assertTrue(runningTaskIdsOf("nope").isEmpty())
        assertTrue(runningTaskIdsOf(null).isEmpty())
        assertTrue(runningTaskIdsOf("""{"not":"an array"}""").isEmpty())
    }

    @Test
    fun onlyAnAiringConversationIsReattached() {
        // the wiring's own line: `active?.takeIf { it.taskId in runningTaskIdsOf(...) }`
        val active = activeConversationOf(status)!!
        assertEquals("sess_abc", active.takeIf { it.taskId in runningTaskIdsOf("""["sess_abc"]""") }?.taskId)
        assertNull(active.takeIf { it.taskId in runningTaskIdsOf("""["other_task"]""") }) // idle: no card
        assertNull(active.takeIf { it.taskId in runningTaskIdsOf(null) }) // a dead channel invents nothing
    }

    @Test
    fun theBootRecoveryCarriesEveryAiringConversationAndOnlyAnAiringOwn() {
        val both = bootRecoveryOf(status, """["sess_abc","sess_other"]""")
        assertEquals("sess_abc", both.own?.taskId) // the bot's own conversation was airing → the card
        assertEquals(setOf("sess_abc", "sess_other"), both.airing) // EVERY airing one re-subscribes

        val ownIdle = bootRecoveryOf(status, """["sess_other"]""")
        assertNull(ownIdle.own) // own idle: no card...
        assertEquals(setOf("sess_other"), ownIdle.airing) // ...but the other one still recovers its questions

        val nothing = bootRecoveryOf(status, null)
        assertNull(nothing.own)
        assertTrue(nothing.airing.isEmpty())
    }
}
