package dev.lattenero.bot.daemon

/**
 * NuTable (24/11): Telegram does NOT render tables in `sendMessage` — a nu
 * table arrives as a wall of box-drawing characters that wraps on a phone.
 * Bot API 10.1 added RICH messages (`sendRichMessage`), which DO render native
 * tables from GitHub-flavored markdown (≤ 20 columns). This is the pure half:
 * find the box tables in a script's output and rewrite them as markdown
 * tables, leaving everything else as text.
 *
 * Two laws learned the hard way on the phone (24/11):
 *  - a GFM table must start its OWN block — prose on the line above joins the
 *    header and Telegram renders the pipes literally, so a blank line goes
 *    before and after every table;
 *  - markdown folds single newlines into spaces — prose lines carry a
 *    trailing backslash (CommonMark's hard break) to keep the terminal shape.
 *
 * A table whose rows do not all carry the same number of cells is NOT guessed
 * at: the whole run falls back to a fenced code block, which keeps the
 * monospace truth instead of inventing columns. A WRAPPED cell (nu prints the
 * continuation with an empty head) is rebuilt, not refused.
 */
internal object NuTable {

    private const val WALL = '│'

    /**
     * The LEFT edge of a border line, across nu's themes: rounded (`╭ ╰`),
     * thin (`┌ └`), heavy (`┏ ┗`) and the mid rules (`├ ┣`). Measured 24/11:
     * the healthcheck sets `table.mode = "thin"`, so the top/bottom borders
     * are `┌ └` — the old constants missed them, they leaked out as prose
     * lines and the table lost its own paragraph (Telegram then rendered
     * literal pipes).
     */
    private val BORDER_HEADS = setOf('╭', '╰', '┌', '└', '┏', '┗', '├', '┣')

    private fun isBorder(line: String): Boolean = line.trimStart().firstOrNull() in BORDER_HEADS

    private fun isRow(line: String): Boolean = line.trim().startsWith(WALL)

    /**
     * The output as rich markdown: `null` when nothing table-shaped was found
     * (the caller keeps its normal path) or when a table is too ambiguous to
     * convert (the caller sends a code block instead).
     */
    @Suppress("ReturnCount") // the guards ARE the answer: no table, then per-block null
    internal fun toRichMarkdown(out: String): String? {
        val lines = out.lines()
        if (lines.none { isRow(it) }) return null
        val md = StringBuilder()
        var i = 0
        while (i < lines.size) {
            if (!isRow(lines[i]) && !isBorder(lines[i])) {
                md.append(proseLine(lines, i))
                i++
                continue
            }
            val block = ArrayList<String>()
            while (i < lines.size && (isRow(lines[i]) || isBorder(lines[i]))) {
                block.add(lines[i])
                i++
            }
            val table = renderTable(block) ?: return null
            // A GFM table must start its own block: prose on the line above
            // becomes part of the header (Telegram then shows literal pipes).
            if (md.isNotEmpty() && !md.endsWith("\n\n")) md.append('\n')
            md.append(table).append('\n')
        }
        return md.toString().trimEnd()
    }

    /**
     * One prose line, with the line structure KEPT. Markdown folds single
     * newlines into spaces, so the healthcheck's per-leg lines collapsed into
     * one paragraph on the phone (measured 24/11). A trailing backslash is
     * CommonMark's hard break; the last line of a run needs none.
     */
    private fun proseLine(lines: List<String>, i: Int): String {
        val line = lines[i]
        val next = lines.getOrNull(i + 1)
        val keepsGoing = next != null && next.isNotBlank() && !isRow(next) && !isBorder(next)
        val needsBreak = line.isNotBlank() && keepsGoing && !line.endsWith("\\")
        return line + (if (needsBreak) "\\" else "") + '\n'
    }

    /**
     * One box table → one markdown table; null when the shape is untrustworthy.
     *
     * WRAPPED CELLS are REBUILT, not refused: nu prints a continuation line
     * with an empty first column and the wrapped text further right (measured
     * on /fleet_healthcheck, 24/11 — the `detail` column). Refusing sent the
     * whole answer to a `<pre>`, which is exactly the wall the owner saw;
     * merging the continuation back into its row restores the logical table,
     * and Telegram's native table WRAPS a long cell properly.
     */
    @Suppress("ReturnCount") // each refusal names its own reason: size, columns, shape
    private fun renderTable(block: List<String>): String? {
        val raw = block.filter { isRow(it) }.map { cells(it) }
        if (raw.size < 2) return null
        val cols = raw.first().size
        if (cols < 2) return null
        if (raw.any { it.size != cols }) return null
        val rows = mergeContinuations(raw, cols)
        if (rows.size < 2) return null
        val sb = StringBuilder()
        rows.forEachIndexed { index, row ->
            sb.append('|')
            row.forEach { cell -> sb.append(' ').append(escape(cell)).append(" |") }
            sb.append('\n')
            if (index == 0) {
                sb.append('|')
                repeat(cols) { sb.append("---|") }
                sb.append('\n')
            }
        }
        return sb.toString()
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

    /** The cells of a wall row: everything between two walls, trimmed. */
    private fun cells(row: String): List<String> {
        val parts = row.trim().trim(WALL).split(WALL)
        return parts.map { it.trim() }
    }

    /**
     * A pipe inside a cell would split the markdown row; runs of spaces are
     * nu's canvas padding (the colima cell repeats it) and would show as gaps
     * in a native table — the data is the words, not the alignment.
     */
    private fun escape(cell: String): String = cell.replace(SPACES, " ").replace("|", "\\|")

    private val SPACES = Regex("\\s+")
}
