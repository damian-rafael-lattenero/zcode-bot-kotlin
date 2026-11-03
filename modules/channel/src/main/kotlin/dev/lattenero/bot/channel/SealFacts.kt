package dev.lattenero.bot.channel

/**
 * The seal's FACTS as one value — what [LivePublisher]'s seal tap
 * receives (the daemon's key-moment notifications ride the SAME seal the
 * card spoke, without re-querying anything). Every field is the seal's
 * own computed truth: the title the card wore, the turn's real start,
 * the tool counters, the FINAL answer exactly as the snapshot yielded it
 * (raw — the receiver escapes and truncates, the seal invents nothing).
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
)
