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

    // ---- v2 §1: the header's clock is zcode's, mirrored -------------------------

    @Test
    fun theStartedAnchorIsThePayloadsStampAndNeverRestarts() {
        // the payload's startedAt (the task's real start) beats the delivery
        // clock — the fresh seed anchors on it
        var s = reduce(null, RenderEvent.Started("task-9", seq = 1, atMs = 60_000, nowMs = 60_000, startedAtMs = 10_000))
        assertEquals(10_000L, s!!.startedAtMs, "the header counts from zcode's own start stamp")
        // MIN-GUARD: a re-emitted task_run_started (stream recovery) never
        // restarts the clock — min(existing, new)
        s = reduce(s, RenderEvent.Started("task-9", seq = 2, atMs = 120_000, nowMs = 120_000, startedAtMs = 30_000))
        assertEquals(10_000L, s!!.startedAtMs, "a re-delivery never restarts the task's clock")
        // absent payload: the delivery clock keeps its fallback
        s = reduce(null, RenderEvent.Started("task-9", seq = 3, atMs = 45_000, nowMs = 50_000, startedAtMs = 0))
        assertEquals(45_000L, s!!.startedAtMs)
        // both absent: the receive clock
        s = reduce(null, RenderEvent.Started("task-9", seq = 4, atMs = 0, nowMs = 77_000, startedAtMs = 0))
        assertEquals(77_000L, s!!.startedAtMs)
    }

    // ---- v2 §3: the in_progress fold ---------------------------------------------

    private fun toolRunning(toolId: String, seq: Long, atMs: Long = seq, nowMs: Long = seq, name: String? = null) =
        RenderEvent.ToolRunning(
            taskId = "task-9",
            seq = seq,
            atMs = atMs,
            nowMs = nowMs,
            tool = ToolCall(toolId = toolId, name = name, streamedLines = 0, rawChars = null, isMcp = false),
        )

    @Test
    fun inProgressRestoresWritingOnTheLanesOwnAnchor() {
        // lane A opens (anchor = 1), a sibling B opens+closes (the phase
        // drifts to Thinking), then A's in_progress arrives: the phase
        // returns to Writing on A's OWN openedAtMs — the action's elapsed,
        // never restarted by the ping's arrival
        var s = reduce(null, toolOpenEvent(toolId = "tA", seq = 1))
        s = reduce(s, toolOpenEvent(toolId = "tB", seq = 2))!!
        s = reduce(s, toolCloseEvent(toolId = "tB", output = "out", seq = 3))!!
        assertTrue(s!!.phase is TurnPhase.Thinking, "the close drifted the phase to Thinking")
        s = reduce(s, toolRunning(toolId = "tA", seq = 4))!!
        val writing = s.phase as TurnPhase.Writing
        assertEquals("tA", writing.toolId)
        assertEquals(1L, writing.sinceMs, "the action's elapsed keeps the lane's openedAtMs anchor")
        assertEquals(4L, s.tools.first { it.toolId == "tA" }.lastUpdateAtMs, "the lane was touched")
    }

    @Test
    fun inProgressOnANeverOpenedLaneOpensItAtTheForksOwnStamp() {
        // no pending/final ever arrived: the lane opens here, anchored on
        // the event's OWN clock (atMs), not the delivery/receive clock
        val s = reduce(null, toolRunning(toolId = "tX", seq = 5, atMs = 5_000, nowMs = 6_000, name = "Bash"))!!
        val lane = s.tools.single()
        assertEquals("Bash", lane.name)
        assertEquals(5_000L, lane.openedAtMs)
        val writing = s.phase as TurnPhase.Writing
        assertEquals("tX", writing.toolId)
        assertEquals(5_000L, writing.sinceMs)
    }

    @Test
    fun inProgressNeverReopensAClosedLane() {
        var s = reduce(null, toolOpenEvent(toolId = "tC", seq = 1))
        s = reduce(s, toolCloseEvent(toolId = "tC", output = "out", seq = 2))!!
        val before = s
        s = reduce(s, toolRunning(toolId = "tC", seq = 3))!!
        assertEquals("completed", s!!.tools.single().status, "a closed lane does not reopen")
        assertEquals(before!!.phase, s.phase, "a closed lane's stale ping moves no phase")
        assertEquals(3L, s.tools.single().lastUpdateAtMs, "the touch itself still lands")
    }

    // ---- v2 §2: the CoT freshness anchor -----------------------------------------

    @Test
    fun onlyTheMainAgentsThoughtsMarkTheCoTFresh() {
        var s = reduce(null, thought("pensando"))!!
        val mainStamp = s.lastThoughtAtMs
        assertNotNull(mainStamp, "the main lane's delta marks the thinking fresh")
        assertEquals(s.lastEventAtMs, mainStamp)
        s = reduce(s, thought("sub narration", parent = "lane-A"))!!
        assertEquals(mainStamp, s!!.lastThoughtAtMs, "a subagent's narration never refreshes the card's CoT")
        assertEquals(13, s.subagentThought["lane-A"], "the subagent's own lane still grows")
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

    // ---- the tool boxes (U5b) ------------------------------------------------------

    private fun boxBorn(boxId: Int, pages: Int = 2): RenderEvent.BoxBorn = RenderEvent.BoxBorn(
        taskId = "task-9",
        boxId = boxId,
        header = "⚡ <b>Bash</b> — <code>ls</code> · $pages líneas\n<i>📜 tocar para ver</i>",
        pages = (1..pages).map { "   $it│ línea $it" },
        messageId = 1_000L + boxId,
    )

    @Test
    fun aBornBoxLandsInTheFoldClosed() {
        val state = reduce(null, thought("pensando"))
        val born = reduce(state, boxBorn(boxId = 7, pages = 3))!!
        val box = born.boxes.single()
        assertEquals(7, box.boxId)
        assertEquals(3, box.pages.size)
        assertEquals(1_007L, box.messageId)
        assertEquals(BoxLive.FOLDED, box.page, "a box is born CLOSED — the header + its one button")
        // the birth moves nothing else the turn owns
        assertEquals(state!!.tools, born.tools)
        assertEquals(state.cotPending, born.cotPending)
    }

    @Test
    fun aRebornIdNeverDoublesAndAForeignBirthIsIgnored() {
        val state = reduce(null, thought("pensando"))
        val once = reduce(state, boxBorn(boxId = 7))!!
        assertEquals(1, once.boxes.size)
        val twice = reduce(once, boxBorn(boxId = 7, pages = 5))!!
        assertEquals(1, twice.boxes.size, "a reborn id replaces, never doubles")
        assertEquals(5, twice.boxes.single().pages.size)
        assertEquals(
            once,
            reduce(once, RenderEvent.BoxBorn("other-task", 7, "h", listOf("p"), 1L)),
            "a foreign task's box birth is ignored — the watched turn stays",
        )
    }

    @Test
    fun theBoxCeilingKeepsTheNewestAndTheMaxIdSurvives() {
        var state = reduce(null, thought("pensando"))
        val ids = 1..(BOX_CAP + 1)
        state = ids.fold(state) { acc, id -> reduce(acc, boxBorn(boxId = id)) }
        assertEquals(BOX_CAP, state!!.boxes.size, "the registry's own ceiling")
        assertEquals(2, state.boxes.minOf { it.boxId }, "eviction takes the oldest (the first born)")
        assertEquals(BOX_CAP + 1, state.boxes.maxOf { it.boxId }, "the max id survives — the next id never regresses")
    }

    @Test
    fun thePagerTapMirrorsThePageTheMessageShows() {
        var state = reduce(reduce(null, thought("pensando")), boxBorn(boxId = 7, pages = 3))
        state = reduce(state, RenderEvent.Page(7, 1))!!
        assertEquals(1, state!!.boxes.single().page, "the fold mirrors the landed edit")
        // the clamp is the reducer's own defense: an out-of-range page lands at the last
        assertEquals(2, reduce(state, RenderEvent.Page(7, 99))!!.boxes.single().page)
        // the fold-back returns the header
        assertEquals(
            BoxLive.FOLDED,
            reduce(state, RenderEvent.Page(7, BoxLive.FOLDED))!!.boxes.single().page,
        )
        // an unknown box moves nothing; a page on one box leaves its siblings
        val seeded = reduce(null, thought("pensando"))
        val two = reduce(reduce(seeded, boxBorn(boxId = 7)), boxBorn(boxId = 8))!!
        val flipped = reduce(two, RenderEvent.Page(8, 0))!!
        assertEquals(BoxLive.FOLDED, flipped.boxes.first { it.boxId == 7 }.page)
        assertEquals(0, flipped.boxes.first { it.boxId == 8 }.page)
        assertEquals(two, reduce(two, RenderEvent.Page(99, 0)), "an unknown box's tap is a no-op")
        // a page event with no state stays nothing
        assertNull(reduce(null, RenderEvent.Page(7, 0)))
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

    // ---- U5b: the exact lane's priority + the lane separation ----------------------

    private fun toolOpenEvent(
        toolId: String,
        name: String = "Bash",
        parent: String? = null,
        seq: Long = 1,
    ): RenderEvent.ToolOpen = RenderEvent.ToolOpen(
        taskId = "task-9",
        seq = seq,
        atMs = seq,
        nowMs = seq,
        tool = ToolCall(
            toolId = toolId,
            name = name,
            parentToolUseId = parent,
            streamedLines = 0,
            rawChars = null,
            isMcp = false,
        ),
        final = true,
    )

    private fun toolCloseEvent(toolId: String, output: String, seq: Long = 2): RenderEvent.ToolClose =
        RenderEvent.ToolClose(
            taskId = "task-9",
            seq = seq,
            atMs = seq,
            nowMs = seq,
            tool = ToolCall(
                toolId = toolId,
                name = null,
                streamedLines = 0,
                rawChars = null,
                isMcp = false,
                status = "completed",
                outputLines = 1,
                output = output,
            ),
        )

    @Test
    fun aTwoHundredEventBurstLandsWholeThroughTheExactLane() = runTest {
        // THE EXACT LANE'S PRICE (the U5a/U5b decision): the stage's slack
        // is 64 slots — a 200-event burst overflows it and the producer
        // SUSPENDS. Waiting is the only cost; a dropped tool line would be
        // a lie, so nothing drops and the order stays the upstream's.
        val burst = (1..200).map { n -> toolOpenEvent(toolId = "t$n", seq = n.toLong()) }
        val taken = KapStream.from(flowOf(*burst.toTypedArray())).withStageSlack().asFlow().toList()
        assertEquals(burst, taken, "200 > 64: the producer waits — every tool line lands, in order")
    }

    @Test
    fun aSpineBurstLosesNoToolLineWhileThoughtsMayConflate() = runTest {
        // THE COORDINATOR'S LAW, end to end: the exact lane has priority
        // over the conflated one — a mixed burst of thoughts, tool opens,
        // tool closes and a typed seal folds EVERY tool line (counters,
        // order, verdicts) and the seal's pill; the CoT lane may lag/drop
        // (its counter is a lower bound, never an invention).
        val frames = buildList {
            var seq = 0L
            fun next(): Long = ++seq
            add(
                ServerFrame.StreamEvent(
                    "task-9", next(), 1_000,
                    buildJsonObject { put("type", "task_run_started") },
                ),
            )
            (1..30).forEach { n ->
                add(
                    ServerFrame.StreamEvent(
                        "task-9", next(), 1_000 + seq,
                        buildJsonObject {
                            put("type", "agent_thought_chunk")
                            put("content", JsonPrimitive("t".repeat(n)))
                        },
                    ),
                )
            }
            (1..10).forEach { n ->
                add(
                    ServerFrame.StreamEvent(
                        "task-9", next(), 1_000 + seq,
                        buildJsonObject {
                            put("type", "tool_call")
                            put("toolId", JsonPrimitive("t$n"))
                            put("toolName", JsonPrimitive("Bash"))
                            put("input", buildJsonObject { put("content", JsonPrimitive((1..n).joinToString("\n") { "line$it" })) })
                            put("raw", buildJsonObject { put("streamingRawInputLength", JsonPrimitive(n)) })
                        },
                    ),
                )
            }
            (1..30).forEach { n ->
                add(
                    ServerFrame.StreamEvent(
                        "task-9", next(), 1_000 + seq,
                        buildJsonObject {
                            put("type", "agent_thought_chunk")
                            put("content", JsonPrimitive("u".repeat(n)))
                        },
                    ),
                )
            }
            (1..10).forEach { n ->
                add(
                    ServerFrame.StreamEvent(
                        "task-9", next(), 1_000 + seq,
                        buildJsonObject {
                            put("type", "tool_call_update")
                            put("toolId", JsonPrimitive("t$n"))
                            put("status", JsonPrimitive("completed"))
                            put("content", JsonPrimitive((1..n).joinToString("\n") { "out$it" }))
                        },
                    ),
                )
            }
            add(
                ServerFrame.StreamEvent(
                    "task-9", next(), 1_000 + seq,
                    buildJsonObject {
                        put("type", "task_error")
                        put("error", JsonPrimitive("usage cap exceeded"))
                        put("code", JsonPrimitive("fault.provider.quotaExhausted"))
                    },
                ),
            )
        }
        val states = spine(flowOf(*frames.toTypedArray()), ticker = flowOf(), nowMs = { 5_000L }).toList()
        assertTrue(states.isNotEmpty())
        val final = states.last()!!
        assertEquals(
            (1..10).map { "t$it" },
            final.tools.map { it.toolId },
            "every tool OPEN landed, in birth order — the exact lane never drops",
        )
        assertEquals(
            (1..10).sumOf { it },
            final.tools.sumOf { it.lines },
            "the exact line counters survived the burst whole",
        )
        assertEquals(10, final.doneTools, "every CLOSE landed")
        assertTrue(final.typedError != null && final.typedError.isQuota, "the seal's pill survives the burst")
        // the thoughts may conflate: their total never EXCEEDS what streamed (no invention)
        assertTrue(final.thoughtChars <= (1..30).sumOf { it } * 2, "conflate drops only — it never invents")
    }

    @Test
    fun aSubagentsOutputNeverFallsIntoTheParentsLaneAndViceVersa() {
        // THE LANE SEPARATION: the parent's narration and a subagent's never
        // share a counter, and each tool lane keeps its OWN output — a
        // subagent's close never stamps the parent's lane and vice versa.
        var s = reduce(null, thought("main narration", parent = null))
        s = reduce(s, thought("sub narration", parent = "lane-A"))!!
        assertEquals("main narration".length, s!!.thoughtChars, "the subagent's thought never grows the parent's counter")
        assertEquals("sub narration".length, s.subagentThought["lane-A"], "the subagent's own lane grows")
        assertEquals(setOf("lane-A"), s.subagentThought.keys, "the parent's thought never grows a lane")
        // the tool lanes: each side keeps its own output and verdict
        s = reduce(s, toolOpenEvent(toolId = "t-main", name = "Edit", parent = null, seq = 10))!!
        s = reduce(s, toolOpenEvent(toolId = "t-sub", name = "Bash", parent = "lane-A", seq = 11))!!
        s = reduce(s, toolCloseEvent(toolId = "t-main", output = "parent output", seq = 12))!!
        s = reduce(s, toolCloseEvent(toolId = "t-sub", output = "sub output", seq = 13))!!
        val main = s.tools.first { it.toolId == "t-main" }
        val sub = s.tools.first { it.toolId == "t-sub" }
        assertNull(main.parentToolUseId, "the parent's lane stays the parent's")
        assertEquals("lane-A", sub.parentToolUseId, "the subagent's lane stays the subagent's")
        assertEquals("parent output", main.output)
        assertEquals("sub output", sub.output, "the subagent's output never falls into the parent's lane")
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
