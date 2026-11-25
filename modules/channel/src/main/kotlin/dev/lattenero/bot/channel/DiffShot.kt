package dev.lattenero.bot.channel

/**
 * THE DIFF SHOT — a unified diff turned into SIDE-BY-SIDE rows (the owner,
 * 25/11: the phone's wrapped unified diff loses the alignment; two columns
 * read at a glance, like the IDE's own diff view).
 *
 * The meaning: `rows(diff)` pairs a run of `-` lines with the `+` lines that
 * replaced them, one row per pair, and `changedSpan(old, new)` marks what
 * actually changed INSIDE the pair (common prefix/suffix stripped) so the
 * drawing can emphasise the middle instead of the whole line.
 *
 * Pure: text in, rows out; the AWT drawer (daemon's `DiffPng`) owns pixels.
 * The bot's own format (`+ 12 │ code`, `- 4 │ code`) and the standard unified
 * one (`+code`) both parse — the marker is the sign, the numbers are a gift.
 */
object DiffShot {

    /** One row of the side-by-side view: a context line, or a replaced pair. */
    data class Row(
        val oldNo: Int?,
        val old: String?,
        val newNo: Int?,
        val new: String?,
    ) {
        /** True when both sides are the same words (a context line). */
        val same: Boolean get() = old == new
    }

    /** The rows plus the honest flag: the budget cut the diff. */
    data class Plan(val rows: List<Row>, val truncated: Boolean)

    /** Rows a phone image holds before the wall of text stops being a view. */
    const val MAX_ROWS: Int = 60

    private val CUSTOM = Regex("""^([+\- ])\s*(\d+)?\s*│\s?(.*)$""")
    private val STANDARD = Regex("""^([+\- ])(.*)$""")

    /** One diff line's sign, number (when the format carries one) and words. */
    private data class Piece(val sign: String, val no: Int?, val text: String)

    /** The numbered format's third capture: the code after `│`. */
    private const val CODE_GROUP = 3

    /**
     * ONE line → its piece; null for the lines that are not changes: the file
     * headers (`--- a/x`, `+++ b/x`), hunk headers, the no-newline marker and
     * blanks. The first sample drew the headers as a red/green pair of paths.
     */
    private fun parseLine(line: String): Piece? = when {
        line.startsWith("---") || line.startsWith("+++") -> null
        line.startsWith("@@") || line.startsWith("\\") || line.isBlank() -> null
        else -> {
            val groups = CUSTOM.find(line)?.groupValues
            if (groups != null) {
                Piece(groups[1], groups[2].toIntOrNull(), groups[CODE_GROUP])
            } else {
                STANDARD.find(line)?.let { Piece(it.groupValues[1], null, it.groupValues[2]) }
            }
        }
    }

    /**
     * Parses the diff and pairs its changes. Consecutive non-context lines
     * form ONE change run: its `-` lines and its `+` lines are zipped by
     * index, the shorter side padded — a row with one side null is a pure
     * deletion/addition, which the drawer paints on its own column.
     */
    fun rows(diff: String, maxRows: Int = MAX_ROWS): Plan {
        val parsed = diff.split('\n').mapNotNull(::parseLine)
        val out = mutableListOf<Row>()
        var i = 0
        while (i < parsed.size) {
            val piece = parsed[i]
            if (piece.sign == " ") {
                out += Row(piece.no, piece.text, piece.no, piece.text)
                i++
            } else {
                val dels = mutableListOf<Pair<Int?, String>>()
                val adds = mutableListOf<Pair<Int?, String>>()
                while (i < parsed.size && parsed[i].sign != " ") {
                    if (parsed[i].sign == "-") dels += parsed[i].no to parsed[i].text
                    else adds += parsed[i].no to parsed[i].text
                    i++
                }
                for (k in 0 until maxOf(dels.size, adds.size)) {
                    out += Row(
                        oldNo = dels.getOrNull(k)?.first,
                        old = dels.getOrNull(k)?.second,
                        newNo = adds.getOrNull(k)?.first,
                        new = adds.getOrNull(k)?.second,
                    )
                }
            }
        }
        return Plan(out.take(maxRows), truncated = out.size > maxRows)
    }

    /**
     * THE CHANGED MIDDLE: the span of each side that is NOT the shared
     * prefix/suffix. Two equal strings answer two empty spans (nothing to
     * emphasise); a pure insertion answers an empty old span.
     */
    fun changedSpan(old: String, new: String): Pair<IntRange, IntRange> {
        val prefix = old.commonPrefixWith(new).length
        val maxSuffix = minOf(old.length, new.length) - prefix
        val suffix = old.commonSuffixWith(new).length.coerceIn(0, maxSuffix)
        val oldEnd = (old.length - suffix).coerceAtLeast(prefix)
        val newEnd = (new.length - suffix).coerceAtLeast(prefix)
        return (prefix until oldEnd) to (prefix until newEnd)
    }
}
