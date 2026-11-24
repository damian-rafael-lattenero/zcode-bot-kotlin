package dev.lattenero.bot.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
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

    // THE CONSERVATIVE LAW: nu wraps a long cell onto a continuation line, and
    // that line carries fewer walls — converting it would move text into the
    // wrong column. The whole run falls back (null = code block upstream).
    @Test
    fun aWrappedCellRefusesTheWholeConversion() {
        val wrapped = """
            │ # │ name                    │
            ├───┼─────────────────────────┤
            │ 0 │ a very long value that  │
            │   │ continues here          │
        """.trimIndent()
        assertNull(NuTable.toRichMarkdown(wrapped))
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
}
