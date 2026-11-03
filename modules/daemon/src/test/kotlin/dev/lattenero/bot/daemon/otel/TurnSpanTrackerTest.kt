package dev.lattenero.bot.daemon.otel

import dev.lattenero.bot.channel.ServerFrame
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The span tree's shape from scripted turns — the SAME frames the bus
 * producer reads, folded by the pure tracker into the in-memory
 * recorder's log. Each law of the lifecycle gets its turn: one root per
 * real turn, one child per tool call, the mid-flight attach's span
 * without an invented start, the replay's silence.
 */
class TurnSpanTrackerTest {

    private val recorder = RecordingSpanTaps()
    private val tracker = TurnSpanTracker(recorder)

    // ── the frame builders (the bus tests' own shapes) ───────────────────

    private fun started(
        taskId: String,
        at: Long,
        forkTurn: String? = null,
        startedAt: Long? = null,
    ): ServerFrame.StreamEvent = ServerFrame.StreamEvent(
        taskId,
        seq = 1,
        at = at,
        event = buildJsonObject {
            put("type", "task_run_started")
            put("startedAt", startedAt ?: at)
            forkTurn?.let { put("turnId", it) }
        },
    )

    private fun toolCall(
        taskId: String,
        id: String,
        tool: String,
        at: Long,
        parent: String? = null,
        seq: Long = 2,
    ): ServerFrame.StreamEvent = ServerFrame.StreamEvent(
        taskId,
        seq,
        at,
        buildJsonObject {
            put("type", "tool_call")
            put("toolCallId", id)
            put("toolName", tool)
            parent?.let { put("parentToolUseId", it) }
        },
    )

    private fun toolClose(
        taskId: String,
        id: String,
        status: String,
        at: Long,
        content: String? = null,
        seq: Long = 3,
    ): ServerFrame.StreamEvent = ServerFrame.StreamEvent(
        taskId,
        seq,
        at,
        buildJsonObject {
            put("type", "tool_call_update")
            put("toolCallId", id)
            put("status", status)
            content?.let { put("content", it) }
        },
    )

    private fun completed(taskId: String, at: Long, seq: Long = 9): ServerFrame.StreamEvent =
        ServerFrame.StreamEvent(taskId, seq, at, buildJsonObject { put("type", "task_complete") })

    private fun failed(taskId: String, at: Long, error: String? = "boom", seq: Long = 9): ServerFrame.StreamEvent =
        ServerFrame.StreamEvent(taskId, seq, at, buildJsonObject {
            put("type", "task_error")
            error?.let { put("error", it) }
        })

    private fun sessionInfo(taskId: String, title: String?, at: Long, seq: Long = 4): ServerFrame.StreamEvent =
        ServerFrame.StreamEvent(taskId, seq, at, buildJsonObject {
            put("type", "session_info_update")
            title?.let { put("title", it) }
        })

    // ── one scripted turn ─────────────────────────────────────────────────

    @Test
    fun aFullTurnIsOneRootSpanWithOneChildPerTool() {
        tracker.onFrame(started("conv-1", at = 1_000, startedAt = 900))
        tracker.onFrame(toolCall("conv-1", "t1", "Bash", at = 1_100))
        tracker.onFrame(toolClose("conv-1", "t1", "completed", at = 1_500, content = "ok out"))
        tracker.onFrame(completed("conv-1", at = 2_000))

        val starts = recorder.ofType<SpanTapEvent.TurnStart>()
        val tools = recorder.ofType<SpanTapEvent.ToolStart>()
        val closes = recorder.ofType<SpanTapEvent.ToolEnd>()
        val seals = recorder.ofType<SpanTapEvent.TurnEnd>()
        assertEquals(1, starts.size)
        assertEquals(1, tools.size)
        assertEquals(1, closes.size)
        assertEquals(1, seals.size)

        assertEquals(900L, starts.single().startedAtMs) // the frame's own start stamp — real
        assertEquals(false, starts.single().midFlight)
        assertEquals(1_100L, tools.single().atMs)
        assertEquals(true, closes.single().ok)
        assertEquals(6, closes.single().chars) // "ok out" in code points
        assertEquals(1_500L, closes.single().atMs) // the close's own stamp — the tool's real end
        assertEquals("completed", seals.single().outcome)
        assertNull(seals.single().error)
        assertEquals(2_000L, seals.single().endAtMs)
        // the order IS the tree: root, child open, child close, root seal
        assertEquals(
            listOf("TurnStart", "ToolStart", "ToolEnd", "TurnEnd"),
            recorder.events.map { it::class.simpleName },
        )
    }

    @Test
    fun twoToolsMakeTwoChildren() {
        tracker.onFrame(started("conv-1", at = 1_000))
        tracker.onFrame(toolCall("conv-1", "t1", "Bash", at = 1_100))
        tracker.onFrame(toolClose("conv-1", "t1", "completed", at = 1_200))
        tracker.onFrame(toolCall("conv-1", "t2", "Write", at = 1_300))
        tracker.onFrame(toolClose("conv-1", "t2", "failed", at = 1_400))
        tracker.onFrame(completed("conv-1", at = 2_000))

        assertEquals(1, recorder.ofType<SpanTapEvent.TurnStart>().size)
        assertEquals(2, recorder.ofType<SpanTapEvent.ToolStart>().size)
        val closes = recorder.ofType<SpanTapEvent.ToolEnd>()
        assertEquals(2, closes.size)
        assertEquals(true, closes[0].ok)
        assertEquals(false, closes[1].ok) // failed is a real verdict, not an absence
    }

    @Test
    fun aFailedTurnCarriesOnlyTheFramesOwnError() {
        tracker.onFrame(started("conv-1", at = 1_000))
        tracker.onFrame(failed("conv-1", at = 2_000, error = "model exploded"))
        val seal = recorder.ofType<SpanTapEvent.TurnEnd>().single()
        assertEquals("failed", seal.outcome)
        assertEquals("model exploded", seal.error)
    }

    @Test
    fun aFailedTurnWithoutAnErrorFieldClaimsNone() {
        tracker.onFrame(started("conv-1", at = 1_000))
        tracker.onFrame(failed("conv-1", at = 2_000, error = null))
        val seal = recorder.ofType<SpanTapEvent.TurnEnd>().single()
        assertEquals("failed", seal.outcome)
        assertNull(seal.error) // the house's "task error" fallback is the BUS's, never the span's
    }

    @Test
    fun theForksOwnTitleRidesWhenTheStreamSaysSo() {
        tracker.onFrame(started("conv-1", at = 1_000))
        tracker.onFrame(sessionInfo("conv-1", "the real title", at = 1_500))
        tracker.onFrame(sessionInfo("conv-1", "", at = 1_600)) // blank = no report
        tracker.onFrame(completed("conv-1", at = 2_000))

        val titles = recorder.ofType<SpanTapEvent.TurnTitled>()
        assertEquals(1, titles.size)
        assertEquals("the real title", titles.single().title)
    }

    @Test
    fun aTitleForAnUnknownOrSealedTurnIsSilence() {
        tracker.onFrame(sessionInfo("conv-1", "too early", at = 100)) // no live turn
        assertEquals(0, recorder.ofType<SpanTapEvent.TurnTitled>().size)
        assertEquals(0, recorder.ofType<SpanTapEvent.TurnStart>().size) // titles never birth spans

        tracker.onFrame(started("conv-1", at = 1_000))
        tracker.onFrame(completed("conv-1", at = 2_000))
        tracker.onFrame(sessionInfo("conv-1", "too late", at = 3_000))
        assertEquals(0, recorder.ofType<SpanTapEvent.TurnTitled>().size)
    }

    // ── the mid-flight attach: the span without an invented start ─────────

    @Test
    fun aTurnWitnessedOnlyAtItsEndOpensAtTheWitnessMoment() {
        tracker.onFrame(completed("conv-1", at = 9_000))
        val start = recorder.ofType<SpanTapEvent.TurnStart>().single()
        val seal = recorder.ofType<SpanTapEvent.TurnEnd>().single()
        assertTrue(start.midFlight)
        assertEquals(9_000L, start.startedAtMs) // the witnessing moment — never backdated
        assertEquals(9_000L, seal.endAtMs)
        assertEquals("completed", seal.outcome)
    }

    @Test
    fun aToolCallForAnUnseenTurnLazilyOpensTheMidFlightRoot() {
        tracker.onFrame(toolCall("conv-1", "t1", "Bash", at = 5_000))
        tracker.onFrame(toolClose("conv-1", "t1", "completed", at = 6_000, content = "out"))
        tracker.onFrame(completed("conv-1", at = 7_000))

        val start = recorder.ofType<SpanTapEvent.TurnStart>().single()
        assertTrue(start.midFlight)
        assertEquals(5_000L, start.startedAtMs) // the tool's own arrival — the first witness
        assertEquals(1, recorder.ofType<SpanTapEvent.TurnStart>().size)
        assertEquals(1, recorder.ofType<SpanTapEvent.ToolStart>().size)
        assertEquals(1, recorder.ofType<SpanTapEvent.ToolEnd>().size)
    }

    @Test
    fun aToolCloseForAnUnseenToolGetsNoSpan() {
        // the daemon attached between the open and the close: the tool's
        // start is lost — a span starting at its close would be invented
        tracker.onFrame(started("conv-1", at = 1_000))
        tracker.onFrame(toolClose("conv-1", "t1", "completed", at = 2_000, content = "out"))
        tracker.onFrame(completed("conv-1", at = 3_000))

        assertEquals(0, recorder.ofType<SpanTapEvent.ToolStart>().size)
        assertEquals(0, recorder.ofType<SpanTapEvent.ToolEnd>().size)
        assertEquals(1, recorder.ofType<SpanTapEvent.TurnStart>().size)
        assertEquals(1, recorder.ofType<SpanTapEvent.TurnEnd>().size)
    }

    @Test
    fun aBelatedRunStartedAdoptsTheLazyRootInsteadOfDoublingIt() {
        tracker.onFrame(toolCall("conv-1", "t1", "Bash", at = 5_000))
        tracker.onFrame(started("conv-1", at = 5_100, forkTurn = "t7", startedAt = 4_000))
        tracker.onFrame(completed("conv-1", at = 7_000))

        // ONE root span for the ONE real turn: the belated birth refreshes
        // the marks, the span keeps its witness-moment start
        assertEquals(1, recorder.ofType<SpanTapEvent.TurnStart>().size)
        assertTrue(recorder.ofType<SpanTapEvent.TurnStart>().single().midFlight)
        assertEquals(5_000L, recorder.ofType<SpanTapEvent.TurnStart>().single().startedAtMs)
        assertEquals(1, recorder.ofType<SpanTapEvent.TurnEnd>().size)
    }

    @Test
    fun aResubscribeBurstSpansTheLiveTurnOnce() {
        tracker.onFrame(started("conv-1", at = 1_000, forkTurn = "t7"))
        tracker.onFrame(toolCall("conv-1", "t1", "Bash", at = 1_100))
        tracker.onFrame(started("conv-1", at = 1_000, forkTurn = "t7")) // the burst's replay
        tracker.onFrame(toolCall("conv-1", "t1", "Bash", at = 1_100)) // replayed tool
        tracker.onFrame(started("conv-1", at = 1_000, forkTurn = "t7"))
        tracker.onFrame(completed("conv-1", at = 4_000))

        assertEquals(1, recorder.ofType<SpanTapEvent.TurnStart>().size)
        assertEquals(1, recorder.ofType<SpanTapEvent.ToolStart>().size)
        assertEquals(1, recorder.ofType<SpanTapEvent.TurnEnd>().size)
    }

    @Test
    fun aBurstReplayingAnAlreadySealedTurnSpansNothing() {
        tracker.onFrame(started("conv-1", at = 1_000, forkTurn = "t7"))
        tracker.onFrame(completed("conv-1", at = 4_000))
        // the reconnect arrives late and replays the WHOLE turn
        tracker.onFrame(started("conv-1", at = 1_000, forkTurn = "t7"))
        tracker.onFrame(toolCall("conv-1", "t1", "Bash", at = 1_100))
        tracker.onFrame(completed("conv-1", at = 4_000))

        assertEquals(1, recorder.ofType<SpanTapEvent.TurnStart>().size)
        assertEquals(0, recorder.ofType<SpanTapEvent.ToolStart>().size)
        assertEquals(1, recorder.ofType<SpanTapEvent.TurnEnd>().size)
    }

    @Test
    fun aToolStillOpenAtTheSealEndsAtTheSealWithAnUnknownVerdict() {
        // a seq gap lost the tool's terminal update: the seal ends it —
        // the verdict is honestly unknown, never guessed
        tracker.onFrame(started("conv-1", at = 1_000))
        tracker.onFrame(toolCall("conv-1", "t1", "Bash", at = 1_100))
        tracker.onFrame(toolCall("conv-1", "t2", "Write", at = 1_200))
        tracker.onFrame(toolClose("conv-1", "t2", "completed", at = 1_300))
        tracker.onFrame(completed("conv-1", at = 4_000))

        val closes = recorder.ofType<SpanTapEvent.ToolEnd>()
        assertEquals(2, closes.size)
        val unclosed = closes.first { it.unclosed }
        assertEquals("t1", unclosed.toolCallId)
        assertNull(unclosed.ok)
        assertNull(unclosed.chars)
        assertEquals(4_000L, unclosed.atMs) // the seal's own moment
        // the known verdict stands untouched
        val known = closes.first { !it.unclosed }
        assertEquals("t2", known.toolCallId)
        assertEquals(true, known.ok)
        // the unclosed child ends BEFORE the root seals
        val order = recorder.events.map { it::class.simpleName }
        assertTrue(order.indexOf("ToolEnd") < order.lastIndexOf("TurnEnd"))
    }

    @Test
    fun aToolWithoutAnIdGetsNoSpan() {
        tracker.onFrame(started("conv-1", at = 1_000))
        tracker.onFrame(
            ServerFrame.StreamEvent(
                "conv-1",
                2,
                1_100,
                buildJsonObject { put("type", "tool_call"); put("toolName", "Bash") },
            ),
        )
        tracker.onFrame(completed("conv-1", at = 2_000))
        assertEquals(0, recorder.ofType<SpanTapEvent.ToolStart>().size)
        assertEquals(1, recorder.ofType<SpanTapEvent.TurnStart>().size) // the root rides
    }

    @Test
    fun aToolIdUnderTheRendersKeyOpensTheSameSpan() {
        // the fork's additive field pair: the render's toolId is the same stamp
        tracker.onFrame(started("conv-1", at = 1_000))
        tracker.onFrame(
            ServerFrame.StreamEvent(
                "conv-1",
                2,
                1_100,
                buildJsonObject { put("type", "tool_call"); put("toolId", "render-key"); put("toolName", "Bash") },
            ),
        )
        tracker.onFrame(completed("conv-1", at = 2_000))
        assertEquals("render-key", recorder.ofType<SpanTapEvent.ToolStart>().single().toolCallId)
    }

    @Test
    fun aPendingToolUpdateIsNotLifecycle() {
        tracker.onFrame(started("conv-1", at = 1_000))
        tracker.onFrame(toolCall("conv-1", "t1", "Bash", at = 1_100))
        tracker.onFrame(toolClose("conv-1", "t1", "pending", at = 1_200))
        tracker.onFrame(toolClose("conv-1", "t1", "completed", at = 1_500, content = "done"))
        tracker.onFrame(completed("conv-1", at = 2_000))

        assertEquals(1, recorder.ofType<SpanTapEvent.ToolStart>().size)
        assertEquals(1, recorder.ofType<SpanTapEvent.ToolEnd>().size)
        assertEquals(1_500L, recorder.ofType<SpanTapEvent.ToolEnd>().single().atMs)
    }

    @Test
    fun theEmptyToolNameIsHonestAbsence() {
        tracker.onFrame(started("conv-1", at = 1_000))
        tracker.onFrame(
            ServerFrame.StreamEvent(
                "conv-1",
                2,
                1_100,
                buildJsonObject { put("type", "tool_call"); put("toolCallId", "t1") },
            ),
        )
        tracker.onFrame(completed("conv-1", at = 2_000))
        assertEquals("", recorder.ofType<SpanTapEvent.ToolStart>().single().tool) // the frame named nothing
    }

    @Test
    fun aSecondTurnInTheSameConversationSpansNormally() {
        tracker.onFrame(started("conv-1", at = 1_000, forkTurn = "t1"))
        tracker.onFrame(completed("conv-1", at = 2_000))
        tracker.onFrame(started("conv-1", at = 5_000, forkTurn = "t2"))
        tracker.onFrame(completed("conv-1", at = 6_500))
        assertEquals(2, recorder.ofType<SpanTapEvent.TurnStart>().size)
        assertEquals(2, recorder.ofType<SpanTapEvent.TurnEnd>().size)
    }

    @Test
    fun aBirthWhileAnotherTurnIsLiveEndsTheUnsealedOneWithoutAnOutcome() {
        // a seq gap swallowed the previous turn's terminal: the next birth
        // is the honest proof the old turn is over — no outcome invented
        tracker.onFrame(started("conv-1", at = 1_000, forkTurn = "t1"))
        tracker.onFrame(started("conv-1", at = 9_000, forkTurn = "t2", startedAt = 8_500))
        tracker.onFrame(completed("conv-1", at = 10_000))

        val ends = recorder.ofType<SpanTapEvent.TurnEnd>()
        assertEquals(2, ends.size)
        assertNull(ends[0].outcome) // the unwitnessed end claims nothing
        assertEquals(9_000L, ends[0].endAtMs) // the proof's own moment
        assertEquals("completed", ends[1].outcome)
        assertEquals(2, recorder.ofType<SpanTapEvent.TurnStart>().size)
    }

    @Test
    fun differentConversationsAreIndependent() {
        tracker.onFrame(started("conv-1", at = 1_000))
        tracker.onFrame(started("conv-2", at = 1_100))
        tracker.onFrame(toolCall("conv-2", "t1", "Bash", at = 1_200))
        tracker.onFrame(completed("conv-2", at = 3_100))
        assertEquals(2, recorder.ofType<SpanTapEvent.TurnStart>().size)
        assertEquals(1, recorder.ofType<SpanTapEvent.TurnEnd>().size)
        assertEquals(1, recorder.ofType<SpanTapEvent.ToolStart>().size)
        assertEquals("conv-2", recorder.ofType<SpanTapEvent.ToolStart>().single().sid)
    }

    @Test
    fun theSealedMemoryIsBoundedAndOldestFirst() {
        // the house's own cap: 512 marks, pruned FIFO — the oldest turn's
        // late replay is forgotten and spans again (the cap's honest cost)
        repeat(513) { i ->
            tracker.onFrame(started("conv-$i", at = i * 1_000L, forkTurn = "t$i"))
            tracker.onFrame(completed("conv-$i", at = i * 1_000L + 500))
        }
        assertEquals(513, recorder.ofType<SpanTapEvent.TurnStart>().size)
        assertEquals(513, recorder.ofType<SpanTapEvent.TurnEnd>().size)
        recorder.clear()
        // conv-0's mark was evicted (513 seals); conv-1's survives
        tracker.onFrame(started("conv-0", at = 0, forkTurn = "t0"))
        assertEquals(1, recorder.ofType<SpanTapEvent.TurnStart>().size)
        tracker.onFrame(started("conv-1", at = 1_000, forkTurn = "t1"))
        assertEquals(1, recorder.ofType<SpanTapEvent.TurnStart>().size) // still remembered
    }
}
