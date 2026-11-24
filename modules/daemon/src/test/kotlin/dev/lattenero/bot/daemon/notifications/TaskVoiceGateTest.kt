package dev.lattenero.bot.daemon.notifications

import dev.lattenero.bot.daemon.notifications.TaskSignal.Terminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cross-source gate's laws (24/11): one transition, one voice. The
 * same verdict folds inside the window; a DIFFERENT verdict always speaks
 * (a correction must ring); a turn that began after the recorded voice is
 * a new turn and always speaks; past the window everything speaks again.
 */
class TaskVoiceGateTest {

    private var clock = 1_000_000L
    private fun gate() = TaskVoiceGate(clockMs = { clock })

    @Test
    fun theSameVerdictInsideTheWindowFolds() {
        val g = gate()
        assertTrue(g.trySpeak("t1", Terminal.FINISHED))
        clock += 5_000L
        assertFalse(g.trySpeak("t1", Terminal.FINISHED), "the other observer already said it")
        clock += 60_000L
        assertFalse(g.trySpeak("t1", Terminal.FINISHED), "still inside the 2 min window (the measured 61 s lag)")
    }

    @Test
    fun pastTheWindowTheVoiceSpeaksAgain() {
        val g = gate()
        assertTrue(g.trySpeak("t1", Terminal.FINISHED))
        clock += TaskVoiceGate.GAP_MS
        assertTrue(g.trySpeak("t1", Terminal.FINISHED))
    }

    @Test
    fun aDifferentVerdictAlwaysSpeaks() {
        val g = gate()
        assertTrue(g.trySpeak("t1", Terminal.FINISHED))
        clock += 1_000L
        assertTrue(g.trySpeak("t1", Terminal.FAILED), "a ❌ is never folded into a 🏁")
    }

    @Test
    fun aTurnThatBeganAfterTheVoiceAlwaysSpeaks() {
        val g = gate()
        assertTrue(g.trySpeak("t1", Terminal.FINISHED))
        clock += 1_000L
        assertTrue(
            g.trySpeak("t1", Terminal.FINISHED, turnHintMs = clock - 500L),
            "the new turn started AFTER the recorded voice — a genuine second end",
        )
    }

    @Test
    fun aTurnFromBeforeTheVoiceFolds() {
        val g = gate()
        assertTrue(g.trySpeak("t1", Terminal.FINISHED))
        clock += 10_000L
        assertFalse(
            g.trySpeak("t1", Terminal.FINISHED, turnHintMs = 500_000L),
            "the turn was already running when the voice spoke — the same transition",
        )
    }

    @Test
    fun tasksAreIndependent() {
        val g = gate()
        assertTrue(g.trySpeak("t1", Terminal.FINISHED))
        assertTrue(g.trySpeak("t2", Terminal.FINISHED))
    }

    @Test
    fun theMapStaysBounded() {
        val g = gate()
        repeat(TaskVoiceGate.LAST_CAP + 10) { i ->
            g.trySpeak("t$i", Terminal.FINISHED)
            clock += 1_000L
        }
        assertEquals(TaskVoiceGate.LAST_CAP, g.underlyingSize(), "the stale prune keeps the map small")
    }
}
