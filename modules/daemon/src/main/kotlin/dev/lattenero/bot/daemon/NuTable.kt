package dev.lattenero.bot.daemon

/**
 * NuTable (24/11): Telegram does NOT render tables in `sendMessage` — a nu
 * table arrives as a wall of box-drawing characters that wraps on a phone.
 * Bot API 10.1 added RICH messages (`sendRichMessage`), which DO render native
 * tables from GitHub-flavored markdown (≤ 20 columns). This is the pure half:
 * find the box tables in a script's output and rewrite them as markdown
 * tables, leaving everything else as text.
 *
 * The parse is CONSERVATIVE by design: a table whose rows do not all carry the
 * same number of wall characters (a wrapped cell spans two lines) is NOT
 * guessed at — the whole run falls back to a fenced code block, which keeps
 * the monospace truth instead of inventing columns.
 */
internal object NuTable {

    private const val WALL = '│'
    private const val TOP = '╭'
    private const val MID = '├'
    private const val BOTTOM = '╰'

    private fun isBorder(line: String): Boolean {
        val t = line.trim()
        return t.startsWith(TOP) || t.startsWith(MID) || t.startsWith(BOTTOM)
    }

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
                md.append(lines[i]).append('\n')
                i++
                continue
            }
            val block = ArrayList<String>()
            while (i < lines.size && (isRow(lines[i]) || isBorder(lines[i]))) {
                block.add(lines[i])
                i++
            }
            md.append(renderTable(block) ?: return null)
        }
        return md.toString().trimEnd()
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

    /** A pipe inside a cell would split the markdown row. */
    private fun escape(cell: String): String = cell.replace("|", "\\|")
}
