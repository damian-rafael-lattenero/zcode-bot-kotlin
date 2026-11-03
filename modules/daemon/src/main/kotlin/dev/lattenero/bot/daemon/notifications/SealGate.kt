package dev.lattenero.bot.daemon.notifications

import java.util.concurrent.ConcurrentHashMap

/**
 * 🔔 MOMENT 2's fold-into-silence: a task's seal notification less than
 * [SEAL_GAP_MS] after this feature's previous one FOR THE SAME TASK folds
 * into silence — the live view's card already showed it (the owner's law:
 * the notification is the loud line, not an echo of the card). One
 * announce moves the clock; a fold does not (a second seal 61 s later
 * still speaks).
 *
 * Pure in time: the clock is injected, the map is the gate's only state
 * (an in-memory gate — the SEALS themselves dedupe across restarts
 * through the live view's own turn lifecycle; the store-file memory is
 * the subagent watcher's law, not this one's).
 */
class SealGate(
    private val minGapMs: Long = SEAL_GAP_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    /** taskId → the last announce's clock reading — the gate's only state. */
    private val last = ConcurrentHashMap<String, Long>()

    /** Atomic check-and-record: true = announce now (the clock moves to now). */
    fun tryAnnounce(taskId: String): Boolean {
        val now = clockMs()
        var allow = false
        last.compute(taskId) { _, prev ->
            if (prev == null || now - prev >= minGapMs) {
                allow = true
                now
            } else {
                prev
            }
        }
        if (last.size > LAST_CAP) prune(now)
        return allow
    }

    /** Stale verdicts die, then the oldest overflow goes — the map never balloons. */
    private fun prune(now: Long) {
        last.entries.removeIf { now - it.value > PRUNE_MS }
        val overflow = last.size - LAST_CAP
        if (overflow > 0) {
            last.entries
                .sortedBy { it.value }
                .take(overflow)
                .forEach { last.remove(it.key) }
        }
    }

    /** The map's size — tests' inspection window, never a caller's concern. */
    internal fun underlyingSize(): Int = last.size

    companion object {
        /** The fold window — the owner's 60 s. */
        const val SEAL_GAP_MS = 60_000L

        /** The map's soft cap; a prune keeps the fresh verdicts only. */
        const val LAST_CAP = 256

        /** A verdict older than this can never be inside a fold window again. */
        const val PRUNE_MS = 10L * SEAL_GAP_MS
    }
}
