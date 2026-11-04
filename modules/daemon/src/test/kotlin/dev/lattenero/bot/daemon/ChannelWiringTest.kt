package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.LiveHub
import dev.lattenero.bot.channel.QueryAnswer
import dev.lattenero.bot.channel.ServerFrame
import dev.lattenero.bot.channel.ToolLive
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The composition's pure helpers: the seq-gap resync rule, the final
 * render through the SPECTATOR's renderer, and the snapshot's last
 * assistant message.
 */
class ChannelWiringTest {

    @Test
    fun aSeqJumpResyncsTheLiveView() = runTest {
        val hub = LiveHub()
        // seq 1..3 arrive
        (1L..3L).forEach { seq ->
            hub.apply(
                ServerFrame.StreamEvent("task-9", seq, at = 100L, event = buildJsonObject { put("type", "task_run_started") }),
                100L,
            )
        }
        val before = hub.peek()!!
        assertEquals(3L, before.lastSeq)
        // a reconnect lost 4..9: the next seen is 10 — the gap re-baselines
        feedAndResync(
            hub,
            ServerFrame.StreamEvent("task-9", 10, at = 200L, event = buildJsonObject { put("type", "agent_thought_chunk"); put("content", "post") }),
            200L,
        )
        val after = hub.peek()!!
        assertEquals(10L, after.lastSeq) // the stream continues from here
        assertEquals("post", after.cotTail) // the LOST CoT stayed lost; the new event landed
    }

    @Test
    fun aCleanSeqContinuesWithoutResync() = runTest {
        val hub = LiveHub()
        hub.apply(ServerFrame.StreamEvent("t", 1, 100L, buildJsonObject { put("type", "task_run_started") }), 100L)
        hub.apply(
            ServerFrame.StreamEvent("t", 2, 110L, buildJsonObject { put("type", "agent_thought_chunk"); put("content", "pienso") }),
            110L,
        )
        assertEquals("pienso", hub.peek()!!.cotTail) // no gap: nothing was thrown away
    }

    @Test
    fun feedBoxesRideTheSpectatorsRendererWithTheVerbatimTail() {
        val closed = ToolLive(
            "t1",
            "Write",
            openedAtMs = 1,
            status = "completed",
            input = buildJsonObject { put("content", "line") },
            output = "the stream's verbatim tail",
        )
        val box = feedBoxOf(closed)
        assertTrue("✅" in box)
        // the spectator renderer's own signature: the numbered green lines of a Write
        assertTrue(Regex("""\d+.*line""").containsMatchIn(box))
        // the transcript's REAL content: the result update's tail rides
        assertTrue("the stream's verbatim tail" in box)
        // the input's own output/result keys are the box's — the tail never doubles
        val selfContained = feedBoxOf(
            ToolLive(
                "t2",
                "Bash",
                openedAtMs = 2,
                status = "failed",
                input = buildJsonObject { put("command", "ls"); put("output", "file") },
                output = "file",
            ),
        )
        assertTrue("❌" in selfContained)
        assertEquals(1, Regex("file").findAll(selfContained).count(), "the output appears exactly once")
        // an input-less lane renders nothing (the feed falls back to its own line)
        assertEquals("", feedBoxOf(ToolLive("t3", "Read", openedAtMs = 3, status = "completed")))
    }

    @Test
    fun snapshotAnswerFindsTheLastAssistantMessage() {
        val snapshot = """
            {"messages":[
              {"role":"user","content":"arreglá el login","timestamp":1},
              {"role":"assistant","content":"ya está","timestamp":2},
              {"role":"user","content":"gracias","timestamp":3}
            ]}
        """.trimIndent()
        assertEquals("ya está", snapshotAnswerOf(snapshot))
    }

    @Test
    fun snapshotAnswerDegradesToNull() {
        assertNull(snapshotAnswerOf("""{"messages":[]}"""))
        assertNull(snapshotAnswerOf("""{"messages":[{"role":"user","content":"x"}]}"""))
        assertNull(snapshotAnswerOf("""{"messages":[{"role":"assistant","content":"  "}]}"""))
        assertNull(snapshotAnswerOf("not json"))
    }

    // ── THE DEFAULT-MODE ENFORCEMENT (4/11: «por defecto siempre Full access») ──

    /** The fake query leg: canned snapshot answers, the SetMode recorder. */
    private class FakeQuery {
        val setModes = mutableListOf<Pair<String, String>>()
        var snapshotAnswer: QueryAnswer = QueryAnswer.Ok("""{"meta":{"mode":"build"},"messages":[]}""")
        var setModeAnswer: QueryAnswer = QueryAnswer.Ok("""{"ok":true}""")

        suspend fun leg(query: dev.lattenero.bot.channel.ChannelQuery): QueryAnswer = when (query) {
            is dev.lattenero.bot.channel.ChannelQuery.SetMode -> {
                setModes.add(query.taskId to query.mode)
                setModeAnswer
            }

            else -> snapshotAnswer
        }
    }

    private val guard = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private suspend fun enforce(q: FakeQuery, taskId: String = "task-77") {
        enforceDefaultModeOnRunStart(
            taskId,
            guard = guard,
            query = { query -> q.leg(query) },
            workspaceOf = { "/ws" },
            log = { },
        )
    }

    @Test
    fun aTaskWithoutMetaModeGetsYoloWrittenOnce() = runTest {
        val q = FakeQuery().apply { snapshotAnswer = QueryAnswer.Ok("""{"meta":{},"messages":[]}""") }
        enforce(q)
        assertEquals(listOf("task-77" to "yolo"), q.setModes, "the default IS Full access — written, not assumed")
        enforce(q) // the second run_started never re-fires: once per task
        assertEquals(1, q.setModes.size)
    }

    @Test
    fun anOwnerChosenModeIsNeverStomped() = runTest {
        val q = FakeQuery().apply { snapshotAnswer = QueryAnswer.Ok("""{"meta":{"mode":"build"},"messages":[]}""") }
        enforce(q)
        assertEquals(0, q.setModes.size, "the mode IS in meta.mode — the fork's truth wins")
    }

    @Test
    fun aFailedSnapshotReadNeverFiresAndNeverLatches() = runTest {
        val q = FakeQuery().apply { snapshotAnswer = QueryAnswer.Unavailable }
        enforce(q)
        assertEquals(0, q.setModes.size, "nothing fires on a dead channel")
        // the guard stayed free: the NEXT turn (channel back) fires
        q.snapshotAnswer = QueryAnswer.Ok("""{"meta":{},"messages":[]}""")
        enforce(q)
        assertEquals(listOf("task-77" to "yolo"), q.setModes)
    }

    @Test
    fun aFailedSetModeFreesTheGuardForTheNextTurn() = runTest {
        val q = FakeQuery().apply {
            snapshotAnswer = QueryAnswer.Ok("""{"meta":{},"messages":[]}""")
            setModeAnswer = QueryAnswer.Unavailable
        }
        enforce(q)
        assertEquals(1, q.setModes.size)
        enforce(q) // the write never landed: the next run_started retries
        assertEquals(2, q.setModes.size, "the retry is honest: one fire per LANDED write")
    }
}
