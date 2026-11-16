package dev.lattenero.bot.daemon.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The attach clock's laws: one fire per arm (the first frame answers), a
 * re-attach restarts the clock, and a task that was never armed never
 * records — nothing invented.
 */
class AttachClockTest {

    @Test
    fun armThenFireAnswersTheLatencyOnce() {
        var now = 1_000L
        val clock = AttachClock { now }
        clock.arm("sess_a")
        now = 1_180L
        assertEquals(180L, clock.fire("sess_a"))
        assertNull(clock.fire("sess_a"), "the clock is spent — a second frame is not the first")
    }

    @Test
    fun anUnarmedTaskNeverRecords() {
        val clock = AttachClock { 5_000L }
        assertNull(clock.fire("sess_never"))
    }

    @Test
    fun aReArmRestartsTheClock() {
        var now = 1_000L
        val clock = AttachClock { now }
        clock.arm("sess_a")
        now = 2_000L
        clock.arm("sess_a") // a new tap re-arms the same task
        now = 2_250L
        assertEquals(250L, clock.fire("sess_a"))
    }
}
