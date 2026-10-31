package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText

/**
 * The TRANSCRIPT FEED (the owner's 30/10 order, his words are the spec):
 * «quiero VER los archivos, leer los commands de bash, leer los cots uno
 * debajo del otro en mensajes separados» — the turn no longer lives only
 * inside one redrawn card; each SEALED CoT block and each tool CLOSE
 * becomes its own Telegram message, real content, in order, while the
 * status card is ALWAYS redrawn as the LAST message of everything.
 *
 * PURE: the feed is a diff between two consecutive turn snapshots —
 * nothing is emitted from a side queue, nothing is re-emitted after a
 * resync (a block once spoken is in `prev`, the diff never sees it
 * again), and a turn switch starts clean (the previous turn's history
 * stays sealed in the chat, never replayed). The publisher holds the
 * `prev` pointer and drains per tick; the level ([FeedVerbosity]) gates:
 * SUMMARY speaks nothing, NORMAL speaks sealed blocks + tool closes,
 * VERBOSE adds the tool OPEN one-liners.
 */
object TranscriptFeed {

    /**
     * The messages born between `prev` and `curr`, oldest first — each a
     * complete send under the message law (a long block chunks like the
     * close shower always did). `toolBox` is the injected per-tool
     * renderer (the daemon passes the SPECTATOR's ToolBox — the same
     * renderer the final transcript uses); a blank render (or an
     * input-less lane) falls back to the honest one-liner, never to
     * silence.
     */
    // one guard per early exit (the silent level, the turn switch) — the diff's own shape
    @Suppress("ReturnCount")
    fun messages(
        prev: TurnLive?,
        curr: TurnLive,
        level: FeedVerbosity,
        toolBox: (ToolLive) -> String,
        verbs: ToolVerbifier,
    ): List<String> {
        if (level == FeedVerbosity.SUMMARY) return emptyList()
        if (prev == null || prev.taskId != curr.taskId) return emptyList()
        val out = mutableListOf<String>()

        // the SEALED CoT blocks — every one below [prev.cotBlocks.size] was
        // already spoken; the diff never re-speaks history
        for (i in prev.cotBlocks.size until curr.cotBlocks.size) {
            out += blockMessages(i + 1, curr.cotBlocks[i])
        }

        // the tool CLOSES — real content per lane, one message each. A lane
        // that opened AND closed between two ticks still speaks its close
        // (the content carrier); its open one-liner would be noise.
        val prevTools = prev.tools.associateBy { it.toolId }
        curr.tools.forEach { lane ->
            val before = prevTools[lane.toolId]
            if (lane.status != null && (before == null || before.status == null)) {
                val box = if (lane.input == null) "" else toolBox(lane)
                out += box.ifBlank { closeFallback(lane, verbs) }
            }
        }

        // the tool OPENS — the VERBOSE extra: a one-liner the moment a lane
        // appears, so the transcript shows the action as it STARTS
        if (level == FeedVerbosity.VERBOSE) {
            curr.tools.forEach { lane ->
                if (lane.status == null && lane.toolId !in prevTools) {
                    out += openLine(lane, verbs)
                }
            }
        }
        return out
    }

    /**
     * The turn's last thought — the block UNDER the seal cap when the
     * turn ended: real streamed text that never sealed. The transcript
     * flushes it at the seal (NORMAL/VERBOSE), so the owner's «los cots
     * uno debajo del otro» loses nothing. Empty when there is no tail.
     */
    fun pendingFlush(state: TurnLive): List<String> =
        if (state.cotPending.isBlank()) emptyList() else blockMessages(null, state.cotPending)

    /**
     * One block's render, chunked under the message law (the close
     * shower's own recipe: chunk by CODE POINTS first, then esc — a
     * prose chunk escapes to ≈ itself; the transport's truncate is the
     * backstop). `number` null = the unsealed tail's honest label.
     */
    private fun blockMessages(number: Int?, text: String): List<String> {
        val head = if (number != null) "🧠 <b>CoT $number</b>" else "🧠 <b>CoT · sin sellar</b>"
        val chunks = mutableListOf<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            val chunk = PyText.take(rest, CHUNK_CP)
            chunks.add(chunk)
            rest = rest.substring(chunk.length)
        }
        return chunks.mapIndexed { i, chunk ->
            val label = if (i == 0) "$head\n" else ""
            label + "<blockquote>${PyText.esc(chunk)}</blockquote>"
        }
    }

    /** The close's fallback: the verdict the card's own stamp line carries — real numbers, nothing invented. */
    private fun closeFallback(lane: ToolLive, verbs: ToolVerbifier): String {
        val mark = when (lane.status) {
            "failed" -> " ❌"
            "denied", "stopped" -> " ⏹️"
            else -> " ✅"
        }
        val verdict = when {
            lane.added != null || lane.removed != null -> "🟢 +${lane.added ?: 0} · 🔴 −${lane.removed ?: 0}"
            lane.outputLines != null -> "${lane.outputLines} líneas de salida"
            else -> lane.status ?: "listo"
        }
        return "${verbs.of(lane.name, lane.input, lane.isMcp).emoji} " +
            "<b>${PyText.esc(lane.name)}</b>: ${PyText.esc(verdict)}$mark"
    }

    /** The open's one-liner: the verb + its detail (the command, the file) — the action STARTING. */
    private fun openLine(lane: ToolLive, verbs: ToolVerbifier): String {
        val verb = verbs.of(lane.name, lane.input, lane.isMcp)
        val clipped = PyText.preview(verb.detail, OPEN_DETAIL_CP)
        val detail = if (clipped.isNotEmpty()) " <code>${PyText.esc(clipped)}</code>" else ""
        return "▶️ ${verb.head()}$detail"
    }

    /** The feed chunk's cap — the close shower's own budget (raw code points before esc). */
    private const val CHUNK_CP = LiveTurn.COT_SHOWER_CHUNK_CP

    /** The open one-liner's detail cap (the card's lane line uses the same window). */
    private const val OPEN_DETAIL_CP = 60
}
