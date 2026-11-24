package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * /watch — THE STREAMING CABLE (24/11): the owner's lever when the live
 * card stops receiving. It fires by hand what the stream watchdog fires on
 * its own — re-subscribe what the card shows, hold it, draw NOW — so the
 * fork's burst re-states the in-flight turn.
 */
class WatchCommandTest {

    private class FakeOps(private val status: String) : ChannelOps {
        val subscribed = mutableListOf<Pair<String, String>>()
        val childSubscribed = mutableListOf<Pair<String, String>>()

        override suspend fun query(query: ChannelQuery): QueryAnswer =
            when (queryKind(query)) {
                "botStatus" -> QueryAnswer.Ok(status)
                else -> QueryAnswer.Failed("internal", "no canned answer")
            }

        override suspend fun sendInbound(
            text: String,
            messageId: String,
            receivedAt: Long,
            attachments: List<InboundAttachment>,
        ): InboundReceipt = InboundReceipt.FIRED

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
            bindTaskId: String?,
        ): InboundAnswer = InboundAnswer.Answered(duplicate = false, replies = emptyList())

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long): Int = 1

        override suspend fun subscribe(taskId: String, workspacePath: String) {
            subscribed.add(taskId to workspacePath)
        }

        override suspend fun subscribeChild(taskId: String, workspacePath: String) {
            childSubscribed.add(taskId to workspacePath)
        }
    }

    private class FakeHistory(private val rows: List<TaskRow>) : TasksHistory {
        override suspend fun ctxTasks(limit: Int): List<TaskRow> = rows.take(limit)
        override suspend fun modelSelection(sid: String): ModelSel? = null
        override suspend fun freshestTask(workspacePath: String): TaskRow? = rows.firstOrNull()
        override suspend fun taskAlive(sid: String): Boolean = true
        override suspend fun subagentSessionIds(parentSid: String): List<String> = emptyList()
        override suspend fun subagentsRunning(parentSid: String): List<RunningSubagent> = emptyList()
    }

    private val cardRow = TaskRow(sid = "task-A", title = "the card's turn", ws = "/ws/a")

    private val contextStatus = """
        {"bot":{"enabled":true},
         "context":{"workspacePath":"/ws/a","activeTaskId":"task-B","mode":"full_access"}}
    """.trimIndent()

    private fun commands(ops: ChannelOps, history: TasksHistory, hub: LiveHub) = BotCommands(
        ops = ops,
        hub = hub,
        daemonReset = { "reset" },
        history = history,
        defaultWorkspacePath = "/Users/x/loose",
    )

    @Test
    fun theCardsOwnTaskIsReattached() = runBlocking {
        val hub = LiveHub()
        hub.watchTaskId("task-A", 1_000L)
        val ops = FakeOps(contextStatus)
        val reply = commands(ops, FakeHistory(listOf(cardRow)), hub).handle("/watch", 1)
        assertEquals(listOf("task-A" to "/ws/a"), ops.subscribed)
        assertTrue(reply!!.text.contains("the card's turn"), reply.text)
        assertTrue(reply.text.contains("re-enganchado"), reply.text)
    }

    @Test
    fun withNoCardTheActiveConversationIsTheTarget() = runBlocking {
        val hub = LiveHub()
        val ops = FakeOps(contextStatus)
        val active = TaskRow(sid = "task-B", title = "the open one", ws = "/ws/a")
        val reply = commands(ops, FakeHistory(listOf(active)), hub).handle("/watch", 2)
        assertEquals(listOf("task-B" to "/ws/a"), ops.subscribed)
        assertTrue(reply!!.text.contains("the open one"), reply.text)
        assertTrue(reply.text.contains("su próximo turno abre la carta"), reply.text)
    }

    @Test
    fun aPinnedChildReattachesItsOwnTopicAndTheParentFocusSurvives() = runBlocking {
        val hub = LiveHub()
        hub.pin("sess_subagent_abc")
        val ops = FakeOps(contextStatus)
        val reply = commands(ops, FakeHistory(listOf(cardRow)), hub).handle("/watch", 3)
        assertEquals(emptyList(), ops.subscribed, "the child topic never moves the parent's focus")
        assertEquals(listOf("sess_subagent_abc" to "/ws/a"), ops.childSubscribed)
        assertTrue(reply!!.text.contains("Subagente"), reply.text)
        assertNull(hub.peekPin()?.takeIf { it != "sess_subagent_abc" }, "the pin stays: the same child")
    }

    @Test
    fun aFireMovesTheCardsHoldToTheFiredConversation() = runBlocking {
        // the card was held on task-A; the owner's text fires into task-B (the
        // bot's active conversation). The FOCUS moves — without moving the
        // hold, the card froze on A with its pipe gone (the 24/11 bug).
        val hub = LiveHub()
        hub.watchTaskId("task-A", 1_000L)
        val ops = FakeOps(contextStatus)
        val rows = listOf(TaskRow(sid = "task-B", title = "the open one", ws = "/ws/a"), cardRow)
        val reply = commands(ops, FakeHistory(rows), hub).handle("hola", 5)
        assertNull(reply, "a fired text speaks via the live view, no ack")
        assertEquals("task-B", hub.peek()?.taskId, "the card follows the text")
        assertEquals("task-B" to "/ws/a", ops.subscribed.last(), "the fresh focus is the fired conversation")
    }

    @Test
    fun nothingToWatchSaysSoHonestly() = runBlocking {
        val hub = LiveHub()
        val ops = FakeOps("""{"bot":{"enabled":true},"context":null}""")
        val reply = commands(ops, FakeHistory(emptyList()), hub).handle("/watch", 4)
        assertEquals(BotCommands.WATCH_NOTHING, reply!!.text)
        assertEquals(emptyList(), ops.subscribed)
        assertEquals(emptyList(), ops.childSubscribed)
    }
}
