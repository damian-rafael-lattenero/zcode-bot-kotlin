package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE PARK TAP's law (U5c, the owner's ⏸️, 3/11): the verdict is a PURE
 * function of the fold — only the LIVE CARD's task parks, a second tap is an
 * honest no-op, an unknown/expired card is refused, and a parked state never
 * re-parks. The tap's own wordings ride [ParkTapTexts] (the ack is the
 * owner-approved wording). The fold's own transitions live in [ParkFoldTest].
 */
class ParkTapTest {

    private fun state(taskId: String = "task-9", parked: ParkLive? = null): TurnLive =
        TurnLive(taskId = taskId, title = taskId.takeLast(LiveTurn.TITLE_TAIL), parked = parked)

    @Test
    fun onlyTheLiveCardsTaskParks() {
        // no card at all: the honest refusal
        assertEquals(ParkTap.Verdict.UNKNOWN, ParkTap.verdict(null, cardTaskId = null, taskId = "task-9"))
        // a card born for ANOTHER task: the button is expired
        assertEquals(
            ParkTap.Verdict.UNKNOWN,
            ParkTap.verdict(state("task-9"), cardTaskId = "other-task", taskId = "task-9"),
        )
        // a tap for a task the fold no longer watches: the button is expired
        assertEquals(
            ParkTap.Verdict.UNKNOWN,
            ParkTap.verdict(state("task-9"), cardTaskId = "task-9", taskId = "task-2"),
        )
        // the CURRENT card parks
        assertEquals(ParkTap.Verdict.PARKED, ParkTap.verdict(state(), cardTaskId = "task-9", taskId = "task-9"))
    }

    @Test
    fun aSecondTapIsAnHonestNoOp() {
        val parked = state(parked = ParkLive(atMs = 2_000L))
        assertEquals(
            ParkTap.Verdict.ALREADY,
            ParkTap.verdict(parked, cardTaskId = "task-9", taskId = "task-9"),
            "a parked card re-parks nothing",
        )
        // an unlanded park (the throttled edit) is STILL parked: the no-op holds
        assertEquals(
            ParkTap.Verdict.ALREADY,
            ParkTap.verdict(state(parked = ParkLive(atMs = 2_000L, landed = false)), "task-9", "task-9"),
        )
    }

    @Test
    fun theFoldCarriesTheParkAndTheClearTakesItWithTheWorld() {
        // a live card folds the tap's fact
        var s = reduce(state(), RenderEvent.Park("task-9", atMs = 2_000L), SimpleLineDiffer)!!
        assertEquals(2_000L, s.parked!!.atMs)
        // a frame is not a task switch — the park survives the stream
        val streamed = reduce(s, RenderEvent.Started("task-9", seq = 1, atMs = 3_000, nowMs = 3_000), SimpleLineDiffer)
        assertEquals(s.parked, streamed!!.parked)
        // a task switch is a fresh card — the park dies with the old one
        val switched = reduce(s, RenderEvent.Started("task-2", seq = 2, atMs = 4_000, nowMs = 4_000), SimpleLineDiffer)
        assertNull(switched!!.parked, "a task switch is a fresh card")
        // the state's Clear (the seal, a ☢️ nuclear) drops it with the turn
        assertNull(reduce(s, RenderEvent.Clear, SimpleLineDiffer), "the world's death takes the park with it")
    }

    @Test
    fun theAcksAreTheOwnerApprovedWording() {
        // the ack is the tap's promise, verbatim from the owner's approval
        assertEquals("▶️ A background — te aviso al terminar", ParkTapTexts.ACK)
        // the refusal names what did NOT happen (the style guide's law)
        assertTrue("no hay cartel vivo que aparcar" in ParkTapTexts.REFUSAL)
        assertTrue("Ya está en background" in ParkTapTexts.ALREADY)
        assertTrue("Sin Telegram" in ParkTapTexts.NO_TELEGRAM)
    }
}
