package dev.lattenero.bot.daemon.notifications

import dev.lattenero.bot.channel.SealFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The format law («mensajes bien fuertes»): emoji + BOLD first line,
 * plain lines after; honest truncation; the empty turn's silence.
 */
class NotifTextTest {

    @Test
    fun theSubagentLineCarriesEmojiBoldHeadTitleAndOutcome() {
        val text = NotifText.subagentDone(SubagentDone("sess_subagent_1", "misión limpia", "completed", 1, 2))
        assertTrue(text.startsWith("🔔 "), "the moment's emoji first")
        assertTrue(text.contains("<b>Subagente terminó</b>"), "the BOLD first line")
        assertTrue(text.contains("«misión limpia»"))
        assertTrue(text.contains("completed"))
        assertEquals(3, text.lines().size, "emoji head + title + outcome")
    }

    @Test
    fun aLongOutcomeIsTruncatedHonestly() {
        val outcome = "x".repeat(500)
        val text = NotifText.subagentDone(SubagentDone("s", "t", outcome, 1, 2))
        val line = text.lines().last()
        assertEquals(NotifText.OUTCOME_PREVIEW_CP + 1, line.length, "120 chars + the ellipsis")
        assertTrue(line.endsWith("…"))
    }

    @Test
    fun aShortOutcomeRidesAsItIs() {
        val text = NotifText.subagentDone(SubagentDone("s", "t", "interrupted", 1, 2))
        assertTrue(text.lines().last() == "interrupted")
    }

    @Test
    fun htmlInTheStoreSpeaksEscaped() {
        val text = NotifText.subagentDone(SubagentDone("s", "<b>inyección</b>", "<i>done</i>", 1, 2))
        assertTrue(text.contains("&lt;b&gt;inyección&lt;/b&gt;"))
        assertTrue(text.contains("&lt;i&gt;done&lt;/i&gt;"))
        assertTrue("<b>inyección" !in text)
    }

    @Test
    fun aBlankTitleFallsBackToTheSidTail() {
        val text = NotifText.subagentDone(SubagentDone("sess_subagent_abcdef12", "", "completed", 1, 2))
        assertTrue(text.contains("«abcdef12»"))
    }

    @Test
    fun theSealLineCarriesEmojiBoldHeadDurationAndTools() {
        val facts = SealFacts(
            taskId = "task-9",
            title = "arreglá el login",
            stopReason = "end_turn",
            startedAtMs = 0,
            sealedAtMs = 192_000L,
            doneTools = 7,
            failedTools = 0,
            answer = "listo, el login valida de nuevo",
        )
        val text = NotifText.turnSealed(facts)!!
        assertTrue(text.startsWith("🏁 "), "the moment's emoji first")
        assertTrue(text.contains("<b>«arreglá el login» — terminó (3m12s, 7 herramientas)</b>"))
        assertTrue(text.contains("listo, el login valida de nuevo"))
        assertEquals(2, text.lines().size, "the bold head + the answer's plain line")
    }

    @Test
    fun aSpokenAnswerIsNeverRepeatedByTheBell() {
        val facts = SealFacts(
            taskId = "task-9",
            title = "arreglá el login",
            stopReason = "end_turn",
            startedAtMs = 0,
            sealedAtMs = 192_000L,
            doneTools = 7,
            failedTools = 0,
            answer = "listo, el login valida de nuevo",
            answerSpoken = true, // the live card just spoke the full answer
        )
        val text = NotifText.turnSealed(facts)!!
        assertTrue(text.contains("terminó (3m12s, 7 herramientas)"), "the moment still speaks")
        assertTrue("listo, el login" !in text, "the single-source law: the 🔔 never repeats the card's answer")
    }

    @Test
    fun oneToolIsSingular() {
        val facts = SealFacts("t", "título", "end_turn", 0, 5_000L, doneTools = 1, failedTools = 0, answer = null)
        assertTrue(NotifText.turnSealed(facts)!!.contains("1 herramienta)"))
    }

    @Test
    fun anAnswerLongerThanTheBudgetIsTruncatedHonestly() {
        val facts = SealFacts("t", "título", "end_turn", 0, 1_000L, doneTools = 2, failedTools = 0, answer = "y".repeat(400))
        val line = NotifText.turnSealed(facts)!!.lines().last()
        assertEquals(NotifText.ANSWER_PREVIEW_CP + 1, line.length, "150 chars + the ellipsis")
        assertTrue(line.endsWith("…"))
    }

    @Test
    fun aTurnThatProducedNothingIsSilence() {
        // the owner's law: silence is data, no decoration
        assertEquals(null, NotifText.turnSealed(SealFacts("t", "título", "end_turn", 0, 1_000L, 0, 0, null)))
        assertEquals(null, NotifText.turnSealed(SealFacts("t", "título", "end_turn", 0, 1_000L, 0, 0, "  ")))
        // but zero tools WITH an answer still speaks
        val withAnswer = NotifText.turnSealed(SealFacts("t", "título", "end_turn", 0, 1_000L, 0, 0, "respondí sin herramientas"))
        assertTrue(withAnswer!!.contains("respondí sin herramientas"))
        // and tools with no answer speak too
        val withTools = NotifText.turnSealed(SealFacts("t", "título", "end_turn", 0, 1_000L, 3, 0, null))
        assertTrue(withTools!!.contains("3 herramientas"))
    }

    @Test
    fun anUnknownStartDoesNotInventADuration() {
        val facts = SealFacts("t", "título", "end_turn", null, 1_000L, 2, 0, null)
        assertTrue(NotifText.turnSealed(facts)!!.contains("(?, 2 herramientas)"))
    }
}
