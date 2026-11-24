package dev.lattenero.bot.daemon.notifications

import java.util.concurrent.ConcurrentHashMap

/**
 * 🔔 THE CROSS-SOURCE GATE (24/11): the SAME transition reaches the phone
 * from TWO independent observers — the live card's seal tap and the global
 * tasks-index notifier — and both spoke: two messages and two buzzes for
 * one turn end (measured live 24/11: 5 s apart, and 61 s apart when the
 * index updated before the card sealed). Whichever voice speaks FIRST wins;
 * a second voice folds only when it carries the SAME verdict, lands inside
 * [GAP_MS], and its turn did not begin AFTER the recorded voice — a
 * genuinely NEW turn (its own start stamp is newer) always speaks.
 *
 * THE VERDICT IS THE KEY: a `❌` is never folded into a `🏁` — when the two
 * observers disagree (the index saw an error the card sealed clean), the
 * correction must still ring.
 *
 * Atomic check-and-record in one [ConcurrentHashMap.compute] (two sources,
 * one process, no window between the read and the write). Time is injected
 * like every clock in the house; the map is the gate's only state and it is
 * bounded (stale verdicts die, then the oldest overflow goes).
 */
class TaskVoiceGate(
    private val gapMs: Long = GAP_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    /** One voice: the verdict it spoke and the clock reading of the speak. */
    private data class Voice(val terminal: TaskSignal.Terminal, val atMs: Long)

    /** taskId → the last voice it earned — the gate's only state. */
    private val last = ConcurrentHashMap<String, Voice>()

    /**
     * Atomic check-and-record: true = speak now (the clock moves to now).
     * [turnHintMs] is when the turn (or its row) was last known ALIVE — a
     * hint STRICTLY NEWER than the recorded voice proves another turn
     * started after that voice, so the gate never folds it.
     */
    fun trySpeak(
        taskId: String,
        terminal: TaskSignal.Terminal,
        turnHintMs: Long? = null,
    ): Boolean {
        val now = clockMs()
        var allow = false
        last.compute(taskId) { _, prev ->
            val folded = prev != null &&
                prev.terminal == terminal &&
                now - prev.atMs < gapMs &&
                (turnHintMs == null || turnHintMs <= prev.atMs)
            if (folded) {
                prev
            } else {
                allow = true
                Voice(terminal, now)
            }
        }
        if (last.size > LAST_CAP) prune(now)
        return allow
    }

    /** Stale verdicts die, then the oldest overflow goes — the map never balloons. */
    private fun prune(now: Long) {
        last.entries.removeIf { now - it.value.atMs > PRUNE_MS }
        val overflow = last.size - LAST_CAP
        if (overflow > 0) {
            last.entries
                .sortedBy { it.value.atMs }
                .take(overflow)
                .forEach { last.remove(it.key) }
        }
    }

    /** The map's size — tests' inspection window, never a caller's concern. */
    internal fun underlyingSize(): Int = last.size

    companion object {
        /**
         * The fold window: 2 min. The same transition's two voices land
         * seconds apart (measured 5 s and 61 s — the index can beat the
         * card); a NEW turn carries its own start stamp and speaks anyway.
         */
        const val GAP_MS = 120_000L

        /** The map's soft cap; a prune keeps the fresh verdicts only. */
        const val LAST_CAP = 256

        /** A verdict older than this can never be inside a fold window again. */
        const val PRUNE_MS = 10L * GAP_MS
    }
}
