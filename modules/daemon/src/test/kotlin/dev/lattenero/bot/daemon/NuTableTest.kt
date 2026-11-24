package dev.lattenero.bot.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NuTable: a nu box table becomes a Telegram-renderable markdown table, and a
 * table whose cells wrapped is NEVER guessed at — the caller falls back to a
 * code block (the monospace truth beats invented columns).
 */
class NuTableTest {

    private val table = """
        ╭───┬──────────────┬────────────┬───────╮
        │ # │     job      │   state    │  pid  │
        ├───┼──────────────┼────────────┼───────┤
        │ 0 │ brain        │ not loaded │     0 │
        │ 1 │ bot          │ idle       │ 12345 │
        ╰───┴──────────────┴────────────┴───────╯
    """.trimIndent()

    @Test
    fun aCleanTableBecomesAPipeTableWithAHeaderRow() {
        val md = NuTable.toRichMarkdown(table)
        assertTrue(md != null)
        val lines = md!!.lines()
        assertEquals("| # | job | state | pid |", lines[0])
        assertEquals("|---|---|---|---|", lines[1])
        assertEquals("| 0 | brain | not loaded | 0 |", lines[2])
        assertEquals("| 1 | bot | idle | 12345 |", lines[3])
        assertEquals(4, lines.size)
    }

    @Test
    fun theProseAroundATableStaysText() {
        val md = NuTable.toRichMarkdown("before\n$table\nafter")
        assertTrue(md != null, "a mixed body must convert")
        val body = md!!
        assertTrue(body.startsWith("before"), body)
        assertTrue(body.trimEnd().endsWith("after"), body)
        assertTrue("| 0 | brain | not loaded | 0 |" in body, body)
    }

    @Test
    fun twoTablesBothConvert() {
        val md = NuTable.toRichMarkdown("$table\nbetween\n$table")
        assertEquals(2, md!!.lines().count { it.startsWith("|---") }, md)
    }

    // THE REBUILD LAW: nu wraps a long cell onto a continuation line (empty
    // head, text further right — the healthcheck's `detail` column). The row
    // is REBUILT, so the answer stays a native Telegram table instead of the
    // <pre> wall the owner saw.
    @Test
    fun aWrappedCellIsMergedBackIntoItsRow() {
        val wrapped = """
            │ # │ name                    │
            ├───┼─────────────────────────┤
            │ 0 │ a very long value that  │
            │   │ continues here          │
            │ 1 │ short                   │
        """.trimIndent()
        val md = NuTable.toRichMarkdown(wrapped)
        assertTrue(md != null, "a wrapped row must be rebuilt, not refused")
        val lines = md!!.lines()
        assertEquals("| # | name |", lines[0])
        assertEquals("| 0 | a very long value that continues here |", lines[2])
        assertEquals("| 1 | short |", lines[3])
    }

    @Test
    fun plainOutputWithoutATableAnswersNull() {
        assertNull(NuTable.toRichMarkdown("just some words\nand more words"))
        assertNull(NuTable.toRichMarkdown(""))
    }

    @Test
    fun aPipeInsideACellIsEscaped() {
        val withPipe = """
            │ # │ expr    │
            ├───┼─────────┤
            │ 0 │ a | b   │
        """.trimIndent()
        val md = NuTable.toRichMarkdown(withPipe)
        assertTrue(md!!.contains("a \\| b"), md)
    }

    // THE THIN-THEME LAW (measured live, 24/11): the healthcheck renders with
    // table.mode = "thin" (`┌ └`), and prose sits right above the table. The
    // borders must be consumed AND the table must own its paragraph, or
    // Telegram shows literal pipes.
    @Test
    fun aThinTableAfterProseKeepsItsOwnParagraph() {
        val out = """
            == battery ==
            ┌────┬───────┬──────┬─────────┐
            │  # │ state │ leg  │ detail  │
            ├────┼───────┼──────┼─────────┤
            │  0 │ ok    │ boot │ a value │
            └────┴───────┴──────┴─────────┘
            11 ok · 0 fail
        """.trimIndent()
        val md = NuTable.toRichMarkdown(out)!!
        assertTrue("\n\n| # | state | leg | detail |" in md, md)
        assertFalse(md.contains('┌'), md)
        assertFalse(md.contains('└'), md)
        assertTrue(md.endsWith("11 ok · 0 fail"), md)
    }

    // THE SOFT-BREAK LAW: markdown folds single newlines into spaces; every
    // prose line of a run keeps a CommonMark hard break so the phone shows the
    // terminal's lines, not one long paragraph.
    @Test
    fun consecutiveProseLinesGetHardBreaks() {
        val out = "one\ntwo\n$table"
        val md = NuTable.toRichMarkdown(out)!!
        assertTrue(md.startsWith("one\\\ntwo\n\n| # |"), md)
    }

    // nu pads cells with runs of spaces (its canvas); in a native table that
    // is a gap, not data.
    @Test
    fun paddingSpacesInsideACellCollapse() {
        val padded = """
            │ # │ who              │
            ├───┼──────────────────┤
            │ 0 │ a    b     c     │
        """.trimIndent()
        val md = NuTable.toRichMarkdown(padded)!!
        assertTrue("| 0 | a b c |" in md, md)
    }
}
