package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE OUTPUT PAGER's pure core (the owner, 3/11): every line carries its
 * REAL position, a page never exceeds its budget, a huge line is split —
 * never truncated — and the header says what it is.
 */
class ToolOutputPagesTest {

    @Test
    fun linesAreNumberedFromOne() {
        assertEquals(listOf("   1│ uno\n   2│ dos"), ToolOutputPages.pages("uno\ndos"))
    }

    @Test
    fun aReadStartsAtItsOffset() {
        assertEquals(listOf("   5│ x\n   6│ y"), ToolOutputPages.pages("x\ny", startLine = 5))
    }

    @Test
    fun aPageNeverExceedsItsBudget() {
        val text = (1..100).joinToString("\n") { "línea $it " + "x".repeat(20) }
        val pages = ToolOutputPages.pages(text, pageCp = 300)
        assertTrue(pages.size > 1, "the output needs more than one page")
        pages.forEach { assertTrue(PyText.cpLength(it) <= 300, "page over budget: ${PyText.cpLength(it)}") }
        assertEquals(100, pages.sumOf { it.lines().size }, "no line is lost")
    }

    @Test
    fun aHugeLineIsSplitNotTruncated() {
        val line = "y".repeat(1000)
        val pages = ToolOutputPages.pages(line, pageCp = 100)
        assertTrue(pages.size > 1)
        assertEquals(1000, pages.joinToString("").count { it == 'y' })
    }

    @Test
    fun theHeaderSaysKindDetailAndCount() {
        val header = ToolOutputPages.headerOf("⚡ <b>Bash</b>", "ls -la", 42)
        assertTrue("⚡ <b>Bash</b>" in header)
        assertTrue("<code>ls -la</code>" in header)
        assertTrue("42 líneas" in header)
        assertTrue("tocar para ver" in header)
        assertTrue("1 línea" in ToolOutputPages.headerOf("⚡ <b>Bash</b>", "", 1), "the singular speaks")
    }

    @Test
    fun beyondTheCeilingItSaysTheRestIsNotInTelegram() {
        val text = (1..5000).joinToString("\n") { "x$it" }
        val pages = ToolOutputPages.pages(text, pageCp = 50)
        assertEquals(ToolOutputPages.MAX_PAGES + 1, pages.size)
        assertTrue("no entra en Telegram" in pages.last())
    }
}
