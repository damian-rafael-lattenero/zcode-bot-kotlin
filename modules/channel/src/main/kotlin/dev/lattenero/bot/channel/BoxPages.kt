package dev.lattenero.bot.channel

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject

private val logger = KotlinLogging.logger {}

/**
 * The collapsed tool outputs' tap world (the owner, 3/11): a shell's
 * output rides the chat COMPRESSED behind one button; the tap opens it
 * IN PLACE in numbered pages, page buttons below, another tap flips the
 * page — never a wall of text. Mirrors [CotBlocks]: ids are monotonic
 * (`bp:<id>:<page>` — old buttons keep working across turns), the store
 * is LRU-capped, and an unwired/missed tap answers honestly.
 *
 * Concurrency: NOT self-serializing — the owner ([LivePublisher]) runs
 * taps and ticks under one mutex; this class stays a plain holder with
 * one effect.
 */
interface BoxTaps {
    /** Opens (or flips to) a page; a non-null String is the toast (a miss). */
    suspend fun open(registryId: Int, page: Int): String?

    /** Folds the message back to its one-line header. */
    suspend fun fold(registryId: Int): String?
}

class BoxPages(
    /** The in-place edit (the publisher's telegram leg, cooldown-gated). */
    private val edit: suspend (messageId: Long, text: String, markup: JsonObject?) -> Boolean,
) : BoxTaps {

    private class Entry(
        val messageId: Long,
        val header: String,
        val pages: List<String>,
    ) {
        var index: Int = -1
    }

    private val entries = object : LinkedHashMap<Int, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Entry>): Boolean =
            size > MAX_ENTRIES
    }

    private var seq = 0

    /**
     * Speaks ONE collapsed message (the header + its 📜 button) and
     * registers the born message under a fresh id. A send that never flew
     * registers NOTHING: there is no message to edit.
     */
    suspend fun speakCollapsed(
        pages: ToolOutputPages.Pages,
        send: suspend (markup: JsonObject?) -> Long?,
    ) {
        val id = ++seq
        val mid = send(Keyboards.boxOpen(id))
        if (mid != null) entries[id] = Entry(messageId = mid, header = pages.header, pages = pages.pages)
    }

    override suspend fun open(registryId: Int, page: Int): String? {
        val entry = entries[registryId] ?: return MISS
        val index = page.coerceIn(0, entry.pages.size - 1)
        val text = ToolOutputPages.renderPage(entry.header, entry.pages, index)
        val ok = edit(entry.messageId, text, Keyboards.boxPages(registryId, index, entry.pages.size))
        if (ok) entry.index = index
        logger.info {
            val verdict = if (ok) "open" else "edit BLOCKED (throttle)"
            "box tap: id=$registryId page=${index + 1}/${entry.pages.size} -> $verdict"
        }
        return null
    }

    override suspend fun fold(registryId: Int): String? {
        val entry = entries[registryId] ?: return MISS
        val ok = edit(entry.messageId, entry.header, Keyboards.boxOpen(registryId))
        if (ok) entry.index = -1
        logger.info { "box tap: id=$registryId -> ${if (ok) "folded" else "fold BLOCKED (throttle)"}" }
        return null
    }

    companion object {
        /** The LRU cap — the last heavy turns' worth of outputs. */
        const val MAX_ENTRIES = 120

        /** A tap on an output the registry no longer knows. */
        const val MISS = "🤷 Esa salida ya no está en el registro — el botón quedó de otro turno."
    }
}
