package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    /**
     * A COLLAPSED tool output (the owner, 3/11): the header above rides
     * the chat with ONE button; the numbered pages live here until the
     * tap. Null = a plain data message.
     */
    val pages: ToolOutputPages.Pages? = null,
    /**
     * A WRITE/EDIT close's file path (the owner, 5/11): the message earns
     * the [📄 archivo completo] button — the publisher owns the registry,
     * the feed only marks which message is a file close. Null = no button.
     */
    val editPath: String? = null,
    /**
     * THE VOICE (5/11): the LLM's own prose, one spent segment — the
     * publisher speaks it as a violet rich message. Null = not a voice
     * message.
     */
    val prose: String? = null,
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
    // one guard per early exit (the silent level, the turn switch) — the diff's
    // own shape; the collapse branch is one more verdict, not a second flow
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
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

        // THE VOICE'S SPENT SEGMENTS (5/11): every prose the model spoke
        // since the last tick, each one violet message, oldest first
        for (i in prev.proseSpent.size until curr.proseSpent.size) {
            out += FeedMessage(text = "", prose = curr.proseSpent[i])
        }

        // the SEALED CoT blocks — every one below [prev.cotBlocks.size] was
        // already spoken; the diff never re-speaks history. COLLAPSED: the
        // header + the tap affordance, never the text.
        for (i in prev.cotBlocks.size until curr.cotBlocks.size) {
            out += FeedMessage(collapsedHeader(i + 1, PyText.cpLength(curr.cotBlocks[i])), block = i + 1)
        }

        // THE LANE SCOPE (measured live 4/11: «están llegando cosas de los
        // subagentes y ahora está frenada»): a subagent's INNER tools are its
        // own narration, never the parent card's transcript — the parent keeps
        // the dispatch tool's line plus the 🤖 lane summary the card renders.
        // A `sa:`-scoped card speaks exactly its lane. Unscoped = only
        // parentToolUseId == null.
        val scope = curr.watchedLane
        fun visible(tool: ToolLive): Boolean =
            if (scope == null) tool.parentToolUseId == null else tool.parentToolUseId == scope

        // the tool CLOSES — real content per lane, one message each. A lane
        // that opened AND closed between two ticks still speaks its close
        // (the content carrier); its open one-liner would be noise.
        val prevTools = prev.tools.filter(::visible).associateBy { it.toolId }
        curr.tools.filter(::visible).forEach { lane ->
            val before = prevTools[lane.toolId]
            if (lane.status != null && (before == null || before.status == null)) {
                val collapsed = collapseOf(lane)
                if (collapsed != null) {
                    out += FeedMessage(text = collapsed.header, pages = collapsed)
                    return@forEach
                }
                val box = if (lane.input == null) "" else toolBox(lane)
                // THE FILE BUTTON (the owner, 5/11): a write/edit close earns
                // «📄 archivo completo» — the path rides the message, the
                // publisher's registry builds the door
                val editPath = lane.editFilePath()
                out += FeedMessage(
                    text = box.ifBlank { closeFallback(lane, verbs) },
                    editPath = editPath,
                )
            }
        }

        // the tool OPENS — the VERBOSE extra: a one-liner the moment a lane
        // appears, so the transcript shows the action as it STARTS. And the
        // LONG-COMMAND LAW (v2 §3): a shell command too long for the card
        // travels here ONCE, COMPLETE — the card's «✂️ … va en el feed»
        // promised it (NORMAL speaks it too: it is DATA, not noise; SUMMARY
        // stays silent by its own law). Split across messages only when a
        // single one would exceed Telegram's 4096 — never elided mid-command.
        curr.tools.filter(::visible).forEach { lane ->
            if (lane.status == null && lane.toolId !in prevTools) {
                if (level == FeedVerbosity.VERBOSE) out += FeedMessage.of(openLine(lane, verbs))
                if (lane.name in SHELL_TOOLS) {
                    commandOf(lane.input)
                        ?.takeIf { PyText.cpLength(it) > LiveTurn.COMMAND_CARD_CP }
                        ?.let { cmd -> out += fullCommandMessages(cmd) }
                }
            }
        }
        return out
    }

    /**
     * The long command's own messages: the COMPLETE command, chunked at
     * [COMMAND_CHUNK_CP] (esc-safe under Telegram's 4096), each chunk its
     * own bash block with its part marker — the chunks CONCATENATE to the
     * whole, byte for byte. Never elided mid-command.
     */
    private fun fullCommandMessages(cmd: String): List<FeedMessage> {
        val chunks = mutableListOf<String>()
        var rest = cmd
        while (rest.isNotEmpty()) {
            val piece = PyText.take(rest, COMMAND_CHUNK_CP)
            chunks += piece
            rest = rest.substring(piece.length)
        }
        return chunks.mapIndexed { i, chunk ->
            val part = if (chunks.size == 1) "" else " (${i + 1}/${chunks.size})"
            FeedMessage.of("<pre><code class=\"language-bash\">${PyText.esc(chunk)}</code></pre>$part")
        }
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
     * expands it: number, the «generado Xk» verdict (v2 §4, the number
     * law), the affordance. The text itself never rides here.
     */
    internal fun collapsedHeader(number: Int?, cp: Int): String {
        // «generado» is a SEALED block's verdict — an unsealed tail never
        // claims it; its size rides as the plain rung (the number law).
        val verdict = when (number) {
            null -> "· ${LiveTurn.fmtKDecimal(cp.toLong())}"
            else -> "generado ${LiveTurn.fmtKDecimal(cp.toLong())}"
        }
        val label = if (number != null) "CoT $number" else "CoT · sin sellar"
        return "📖 <b>$label</b> $verdict — tocar para leer"
    }

    /** The tap's EXPANDED render: the header, then the block WHOLE. */
    internal fun expandedBlock(number: Int?, cp: Int, text: String): String {
        val label = if (number != null) "CoT $number" else "CoT · sin sellar"
        return "📖 <b>$label</b> · ${LiveTurn.fmtKDecimal(cp.toLong())}\n<blockquote>${PyText.esc(text)}</blockquote>"
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
        val clipped = PathName.shaped(verb.detail, OPEN_DETAIL_CP)
        val detail = if (clipped.isNotEmpty()) " <code>${PyText.esc(clipped)}</code>" else ""
        return "▶️ ${verb.head()}$detail"
    }

    /**
     * THE COLLAPSED OUTPUT (the owner, 3/11): a shell's output ALWAYS
     * travels behind the pager's button — that IS the verbosity cure; a
     * file read only when it is long enough to bury the chat. Null = the
     * box speaks. Every page's lines carry their REAL numbers (the read's
     * offset included), so a snippet is never anonymous.
     */
    private fun collapseOf(lane: ToolLive): ToolOutputPages.Pages? {
        val out = lane.output?.takeIf { it.isNotBlank() }
        val shell = lane.name in SHELL_TOOLS
        val read = lane.name in READ_TOOLS
        val tiny = out != null && !shell && (!read || PyText.cpLength(out) <= ToolOutputPages.INLINE_READ_CP)
        return if (out == null || tiny) {
            null
        } else {
            val detail = if (shell) "" else fileOf(lane.input).orEmpty()
            val start = if (read) offsetOf(lane.input) + 1 else 1
            val kind = if (shell) "⚡ <b>${PyText.esc(lane.name)}</b>" else "📖 <b>${PyText.esc(lane.name)}</b>"
            ToolOutputPages.Pages(
                header = ToolOutputPages.headerOf(
                    kind,
                    detail,
                    PyText.splitLines(out).size,
                    // THE FENCE LAW (v2 §7): a shell's command never rides
                    // inline — it IS the fenced bash block under the header
                    fence = commandOf(lane.input).takeIf { shell },
                    language = if (shell) "bash" else null,
                ),
                pages = ToolOutputPages.pages(out, start),
                language = if (shell) "bash" else null, // v2 §5: the shell's pages ride the bash fence
            )
        }
    }

    /** The command as the shell would see it (a list joins with spaces).
     *  SHARED — the card's bash block and the pager speak the same text. */
    internal fun commandOf(input: JsonObject?): String? = when (val cmd = input?.get("command")) {
        is JsonArray -> cmd.joinToString(" ") { PyText.pyStr(it) }
        is JsonPrimitive -> if (cmd.isString) cmd.content else null
        else -> null
    }

    private fun fileOf(input: JsonObject?): String? =
        listOf("file_path", "path").firstNotNullOfOrNull { k ->
            (input?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        }

    /** The read's offset (a slice starts deeper in the file) — 0 when absent. */
    private fun offsetOf(input: JsonObject?): Int =
        (input?.get("offset") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    /** The tools whose output is collapsed behind the pager. */
    private val READ_TOOLS = setOf("Read", "View")

    /** The open one-liner's detail cap (the card's lane line uses the same window). */
    private const val OPEN_DETAIL_CP = 60

    /**
     * The long command's per-message chunk (esc-safe: a pathological
     * all-`&` chunk still lands under Telegram's 4096 with the tags on).
     */
    private const val COMMAND_CHUNK_CP = 3000
}
