package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.LiveHub
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
}
