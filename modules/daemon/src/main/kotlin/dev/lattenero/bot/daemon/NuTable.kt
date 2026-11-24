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

    /** One box table → one markdown table; null when the rows disagree. */
    @Suppress("ReturnCount") // each refusal names its own reason: size, columns, wrap
    private fun renderTable(block: List<String>): String? {
        val rows = block.filter { isRow(it) }
        if (rows.size < 2) return null
        val cols = cells(rows.first()).size
        if (cols < 2) return null
        if (rows.any { cells(it).size != cols }) return null
        // A WRAPPED cell prints a continuation line: empty first column, text
        // in the wrapped one (nu pads every other cell). It is structurally a
        // row, so the shape check cannot see it — the empty head can, and a
        // real row always carries its index/name there.
        if (rows.any { cells(it).first().isEmpty() }) return null
        val sb = StringBuilder()
        rows.forEachIndexed { index, row ->
            sb.append('|')
            cells(row).forEach { cell -> sb.append(' ').append(escape(cell)).append(" |") }
            sb.append('\n')
            if (index == 0) {
                sb.append('|')
                repeat(cols) { sb.append("---|") }
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    /** The cells of a wall row: everything between two walls, trimmed. */
    private fun cells(row: String): List<String> {
        val parts = row.trim().trim(WALL).split(WALL)
        return parts.map { it.trim() }
    }

    /** A pipe inside a cell would split the markdown row. */
    private fun escape(cell: String): String = cell.replace("|", "\\|")
}
