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
        assertTrue(s.subagentLanes.isEmpty(), "the fork never sends an in-band child thought — nothing folds")
    }

    // ---- the thinking's boundary: the tail under the cap seals at the segment's end ----

    private fun answer(delta: String): RenderEvent.Answering {
        seq += 1
        return RenderEvent.Answering(
            taskId = "task-9",
            seq = seq,
            atMs = 1_000L + seq * 100,
            nowMs = 1_000L + seq * 100,
            delta = delta,
        )
    }

    @Test
    fun aThinkingSegmentThatEndsAtAToolSealsItsTailBelowTheCap() {
        // the owner's law (10/11): the tail that never touched the 3K cap
        // is NOT lost — the tool's arrival ends the segment, the tail
        // seals as a REAL block, the feed speaks it like any other
        var s = reduce(null, thought("a".repeat(2700)))
        assertEquals(0, s!!.cotBlocks.size, "still pending: the cap never rang")
        assertEquals(2700, s.cotPending.length)
        s = reduce(s, toolOpenEvent(toolId = "t1", seq = 5))
        assertEquals(listOf("a".repeat(2700)), s!!.cotBlocks)
        assertEquals("", s.cotPending)
    }

    @Test
    fun theAnswersFirstWordEndsTheThinkingSegmentToo() {
        var s = reduce(null, thought("b".repeat(1200)))
        s = reduce(s, answer("Hola"))!!
        assertEquals(listOf("b".repeat(1200)), s!!.cotBlocks)
        assertEquals("", s.cotPending)
        assertEquals(4, s.answerChars, "the answer's own delta still lands")
    }

    @Test
    fun aBlankPendingSealsNothingAndTheFlushIsIdempotent() {
        var s = reduce(null, toolOpenEvent(toolId = "t0", seq = 1)) // no thoughts yet
        assertEquals(0, s!!.cotBlocks.size, "a tool after no thought seals nothing")
        s = reduce(s, thought("x".repeat(3400))) // the cap seals block 1 at 3000, 400 ride pending
        assertEquals(1, s!!.cotBlocks.size)
        assertEquals(400, s.cotPending.length)
        s = reduce(s, toolOpenEvent(toolId = "t1", seq = 6))
        assertEquals(2, s!!.cotBlocks.size, "the 400-cp tail joins as its own block")
        assertEquals(400, s.cotBlocks[1].length)
        s = reduce(s, toolOpenEvent(toolId = "t2", seq = 7))
        assertEquals(2, s!!.cotBlocks.size, "the second flush is a no-op")
    }

    // ---- the sentence seal (22/11): ~1K blocks that end at completed sentences ----

    /** A n-cp sentence unit whose end CONFIRMS in the pending (terminator + separator). */
    private fun sentence(n: Int): String = "s".repeat(n - 2) + ". "

    @Test
    fun theSealCutsAtTheLastSentenceUnderTheTarget() {
        // 12 x 100-cp sentences: the last boundary at-or-under the 1K
        // target is exactly 1000 — the block fills FULL, ends with its
        // punctuation, and the next opens on sentence 11's first word
        var s = reduce(null, thought(sentence(100).repeat(12)))
        s = assertNotNull(s)
        assertEquals(1, s.cotBlocks.size)
        assertEquals(1000, s.cotBlocks[0].length, "the greedy fill: the LAST boundary under the target")
        assertTrue(s.cotBlocks[0].endsWith(". "), "the block ends at the completed sentence")
        assertEquals(sentence(100).repeat(2), s.cotPending, "sentences 11 and 12 ride pending, whole")
    }

    @Test
    fun aSentenceCrossingTheTargetSealsWholePastIt() {
        // a 100-cp opener then a 1.1K sentence with no inner end: sealing
        // the opener alone would be a stub block — the crossing sentence
        // seals WHOLE at its own end, past 1K but never cut mid-sentence
        var s = reduce(null, thought(sentence(100) + "g".repeat(1098) + ". tail"))
        s = assertNotNull(s)
        assertEquals(1, s.cotBlocks.size)
        assertEquals(1200, s.cotBlocks[0].length, "the stub merges with the crossing sentence's own end")
        assertTrue(s.cotBlocks[0].endsWith(". "), "the giant sentence closes intact")
        assertEquals("tail", s.cotPending)
    }

    @Test
    fun aTrailingTerminatorWithoutItsSeparatorIsNotYetABoundary() {
        // 1100 cp ending in a bare '.': it may still grow into «3.14» —
        // the seal WAITS; the next delta's separator confirms the end
        var s = reduce(null, thought("w".repeat(1099) + "."))
        s = assertNotNull(s)
        assertEquals(0, s.cotBlocks.size, "unconfirmed: the sentence may still grow")
        assertEquals(1100, s.cotPending.length)
        s = reduce(s, thought(" next."))!!
        assertEquals(1, s.cotBlocks.size, "the separator confirms the end and the seal rings")
        assertEquals(1101, s.cotBlocks[0].length, "the block swallows its confirming separator")
        assertTrue(s.cotBlocks[0].endsWith(". "))
        assertEquals("next.", s.cotPending)
    }

    @Test
    fun decimalsAreNotBoundaries() {
        // «3.14159» — a '.' followed by a digit is a number, not an end;
        // the run carries NO boundary and the seal waits (never cuts a
        // number, never mind the commas — only point-final ends count)
        var s = reduce(null, thought("pi is 3.14159 and e is 2.71828, so ".repeat(40)))
        s = assertNotNull(s)
        assertEquals(0, s.cotBlocks.size, "a number's dot never confirms a sentence")
    }

    @Test
    fun lineEndsAreBoundariesForUnpunctuatedProse() {
        // markdown-list thinking: no periods at all, every line a coherent
        // unit — the seal cuts after the line's newline
        val line = "-" + "x".repeat(98) + "\n" // 100 cp, boundary at its newline
        var s = reduce(null, thought(line.repeat(12)))
        s = assertNotNull(s)
        assertEquals(1, s.cotBlocks.size)
        assertEquals(1000, s.cotBlocks[0].length)
        assertTrue(s.cotBlocks[0].endsWith("\n"), "the cut lands on the line's end")
        assertEquals(line.repeat(2), s.cotPending)
    }

    @Test
    fun aCoalescedBurstSealsEveryBlockInOneFold() {
        // the fork coalesces same-key chunks: ONE delta can carry a whole
        // window — the loop seals each ~1K block at its own sentence end.
        // Each unit is UNIQUE: identical blocks are twins, and the content
        // keys would honestly skip them (the re-statement law)
        val units = (1..35).map { n -> "n%02d".format(n) + "s".repeat(95) + ". " } // 100 cp each
        var s = reduce(null, thought(units.joinToString("")))
        s = assertNotNull(s)
        assertEquals(listOf(1000, 1000, 1000), s.cotBlocks.map { it.length })
        assertEquals(500, s.cotPending.length, "the last half-full block rides pending")
        assertTrue(s.cotBlocks.all { it.endsWith(". ") }, "every block closes its sentences")
    }

    @Test
    fun theValveKeepsTheOldExactCutForBoundarylessRuns() {
        // pure law, no fold: under the target nothing seals; a boundaryless
        // run under the valve waits; past it the old exact cut answers
        assertNull(cotSealCut("x".repeat(999)))
        assertNull(cotSealCut("x".repeat(2999)), "no boundary in sight, under the valve: wait")
        assertEquals(3000, cotSealCut("x".repeat(3400)))
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
        state = reduce(state, answer("Hola mundo"))!! // the answer's windows hold text
        assertEquals("Hola mundo", state!!.answerTail)
        assertEquals("Hola mundo", state.prosePending)
        state = reduce(state, RenderEvent.Resync("task-9"))
        assertEquals(listOf("x".repeat(3000)), state!!.cotBlocks)
        assertEquals("", state.cotTail)
        assertEquals("", state.cotPending)
        assertEquals("", state.answerTail, "the answer's live window dies with the baseline")
        assertEquals("", state.prosePending, "the unspoken prose dies with the baseline")
        assertEquals(TurnPhase.Idle, state.phase)
        // a foreign task's resync is ignored
        val untouched = reduce(state, RenderEvent.Resync("other"))
        assertEquals(state, untouched)
    }

    // ---- the re-statement seams (14/11, measured live) ---------------------------

    @Test
    fun aReEmittedShortFragmentNeverRepeatsTheTail() {
        // THE MEASURED SEAM: the fork's stream recovery re-emitted the last
        // 17-cp fragment of the streamed answer — under the sentence floor
        // it appended and the owner read «…tests): detekt + tests)»
        val preamble = "Sin tests huérfanos. Corro el gate completo del bot (`./gradlew build` — detekt + tests):"
        var s = reduce(null, thought("preparando el gate"))
        s = reduce(s, answer(preamble))!!
        assertEquals(preamble, s.answerTail)
        val reemitted = " detekt + tests):"
        s = reduce(s, answer(reemitted))!!
        assertEquals(preamble, s.answerTail, "a re-emitted fragment the tail already ends with appends nothing")
        assertEquals(preamble.length, s.answerChars, "the counter never counted the second copy")
    }

    @Test
    fun aPureRunKeepsTheSentenceFloor() {
        // separators legitimately repeat: a second rule line under a first
        // one is a REAL delta, never a re-emission — only the run shape
        // demands sentence size
        var s = reduce(null, answer("regla uno:\n----"))
        s = reduce(s, answer("----"))!!
        assertEquals("regla uno:\n----" + "----", s.answerTail, "a pure run's repeat lands: both rule lines survive")
    }

    @Test
    fun aRestatedMergedChunkNeverDoublesTheFirstWord() {
        // THE MEASURED SEAM: the first thinking emission was the word
        // «Check» alone; the recovery re-emitted the text MERGED — one chunk
        // starting with the whole window. Appended whole it read
        // «CheckCheck leftover…»; only the remainder is new
        var s = reduce(null, thought("Check"))
        assertEquals("Check", s!!.cotTail)
        val merged = "Check leftover unused imports in MetricsTest (BrainReply still used by " +
            "brainRequest test ✓; assertTrue still used by the cancelled test ✓)"
        s = reduce(s, thought(merged))!!
        assertEquals(merged, s!!.cotTail, "the merged re-statement trimmed its head: the word never doubles")
        assertEquals(merged.length, s.thoughtChars, "the counter counted the text once")
    }

    @Test
    fun aChildSessionsResyncClearsItsOwnLanesWindows() {
        // THE RECONNECT REPLAY (14/11): a re-subscribed child session's topic
        // re-states its in-flight text — the lane's windows die BEFORE the
        // burst lands, or the re-stated deltas double onto them
        var s = reduce(null, toolOpenEvent(toolId = "lane-1", name = "Task", seq = 1))
        s = reduce(s, toolOpenEvent(toolId = "lane-2", name = "Task", seq = 2))
        s = reduce(
            s,
            RenderEvent.ToolOpen(
                taskId = "task-9",
                seq = 3,
                atMs = 3,
                nowMs = 3,
                tool = ToolCall(
                    toolId = "lane-1",
                    name = "Task",
                    subagentAgentId = "agent-7",
                    streamedLines = 0,
                    rawChars = null,
                    isMcp = false,
                ),
                final = true,
            ),
        )
        s = reduce(s, RenderEvent.ToolOpen(
            taskId = "task-9",
            seq = 4,
            atMs = 4,
            nowMs = 4,
            tool = ToolCall(
                toolId = "lane-2",
                name = "Task",
                subagentAgentId = "agent-8",
                streamedLines = 0,
                rawChars = null,
                isMcp = false,
            ),
            final = true,
        ))
        s = reduce(s, RenderEvent.ChildStream("lane-1", thought = false, delta = "informe del hijo siete", nowMs = 5))
        s = reduce(s, RenderEvent.ChildStream("lane-2", thought = false, delta = "informe del hijo ocho", nowMs = 6))
        assertEquals("informe del hijo siete", s!!.subagentLanes["lane-1"]?.answerTail)
        // the child's OWN topic resyncs (the reconnect replay): only ITS lane dies
        s = reduce(s, RenderEvent.Resync("sess_subagent_agent-7"))
        val seven = assertNotNull(assertNotNull(s).subagentLanes["lane-1"])
        assertEquals("", seven.answerTail, "the re-subscribed child's window is empty: the burst rebuilds it")
        assertEquals(0, seven.answerChars)
        assertEquals(0L, seven.lastDeltaAtMs, "the row hides its tail until the burst speaks again")
        val eight = assertNotNull(s.subagentLanes["lane-2"])
        assertEquals("informe del hijo ocho", eight.answerTail, "a sibling lane never pays for another child's resync")
        // a burst re-stating the child's text rebuilds the window instead of doubling
        s = reduce(s, RenderEvent.ChildStream("lane-1", thought = false, delta = "informe del hijo siete y sigue", nowMs = 7))
        assertEquals("informe del hijo siete y sigue", assertNotNull(s).subagentLanes["lane-1"]?.answerTail)
    }

    @Test
    fun theWatchdogsRebaseKillsTheAnswerWindowsAndKeepsTheTurn() {
        // a turn mid-answer AND mid-thought: a sealed CoT block, one spent
        // prose segment, the answer's live windows holding unspoken text, and
        // the thinking's pending accumulator holding the under-cap tail
        var state = reduce(null, thought("a".repeat(3000))) // block 1 sealed
        state = reduce(state, answer("Mi edición partió"))!!
        state = reduce(state, toolOpenEvent(toolId = "t1", seq = 5)) // the boundary spends the prose
        state = reduce(state, thought("b".repeat(700)))!! // the next segment's tail rides the pending
        val before = state!!
        assertEquals(listOf("Mi edición partió"), before.proseSpent)
        assertEquals("Mi edición partió", before.proseSpent.single())
        assertEquals("", before.prosePending, "the boundary flushed the pending")
        assertEquals("b".repeat(700), before.cotPending, "the thinking's accumulator holds the under-cap tail")
        // THE REBASE: the windows die, the turn's truth stands
        val rebased = reduce(before, RenderEvent.StreamRebase("task-9"))!!
        assertEquals("", rebased.answerTail, "the answer tail dies — the burst rebuilds it")
        assertEquals("", rebased.prosePending, "the pending prose dies — never re-spoken twice")
        assertEquals("", rebased.cotTail, "the thinking's tail dies with the baseline")
        assertEquals("", rebased.cotPending, "the pending thought dies too — one delta writes tail AND pending")
        assertEquals(listOf("Mi edición partió"), rebased.proseSpent, "the spent prose is history — untouched")
        assertEquals(before.cotBlocks, rebased.cotBlocks, "the sealed blocks are real text — untouched")
        assertEquals(before.phase, rebased.phase, "the turn IS running — the phase stands")
        assertEquals(before.tools, rebased.tools, "the lanes stand")
        assertEquals(before.taskId, rebased.taskId)
        // a foreign task's rebase moves nothing
        assertEquals(before, reduce(before, RenderEvent.StreamRebase("other")))
    }

    @Test
    fun theRestatedThoughtDeltaRebuildsThePendingFromZero() {
        // the rebase's own premise: the burst re-states the in-flight content
        // — a re-stated delta lands on the EMPTY accumulator and rebuilds it,
        // never doubles onto the surviving text
        var state = reduce(null, thought("a".repeat(3000))) // block 1 sealed
        state = reduce(state, thought("b".repeat(700)))!! // the pending holds 700 cp
        state = reduce(state, RenderEvent.StreamRebase("task-9"))!!
        assertEquals("", state!!.cotPending, "the accumulator dies with the baseline")
        state = reduce(state, thought("re-stated tail"))!!
        assertEquals("re-stated tail", state!!.cotPending, "the re-stated delta rebuilds from zero")
        assertEquals(listOf("a".repeat(3000)), state.cotBlocks, "the sealed block never doubles")
    }

    @Test
    fun aSuffixOverlapRestatementNeverGluesTheFirstWord() {
        // THE 22/11 SEAM (measured on Telegram: «TheThe old todo list…» in
        // the CoT AND the talking): the recovery re-states from a word
        // start INSIDE the window — the delta starts with the window's
        // TAIL, not with the whole window
        val first = "Revisando el plan que quedó de la tanda anterior. The"
        var s = reduce(null, thought(first))!!
        s = reduce(s, thought("The old todo list is stale. Update for the new tanda."))!!
        assertTrue("TheThe" !in s.cotTail, "the overlapped word never doubles")
        assertTrue(
            s.cotTail.endsWith("anterior. The old todo list is stale. Update for the new tanda."),
            "the remainder appends once, whole: ${s.cotTail}",
        )
    }

    @Test
    fun aLeadingSpaceAppendNeverCutsLegitProse() {
        // the overlap's discriminator: a normal append arrives WITH its
        // leading space (or mid-word) — «ha ha» prose never matches the
        // cold word-restart, however long the delta
        var s = reduce(null, thought("vamos bien ha"))!!
        s = reduce(s, thought(" ha, seguimos con la próxima parte del plan de la tanda"))!!
        assertEquals("vamos bien ha ha, seguimos con la próxima parte del plan de la tanda", s.cotTail)
    }

    @Test
    fun theAnswersSuffixOverlapNeverGluesEither() {
        // the talking's own seam (the owner read it in the violet prose):
        // same law, same shared cut — restatedFresh no longer gates on the
        // whole-window match
        var s = reduce(null, answer("El plan sigue. The"))!!
        s = reduce(s, answer("The next step is the E2 types work."))!!
        assertTrue("TheThe" !in s.answerTail)
        assertTrue(s.answerTail.endsWith("sigue. The next step is the E2 types work."))
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
        assertTrue(s.subagentLanes.isEmpty(), "an in-band child thought does not exist — nothing folds")
        // the child's OWN stream (the routed one) grows its window, never the parent's
        s = reduce(s, RenderEvent.ChildStream(laneId = "lane-A", thought = true, delta = "sub narration", nowMs = 500))!!
        assertEquals(
            "sub narration".length,
            s.subagentLanes["lane-A"]?.cotChars,
            "the routed child's window grows its own chars",
        )
        assertEquals("sub narration", s.subagentLanes["lane-A"]?.cotTail, "the tail keeps the words")
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

    // ---- 11/11: the univocal answer ---------------------------------------------

    @Test
    fun theSealOwnsTheAnswerAndTheFeedNeverSeesIt() {
        // the pending prose rides answerAtSeal at the Sealed frame — the spent
        // list stays empty (the seal feed's diff never sees the answer) and
        // the seal's violet FULL text is the only copy the owner gets
        var s = reduce(null, RenderEvent.Answering("task-9", seq = 1, atMs = 1_000, nowMs = 1_000, delta = "La conclusión."))
        s = reduce(s, RenderEvent.Sealed("task-9", seq = 2, atMs = 1_100, nowMs = 1_100, stopReason = "end_turn", usageTokens = null, error = null))
        s = assertNotNull(s)
        assertTrue(s.proseSpent.isEmpty(), "the seal feed speaks no prose — the answer is not spent")
        assertEquals("La conclusión.", s.answerAtSeal, "the seal's own copy holds the pending answer")
        assertEquals("", s.prosePending)
    }

    @Test
    fun theMidTurnSegmentsStillSpendAtBoundaries() {
        // 16/11: the mid-turn boundaries keep the spending law (segment
        // spent, keyed) — but the FEED never speaks it; the seal's digest
        // ([TurnDigest.ofFold]) owns the voice
        var s = reduce(null, RenderEvent.Answering("task-9", seq = 1, atMs = 1_000, nowMs = 1_000, delta = "hablo"))
        s = reduce(s, toolOpenEvent(toolId = "t1", seq = 2))
        s = assertNotNull(s)
        assertEquals(1, s.proseSpent.size, "the tool boundary spent the segment as always")
        assertEquals("", s.prosePending)
        assertEquals(1, s.proseKeys.size, "the spent segment carries its content key")
    }

    @Test
    fun theRestatedProseNeverReSpendsAfterAWindowRebaseline() {
        // the subscribe reburst re-states the streamed prose: the windows die
        // (StreamRebase) but the content key SURVIVES — the re-flush is a
        // no-op and the feed's positional diff never sees a second segment
        var s = reduce(null, RenderEvent.Answering("task-9", seq = 1, atMs = 1_000, nowMs = 1_000, delta = "tramo uno"))
        s = reduce(s, toolOpenEvent(toolId = "t1", seq = 2)) // the boundary spends it (key recorded)
        s = reduce(s, RenderEvent.StreamRebase("task-9")) // the watchdog re-arms: the windows die
        s = reduce(s, RenderEvent.Answering("task-9", seq = 3, atMs = 3_000, nowMs = 3_000, delta = "tramo uno"))
        s = reduce(s, toolOpenEvent(toolId = "t2", seq = 4)) // the re-stated prose flushes again
        s = assertNotNull(s)
        assertEquals(1, s.proseSpent.size, "the re-stated segment never re-spends")
        assertEquals("", s.prosePending)
    }

    @Test
    fun theReburstsFirstChunkNeverDuplicates() {
        // THE 11/11 DUPLICATED FIRST WORD: the subscribe burst re-states the
        // answer's first chunk — the whole streamed answer IS that delta
        // (chars equal, tail equal) — and it appended again. One copy stays
        // (the bookkeeping's clock may move: a replay proves the bus); a
        // genuine continuation (more content) never matches.
        val first = reduce(null, RenderEvent.Answering("task-9", seq = 1, atMs = 1_000, nowMs = 1_000, delta = "Hola"))
        val reburst = assertNotNull(
            reduce(first, RenderEvent.Answering("task-9", seq = 2, atMs = 1_000, nowMs = 1_100, delta = "Hola")),
        )
        assertEquals(4, reburst.answerChars, "the re-stated first chunk appends nothing")
        assertEquals("Hola", reburst.answerTail)
        assertEquals("Hola", reburst.prosePending)
        val live = assertNotNull(
            reduce(reburst, RenderEvent.Answering("task-9", seq = 3, atMs = 1_200, nowMs = 1_200, delta = " mundo")),
        )
        assertEquals("Hola mundo", live.answerTail, "the genuine continuation folds whole")
        assertEquals(10, live.answerChars)
    }

    @Test
    fun theReburstsFirstThinkingChunkNeverDuplicates() {
        // the answer's twin guard: the re-stated very first thinking chunk
        // appends nothing; the real next delta grows the counter
        val first = reduce(null, RenderEvent.Thought("task-9", seq = 1, atMs = 1_000, nowMs = 1_000, delta = "Pienso", parentToolUseId = null))
        val reburst = assertNotNull(
            reduce(first, RenderEvent.Thought("task-9", seq = 2, atMs = 1_000, nowMs = 1_100, delta = "Pienso", parentToolUseId = null)),
        )
        assertEquals(6, reburst.thoughtChars, "the re-stated first thought appends nothing")
        assertEquals("Pienso", reburst.cotPending)
        val live = assertNotNull(
            reduce(reburst, RenderEvent.Thought("task-9", seq = 3, atMs = 1_200, nowMs = 1_200, delta = " más", parentToolUseId = null)),
        )
        assertEquals(10, live.thoughtChars, "the genuine continuation grows the counter")
    }

    @Test
    fun theRestatedThoughtNeverReSealsItsBlocks() {
        // THE 11/11 COT DOUBLE (the owner: the CoTs carried the first
        // sentence twice): the subscribe reburst re-states the whole thought
        // — the windows die (StreamRebase) and the re-stated deltas re-fill
        // the pending; at 3K they would seal a TWIN block and the feed would
        // speak it again. The block's content key says spoken: skipped, the
        // pending drains without a second voice.
        val big = "0123456789".repeat(300) // 3000 cp — exactly one block
        var s = reduce(null, RenderEvent.Thought("task-9", seq = 1, atMs = 1_000, nowMs = 1_000, delta = big, parentToolUseId = null))
        s = assertNotNull(s)
        assertEquals(1, s.cotBlocks.size, "the first pass seals its block")
        s = reduce(s, RenderEvent.StreamRebase("task-9")) // the watchdog re-arms: the windows die
        s = assertNotNull(s)
        assertEquals("", s.cotPending, "the pending died with the windows")
        s = reduce(s, RenderEvent.Thought("task-9", seq = 2, atMs = 2_000, nowMs = 2_000, delta = big, parentToolUseId = null))
        s = assertNotNull(s)
        assertEquals(1, s.cotBlocks.size, "the re-stated thought never seals a twin block")
        assertEquals("", s.cotPending, "the re-stated content drains without speaking")
    }

    @Test
    fun theRecoveryHeraldRebasesBeforeTheRegeneratedStreamLands() {
        // THE 11/11 SCREENSHOT («NoNo …»): the core's stream recovery
        // re-opened the request and the fork re-stated the reasoning FROM
        // ITS START — the re-stated first deltas appended onto the
        // surviving windows and the CoT carried the first sentence twice.
        // The herald (the stream_recovery pill) folds the cut FIRST: the
        // re-generated stream rebuilds the windows from zero, never doubles.
        var s = reduce(null, RenderEvent.Thought("task-9", seq = 1, atMs = 1_000, nowMs = 1_000, delta = "Primera frase.", parentToolUseId = null))
        s = reduce(s, RenderEvent.Thought("task-9", seq = 2, atMs = 1_100, nowMs = 1_100, delta = " Sigo pensando.", parentToolUseId = null))
        s = assertNotNull(s)
        assertEquals("Primera frase. Sigo pensando.", s.cotPending)
        s = reduce(s, RenderEvent.StreamRebase("task-9")) // the herald's cut
        s = assertNotNull(s)
        assertEquals("", s.cotPending, "the windows died at the herald")
        s = reduce(s, RenderEvent.Thought("task-9", seq = 3, atMs = 1_300, nowMs = 1_300, delta = "Primera frase.", parentToolUseId = null))
        s = reduce(s, RenderEvent.Thought("task-9", seq = 4, atMs = 1_400, nowMs = 1_400, delta = " Sigo pensando de nuevo.", parentToolUseId = null))
        s = assertNotNull(s)
        assertEquals(
            "Primera frase. Sigo pensando de nuevo.",
            s.cotPending,
            "the regenerated stream rebuilds the pending, never doubles it",
        )
    }
}
