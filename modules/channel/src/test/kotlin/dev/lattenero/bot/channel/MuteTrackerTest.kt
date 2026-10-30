package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The mute-window tracker: the bot explains its own silences — the first
 * failure opens the window, the first success closes it, blips under a
 * minute are noise, and the render is the owner's wording.
 */
class MuteTrackerTest {

    @Test
    fun aLongWindowIsReportedOnRecovery() {
        var now = 1_000_000L
        val tracker = MuteTracker(clock = { now })
        assertNull(tracker.onFetchOk()) // healthy: nothing to close

        now += 10_000
        tracker.onFetchFailed() // the window opens
        now += 5_000
        tracker.onFetchFailed() // extends, does not restart
        now += 300_000
        val window = tracker.onFetchOk() // recovered after 5 min
        assertNotNull(window)
        assertEquals(1_010_000L, window.fromMs)
        assertEquals(1_315_000L, window.toMs)
        val text = window.render()
        assertEquals(true, text.startsWith("🔌 estuve desconectado "))
        assertEquals(true, "(5 min" in text)
        assertEquals(true, "Telegram lo guardó" in text) // honesty: nothing was lost
    }

    @Test
    fun aBlipUnderAMinuteIsNoise() {
        var now = 5_000_000L
        val tracker = MuteTracker(clock = { now })
        tracker.onFetchFailed()
        now += 30_000
        assertNull(tracker.onFetchOk()) // under REPORT_AFTER: no notice
        // and the tracker is clean afterwards
        assertNull(tracker.onFetchOk())
    }

    @Test
    fun aSecondWindowCountsFromItsOwnStart() {
        var now = 10_000_000L
        val tracker = MuteTracker(clock = { now })
        tracker.onFetchFailed()
        now += 120_000
        assertNotNull(tracker.onFetchOk())
        // healthy again, then a NEW window
        now += 600_000
        tracker.onFetchFailed()
        now += 90_000
        val second = tracker.onFetchOk()
        assertNotNull(second)
        assertEquals(10_720_000L, second.fromMs)
    }
}
