package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText

/**
 * NuTable (24/11): Telegram does NOT render tables in `sendMessage` — a nu
 * table arrives as a wall of box-drawing characters that wraps on a phone.
 * Bot API 10.1's rich messages render a real table from `<table bordered>`,
 * so this pure parser finds the box tables in a script's output and rewrites
 * them as Telegram rich HTML, leaving everything else as prose.
 *
 * It lives in `channel` (not `daemon`) because BOTH renderers feed on it:
 * the fleet answers' rich HTML here, and the assistant voice's rich `table`
 * blocks in [RichVoice].
 *
 * The laws, each measured on the phone:
 *  - the LEFT-edge borders are per-theme (`╭ ┌ ┏ ├`), never one constant —
 *    the healthcheck renders thin (`┌`), and a missed border leaked a border
 *    line into the prose and killed the table;
 *  - a WRAPPED cell (nu prints the continuation with an empty head) is
 *    rebuilt, not refused;
 *  - a cell's runs of spaces are nu's canvas padding, not data;
 *  - a table whose rows disagree on the column count is NOT guessed at —
 *    the whole answer falls back to the caller's `<pre>`.
 */
object NuTable {

    /** A script's output as the two shapes the phone renders differently. */
    internal sealed interface Segment {
        /** A run of literal lines: the caller keeps their line breaks. */
        data class Prose(val lines: List<String>) : Segment

        /** One parsed table: the first row is the header. */
        data class Table(val rows: List<List<String>>) : Segment
    }

    private const val WALL = '│'

    /**
     * The LEFT edge of a border line across nu's themes: rounded (`╭ ╰`),
     * thin (`┌ └`), heavy (`┏ ┗`) and the mid rules (`├ ┣`).
     */
    private val BORDER_HEADS = setOf('╭', '╰', '┌', '└', '┏', '┗', '├', '┣')

    private fun isBorder(line: String): Boolean = line.trimStart().firstOrNull() in BORDER_HEADS

    private fun isRow(line: String): Boolean = line.trim().startsWith(WALL)

    /**
     * The output as rich HTML: `null` when nothing table-shaped was found
     * (the caller keeps its `<pre>`) or when a table is too ambiguous to
     * convert (same fallback — the monospace truth beats invented columns).
     * Prose keeps its newlines: [RichRequest.htmlRich] turns them into the
     * phone's `<br>` at the envelope.
     */
    fun toRichHtml(out: String): String? = parse(out)?.joinToString("\n") { render(it) }

    /**
     * The output as SEGMENTS — the ONE parse both renderers feed on: the
     * fleet's rich HTML above and [RichVoice]'s `table` blocks. `null` = no
     * table-shaped row at all, or a table whose shape is doubtful.
     */
    @Suppress("ReturnCount") // the guards ARE the answer: no table, then per-block null
    internal fun parse(out: String): List<Segment>? {
        val lines = out.lines()
        if (lines.none { isRow(it) }) return null
        val segments = ArrayList<Segment>()
        val prose = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            if (!isRow(lines[i]) && !isBorder(lines[i])) {
                prose.add(lines[i])
                i++
                continue
            }
            if (prose.isNotEmpty()) {
                segments.add(Segment.Prose(prose.toList()))
                prose.clear()
            }
            val block = ArrayList<String>()
            while (i < lines.size && (isRow(lines[i]) || isBorder(lines[i]))) {
                block.add(lines[i])
                i++
            }
            segments.add(renderTable(block) ?: return null)
        }
        if (prose.isNotEmpty()) segments.add(Segment.Prose(prose.toList()))
        return segments
    }

    private fun render(segment: Segment): String = when (segment) {
        is Segment.Prose -> segment.lines.joinToString("\n")
        is Segment.Table -> renderHtml(segment)
    }

    private fun renderHtml(table: Segment.Table): String = buildString {
        append("<table bordered>")
        table.rows.forEachIndexed { index, row ->
            append("<tr>")
            val tag = if (index == 0) "th" else "td"
            row.forEach { cell ->
                append('<').append(tag).append('>').append(PyText.esc(cell)).append("</").append(tag).append('>')
            }
            append("</tr>")
        }
        append("</table>")
    }

    /**
     * One box table → one [Segment.Table]; null when the shape is
     * untrustworthy.
     *
     * WRAPPED CELLS are REBUILT: nu prints a continuation line with an empty
     * first column and the wrapped text further right (measured on
     * /fleet_healthcheck — the `detail` column).
     */
    @Suppress("ReturnCount") // each refusal names its own reason: size, columns, shape
    private fun renderTable(block: List<String>): Segment.Table? {
        val raw = block.filter { isRow(it) }.map { cells(it) }
        if (raw.size < 2) return null
        val cols = raw.first().size
        if (cols < 2) return null
        if (raw.any { it.size != cols }) return null
        val rows = mergeContinuations(raw, cols)
        if (rows.size < 2) return null
        return Segment.Table(rows)
    }

    /** Fold each continuation line (empty head) into the row above it. */
    private fun mergeContinuations(raw: List<List<String>>, cols: Int): List<List<String>> {
        val out = ArrayList<MutableList<String>>()
        raw.forEach { cells ->
            if (isContinuation(out, cells)) appendTo(out.last(), cells, cols) else out.add(cells.toMutableList())
        }
        return out
    }

    private fun isContinuation(out: List<MutableList<String>>, cells: List<String>): Boolean =
        out.isNotEmpty() && cells.first().isEmpty() && cells.drop(1).any { it.isNotEmpty() }

    private fun appendTo(prev: MutableList<String>, cells: List<String>, cols: Int) {
        for (i in 1 until cols) {
            if (cells[i].isNotEmpty()) {
                prev[i] = if (prev[i].isEmpty()) cells[i] else prev[i] + " " + cells[i]
            }
        }
    }

    /**
     * The cells of a wall row: everything between two walls, trimmed, with
     * runs of spaces collapsed — that padding is nu's alignment, not the
     * value (measured: the colima row repeated it inside one cell).
     */
    private fun cells(row: String): List<String> {
        val parts = row.trim().trim(WALL).split(WALL)
        return parts.map { it.trim().replace(SPACES, " ") }
    }

    private val SPACES = Regex("\\s+")
}
