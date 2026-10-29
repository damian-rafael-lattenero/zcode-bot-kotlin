package dev.lattenero.bot.daemon.spectator

import dev.lattenero.bot.contracts.ShadowDecision
import dev.lattenero.bot.daemon.send.SendLeg
import dev.lattenero.bot.daemon.send.SendLegs
import dev.lattenero.bot.daemon.shadow.ShadowLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A whole Mac turn against a real directory tree, in virtual time: the
 * marker appears, the turn streams its steps to the shadow log, the
 * marker's death ends the process, and the orchestrator reaps it.
 */
class SpectatorWatchTest {

    @TempDir
    lateinit var base: File

    private val sid = "session_e2e"
    private val stateDir: File get() = File(File(base, "hooks"), "state").apply { mkdirs() }
    private val rollouts: File get() = File(File(base, "cli"), "rollout").apply { mkdirs() }
    private val shadowDir: File get() = File(base, "shadow")

    private fun recordLine(turnId: String = "turn_1", text: String = "", command: String? = null): String {
        val tools = if (command == null) {
            ""
        } else {
            ""","toolCalls": [{"name": "Bash", "input": {"command": "$command"}}]"""
        }
        val narration = if (text.isEmpty()) "" else ""","text": "$text""""
        return """{"querySource": "main_turn", "startedAt": "2026-10-29T12:00:01.000Z",""" +
            """"turnId": "$turnId", "response": {"finishReason": "tool-calls"$narration$tools}}""" + "\n"
    }

    private fun decisions(): List<ShadowDecision> =
        File(shadowDir, "spectator.jsonl").readLines()
            .map { Json.decodeFromString(ShadowDecision.serializer(), it) }

    @Test
    fun aWholeMacTurnStreamsToTheShadowAndEndsWithTheMarker() = runTest {
        val marker = SpectatorIo.markerPath(stateDir, sid)
        marker.writeText("""{"token": "t1", "prompt": "p"}""")
        stateDir.resolve("bot-session.json").writeText("""{"sid": "$sid", "title": "t"}""")
        // the rollout EXISTS but is empty: like Python's hook-spawned spectator,
        // the turn starts at EOF and only records completing from NOW on exist
        val rollout = File(rollouts, "model-io-$sid.jsonl")
        rollout.writeText("")

        var nowVal = 1000.0
        val turn = SpectatorTurn(base, stateDir, ShadowLog(shadowDir)) { nowVal }
        val job = launch { turn.run(sid) }
        runCurrent() // round 1: armed, nothing new yet

        rollout.appendText(recordLine(text = "voy a leer el archivo") + recordLine(command = "wc -l x.kt"))
        nowVal = 1001.0
        advanceTimeBy(1_000)
        runCurrent() // advanceTimeBy does not run tasks at the exact boundary

        val first = decisions()
        assertEquals(3, first.size, "header + narration + one box")
        assertEquals("🖥️ <b>«ZCode»</b> — Mac turn live (spectator)", first[0].text)
        assertTrue(first[0].gated == true, "the task is current, the gate is open")
        assertEquals(sid, first[0].sid)
        assertTrue(first[2].text.startsWith("🔧 <b>Bash</b> — <code>wc -l x.kt</code>"))
        assertTrue(first[2].text.endsWith(" · <b>1</b>"))

        marker.delete() // F32 consumed it
        nowVal = 1003.0
        advanceTimeBy(2_000)
        job.join()
        assertEquals(3, decisions().size, "the turn is over: nothing more lands")
    }

    @Test
    fun aNewPromptTakesOverAndTheOldTurnStepsDown() = runTest {
        val marker = SpectatorIo.markerPath(stateDir, sid)
        marker.writeText("""{"token": "t1", "prompt": "p"}""")
        val rollout = File(rollouts, "model-io-$sid.jsonl")
        rollout.writeText("")

        val turn = SpectatorTurn(base, stateDir, ShadowLog(shadowDir)) { 1000.0 }
        val job = launch { turn.run(sid) }
        runCurrent()

        rollout.appendText(recordLine(command = "ls"))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, decisions().size)

        // a NEW prompt re-created the marker: its own spectator owns the turn
        marker.writeText("""{"token": "t2", "prompt": "otra cosa"}""")
        rollout.appendText(recordLine(turnId = "turn_2", command = "make"))
        advanceTimeBy(2_000)
        job.join()
        assertEquals(2, decisions().size, "the old turn no longer exists for this spectator")
    }

    @Test
    fun theThirtyFiveMinuteCapEndsTheTurnOnItsOwn() = runTest {
        // the marker never dies and nothing new arrives: the ONLY way out
        // is the cap — 2100 ticks at 1 s, the `now() < deadline` check as
        // schedule algebra (repeat runs the body once before deciding,
        // so the last delay lands at 34:59)
        SpectatorIo.markerPath(stateDir, sid).writeText("""{"token": "t1", "prompt": "p"}""")
        File(rollouts, "model-io-$sid.jsonl").writeText("")
        stateDir.resolve("bot-session.json").writeText("""{"sid": "$sid", "title": "t"}""")

        val turn = SpectatorTurn(base, stateDir, ShadowLog(shadowDir)) { 1000.0 }
        val job = launch { turn.run(sid) }
        runCurrent() // the turn is armed and watching

        advanceTimeBy(35 * 60_000)
        runCurrent() // advanceTimeBy does not run tasks at the exact boundary
        kotlinx.coroutines.withTimeoutOrNull(1_000) { job.join() }
        assertEquals(true, job.isCompleted, "the cap ends the turn without the marker dying")
        assertTrue(testScheduler.currentTime >= (35 * 60 - 1) * 1_000L, "a full 35-minute window of ticks ran")
    }

    @Test
    fun theOrchestratorStartsUnownedTurnsAndReapsThem() = runTest {
        val unowned = "session_m"
        val owned = "session_w"
        File(rollouts, "model-io-$unowned.jsonl").writeText("")
        File(rollouts, "model-io-$owned.jsonl").writeText("")
        stateDir.resolve("bot-workers.json").writeText(
            """{"$owned": {"t0": 1.0, "prompt": "p", "ws": "/w", "title": "t", "pid": 7}}""",
        )
        // the F15 gate at spawn: ONLY the current task gets a spectator
        // (the comparator's first real catch was this gate missing)
        stateDir.resolve("bot-session.json").writeText("""{"sid": "$unowned", "title": "t"}""")
        val m1 = SpectatorIo.markerPath(stateDir, unowned)
        m1.writeText("""{"token": "t1", "prompt": "p"}""")
        SpectatorIo.markerPath(stateDir, owned).writeText("""{"token": "t2", "prompt": "p"}""")

        val orch = SpectatorOrchestrator(base, stateDir, ShadowLog(shadowDir), { 1000.0 }, backgroundScope)
        assertEquals(1, orch.scan(), "the worker-owned session gets no spectator")
        assertEquals(1, orch.liveTurns())

        m1.delete() // the turn ended
        advanceTimeBy(2_000)
        orch.scan()
        assertEquals(0, orch.liveTurns(), "a completed turn's handle is reaped")
    }

    @Test
    fun aMarkerOutsideTheStreamFocusGetsNoSpectator() = runTest {
        val sid = "session_x"
        File(rollouts, "model-io-$sid.jsonl").writeText("")
        stateDir.resolve("bot-session.json").writeText("""{"sid": "session_otra", "title": "t"}""")
        SpectatorIo.markerPath(stateDir, sid).writeText("""{"token": "t", "prompt": "p"}""")

        val orch = SpectatorOrchestrator(base, stateDir, ShadowLog(shadowDir), { 1000.0 }, backgroundScope)
        assertEquals(0, orch.scan(), "Python spawns only for the focused task (hook F15 gate)")
    }

    @Test
    fun theSendLegCarriesOnlyGatedMessagesSilently() = runTest {
        // B2's knob: Python's send_msg sends ONLY when may_push says yes
        // (the decision is recorded either way), and the boxes are silent
        // (loud=False — only zconn's ⚠️/✅ notify)
        val marker = SpectatorIo.markerPath(stateDir, sid)
        marker.writeText("""{"token": "t1", "prompt": "p"}""")
        stateDir.resolve("bot-session.json").writeText("""{"sid": "$sid", "title": "t"}""")
        val rollout = File(rollouts, "model-io-$sid.jsonl")
        rollout.writeText("")

        val delivered = mutableListOf<Pair<String, Boolean>>()
        val leg = SendLeg { d, loud -> delivered.add(d.text to loud) }
        val turn = SpectatorTurn(base, stateDir, ShadowLog(shadowDir), leg) { 1000.0 }
        val job = launch { turn.run(sid) }
        runCurrent()

        rollout.appendText(recordLine(text = "voy") + recordLine(command = "wc -l x.kt"))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, decisions().size, "all three decisions recorded, gated or not")
        assertEquals(3, delivered.size, "the task is current — every message passed the live gate")
        assertTrue(delivered.all { it.second == false }, "the spectator never notifies loudly")

        // the F15 gate closes mid-turn: the stream focus moves away — the
        // decisions keep landing in the shadow log, the send goes quiet
        stateDir.resolve("bot-session.json").writeText("""{"sid": "session_otra", "title": "t"}""")
        rollout.appendText(recordLine(command = "ls"))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(4, decisions().size, "the decision is still recorded")
        assertEquals(3, delivered.size, "but nothing is sent while gated")

        marker.delete()
        advanceTimeBy(2_000)
        job.join()
    }

    @Test
    fun theOwnerIgnoresMacSpectatorFalseAtTheCutover() = runTest {
        // The flip's meaning change: mac_spectator=false silences PYTHON's
        // spawns; the OWNER (send mode != shadow) keeps spawning — turning
        // the knob off must not mute the phone. In shadow mode the knob
        // still gates BOTH sides (parity, unchanged).
        File(rollouts, "model-io-$sid.jsonl").writeText("")
        stateDir.resolve("bot-session.json").writeText("""{"sid": "$sid", "title": "t"}""")
        SpectatorIo.markerPath(stateDir, sid).writeText("""{"token": "t1", "prompt": "p"}""")
        File(base, "telegram-remote.json").writeText("""{"mac_spectator": false}""")

        val parity = SpectatorOrchestrator(base, stateDir, ShadowLog(shadowDir), { 1000.0 }, backgroundScope)
        assertEquals(0, parity.scan(), "shadow mode honors the knob on both sides — today's contract")

        SpectatorIo.markerPath(stateDir, sid).writeText("""{"token": "t2", "prompt": "p"}""")
        val owner = SpectatorOrchestrator(
            base, stateDir, ShadowLog(shadowDir), { 1000.0 }, backgroundScope,
            dev.lattenero.bot.daemon.send.DaemonSend(leg = SendLegs.NONE, ownsSpectator = true),
        )
        assertEquals(1, owner.scan(), "the owner spawns: the knob now silences only Python")
    }
}
