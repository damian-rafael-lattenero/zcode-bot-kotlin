package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText

/**
 * One TRANSCRIPT FEED message: the words, the optional inline keyboard,
 * and — for a collapsed CoT — WHICH block the message opens (`block`:
 * 1-based for the sealed blocks, 0 for the unsealed tail, null for
 * everything else). The markup itself is built by the PUBLISHER (it owns
 * the callback's registry id, see [LivePublisher]); the feed only marks
 * the message as a CoT one.
 */
data class FeedMessage(
    val text: String,
    val block: Int? = null,
) {
    companion object {
        /** A plain data message (a tool close, an open one-liner). */
        fun of(text: String) = FeedMessage(text)
    }
}

/**
 * The TRANSCRIPT FEED — THE DATA half of the ley raíz (the owner, 2/11:
 * «separar bien el dato del proceso»): each tool CLOSE becomes its own
 * Telegram message with its real content (the bash command + output, the
 * edit's diff box), ordered, under the always-last process card. THE CoT
 * STOPS TRAVELING WHOLE: each sealed 3K block becomes a COLLAPSED message
 * — the header «📖 CoT N · 3K — tocar para leer» and a button; the tap
 * expands the block WHOLE in place on that same message (the publisher's
 * edit), another tap re-collapses (the owner, 2/11: «yo decido si los leo
 * o no una vez que se llenó el botón de los 3k»). A block never chunks:
 * it is capped at [LiveTurn.COT_BLOCK_CP] (3000 cp), one message by law.
 *
 * PURE: the feed is a diff between two consecutive turn snapshots —
 * nothing is emitted from a side queue, nothing is re-emitted after a
 * resync (a block once spoken is in `prev`, the diff never sees it
 * again), and a turn switch starts clean (the previous turn's history
 * stays sealed in the chat, never replayed). The publisher holds the
 * `prev` pointer and drains per tick; the level ([FeedVerbosity]) gates:
 * SUMMARY speaks nothing (the collapsed headers are data too), NORMAL
 * speaks the collapsed blocks + tool closes, VERBOSE adds the tool OPEN
 * one-liners.
 */
object TranscriptFeed {

    /**
     * The messages born between `prev` and `curr`, oldest first — each a
     * complete send under the message law. `toolBox` is the injected
     * per-tool renderer (the daemon passes the SPECTATOR's ToolBox — the
     * same renderer the final transcript uses); a blank render (or an
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
    ): List<FeedMessage> {
        if (level == FeedVerbosity.SUMMARY) return emptyList()
        if (prev == null || prev.taskId != curr.taskId) return emptyList()
        val out = mutableListOf<FeedMessage>()

        // the SEALED CoT blocks — every one below [prev.cotBlocks.size] was
        // already spoken; the diff never re-speaks history. COLLAPSED: the
        // header + the tap affordance, never the text.
        for (i in prev.cotBlocks.size until curr.cotBlocks.size) {
            out += FeedMessage(collapsedHeader(i + 1, PyText.cpLength(curr.cotBlocks[i])), block = i + 1)
        }

        // the tool CLOSES — real content per lane, one message each. A lane
        // that opened AND closed between two ticks still speaks its close
        // (the content carrier); its open one-liner would be noise.
        val prevTools = prev.tools.associateBy { it.toolId }
        curr.tools.forEach { lane ->
            val before = prevTools[lane.toolId]
            if (lane.status != null && (before == null || before.status == null)) {
                val box = if (lane.input == null) "" else toolBox(lane)
                out += FeedMessage.of(box.ifBlank { closeFallback(lane, verbs) })
            }
        }

        // the tool OPENS — the VERBOSE extra: a one-liner the moment a lane
        // appears, so the transcript shows the action as it STARTS
        if (level == FeedVerbosity.VERBOSE) {
            curr.tools.forEach { lane ->
                if (lane.status == null && lane.toolId !in prevTools) {
                    out += FeedMessage.of(openLine(lane, verbs))
                }
            }
        }
        return out
    }

    /**
     * The turn's last thought — the block UNDER the seal cap when the
     * turn ended: real streamed text that never sealed. Collapsed like
     * the rest (block 0): the owner decides whether to read it. Empty
     * when there is no tail.
     */
    fun pendingFlush(state: TurnLive): List<FeedMessage> =
        if (state.cotPending.isBlank()) {
            emptyList()
        } else {
            listOf(FeedMessage(collapsedHeader(null, PyText.cpLength(state.cotPending)), block = 0))
        }

    /**
     * The collapsed block's header — the whole message until the tap
     * expands it: number, size, the affordance. The text itself never
     * rides here.
     */
    internal fun collapsedHeader(number: Int?, cp: Int): String {
        val label = if (number != null) "CoT $number" else "CoT · sin sellar"
        return "📖 <b>$label</b> · ${LiveTurn.fmtK(cp)} — tocar para leer"
    }

    /** The tap's EXPANDED render: the header, then the block WHOLE. */
    internal fun expandedBlock(number: Int?, cp: Int, text: String): String {
        val label = if (number != null) "CoT $number" else "CoT · sin sellar"
        return "📖 <b>$label</b> · ${LiveTurn.fmtK(cp)}\n<blockquote>${PyText.esc(text)}</blockquote>"
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

    /** The open one-liner's detail cap (the card's lane line uses the same window). */
    private const val OPEN_DETAIL_CP = 60
}
