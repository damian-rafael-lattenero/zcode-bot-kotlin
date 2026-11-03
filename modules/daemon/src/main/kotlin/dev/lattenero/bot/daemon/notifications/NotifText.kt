package dev.lattenero.bot.daemon.notifications

import dev.lattenero.bot.channel.LiveTurn
import dev.lattenero.bot.channel.SealFacts
import dev.lattenero.bot.contracts.PyText

/**
 * The notifications' TEXT — the owner's format law («mensajes bien
 * fuertes»): the moment's emoji + a BOLD first line, plain lines after.
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
        "🔔 <b>Subagente terminó</b>\n" +
            "«${PyText.esc(titleOf(done.title, done.sid))}»\n" +
            PyText.esc(PyText.preview(done.outcome, OUTCOME_PREVIEW_CP))

    /**
     * 🔔 MOMENT 2 — the owner's bound conversation's turn sealed:
     * «🏁 <título> — terminó (X, N herramientas)» + the answer's first
     * ~150 chars ONLY when the card did not speak the answer itself
     * ([SealFacts.answerSpoken] — measured 3/11: repeating the head after
     * a live card's full answer is exactly the duplication the owner
     * reported). A turn that produced NOTHING (answer blank AND zero
     * tools) answers null — silence is data, no decoration.
     */
    fun turnSealed(seal: SealFacts): String? {
        val raw = seal.answer
        if ((raw == null || raw.isBlank()) && seal.doneTools == 0) return null
        val elapsed = seal.startedAtMs
            ?.let { LiveTurn.fmtDur((seal.sealedAtMs - it).coerceAtLeast(0L)) }
            ?: "?"
        val tools = seal.doneTools
        val head = "🏁 <b>«${PyText.esc(titleOf(seal.title, seal.taskId))}» — terminó " +
            "($elapsed, $tools ${if (tools == 1) "herramienta" else "herramientas"})</b>"
        val answer = raw
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { !seal.answerSpoken } // the card already said it — the 🔔 never repeats (single-source law)
            ?.let { "\n" + PyText.esc(PyText.preview(it, ANSWER_PREVIEW_CP)) }
            .orEmpty()
        return head + answer
    }

    private fun titleOf(title: String, sid: String): String =
        title.takeIf { it.isNotBlank() } ?: sid.takeLast(SID_TAIL)
}
