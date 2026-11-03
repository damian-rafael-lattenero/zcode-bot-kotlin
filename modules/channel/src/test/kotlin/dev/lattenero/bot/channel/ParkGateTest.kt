package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * THE PARK MACHINE's law (the owner's ⏸️, 3/11): only the current card
 * parks, a second tap is a no-op, the park dies with its card (seal,
 * replace, world) — a NEW turn for the same task is never parked, by
 * construction. The tap's own wordings ride [ParkTapTexts] (the ack is
 * the owner-approved wording).
 */
class ParkGateTest {

    @Test
    fun onlyTheCurrentCardParks() {
        val gate = ParkGate()
        // no card at all: the honest refusal
        assertEquals(ParkGate.Verdict.UNKNOWN, gate.park("task-9", cardTaskId = null))
        assertFalse(gate.isParked("task-9"))
        // a tap for a card that is not the current one (expired/replaced): refused
        assertEquals(ParkGate.Verdict.UNKNOWN, gate.park("task-9", cardTaskId = "other-task"))
        assertFalse(gate.isParked("task-9"), "a refused tap parks nothing")
        // the CURRENT card parks
        assertEquals(ParkGate.Verdict.PARKED, gate.park("task-9", cardTaskId = "task-9"))
        assertTrue(gate.isParked("task-9"))
    }

    @Test
    fun aSecondTapIsAnHonestNoOp() {
        val gate = ParkGate()
        assertEquals(ParkGate.Verdict.PARKED, gate.park("task-9", cardTaskId = "task-9"))
        assertEquals(ParkGate.Verdict.ALREADY, gate.park("task-9", cardTaskId = "task-9"))
        assertTrue(gate.isParked("task-9"), "the second tap changes nothing")
    }

    @Test
    fun theParkDiesWithItsCardAtASealANewTurnIsNeverParked() {
        val gate = ParkGate()
        assertEquals(ParkGate.Verdict.PARKED, gate.park("task-9", cardTaskId = "task-9"))
        // THE SEAL: the same seal that ends the parked turn drops the entry
        gate.endCard("task-9")
        assertFalse(gate.isParked("task-9"))
        // a NEW turn re-binds a fresh card: it parks FRESH (it is a new card)
        assertEquals(
            ParkGate.Verdict.PARKED,
            gate.park("task-9", cardTaskId = "task-9"),
            "the one-shot is per CARD: the new card is parkable again",
        )
    }

    @Test
    fun aReplacedCardLosesItsParkToo() {
        val gate = ParkGate()
        assertEquals(ParkGate.Verdict.PARKED, gate.park("task-9", cardTaskId = "task-9"))
        gate.endCard("task-9") // the replaced-seal's own drop
        assertFalse(gate.isParked("task-9"))
    }

    @Test
    fun theWorldSDeathTakesEveryParkWithIt() {
        val gate = ParkGate()
        assertEquals(ParkGate.Verdict.PARKED, gate.park("task-9", cardTaskId = "task-9"))
        assertEquals(ParkGate.Verdict.PARKED, gate.park("task-2", cardTaskId = "task-2"))
        gate.worldEnded() // the hub went empty (nuclear / spent seal)
        assertFalse(gate.isParked("task-9"))
        assertFalse(gate.isParked("task-2"))
    }

    @Test
    fun theAcksAreTheOwnerApprovedWording() {
        // the ack is the tap's promise, verbatim from the owner's approval
        assertEquals("▶️ A background — te aviso al terminar", ParkTapTexts.ACK)
        // the refusal names what did NOT happen (the style guide's law)
        assertTrue("no hay cartel vivo que aparcar" in ParkTapTexts.REFUSAL)
    }
}
