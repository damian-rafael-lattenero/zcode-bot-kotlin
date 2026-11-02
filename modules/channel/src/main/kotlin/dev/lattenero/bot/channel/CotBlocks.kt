package dev.lattenero.bot.channel

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject

private val logger = KotlinLogging.logger {}

/**
 * THE CoT REGISTRY — the collapsed blocks' world (the owner, 2/11: «yo
 * decido si los leo o no una vez que se llenó el botón de los 3k»). Each
 * collapsed message the feed speaks registers here (id → message id +
 * block number + the FULL text); the tap toggles it IN PLACE on that
 * same message: expand renders the block whole, fold returns the
 * one-line header. The ids are monotonic (`cb:<id>` — no collisions
 * across turns, the old buttons keep working after the seal), the store
 * is LRU-capped (a daemon running for weeks must not hoard every turn's
 * blocks). Concurrency: NOT self-serializing — the owner ([LivePublisher])
 * runs taps and ticks under one mutex; this class stays a plain value
 * holder with one effect.
 */
class CotBlocks(
    /** The in-place edit (the publisher's telegram leg, cooldown-gated). */
    private val edit: suspend (messageId: Long, text: String, markup: JsonObject?) -> Boolean,
) {

    private class Entry(
        val messageId: Long,
        /** 1-based block number; null = the unsealed tail's flush. */
        val number: Int?,
        val text: String,
        val cp: Int,
    ) {
        var expanded: Boolean = false
    }

    private val entries = object : LinkedHashMap<Int, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Entry>): Boolean =
            size > MAX_ENTRIES
    }

    private var seq = 0

    /**
     * Speaks ONE collapsed message through the render lane (the injected
     * send — the collapsed header + this block's button) and registers
     * the born message under a fresh monotonic id. A send that never flew
     * (parked/throttled) registers NOTHING: there is no message to edit,
     * the tap on it would be a honest miss.
     */
    suspend fun speakCollapsed(
        number: Int?,
        text: String,
        cp: Int,
        send: suspend (markup: JsonObject?) -> Long?,
    ) {
        val id = ++seq
        val mid = send(Keyboards.cotToggle(id, expanded = false))
        if (mid != null) entries[id] = Entry(messageId = mid, number = number, text = text, cp = cp)
    }

    /**
     * THE TAP: expand ↔ fold IN PLACE (the owner's refinement, verbatim:
     * «el lugar para que aparezca el texto ES EN EL BOTÓN del CoT»).
     * Null = the edit is the feedback (no new message — the chat's order
     * untouched). A throttled tap flips NOTHING: the message keeps its
     * button and the owner retries.
     */
    suspend fun toggle(registryId: Int): String? {
        val entry = entries[registryId] ?: return MISS
        val expanded = !entry.expanded
        val text = if (expanded) {
            TranscriptFeed.expandedBlock(entry.number, entry.cp, entry.text)
        } else {
            TranscriptFeed.collapsedHeader(entry.number, entry.cp)
        }
        val ok = edit(entry.messageId, text, Keyboards.cotToggle(registryId, expanded))
        if (ok) entry.expanded = expanded
        logger.info {
            "cot tap: id=$registryId block=${entry.number ?: "tail"} -> " +
                if (ok) if (expanded) "expanded" else "folded" else "edit BLOCKED (throttle)"
        }
        return null
    }

    private companion object {
        /** The LRU cap — the last ~2 heavy turns' worth of blocks. */
        const val MAX_ENTRIES = 240

        /** A tap on a block the registry no longer knows (an old turn, a bot restart). */
        const val MISS = "🤷 Ese CoT ya no está en el registro — el botón quedó de otro turno."
    }
}
