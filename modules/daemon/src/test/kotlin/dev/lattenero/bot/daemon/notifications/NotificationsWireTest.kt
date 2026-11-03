package dev.lattenero.bot.daemon.notifications

import dev.lattenero.bot.channel.SealFacts
import dev.lattenero.bot.contracts.ShadowDecision
import dev.lattenero.bot.daemon.DaemonConfig
import dev.lattenero.bot.daemon.send.DaemonSend
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The assembly's own laws: OFF = null (the wiring builds nothing), ON =
 * both taps; the seal tap answers null-turns with silence (no send, no
 * gate spend), folds a repeat seal of the same task inside the window,
 * and delivers through the COMMANDS lane's leg, loud, one decision.
 */
class NotificationsWireTest {

    private val sent = mutableListOf<Pair<ShadowDecision, Boolean>>()

    private val leg = dev.lattenero.bot.daemon.send.SendLeg { decision, loud -> sent += decision to loud }

    private val send = DaemonSend.of(dev.lattenero.bot.daemon.send.SendMode.LIVE, leg)

    private fun tmpConfig(): DaemonConfig {
        val home = Files.createTempDirectory("notif-wire-home").toFile()
        return DaemonConfig(
            baseDir = Files.createTempDirectory("notif-wire-base").toFile(),
            homeDir = home,
        )
    }

    private fun facts(
        taskId: String,
        answer: String? = "el turno dijo algo",
        doneTools: Int = 2,
        startedAtMs: Long? = 1_000L,
        sealedAtMs: Long = 61_000L,
    ) = SealFacts(taskId, "título", "end_turn", startedAtMs, sealedAtMs, doneTools, 0, answer)

    @Test
    fun offBuildsNothing() {
        val taps = NotificationsWire.build(tmpConfig(), send, on = false)
        assertNull(taps, "the knob's OFF is the whole feature absent")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun onBuildsBothTaps() {
        val taps = NotificationsWire.build(tmpConfig(), send, on = true)
        assertNotNull(taps)
        assertNotNull(taps.watcher)
    }

    @Test
    fun theSealTapDeliversLoudThroughTheCommandsLeg() = runTest {
        val taps = NotificationsWire.build(tmpConfig(), send, on = true)!!
        taps.onSeal(facts("t1"))
        assertEquals(1, sent.size)
        val (decision, loud) = sent[0]
        assertEquals(NotifText.WATCHER, decision.watcher)
        assertEquals("turn_sealed", decision.decision)
        assertEquals(true, loud)
        assertTrue(decision.text.startsWith("🏁 "))
    }

    @Test
    fun anEmptyTurnFoldsIntoSilenceWithoutSpendingTheGate() = runTest {
        val taps = NotificationsWire.build(tmpConfig(), send, on = true)!!
        taps.onSeal(facts("t1", answer = null, doneTools = 0))
        assertTrue(sent.isEmpty(), "silence is data — no decoration, no gate spend")
        // the gate was NOT moved: a REAL turn right after still speaks
        taps.onSeal(facts("t1"))
        assertEquals(1, sent.size)
    }

    @Test
    fun aRepeatSealInsideTheWindowFoldsIntoSilence() = runTest {
        var clock = 100_000L
        val taps = NotificationsWire.build(tmpConfig(), send, on = true, clockMs = { clock })!!
        taps.onSeal(facts("t1"))
        clock += 30_000L
        taps.onSeal(facts("t1", answer = "otra respuesta"))
        assertEquals(1, sent.size, "the card already showed it: 60 s per task")
        clock += 40_000L // 70 s after the announce
        taps.onSeal(facts("t1", answer = "una tercera"))
        assertEquals(2, sent.size, "past the window: a new seal speaks again")
    }

    @Test
    fun aTapFailureIsOneLogLineNeverAThrow() = runTest {
        val exploding = dev.lattenero.bot.daemon.send.SendLeg { _, _ -> error("the wire is down") }
        val taps = NotificationsWire.build(tmpConfig(), DaemonSend.of(dev.lattenero.bot.daemon.send.SendMode.LIVE, exploding), on = true)!!
        taps.onSeal(facts("t1")) // must not throw — the bus's tap invariant
        Unit
    }
}
