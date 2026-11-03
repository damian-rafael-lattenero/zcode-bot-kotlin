package dev.lattenero.bot.daemon.notifications

/**
 * 🔔 MOMENT 1 — THE SUBAGENT'S END, as a pure machine. The store's own
 * truth (`~/.zcode/cli/db/db.sqlite`'s `session` rows, `parent_id IS NOT
 * NULL`) folds through the daemon's memory ([SeenOutcome]): a child's
 * `last_outcome` flipping from '' (the store's own convention for STILL
 * RUNNING — measured live 30/10) to a value is the ONE transition that
 * speaks.
 *
 * Laws (each one a test):
 * - THE GHOST LAW: only FRESH rows speak — a row whose `time_updated` is
 *   older than [FRESH_WINDOW_MS] must never notify (a restart, a missed
 *   cycle or a first boot reads old ends without a word; the same 15-min
 *   freshness the keyboard's ghost marks live by).
 * - ONE NOTIFICATION PER TRANSITION: the fold's memory records the
 *   outcome it announced (or declined), so a restart re-reading the same
 *   row announces nothing; only a new '' → value flip speaks again.
 * - THE MEMORY ONLY MOVES ON CHANGE: an unchanged observation writes
 *   nothing (the state file's mtime stays a clock, the hb invariant
 *   family), a value → value flip records but never speaks.
 */

/** One child-session row the store read produced — the cli db's own words. */
data class ChildSession(
    val sid: String,
    val title: String,
    /** The store's `last_outcome`: '' = still running, a value = ended. */
    val outcome: String,
    val startedMs: Long,
    val updatedMs: Long,
)

/** One finished child to announce — the notification's facts. */
data class SubagentDone(
    val sid: String,
    val title: String,
    val outcome: String,
    val startedMs: Long,
    val endedMs: Long,
)

/** What the fold last recorded for a sid — the state file's row. */
data class SeenOutcome(val outcome: String, val atMs: Long)

/** One pass's fold: the next memory + the transitions to announce. */
data class SubagentFold(val seen: Map<String, SeenOutcome>, val announce: List<SubagentDone>)

object SubagentWatch {

    private const val MILLIS_PER_SECOND_L = 1000L

    /**
     * The freshness window — the keyboard ghost law's own bound
     * (Keyboards.SUB_STALE_MIN): a child that ended within the last 15
     * minutes is a fresh end; anything older is the ghost it already was.
     */
    const val FRESH_WINDOW_MS = 15L * 60L * MILLIS_PER_SECOND_L

    /** The memory's age cap — a state file entry older than a day is dead weight. */
    const val PRUNE_AFTER_MS = 24L * 60L * 60L * MILLIS_PER_SECOND_L

    /** The memory's size cap — batches of children never balloon the state file. */
    const val SEEN_CAP = 512

    /**
     * One pass: the memory + this cycle's rows → the next memory + the
     * transitions to announce. PURE — `nowMs` is injected, the store is
     * the caller's read.
     */
    fun pass(seen: Map<String, SeenOutcome>, rows: List<ChildSession>, nowMs: Long): SubagentFold {
        val next = HashMap<String, SeenOutcome>(seen)
        val announce = ArrayList<SubagentDone>()
        for (row in rows) {
            val prev = seen[row.sid]
            val outcome = row.outcome
            if (outcome.isBlank()) {
                // still running: record the observation only when it fills a
                // gap or undoes a recorded end (an honest mirror of the store)
                if (prev == null || prev.outcome.isNotBlank()) {
                    next[row.sid] = SeenOutcome("", nowMs)
                }
                continue
            }
            if (prev == null || prev.outcome != outcome) {
                next[row.sid] = SeenOutcome(outcome, nowMs)
            }
            // THE TRANSITION: the store's own '' → value flip, witnessed by
            // this memory (absent counts — a short child can run and end
            // between two passes), FRESH by the row's own time_updated.
            val fresh = nowMs - row.updatedMs in 0 until FRESH_WINDOW_MS
            if ((prev == null || prev.outcome.isBlank()) && fresh) {
                announce += SubagentDone(row.sid, row.title, outcome, row.startedMs, row.updatedMs)
            }
        }
        return SubagentFold(prune(next, nowMs), announce)
    }

    /** The memory stays small: a day-old entry dies, then the cap keeps the newest. */
    internal fun prune(seen: Map<String, SeenOutcome>, nowMs: Long): Map<String, SeenOutcome> {
        val alive = seen.filterValues { nowMs - it.atMs in 0 until PRUNE_AFTER_MS }
        if (alive.size <= SEEN_CAP) return alive
        return alive.entries
            .sortedByDescending { it.value.atMs }
            .take(SEEN_CAP)
            .associate { it.key to it.value }
    }
}
