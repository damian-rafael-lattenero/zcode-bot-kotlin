package dev.lattenero.bot.channel

import kap.KapStream
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE PURE FOLD (U5a, law 2): the hub's own verbs are EVENTS now —
 * Retitle/Resync/Keepalive/ScopeLane/WatchTask/Clear fold exactly as the
 * old imperative copies did (their old tests pin the hub's edge, these pin
 * the reducer) — and the staleness tick reduces into the state (the card's
 * age is state, not a side channel). Plus the spine's composition: the
 * named stages' drop law, end to end.
 */
class RenderFoldTest {

    private var seq = 0L

    private fun thought(content: String, parent: String? = null): RenderEvent.Thought {
        seq += 1
        return RenderEvent.Thought(
            taskId = "task-9",
            seq = seq,
            atMs = 1_000L + seq * 100,
            nowMs = 1_000L + seq * 100,
            delta = content,
            parentToolUseId = parent,
        )
    }

    private fun reduce(state: RenderState?, event: RenderEvent): RenderState? = reduce(state, event, SimpleLineDiffer)

    // ---- the seed --------------------------------------------------------------

    @Test
    fun theEmptySeedIsUnbornAndTheFirstFrameNamesIt() {
        val state = reduce(
            RenderState.empty,
            RenderEvent.Started("task-9", seq = 1, atMs = 1_000, nowMs = 1_000),
        )
        val s = assertNotNull(state)
        assertEquals("task-9", s.taskId)
        assertEquals("task-9".takeLast(12), s.title)
        assertEquals(1_000L, s.startedAtMs)
    }

    // ---- the hub's verbs, as events -------------------------------------------

    @Test
    fun retitleOnlyTouchesTheWatchedTask() {
        val state = reduce(null, thought("pensando"))
        val retitled = reduce(state, RenderEvent.Retitle("another-task", "otra"))
        assertEquals(state, retitled, "a foreign task's retitle is ignored")
        assertEquals("arreglá el login", reduce(state, RenderEvent.Retitle("task-9", "arreglá el login"))!!.title)
    }

    @Test
    fun resyncRebaselinesTheLiveWindowsAndTheBlocksSurvive() {
        var state = reduce(null, thought("x".repeat(3000))) // block 1 sealed
        state = reduce(state, RenderEvent.Resync("task-9"))
        assertEquals(listOf("x".repeat(3000)), state!!.cotBlocks)
        assertEquals("", state.cotTail)
        assertEquals("", state.cotPending)
        assertEquals(TurnPhase.Idle, state.phase)
        // a foreign task's resync is ignored
        val untouched = reduce(state, RenderEvent.Resync("other"))
        assertEquals(state, untouched)
    }

    @Test
    fun keepaliveAdvancesTheSeqAndNothingElse() {
        var state = reduce(null, thought("pensando"))
        val before = state!!
        state = reduce(state, RenderEvent.Keepalive("task-9", seq = 79))
        assertEquals(79L, state!!.lastSeq)
        assertEquals(before.cotPending, state.cotPending) // the accumulation SURVIVES the heartbeat
        assertEquals(before.lastEventAtMs, state.lastEventAtMs) // a quiet model stays honestly quiet
        val advanced = state
        assertEquals(
            advanced,
            reduce(state, RenderEvent.Keepalive("other", seq = 100)),
            "a foreign heartbeat moves nothing",
        )
        assertEquals(79L, reduce(state, RenderEvent.Keepalive("task-9", seq = 5))!!.lastSeq, "an old seq never rewinds")
        // no base yet (a state that never saw a frame): the heartbeat arms nothing
        val newborn = TurnLive(taskId = "task-9", title = "x")
        assertEquals(newborn, reduce(newborn, RenderEvent.Keepalive("task-9", seq = 2)))
    }

    @Test
    fun laneScopeIsFoldedStateAndTheClearResets() {
        val state = reduce(null, thought("pensando"))
        assertEquals("tooluse-77", reduce(state, RenderEvent.ScopeLane("tooluse-77"))!!.watchedLane)
        assertNull(reduce(state, RenderEvent.WatchTask)!!.watchedLane)
        assertNull(reduce(state, RenderEvent.Clear), "the turn is spent — the fold's own reset")
    }

    // ---- the staleness tick ------------------------------------------------------

    @Test
    fun theTickReducesTheCardsAgeIntoTheStateAndNothingElseMoves() {
        var state = reduce(null, thought("pensando")) // lastEventAtMs = 1_100
        val before = state!!
        state = reduce(state, RenderEvent.Tick(nowMs = 41_100))
        assertEquals(40_000L, state!!.staleMs, "the tick's own clock against the last real event")
        assertEquals(before.lastEventAtMs, state.lastEventAtMs, "a tick is not a model event")
        assertEquals(before.lastSeq, state.lastSeq)
        assertEquals(before.cotTail, state.cotTail)
        // the unborn seed has no age to speak of
        assertNull(reduce(null, RenderEvent.Tick(nowMs = 5L)))
    }

    // ---- the spine's named stages ------------------------------------------------

    @Test
    fun theCoTStageConflatesOnlyTheLatestMatters() = runTest {
        // THE DROP LAW (what may drop): a burst of CoT deltas may lose every
        // superseded element — the survivors are a SUBSEQUENCE of the burst
        // (order is never invented) and the LATEST always survives (what
        // never drops: the freshest thinking, and — on the exact lane —
        // every tool line, seal and pill)
        val burst = (1..10).map { n -> thought("0123456789".repeat(n)) }
        val taken = KapStream.from(flowOf(*burst.toTypedArray())).conflate().asFlow().toList()
        assertTrue(taken.size < burst.size, "the burst collapses — got ${taken.size} of ${burst.size}")
        assertEquals(burst.last(), taken.last(), "the latest thinking survives")
        assertEquals(burst.filter { it in taken }, taken, "the survivors keep the upstream's order")
    }

    @Test
    fun theExactLaneNeverDropsAndNeverReorders() = runTest {
        val burst = (1..10).map { n ->
            RenderEvent.ToolOpen(
                taskId = "task-9",
                seq = n.toLong(),
                atMs = n.toLong(),
                nowMs = n.toLong(),
                tool = ToolCall(toolId = "t$n", name = "Bash", streamedLines = n, rawChars = null, isMcp = false),
                final = true,
            )
        }
        val taken = KapStream.from(flowOf(*burst.toTypedArray())).withStageSlack().asFlow().toList()
        assertEquals(burst, taken, "exact: the producer waits, nothing drops, order is the upstream's")
    }

    @Test
    fun theTickerDropsTheOldestOnlyTheFreshestSurvives() = runTest {
        val ticks = listOf(1L, 2L, 3L)
        val taken = KapStream.from(flowOf(*ticks.toTypedArray())).dropOldest(1).asFlow().toList()
        assertTrue(taken.size < ticks.size, "an old tick is worthless — the backlog drops it")
        assertEquals(3L, taken.last(), "the freshest tick is the only one that never drops")
    }

    // ---- the spine, end to end ----------------------------------------------------

    @Test
    fun theSpineFoldsFramesIntoStatesFromTheEmptySeed() = runTest {
        val frames = flowOf(
            ServerFrame.StreamEvent("task-9", 1, 1_000, buildJsonObject { put("type", "task_run_started") }),
            ServerFrame.StreamEvent(
                "task-9",
                2,
                1_100,
                buildJsonObject {
                    put("type", "tool_call")
                    put("toolId", "t1")
                    put("toolName", "Bash")
                    put("input", buildJsonObject { put("command", JsonPrimitive("npm test")) })
                },
            ),
            ServerFrame.StreamEvent(
                "task-9",
                3,
                1_500,
                buildJsonObject { put("type", "tool_call_update"); put("toolId", "t1"); put("status", JsonPrimitive("completed")) },
            ),
            ServerFrame.StreamEvent(
                "task-9",
                4,
                2_000,
                buildJsonObject { put("type", "task_complete"); put("stopReason", JsonPrimitive("end_turn")) },
            ),
        )
        val states = spine(frames, ticker = flowOf(), nowMs = { 1_000L }).toList()
        assertEquals(4, states.size, "one state per event — the scan's law")
        // the OPEN state: the lane exists, its verdict pending
        val open = states[1]!!
        assertEquals("Bash", open.tools.first().name)
        assertNull(open.tools.first().status)
        val final = states.last()!!
        val done = final.phase as TurnPhase.Done
        assertEquals("end_turn", done.stopReason)
        assertEquals(1, final.doneTools)
        assertEquals(2_000L, final.lastEventAtMs)
    }

    @Test
    fun theSpineCarriesTheSealThePillAndTheAdditiveFreshness() = runTest {
        val frames = flowOf(
            ServerFrame.StreamEvent("task-9", 1, 1_000, buildJsonObject { put("type", "task_run_started") }),
            // the typed error's seal — the honest end of a dead turn
            ServerFrame.StreamEvent(
                "task-9",
                2,
                1_100,
                buildJsonObject {
                    put("type", "task_error")
                    put("error", JsonPrimitive("usage cap exceeded"))
                    put("code", JsonPrimitive("fault.provider.quotaExhausted"))
                },
            ),
            ServerFrame.StreamEvent("task-9", 3, 1_200, buildJsonObject { put("type", "usage_update") }),
        )
        val states = spine(frames, ticker = flowOf(), nowMs = { 1_000L }).toList()
        val errored = states[1]!!
        assertTrue(errored.phase is TurnPhase.Done)
        assertTrue(errored.typedError!!.isQuota)
        // the additive frame: freshness only — and an earlier pill stays untouched
        val last = states.last()!!
        assertEquals(1_200L, last.lastEventAtMs)
        assertTrue(last.phase is TurnPhase.Done)
    }
}
