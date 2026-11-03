package dev.lattenero.bot.daemon.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The transition machine's laws: the store's '' → value flip speaks once,
 * the ghost law silences stale rows, the memory moves only on change and
 * dedupes across restarts.
 */
class SubagentWatchTest {

    private val now = 2_000_000_000L
    private val fresh = SubagentWatch.FRESH_WINDOW_MS - 5_000L

    private fun ended(sid: String, outcome: String, ageMs: Long = fresh, started: Long = now - 600_000L) =
        ChildSession(sid = sid, title = "misión $sid", outcome = outcome, startedMs = started, updatedMs = now - ageMs)

    private fun running(sid: String) = ChildSession(sid, "misión $sid", "", now - 600_000L, now - 1_000L)

    @Test
    fun aRunningRowIsRecordedAndSpeaksNothing() {
        val fold = SubagentWatch.pass(emptyMap(), listOf(running("a")), now)
        assertEquals("", fold.seen["a"]!!.outcome)
        assertTrue(fold.announce.isEmpty())
    }

    @Test
    fun theWitnessedFlipSpeaksOnce() {
        val first = SubagentWatch.pass(emptyMap(), listOf(running("a")), now)
        val second = SubagentWatch.pass(first.seen, listOf(ended("a", "completed")), now + 1_000L)
        assertEquals(1, second.announce.size)
        assertEquals("completed", second.announce[0].outcome)
        assertEquals("misión a", second.announce[0].title)
        // and the SAME row again is silence — one notification per transition
        val third = SubagentWatch.pass(second.seen, listOf(ended("a", "completed")), now + 2_000L)
        assertTrue(third.announce.isEmpty())
    }

    @Test
    fun aRestartWithThePersistedMemorySpeaksNothing() {
        val first = SubagentWatch.pass(emptyMap(), listOf(ended("a", "completed")), now)
        assertEquals(1, first.announce.size, "a never-seen fresh end speaks (a short child between passes)")
        // the daemon restarts with the state file's memory loaded back
        val restarted = SubagentWatch.pass(first.seen, listOf(ended("a", "completed")), now + 1_000L)
        assertTrue(restarted.announce.isEmpty(), "the dedupe crosses restarts")
    }

    @Test
    fun aNeverSeenStaleEndIsTheGhostItAlreadyWas() {
        val fold = SubagentWatch.pass(emptyMap(), listOf(ended("old", "completed", ageMs = SubagentWatch.FRESH_WINDOW_MS * 3)), now)
        assertTrue(fold.announce.isEmpty(), "a stale row with an old updated_at must never notify")
        assertEquals("completed", fold.seen["old"]!!.outcome, "recorded silently")
    }

    @Test
    fun aWitnessedEndBeyondTheFreshnessWindowIsSilenceToo() {
        // the state file said running; the daemon was down for an hour; the
        // row ended 50 minutes ago — the ghost law silences the stale end
        val seen = mapOf("a" to SeenOutcome("", now - 3_600_000L))
        val fold = SubagentWatch.pass(seen, listOf(ended("a", "interrupted", ageMs = 3_000_000L)), now)
        assertTrue(fold.announce.isEmpty())
    }

    @Test
    fun aValueToValueFlipRecordsButNeverSpeaks() {
        val seen = mapOf("a" to SeenOutcome("completed", now - 60_000L))
        val fold = SubagentWatch.pass(seen, listOf(ended("a", "interrupted")), now)
        assertTrue(fold.announce.isEmpty())
        assertEquals("interrupted", fold.seen["a"]!!.outcome)
    }

    @Test
    fun aFutureStampIsNeverFresh() {
        val fold = SubagentWatch.pass(emptyMap(), listOf(ended("a", "completed", ageMs = -60_000L)), now)
        assertTrue(fold.announce.isEmpty(), "clock skew is silence, not a notification")
    }

    @Test
    fun anUnchangedObservationWritesNothing() {
        val first = SubagentWatch.pass(emptyMap(), listOf(running("a")), now)
        val second = SubagentWatch.pass(first.seen, listOf(running("a")), now + 30_000L)
        assertEquals(first.seen, second.seen, "the memory only moves on change (the mtime stays a clock)")
    }

    @Test
    fun aBatchOfEndsAnnouncesEachOnce() {
        val first = SubagentWatch.pass(
            emptyMap(),
            listOf(running("a"), running("b"), running("c")),
            now,
        )
        val second = SubagentWatch.pass(
            first.seen,
            listOf(ended("a", "completed"), ended("b", "completed"), ended("c", "interrupted")),
            now + 1_000L,
        )
        assertEquals(3, second.announce.size)
    }

    @Test
    fun theMemoryPrunesOldEntriesAndCapsTheRest() {
        val old = (1L..600L).associate { "old-$it" to SeenOutcome("completed", now - SubagentWatch.PRUNE_AFTER_MS - it) }
        val recent = mapOf("fresh" to SeenOutcome("completed", now - 1_000L))
        val pruned = SubagentWatch.prune(old + recent, now)
        assertEquals(1, pruned.size, "a day-old entry dies")
        assertEquals("fresh", pruned.keys.first())
        val huge = (1L..700L).associate { "s-$it" to SeenOutcome("completed", now - it) }
        assertEquals(SubagentWatch.SEEN_CAP, SubagentWatch.prune(huge, now).size, "the cap keeps the newest")
    }
}
