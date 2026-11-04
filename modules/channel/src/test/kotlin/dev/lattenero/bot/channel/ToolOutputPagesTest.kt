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

    // ---- live card v2 §5/§6: the bash fence and the filename law ----

    @Test
    fun shellPagesRideTheBashFenceNonShellKeepPlain() {
        val pages = listOf("   1│ uno", "   2│ dos")
        val bash = ToolOutputPages.renderPage("H", pages, 0, "bash")
        assertTrue("<pre><code class=\"language-bash\">   1│ uno</code></pre>" in bash, bash)
        assertEquals("H\n<pre>   1│ uno</pre>", ToolOutputPages.renderPage("H", pages, 0), "non-shell: the plain pre")
        // the esc law holds inside the fence too
        val evil = ToolOutputPages.renderPage("H", listOf("   1│ <b>&</b>"), 0, "bash")
        assertTrue("&lt;b&gt;&amp;&lt;/b&gt;" in evil, evil)
    }

    @Test
    fun theHeaderElidesAPathButKeepsThePreviewForACommand() {
        // 162 cp of path against the 120 budget: dirs elide, basename whole
        val deep = "/verylongpath".repeat(10) + "/file-name.kt"
        val header = ToolOutputPages.headerOf("📖 <b>Read</b>", deep, 3)
        assertTrue("…/" in header, header)
        assertTrue("file-name.kt</code>" in header, "basename + extension byte-for-byte: $header")
        assertTrue(deep !in header, "the dir chain actually elided")
        // a COMMAND is not a path — it previews as always
        val cmd = ToolOutputPages.headerOf("⚡ <b>Bash</b>", "npm run build --verbose", 5)
        assertTrue("<code>npm run build --verbose</code>" in cmd)
    }
}
