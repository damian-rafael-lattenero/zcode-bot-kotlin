package dev.lattenero.bot.daemon.notifications

import dev.lattenero.bot.channel.LiveTurn
import dev.lattenero.bot.channel.SealFacts
import dev.lattenero.bot.contracts.PyText

/**
 * The notifications' TEXT — the owner's format law («messages good and
 * loud» [translated]): the moment's emoji + a BOLD first line, plain lines after.
 * The composers are PURE and honest: HTML escapes every piece that came
 * from a store or a snapshot, the previews are the house truncation
 * (PyText.preview — whitespace collapsed, the cut named with an ellipsis),
 * and the facts speak for themselves — nothing invented.
 */
object NotifText {

    /** The watcher name the decisions ride (the shadow/dry-run paper trail). */
    const val WATCHER = "notifications"

    /** The subagent line's outcome budget — the FIRST ~120 chars, honest truncation. */
    const val OUTCOME_PREVIEW_CP = 120

    /** The seal's answer budget — the first ~150 chars, honest truncation. */
    const val ANSWER_PREVIEW_CP = 150

    /** A blank title falls back to the sid's tail — the house fallback, nothing invented. */
    private const val SID_TAIL = 8

    /**
     * 🔔 MOMENT 1 — the subagent's end: the task title + the outcome's
     * first ~120 chars (the store's `last_outcome` word — completed,
     * interrupted, or a longer verdict; the preview is honest either way).
     */
    fun subagentDone(done: SubagentDone): String =
        "🔔 <b>Subagent finished</b>\n" +
            "«${PyText.esc(titleOf(done.title, done.sid))}»\n" +
            PyText.esc(PyText.preview(done.outcome, OUTCOME_PREVIEW_CP))

    /**
     * 🔔 MOMENT 2 — the owner's bound conversation's turn sealed:
     * «🏁 <title> — finished (X, N tools)» + the answer's first
     * ~150 chars ONLY when the card did not speak the answer itself
     * ([SealFacts.answerSpoken] — measured 3/11: repeating the head after
     * a live card's full answer is exactly the duplication the owner
     * reported). A turn that produced NOTHING (answer blank AND zero
     * tools) answers null — silence is data, no decoration.
     */
    fun turnSealed(seal: SealFacts): String? {
        val raw = seal.answer
        if (isSilent(seal, raw)) return null
        val elapsed = seal.startedAtMs
            ?.let { LiveTurn.fmtDur((seal.sealedAtMs - it).coerceAtLeast(0L)) }
            ?: "?"
        val head = if (seal.failed) {
            failedHead(seal, elapsed)
        } else {
            "🏁 <b>«${PyText.esc(titleOf(seal.title, seal.taskId))}» — finished " +
                "($elapsed, ${toolCount(seal.doneTools)})</b>"
        }
        val answer = raw
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { !seal.answerSpoken } // the card already said it — the 🔔 never repeats (single-source law)
            ?.let { "\n" + PyText.esc(PyText.preview(it, ANSWER_PREVIEW_CP)) }
            .orEmpty()
        return head + answer
    }

    /**
     * THE SILENCE LAW: a CLEAN turn that produced nothing (no answer, zero
     * tools) says nothing — silence is data, no decoration. A FAILED turn
     * is the exception (24/11): an error must always ring, even empty.
     */
    private fun isSilent(seal: SealFacts, answer: String?): Boolean =
        !seal.failed && seal.doneTools == 0 && (answer == null || answer.isBlank())

    /**
     * The FAILED head (24/11): the verdict, the same duration/tools budget
     * and the fork's own error line — escaped and truncated like every
     * other store-sourced piece. Before this, a failed turn read as a clean
     * finish and an empty failed turn was silent.
     */
    private fun failedHead(seal: SealFacts, elapsed: String): String {
        val head = "❌ <b>«${PyText.esc(titleOf(seal.title, seal.taskId))}» — failed " +
            "($elapsed, ${toolCount(seal.doneTools)})</b>"
        val reason = seal.stopReason.trim()
        return if (reason.isBlank()) head else head + "\n" + PyText.esc(PyText.preview(reason, ANSWER_PREVIEW_CP))
    }

    private fun toolCount(tools: Int): String =
        "$tools ${if (tools == 1) "tool" else "tools"}"

    /**
     * 🔔 MOMENT 1b — the subagent's START (owner, 16/11): a child began its
     * turn. The title and its own start stamp; nothing invented.
     */
    fun subagentStarted(started: SubagentStarted): String =
        "🚀 <b>Subagent started</b>\n" +
            "«${PyText.esc(titleOf(started.title, started.sid))}»"

    /**
     * 🏁 M4 — ANY task's end, subscribed or not (owner, 16/11): the task's
     * title, the workspace it belongs to, and the fork's own verdict word.
     */
    fun taskFinished(task: TaskSignal): String =
        "🏁 <b>«${PyText.esc(titleOf(task.title, task.taskId))}» — finished</b>\n" +
            PyText.esc(workspaceLabel(task.workspacePath)) + outcomeLine(task.lastOutcome)

    /** ❌ M4 — ANY task's failure, subscribed or not. */
    fun taskFailed(task: TaskSignal): String =
        "❌ <b>«${PyText.esc(titleOf(task.title, task.taskId))}» — failed</b>\n" +
            PyText.esc(workspaceLabel(task.workspacePath))

    /** The workspace's own name (its last path segment) — the phone's context line. */
    private fun workspaceLabel(workspacePath: String): String =
        workspacePath.substringAfterLast('/').ifBlank { workspacePath }

    /** The CLI's verdict word, when it is more than the plain completed. */
    private fun outcomeLine(lastOutcome: String): String =
        lastOutcome.takeIf { it.isNotBlank() && it != "completed" }
            ?.let { " · " + PyText.esc(it) }
            .orEmpty()

    private fun titleOf(title: String, sid: String): String =
        title.takeIf { it.isNotBlank() } ?: sid.takeLast(SID_TAIL)
}
