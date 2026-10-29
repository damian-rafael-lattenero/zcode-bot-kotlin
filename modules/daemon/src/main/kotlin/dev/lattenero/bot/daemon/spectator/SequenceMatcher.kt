package dev.lattenero.bot.daemon.spectator

/**
 * A faithful port of CPython's difflib (SequenceMatcher + the unified
 * diff line emission), because the tool boxes' colored diffs must be
 * byte-identical to the Python bot's — including the autojunk heuristic
 * for b-sequences of 200+ lines (pinned by the golden vectors in
 * spectator-golden.json, generated from the real difflib).
 */
internal class SequenceMatcher(private val a: List<String>, private val b: List<String>, autojunk: Boolean = true) {

    internal enum class Tag { REPLACE, DELETE, INSERT, EQUAL }

    internal data class Match(val a: Int, val b: Int, val size: Int)

    internal data class Opcode(val tag: Tag, val i1: Int, val i2: Int, val j1: Int, val j2: Int)

    private val b2j: MutableMap<String, MutableList<Int>> = run {
        val m = mutableMapOf<String, MutableList<Int>>()
        b.forEachIndexed { i, elt -> m.getOrPut(elt) { mutableListOf() }.add(i) }
        // autojunk: in a 200+ line b, lines occurring more than ~1% of the time are
        // "popular" and do not anchor matches — pinned by the autojunk golden vector.
        if (autojunk && b.size >= AUTOJUNK_MIN_LINES) {
            val ntest = b.size / HUNDRED + 1
            m.keys.filter { m.getValue(it).size > ntest }.forEach { m.remove(it) }
        }
        m
    }

    /** find_longest_match, junk-free (isjunk is None in every caller here). */
    // CPython verbatim — the algorithm's shape is the contract.
    @Suppress("LoopWithTooManyJumpStatements", "CyclomaticComplexMethod")
    private fun findLongestMatch(alo: Int, ahi: Int, blo: Int, bhi: Int): Match {
        var bestI = alo
        var bestJ = blo
        var bestSize = 0
        var j2len: Map<Int, Int> = emptyMap()
        for (i in alo until ahi) {
            val newJ2len = HashMap<Int, Int>()
            val indices = b2j[a[i]] ?: emptyList()
            // CPython's guards, verbatim — the algorithm's shape is the contract
            @Suppress("LoopWithTooManyJumpStatements")
            for (j in indices) {
                if (j < blo) continue
                if (j >= bhi) break
                val k = (j2len[j - 1] ?: 0) + 1
                newJ2len[j] = k
                if (k > bestSize) {
                    bestI = i - k + 1
                    bestJ = j - k + 1
                    bestSize = k
                }
            }
            j2len = newJ2len
        }
        // Extend the best by equal elements on each end (the junk loops never run: no junk set)
        while (bestI > alo && bestJ > blo && a[bestI - 1] == b[bestJ - 1]) {
            bestI--
            bestJ--
            bestSize++
        }
        while (bestI + bestSize < ahi && bestJ + bestSize < bhi && a[bestI + bestSize] == b[bestJ + bestSize]) {
            bestSize++
        }
        return Match(bestI, bestJ, bestSize)
    }

    fun matchingBlocks(): List<Match> {
        data class Range(val alo: Int, val ahi: Int, val blo: Int, val bhi: Int)

        val queue = ArrayDeque<Range>()
        val blocks = mutableListOf<Match>()
        queue.addLast(Range(0, a.size, 0, b.size))
        while (queue.isNotEmpty()) {
            val r = queue.removeLast()
            val m = findLongestMatch(r.alo, r.ahi, r.blo, r.bhi)
            if (m.size > 0) {
                blocks.add(m)
                if (r.alo < m.a && r.blo < m.b) queue.addLast(Range(r.alo, m.a, r.blo, m.b))
                if (m.a + m.size < r.ahi && m.b + m.size < r.bhi) {
                    queue.addLast(Range(m.a + m.size, r.ahi, m.b + m.size, r.bhi))
                }
            }
        }
        blocks.sortWith(compareBy({ it.a }, { it.b }))
        var i1 = 0
        var j1 = 0
        var k1 = 0
        val nonAdjacent = mutableListOf<Match>()
        for (m in blocks) {
            if (i1 + k1 == m.a && j1 + k1 == m.b) {
                k1 += m.size
            } else {
                if (k1 > 0) nonAdjacent.add(Match(i1, j1, k1))
                i1 = m.a
                j1 = m.b
                k1 = m.size
            }
        }
        if (k1 > 0) nonAdjacent.add(Match(i1, j1, k1))
        nonAdjacent.add(Match(a.size, b.size, 0))
        return nonAdjacent
    }

    fun opcodes(): List<Opcode> {
        val answer = mutableListOf<Opcode>()
        var i = 0
        var j = 0
        for (m in matchingBlocks()) {
            val tag = when {
                i < m.a && j < m.b -> Tag.REPLACE
                i < m.a -> Tag.DELETE
                j < m.b -> Tag.INSERT
                else -> null
            }
            if (tag != null) answer.add(Opcode(tag, i, m.a, j, m.b))
            i = m.a + m.size
            j = m.b + m.size
            if (m.size > 0) answer.add(Opcode(Tag.EQUAL, m.a, i, m.b, j))
        }
        return answer
    }

    /** get_grouped_opcodes(n): groups with n context lines; pure-equal tails dropped. */
    fun groupedOpcodes(n: Int = 3): List<List<Opcode>> {
        var codes = opcodes().toMutableList()
        if (codes.isEmpty()) codes.add(Opcode(Tag.EQUAL, 0, 1, 0, 1))
        if (codes.first().tag == Tag.EQUAL) {
            val c = codes.first()
            codes[0] = c.copy(i1 = maxOf(c.i1, c.i2 - n), j1 = maxOf(c.j1, c.j2 - n))
        }
        if (codes.last().tag == Tag.EQUAL) {
            val c = codes.last()
            codes[codes.size - 1] = c.copy(i2 = minOf(c.i2, c.i1 + n), j2 = minOf(c.j2, c.j1 + n))
        }
        val nn = n + n
        val groups = mutableListOf<List<Opcode>>()
        var group = mutableListOf<Opcode>()
        for (c in codes) {
            if (c.tag == Tag.EQUAL && c.i2 - c.i1 > nn) {
                group.add(c.copy(i2 = minOf(c.i2, c.i1 + n), j2 = minOf(c.j2, c.j1 + n)))
                groups.add(group)
                group = mutableListOf(c.copy(i1 = maxOf(c.i1, c.i2 - n), j1 = maxOf(c.j1, c.j2 - n)))
            } else {
                group.add(c)
            }
        }
        if (group.isNotEmpty() && !(group.size == 1 && group[0].tag == Tag.EQUAL)) groups.add(group)
        return groups
    }

    private companion object {
        const val AUTOJUNK_MIN_LINES = 200
        const val HUNDRED = 100
    }
}

/** unified_diff's line emission (headers and @@ markers never generated — the caller strips them anyway). */
// The emission is the algorithm: groups → lines, exactly difflib's order
// (replace emits its '-' half AND its '+' half, like CPython's two ifs).
@Suppress("NestedBlockDepth")
internal fun unifiedDiffLines(a: List<String>, b: List<String>): List<String> {
    val out = mutableListOf<String>()
    for (g in SequenceMatcher(a, b).groupedOpcodes()) {
        for (c in g) {
            if (c.tag == SequenceMatcher.Tag.EQUAL) {
                a.subList(c.i1, c.i2).forEach { out.add(" $it") }
            } else {
                if (c.tag == SequenceMatcher.Tag.DELETE || c.tag == SequenceMatcher.Tag.REPLACE) {
                    a.subList(c.i1, c.i2).forEach { out.add("-$it") }
                }
                if (c.tag == SequenceMatcher.Tag.INSERT || c.tag == SequenceMatcher.Tag.REPLACE) {
                    b.subList(c.j1, c.j2).forEach { out.add("+$it") }
                }
            }
        }
    }
    return out
}

/** colorize_diff: 🟢 added / 🔴 removed / · context; first line the diffstat. Bare circles without @@ headers. */
// messages.colorize_diff verbatim — every branch is a line class.
@Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
internal fun colorizeDiff(lines: List<String>): List<String> {
    val more = lines.count { it.startsWith("+") }
    val less = lines.count { it.startsWith("-") }
    val out = mutableListOf<String>()
    if (more > 0 || less > 0) out.add("● 🟢 +$more  🔴 −$less")
    var oldLn: Int? = null
    var newLn: Int? = null
    for (ln in lines) {
        when {
            ln.startsWith("@@") -> {
                oldLn = FIRST_MINUS.find(ln)?.groupValues?.get(1)?.toIntOrNull()
                newLn = FIRST_PLUS.find(ln)?.groupValues?.get(1)?.toIntOrNull()
            }

            ln.startsWith("\\") -> out.add("· $ln")

            ln.startsWith("+") && newLn != null -> {
                out.add("🟢 $newLn $ln")
                newLn++
            }

            ln.startsWith("-") && oldLn != null -> {
                out.add("🔴 $oldLn $ln")
                oldLn++
            }

            newLn != null -> {
                out.add("· $newLn $ln")
                newLn++
                if (oldLn != null) oldLn++
            }

            else ->
                out.add((if (ln.startsWith("-")) "🔴 " else if (ln.startsWith("+")) "🟢 " else "· ") + ln)
        }
    }
    return out
}

private val FIRST_MINUS = Regex("-(\\d+)")
private val FIRST_PLUS = Regex("\\+(\\d+)")

/** _colored_diff: difflib over old/new, @@ headers stripped (bare circles), capped, never empty. */
internal fun coloredDiff(old: List<String>, new: List<String>, maxChars: Int): String {
    val d = unifiedDiffLines(old, new).filterNot { it.startsWith("@@") }
    val joined = colorizeDiff(d).joinToString("\n")
    return PyText.take(joined, maxChars).ifEmpty { "(no changes)" }
}
