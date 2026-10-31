package dev.lattenero.bot.channel

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tap vocabulary's reply contract, ON the dispatcher (KeyboardsTest
 * pins the pure table, BotCommandsTest the t: re-bind): the F17 pair
 * (intr:/qgo:), the ☢️ pair and the 💀 hard stop — every tap arrives as
 * the owner's typed words (R5's table) and answers its own promise. The
 * OLD CoT block tap (cb:) reaches the dispatcher only to answer the
 * RETIRED note honestly — the transcript feed carries the blocks as
 * their own messages now.
 */
class CallbackTapsTest {

    private class FakeOps : ChannelOps {
        var available = true
        val queries = mutableListOf<ChannelQuery>()
        val answers = mutableMapOf<String, String>()

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            queries.add(query)
            if (!available) return QueryAnswer.Unavailable
            return answers[queryKind(query)]?.let { QueryAnswer.Ok(it) }
                ?: QueryAnswer.Failed("internal", "no canned answer")
        }

        override suspend fun sendInbound(text: String, messageId: String, receivedAt: Long) = InboundReceipt.FIRED

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
        ) = InboundAnswer.Answered(duplicate = false, replies = emptyList())

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long) = 0

        override suspend fun subscribe(taskId: String, workspacePath: String) {}
    }

    private val status = """
        {"bot":{"id":"daemon-local","enabled":true},
         "context":{"workspacePath":"/Users/damian/Projects/app","activeTaskId":"task-77","mode":"task"}}
    """.trimIndent()

    private fun commands(o: FakeOps, resets: suspend () -> String = { "ladder ok" }): BotCommands = BotCommands(
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
    )

    @Test
    fun theOldBlockTapAnswersTheRetiredNoteHonestly() = runBlocking {
        val hub = LiveHub()
        val o = FakeOps()
        val cmds = BotCommands(
            ops = o,
            hub = hub,
            daemonReset = { "ladder ok" },
            clock = { 1_000_000L },
        )
        // one block sealed: 3000 cp of streamed thinking through the hub
        hub.apply(
            ServerFrame.StreamEvent(
                "task-9",
                1,
                100L,
                buildJsonObject {
                    put("type", "agent_thought_chunk")
                    put("content", "x".repeat(3000))
                },
            ),
            100L,
        )
        // the OLD block tap is RETIRED (the transcript feed carries the
        // blocks as their own messages): the stale button answers honestly,
        // no channel query, the hub untouched
        val reply = cmds.onCallback("cb:1", 1L)!!
        assertTrue("mensajes propios" in reply.text)
        assertTrue(o.queries.isEmpty())
        assertEquals("x".repeat(3000), hub.peek()!!.cotBlocks[0])
    }
    @Test
    fun thePushTapStopsTheTurnAndPromisesTheFire() = runBlocking {
        val o = FakeOps().apply {
            answers["botStatus"] = status
            answers["stop"] = """{"stopped":["task-77"]}"""
        }
        val reply = commands(o).onCallback("intr:task-77", 1L)!!
        assertEquals("⚡ Interrumpido — tu mensaje entra ya.", reply.text)
        assertEquals(
            listOf<ChannelQuery>(ChannelQuery.Stop("task-77")),
            o.queries,
            "the push IS the stop — the drain fires the parked text",
        )
    }

    @Test
    fun aRefusedPushPointsAtTheHardWay() = runBlocking {
        val o = FakeOps().apply { answers["botStatus"] = status } // no canned stop -> Failed
        val reply = commands(o).onCallback("intr:task-77", 2L)!!
        assertTrue("⚠️ No pude interrumpir (internal)" in reply.text, reply.text)
        assertTrue("/stop go es la vía dura" in reply.text)
    }

    @Test
    fun theKeepQueuedTapSpeaksWithoutTouchingTheChannel() = runBlocking {
        val o = FakeOps().apply { answers["botStatus"] = status }
        val reply = commands(o).onCallback("qgo:task-77", 3L)!!
        assertEquals("📬 Queda encolado — entra cuando el turno termine.", reply.text)
        assertTrue(o.queries.isEmpty(), "the natural wait is a no-op by design")
    }

    @Test
    fun theConfirmTapRunsTheWholeLadderThroughTheTable() = runBlocking {
        val o = FakeOps().apply { answers["botStatus"] = status }
        var runs = 0
        val reply = commands(o, resets = { runs += 1; "2 stopped, app booted" })
            .onCallback("nuc:go", 4L)!!
        assertEquals(1, runs)
        assertTrue(reply.text.startsWith("☢️ Nuclear go — 2 stopped, app booted"), reply.text)
    }

    @Test
    fun theCancelTapTouchesNothing() = runBlocking {
        val o = FakeOps().apply { answers["botStatus"] = status }
        var runs = 0
        val reply = commands(o, resets = { runs += 1; "never" }).onCallback("nuc:no", 5L)!!
        assertEquals("🙅 Cancelled — nothing was touched.", reply.text)
        assertEquals(0, runs)
        assertTrue(o.queries.isEmpty())
    }

    @Test
    fun theSkullTapIsTheHardAll() = runBlocking {
        val o = FakeOps().apply {
            answers["botStatus"] = status
            answers["stopAll"] = """{"stopped":["task-77","task-02"]}"""
        }
        val reply = commands(o).onCallback("stop:go", 6L)!!
        assertEquals("💀 Hard stop: 2 run(s) stopped — every active task's turn.", reply.text)
        assertTrue(o.queries.any { it is ChannelQuery.StopAll })
    }

    @Test
    fun aSecondNuclearWhileOneRunsIsRefusedInFlight() = runBlocking {
        // the 08:42 lesson: the button stays on screen ~40s and impatience is
        // indistinguishable from a loop — one ladder at a time, honestly refused
        val o = FakeOps().apply { answers["botStatus"] = status }
        val bc = commands(o, resets = {
            kotlinx.coroutines.delay(100)
            "slow ladder"
        })
        val first = async { bc.handle("/nuclear go", 7L) }
        kotlinx.coroutines.delay(20)
        val second = bc.handle("/nuclear go", 8L)!!
        val one = first.await()!!
        assertTrue(one.text.contains("slow ladder"), one.text)
        assertEquals("☢️ Ya hay un nuclear corriendo — esperá el resumen antes de lanzar otro.", second.text)
        // the gate LIFTS when the ladder ends: a third trigger runs again
        val third = bc.handle("/nuclear go", 9L)!!
        assertTrue(third.text.contains("slow ladder"), third.text)
    }
}
