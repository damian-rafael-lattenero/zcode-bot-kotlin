package dev.lattenero.bot.channel

/**
 * The seal's FACTS as one value — what [LivePublisher]'s seal tap
 * receives (the daemon's key-moment notifications ride the SAME seal the
 * card spoke, without re-querying anything). Every field is the seal's
 * own computed truth: the title the card wore, the turn's real start,
 * the tool counters, the FINAL answer exactly as the snapshot yielded it
 * (raw — the receiver escapes and truncates, the seal invents nothing).
 *
 * [answerSpoken] is the single-source law (measured 3/11: the 🏁 repeated
 * the answer's head the card had just spoken): TRUE when the seal already
 * delivered the answer to the user (a live, non-parked card speaks the
 * full text) — the notification must then NOT repeat any part of it.
 * FALSE when the card stayed silent (a parked card's frozen render) and
 * the notification IS the answer's only mouth.
 */
data class SealFacts(
    val taskId: String,
    val title: String,
    val stopReason: String,
    val startedAtMs: Long?,
    val sealedAtMs: Long,
    val doneTools: Int,
    val failedTools: Int,
    val answer: String?,
    val answerSpoken: Boolean = false,
)
