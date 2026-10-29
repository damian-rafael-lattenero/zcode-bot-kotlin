package dev.lattenero.bot.daemon.spectator

import dev.lattenero.bot.daemon.shadow.ShadowDecision
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
    fun theOrchestratorStartsUnownedTurnsAndReapsThem() = runTest {
        val unowned = "session_m"
        val owned = "session_w"
        File(rollouts, "model-io-$unowned.jsonl").writeText("")
        File(rollouts, "model-io-$owned.jsonl").writeText("")
        stateDir.resolve("bot-workers.json").writeText(
            """{"$owned": {"t0": 1.0, "prompt": "p", "ws": "/w", "title": "t", "pid": 7}}""",
        )
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
}
