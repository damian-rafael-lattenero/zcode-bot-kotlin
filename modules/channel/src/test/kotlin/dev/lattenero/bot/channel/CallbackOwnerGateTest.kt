package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bot's configured owner — the taps the suite sends come from him. */
private const val OWNER = 7L

/** Somebody else's Telegram id: never the owner, whatever the config says. */
private const val STRANGER = 404L

/**
 * U9's second lock, on the dispatcher: the DESTRUCTIVE taps re-check their
 * sender (`onCallback`'s `fromId`) against the config's owner — the
 * /deploy precedent applied to every tap that spends something un-spendable
 * (a running turn, a subagent's generation, every run on the Mac, a
 * coding-plan reset). The poller's fail-closed filter stays the wire's
 * WORKING boundary; this lock is the one that still refuses if a future
 * path ever bypasses dispatch. Each refusal is the honest 🚫 line and
 * executes NOTHING behind it (no ops call, no ladder, no state change).
 * The non-destructive taps (attach, view, pickers) keep their old law.
 */
class CallbackOwnerGateTest {

    private class FakeOps : ChannelOps {
        val queries = mutableListOf<ChannelQuery>()
        val inbounds = mutableListOf<String>()

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            queries.add(query)
            return when (query) {
                is ChannelQuery.Stop -> QueryAnswer.Ok("""{"stopped":true}""")
                is ChannelQuery.StopAll -> QueryAnswer.Ok("""{"stopped":["task-77"]}""")
                else -> QueryAnswer.Failed("internal", "no canned answer")
            }
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

    private val status = """
        {"bot":{"id":"daemon-local","enabled":true},
         "context":{"workspacePath":"/Users/damian/Projects/app","activeTaskId":"task-77","mode":"task"}}
    """.trimIndent()

    private fun commands(
        o: FakeOps,
        owner: Long? = OWNER,
        resets: suspend () -> String = { "ladder ok" },
    ): BotCommands = BotCommands(
        ops = o,
        hub = LiveHub(),
        daemonReset = resets,
        clock = { 1_000_000L },
        history = object : TasksHistory {
            override suspend fun ctxTasks(limit: Int) = emptyList<TaskRow>()
            override suspend fun modelSelection(sid: String): ModelSel? = null
            override suspend fun freshestTask(workspacePath: String): TaskRow? = null
            override suspend fun taskAlive(sid: String) = true
        },
        ownerUserId = { owner },
    )

    // ---- the vocabulary's own inventory -----------------------------------------

    @Test
    fun theDestructiveVocabularyIsPinned() {
        // every tap that kills a turn, a lane, every run, or spends a reset
        listOf("intr:task-77", "odp:1", "sst:dispatch-1", "nuc:go", "nuc:no", "stop:go", "gfy:")
            .forEach { assertTrue(isDestructiveTap(it), "$it is destructive") }
        // the FALLBACK's own spellings: the data runs as typed words, so a
        // payload that spells the heavy commands is the same tap
        listOf("/nuclear go", "/nuclear", "/nuke", "/stop", "/stop go", "/stop@bot go")
            .forEach { assertTrue(isDestructiveTap(it), "$it is destructive") }
        // everything reversible or inert keeps the old law
        listOf(
            "t:task-77", "sa:deadbeef", "so:toolu_1", "sn:toolu_1", "odq:1", "qa:q1:0", "qf:q1",
            "qgo:task-77", "ql:tg-1", "qd:tg-1", "vb:verbose", "sao:", "saa:", "pl:opencode-go-chat",
            "pm:kimi-k2", "pe:max", "pd:", "pac:", "pcl:", "mo:task", "cly:", "gfy", "gxn:", "cl:plan",
            "gf:WEEK", "cb:7", "bp:7:2", "bf:7", "nuclear", "stop", "/tasks", "/status", "/help",
        ).forEach { assertFalse(isDestructiveTap(it), "$it is not destructive") }
    }

    // ---- the owner passes, everybody else does not -------------------------------

    @Test
    fun theOwnersDestructiveTapRunsAsAlways() = runBlocking {
        val o = FakeOps()
        val reply = commands(o).onCallback("intr:task-77", 1L, OWNER)!!
        assertEquals("⚡ Interrumpido — tu mensaje entra ya.", reply.text)
        assertEquals(listOf<ChannelQuery>(ChannelQuery.Stop("task-77")), o.queries)
    }

    @Test
    fun anotherUsersTapIsRefusedWithTheHonestLineAndNothingRuns() = runBlocking {
        val o = FakeOps()
        val reply = commands(o).onCallback("intr:task-77", 2L, STRANGER)!!
        assertEquals(BotCommands.NOT_OWNER, reply.text)
        assertNull(reply.markup, "a refusal carries no buttons")
        assertTrue(o.queries.isEmpty(), "no stop, no session, no query")
        assertTrue(o.inbounds.isEmpty(), "nothing rides to the agent either")
    }

    @Test
    fun aNullSenderIsRefused() = runBlocking {
        val o = FakeOps()
        val reply = commands(o).onCallback("sst:dispatch-1", 3L, null)!!
        assertEquals(BotCommands.NOT_OWNER, reply.text)
        assertTrue(o.queries.isEmpty(), "the tail was never even resolved")
    }

    @Test
    fun anUnconfiguredOwnerFailsClosed() = runBlocking {
        val o = FakeOps()
        var runs = 0
        val bc = commands(o, owner = null, resets = { runs += 1; "never" })
        val reply = bc.onCallback("nuc:go", 4L, OWNER)!!
        assertEquals(BotCommands.NOT_OWNER, reply.text)
        assertEquals(0, runs, "the ladder never ran")
        assertTrue(o.queries.isEmpty())
    }

    @Test
    fun theLadderAndTheHardStopAreGatedToo() = runBlocking {
        val o = FakeOps()
        var runs = 0
        val bc = commands(o, resets = { runs += 1; "never" })
        assertEquals(BotCommands.NOT_OWNER, bc.onCallback("nuc:go", 5L, STRANGER)!!.text)
        assertEquals(0, runs, "the ☢️ ladder is the heaviest button — a stranger never runs it")
        assertEquals(BotCommands.NOT_OWNER, bc.onCallback("stop:go", 6L, STRANGER)!!.text)
        assertTrue(o.queries.isEmpty(), "no StopAll, no Stop")
        // the same taps from the owner still answer their own promises
        assertTrue(bc.onCallback("nuc:go", 7L, OWNER)!!.text.startsWith("☢️ Nuclear go — never"), "the owner's go runs")
        assertEquals("💀 Hard stop: 1 run(s) stopped — every active task's turn.",
            bc.onCallback("stop:go", 8L, OWNER)!!.text)
    }

    @Test
    fun aRefusedSubagentStopNeverTouchesTheFork() = runBlocking {
        val o = FakeOps()
        val reply = commands(o).onCallback("sst:dispatch-1", 9L, STRANGER)!!
        assertEquals(BotCommands.NOT_OWNER, reply.text)
        assertTrue(o.queries.none { it is ChannelQuery.Stop }, "no generation was stopped")
    }

    @Test
    fun aSlashPayloadFromAStrangerNeverReachesTheLadder() = runBlocking {
        // the fallback runs the data as typed words: a payload spelling
        // «/nuclear go» is the same heavy tap, gated the same way
        val o = FakeOps()
        var runs = 0
        val bc = commands(o, resets = { runs += 1; "never" })
        assertEquals(BotCommands.NOT_OWNER, bc.onCallback("/nuclear go", 11L, STRANGER)!!.text)
        assertEquals(BotCommands.NOT_OWNER, bc.onCallback("/stop go", 12L, STRANGER)!!.text)
        assertEquals(0, runs, "the ladder never ran")
        assertTrue(o.queries.isEmpty(), "no StopAll")
    }

    // ---- the taps that own nothing keep their old law ----------------------------

    @Test
    fun aNonDestructiveTapFromAStrangerIsUntouched() = runBlocking {
        val o = FakeOps()
        val reply = commands(o).onCallback("qgo:task-77", 10L, STRANGER)!!
        assertEquals("📬 Queda encolado — entra cuando el turno termine.", reply.text)
        assertTrue(o.queries.isEmpty(), "the natural wait is a no-op for anyone who taps it")
    }
}
