package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The bot's configured owner — the taps the suite sends come from him. */
private const val OWNER = 7L

/** Somebody else's Telegram id: never the owner, whatever the config says. */
private const val STRANGER = 404L

/**
 * The auth sentinel's tap edge (12/11): `auth:<pid>` is the owner's «already
 * attended» verdict on a Mac password dialog — a MARKER the sentinel's loop
 * consumes (state/auth-dismissed), never a password. Three honesties pinned
 * here: a well-formed pid writes exactly the marker the sentinel expects; a
 * malformed or probe pid (0) writes NOTHING; and the tap keeps the benign
 * taps' old law (no owner gate — it owns nothing the way a watch or the
 * natural-wait reply owns nothing). The interceptor itself is the safety:
 * without it the router's fallback would ride the raw data to the agent as
 * the owner's own words.
 */
class AuthTapTest {

    private class FakeOps : ChannelOps {
        val queries = mutableListOf<ChannelQuery>()
        val inbounds = mutableListOf<String>()

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            queries.add(query)
            return QueryAnswer.Failed("internal", "no canned answer")
        }

        override suspend fun sendInbound(
            text: String,
            messageId: String,
            receivedAt: Long,
            attachments: List<InboundAttachment>,
        ): InboundReceipt {
            inbounds.add(text)
            return InboundReceipt.FIRED
        }

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
            bindTaskId: String?,
        ): InboundAnswer {
            inbounds.add(text)
            return InboundAnswer.Answered(duplicate = false, replies = emptyList())
        }

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long): Int = 0
        override suspend fun subscribe(taskId: String, workspacePath: String) {}
    }

    private fun commands(
        o: FakeOps,
        dismisses: MutableList<String>,
    ): BotCommands = BotCommands(
        ops = o,
        hub = LiveHub(),
        daemonReset = { "ladder ok" },
        clock = { 1_000_000L },
        history = object : TasksHistory {
            override suspend fun ctxTasks(limit: Int) = emptyList<TaskRow>()
            override suspend fun modelSelection(sid: String): ModelSel? = null
            override suspend fun freshestTask(workspacePath: String): TaskRow? = null
            override suspend fun taskAlive(sid: String) = true
        },
        ownerUserId = { OWNER },
        authDismiss = { pid -> dismisses.add(pid) },
    )

    @Test
    fun aWellFormedPidWritesExactlyTheMarker() = runBlocking {
        val o = FakeOps()
        val dismisses = mutableListOf<String>()
        val reply = commands(o, dismisses).onCallback("auth:36747", 1L, OWNER)!!
        assertEquals("✅ Anotado — silencio para ese diálogo.", reply.text)
        assertEquals(listOf("36747"), dismisses, "the marker carries the pid, nothing else")
        assertTrue(o.queries.isEmpty(), "no channel query behind a marker")
        assertTrue(o.inbounds.isEmpty(), "nothing rides to the agent — the fallback never sees auth:")
    }

    @Test
    fun aMalformedPidWritesNothingAndSaysSo() = runBlocking {
        val o = FakeOps()
        val dismisses = mutableListOf<String>()
        val reply = commands(o, dismisses).onCallback("auth:not-a-pid", 2L, OWNER)!!
        assertEquals("🔐 Marca fuera de lugar — no escribí nada.", reply.text)
        assertTrue(dismisses.isEmpty(), "a malformed id never becomes a marker")
    }

    @Test
    fun theChannelProbeAnswersWithoutWriting() = runBlocking {
        val o = FakeOps()
        val dismisses = mutableListOf<String>()
        val reply = commands(o, dismisses).onCallback("auth:0", 3L, OWNER)!!
        assertEquals("✅ Prueba recibida — el canal anda.", reply.text)
        assertTrue(dismisses.isEmpty(), "the sentinel's own probe leaves no marker")
    }

    @Test
    fun aStrangersTapKeepsTheBenignTapsOldLaw() = runBlocking {
        val o = FakeOps()
        val dismisses = mutableListOf<String>()
        val reply = commands(o, dismisses).onCallback("auth:36747", 4L, STRANGER)!!
        assertEquals("✅ Anotado — silencio para ese diálogo.", reply.text)
        assertEquals(listOf("36747"), dismisses, "a marker owns nothing — same law as the natural-wait reply")
    }
}
