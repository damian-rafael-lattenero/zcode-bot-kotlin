package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * THE CONNECTION'S SEQ FLOOR (14/11): the fork's `seq` counts EVERY frame
 * the connection delivers — any subscribed task's events and any
 * subscription's keepalives — so only a jump past everything admitted is a
 * real loss. These laws pin the measured failure: the per-task read of the
 * shared counter called the other subscriptions' traffic a gap 1095 times
 * in one day.
 */
class ConnSeqFloorTest {

    @Test
    fun theOrdinaryAdvanceIsNeverAGap() {
        val floor = ConnSeqFloor()
        assertFalse(floor.admit(1L), "the first frame arms the floor, never gaps")
        assertFalse(floor.admit(2L))
        assertFalse(floor.admit(3L))
    }

    @Test
    fun anotherSubscriptionsSeqsAreConsumedNotLost() {
        val floor = ConnSeqFloor()
        floor.admit(10L) // the watched task's event
        assertFalse(floor.admit(11L), "a sibling task's event on the same connection is delivered, not lost")
        assertFalse(floor.admit(12L), "a sibling's keepalive consumes the counter too")
        assertFalse(floor.admit(13L), "the watched task's next frame continues without a gap")
    }

    @Test
    fun aRealJumpPastEverythingAdmittedIsAGap() {
        val floor = ConnSeqFloor()
        floor.admit(5L)
        floor.admit(6L)
        assertTrue(floor.admit(9L), "7..8 were delivered to nobody — a real loss")
    }

    @Test
    fun theReconnectEpochRestartsSilentlyAndStillGapsAfterwards() {
        val floor = ConnSeqFloor()
        floor.admit(5000L)
        assertFalse(floor.admit(1L), "the new connection restarts the counter: a re-baseline, not a loss")
        assertFalse(floor.admit(2L))
        assertTrue(floor.admit(6L), "a real loss inside the new epoch is still a gap")
    }
}
