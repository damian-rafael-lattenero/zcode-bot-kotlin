package dev.lattenero.bot.daemon.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The global fold's laws: a real transition speaks (finished and failed),
 * the boot baseline records silently, a first-sighted end speaks only when
 * it is FRESH, and the memory never repeats a notice.
 */
class GlobalNoticesTest {

    private val now = 2_000_000_000L

    private fun signal(
        sid: String,
        status: String = "running",
        outcome: String = "",
        updatedMs: Long = now - 1_000L,
        ws: String = "/Users/damian/Projects/app",
    ) = TaskSignal(
        taskId = sid,
        title = "tarea $sid",
        workspacePath = ws,
        status = status,
        lastOutcome = outcome,
        updatedMs = updatedMs,
    )

    @Test
    fun theBaselineRecordsAndSpeaksNothing() {
        val memory = GlobalNotices.baseline(
            listOf(signal("a", "completed", "completed"), signal("b")),
        )
        assertEquals(2, memory.size)
    }

    @Test
    fun aRunningToFinishedTransitionSpeaks() {
        val prev = listOf(signal("a"))
        val fold = GlobalNotices.pass(prev, listOf(signal("a", "completed", "completed")), now)
        assertEquals(1, fold.announce.size)
        assertTrue(fold.announce[0] is GlobalNotice.Finished)
    }

    @Test
    fun aRunningToFailedTransitionSpeaks() {
        val prev = listOf(signal("a"))
        val fold = GlobalNotices.pass(prev, listOf(signal("a", "error", "error")), now)
        assertEquals(1, fold.announce.size)
        assertTrue(fold.announce[0] is GlobalNotice.Failed)
    }

    @Test
    fun anInterruptedOutcomeIsAFinishedTask() {
        val prev = listOf(signal("a"))
        val fold = GlobalNotices.pass(prev, listOf(signal("a", "", "interrupted")), now)
        assertEquals(1, fold.announce.size)
        assertTrue(fold.announce[0] is GlobalNotice.Finished)
    }

    @Test
    fun aFreshFirstSightOfAnEndSpeaks() {
        // the task was born and finished between two reads: the memory knew
        // OTHER tasks (so this is a pass, not the baseline) but not this one
        val prev = listOf(signal("other"))
        val fold = GlobalNotices.pass(prev, listOf(signal("new", "completed", "completed")), now)
        assertEquals(1, fold.announce.size, "a fresh end first seen is news")
    }

    @Test
    fun anOldFirstSightIsSilence() {
        val prev = listOf(signal("other"))
        val stale = signal("old", "completed", "completed", updatedMs = now - GlobalNotices.FRESH_WINDOW_MS * 3)
        val fold = GlobalNotices.pass(prev, listOf(stale), now)
        assertTrue(fold.announce.isEmpty(), "a stale row is history, not news")
    }

    @Test
    fun aTerminalRowNeverSpeaksTwice() {
        val once = GlobalNotices.pass(listOf(signal("a")), listOf(signal("a", "completed", "completed")), now)
        assertEquals(1, once.announce.size)
        val twice = GlobalNotices.pass(once.seen, listOf(signal("a", "completed", "completed")), now + 5_000L)
        assertTrue(twice.announce.isEmpty(), "the memory recorded the terminal row")
    }

    @Test
    fun aFinishedToFinishedRewriteIsSilence() {
        val prev = listOf(signal("a", "completed", "completed"))
        val fold = GlobalNotices.pass(prev, listOf(signal("a", "completed", "interrupted")), now)
        assertTrue(fold.announce.isEmpty(), "value → value never speaks")
    }

    @Test
    fun anUnknownStatusIsNotTerminal() {
        val fold = GlobalNotices.pass(listOf(signal("a")), listOf(signal("a", "", "")), now)
        assertTrue(fold.announce.isEmpty())
    }

    @Test
    fun theNoticeCarriesTheAliveStampsProof() {
        val prev = listOf(signal("a", updatedMs = now - 9_000L))
        val fold = GlobalNotices.pass(prev, listOf(signal("a", "completed", "completed")), now)
        val notice = fold.announce[0] as GlobalNotice.Finished
        assertEquals(now - 9_000L, notice.aliveStampMs, "the alive row's stamp proves a new turn after the other voice")
    }

    @Test
    fun theMemoryIsBoundedAndPrunesOldEntries() {
        val prev = (1..600).map { signal("s-$it", updatedMs = now - it) }
        val fold = GlobalNotices.pass(prev, emptyList(), now)
        assertEquals(GlobalNotices.SEEN_CAP, fold.seen.size, "the cap keeps the newest")
        val ancient = listOf(signal("old", updatedMs = now - GlobalNotices.PRUNE_AFTER_MS - 1))
        assertTrue(GlobalNotices.pass(ancient, emptyList(), now).seen.isEmpty(), "a day-old entry dies")
    }
}
