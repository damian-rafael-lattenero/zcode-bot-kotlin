package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NuTable: a nu box table becomes Telegram rich HTML (`<table bordered>`),
 * prose keeps its lines for the envelope's `<br>` law, and a table whose
 * shape is doubtful refuses the whole conversion (the caller's `<pre>` wins).
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
    fun aCleanTableBecomesANativeHtmlTable() {
        val html = NuTable.toRichHtml(table)
        assertTrue(html != null)
        assertEquals(
            "<table bordered><tr><th>#</th><th>job</th><th>state</th><th>pid</th></tr>" +
                "<tr><td>0</td><td>brain</td><td>not loaded</td><td>0</td></tr>" +
                "<tr><td>1</td><td>bot</td><td>idle</td><td>12345</td></tr></table>",
            html,
        )
    }

    @Test
    fun theProseAroundATableKeepsItsLinesAndStaysProse() {
        val body = NuTable.toRichHtml("before\n$table\nafter")!!
        assertTrue(body.startsWith("before\n"), body)
        assertTrue(body.trimEnd().endsWith("after"), body)
        assertTrue("<table bordered>" in body, body)
    }

    @Test
    fun twoTablesBothConvert() {
        val html = NuTable.toRichHtml("$table\nbetween\n$table")!!
        assertEquals(2, Regex("<table bordered>").findAll(html).count(), html)
    }

    // THE REBUILD LAW: nu wraps a long cell onto a continuation line (empty
    // head, text further right — the healthcheck's `detail` column). The row
    // is REBUILT so the native table keeps every cell.
    @Test
    fun aWrappedCellIsMergedBackIntoItsRow() {
        val wrapped = """
            │ # │ name                    │
            ├───┼─────────────────────────┤
            │ 0 │ a very long value that  │
            │   │ continues here          │
            │ 1 │ short                   │
        """.trimIndent()
        val html = NuTable.toRichHtml(wrapped)!!
        assertTrue("<td>a very long value that continues here</td>" in html, html)
        assertTrue("<td>short</td>" in html, html)
    }

    // THE THIN-THEME LAW (measured live, 24/11): the healthcheck renders with
    // table.mode = "thin" (`┌ └`) — every theme's edge is a border, and none
    // of them may leak into the prose.
    @Test
    fun aThinTableConsumesEveryBorderLine() {
        val out = """
            == battery ==
            ┌────┬───────┬──────┬─────────┐
            │  # │ state │ leg  │ detail  │
            ├────┼───────┼──────┼─────────┤
            │  0 │ ok    │ boot │ a value │
            └────┴───────┴──────┴─────────┘
            11 ok · 0 fail
        """.trimIndent()
        val html = NuTable.toRichHtml(out)!!
        assertFalse(html.contains('┌'), html)
        assertFalse(html.contains('└'), html)
        assertFalse(html.contains('│'), html)
        assertTrue(html.contains("<td>a value</td>"), html)
        assertTrue(html.trimEnd().endsWith("11 ok · 0 fail"), html)
    }

    // nu pads cells with runs of spaces (its canvas); the value is the words.
    @Test
    fun paddingSpacesInsideACellCollapse() {
        val padded = """
            │ # │ who              │
            ├───┼──────────────────┤
            │ 0 │ a    b     c     │
        """.trimIndent()
        val html = NuTable.toRichHtml(padded)!!
        assertTrue("<td>a b c</td>" in html, html)
    }

    @Test
    fun aWildCellIsHtmlEscaped() {
        val wild = """
            │ # │ expr      │
            ├───┼───────────┤
            │ 0 │ <b>a|b   │
        """.trimIndent()
        val html = NuTable.toRichHtml(wild)!!
        assertTrue("<td>&lt;b&gt;a|b</td>" in html, html)
    }

    @Test
    fun plainOutputWithoutATableAnswersNull() {
        assertNull(NuTable.toRichHtml("just some words\nand more words"))
        assertNull(NuTable.toRichHtml(""))
    }

    @Test
    fun anUnEvenTableRefusesTheWholeConversion() {
        val broken = """
            │ # │ a │ b │
            ├───┼───┼───┤
            │ 0 │ x │
        """.trimIndent()
        assertNull(NuTable.toRichHtml(broken))
    }
}
