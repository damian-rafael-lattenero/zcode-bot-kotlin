package dev.lattenero.bot.daemon.notifications

/**
 * M4'S GLOBAL VIEW — one task's lifecycle as the app's tasks-index reports
 * it. The notifier's input row: WHICH task, its title and workspace, the
 * fork's own status facts, and the stamp that says how fresh it is.
 */
data class TaskSignal(
    val taskId: String,
    val title: String,
    val workspacePath: String,
    /** The index's `task_status`: running | completed | error ('' = unknown). */
    val status: String,
    /** The CLI's `last_outcome`: completed | interrupted | error ('' = still running). */
    val lastOutcome: String,
    val updatedMs: Long,
) {
    /** The terminal verdict the owner must hear, null while the task is alive. */
    val terminal: Terminal?
        get() = when {
            status == "error" || lastOutcome == "error" -> Terminal.FAILED
            status == "completed" || lastOutcome.isNotBlank() -> Terminal.FINISHED
            else -> null
        }

    enum class Terminal { FINISHED, FAILED }
}

/** What the owner must hear about ONE task, decided by a pure transition. */
sealed interface GlobalNotice {
    /** The row's task — both arms speak about the same identity. */
    val task: TaskSignal

    /**
     * The last stamp the task's row carried while it was still ALIVE (the
     * fold's previous read) — the cross-source gate's proof that a NEW turn
     * began after the other voice spoke ([TaskVoiceGate]).
     */
    val aliveStampMs: Long?

    /** The task reached its end (completed or interrupted). */
    data class Finished(
        override val task: TaskSignal,
        override val aliveStampMs: Long? = null,
    ) : GlobalNotice

    /** The task died with an error. */
    data class Failed(
        override val task: TaskSignal,
        override val aliveStampMs: Long? = null,
    ) : GlobalNotice
}

/** One pass's answer: the next memory + the notices this pass earned. */
data class GlobalFold(val seen: List<TaskSignal>, val announce: List<GlobalNotice>)

/**
 * THE GLOBAL NOTIFIER'S PURE CORE (owner, 16/11: the task states must reach
 * his phone GLOBALLY — when ANY task finishes, fails, or blocks on a
 * question, he hears it whether or not he is subscribed to that task). The
 * bot polls the tasks-index (the app's own store) and this fold decides —
 * with no clock, no store and no socket of its own — what deserves the
 * owner's phone.
 *
 * Laws (each one a test):
 * - TRANSITION ONLY: a task speaks when the memory knew it ALIVE and this
 *   read finds it terminal; a value → value read is silence.
 * - FRESHNESS: a task first seen terminal speaks only when its own stamp is
 *   inside [FRESH_WINDOW_MS] — the phone being off is not a backlog replay.
 * - ONE NOTICE PER TRANSITION: the memory records the terminal row, so a
 *   restart re-reading the same store is silent.
 * - SILENT BASELINE: [baseline] records everything and speaks nothing (the
 *   first pass of a boot must not announce yesterday's ends).
 * - BOUNDED MEMORY: a day-old entry dies, then the cap keeps the newest.
 */
object GlobalNotices {

    /** How recent a first-seen terminal must be to speak (the subagent watcher's own window). */
    const val FRESH_WINDOW_MS = 15L * 60L * 1000L

    /** The memory's age cap — a day-old entry is dead weight. */
    const val PRUNE_AFTER_MS = 24L * 60L * 60L * 1000L

    /** The memory's size cap — batches of tasks never balloon the state file. */
    const val SEEN_CAP = 512

    /** The boot pass: record every row, speak NOTHING. */
    fun baseline(rows: List<TaskSignal>): List<TaskSignal> = rows
        .sortedByDescending { it.updatedMs }
        .take(SEEN_CAP)

    /** One pass: the previous memory + this read → the next memory + the notices. */
    fun pass(prev: List<TaskSignal>, rows: List<TaskSignal>, nowMs: Long): GlobalFold {
        val memory = HashMap<String, TaskSignal>(prev.associateBy { it.taskId })
        val announce = ArrayList<GlobalNotice>()
        for (row in rows) {
            val previous = memory[row.taskId]
            val terminal = row.terminal
            if (terminal != null && previous?.terminal == null) {
                val fresh = nowMs - row.updatedMs in 0 until FRESH_WINDOW_MS
                // the memory knew it alive (a real transition) OR it is a
                // fresh end first seen (a task born and finished between reads)
                if (previous != null || fresh) {
                    // the ALIVE row's last stamp rides the notice: the
                    // cross-source gate reads it as «this turn began after
                    // the voice that already spoke» (the new-turn proof)
                    announce += when (terminal) {
                        TaskSignal.Terminal.FINISHED -> GlobalNotice.Finished(row, previous?.updatedMs)
                        TaskSignal.Terminal.FAILED -> GlobalNotice.Failed(row, previous?.updatedMs)
                    }
                }
            }
            if (previous != row) memory[row.taskId] = row
        }
        return GlobalFold(prune(memory.values, nowMs), announce)
    }

    /** The memory stays small: a day-old entry dies, then the cap keeps the newest. */
    internal fun prune(seen: Collection<TaskSignal>, nowMs: Long): List<TaskSignal> {
        val alive = seen.filter { nowMs - it.updatedMs in 0 until PRUNE_AFTER_MS }
        return if (alive.size <= SEEN_CAP) {
            alive
        } else {
            alive.sortedByDescending { it.updatedMs }.take(SEEN_CAP)
        }
    }
}
