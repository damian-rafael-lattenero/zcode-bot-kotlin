package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText

/**
 * THE OUTPUT PAGER (the owner, 3/11): a shell's output is TOO VERBOSE for
 * the chat — it travels COMPRESSED behind one button and opens in pages of
 * NUMBERED lines. The numbering is the owner's other law: every line the
 * bot shows carries its real position, so a snippet is never anonymous
 * («I can't tell what I am reading, which line it corresponds to» [translated]).
 *
 * Pure: text in → header + pages out. The state that owns message ids and
 * pages is the fold's ([RenderState.boxes], U5b); this object never touches
 * Telegram.
 */
object ToolOutputPages {

    /** One collapsed output: the header (always visible) + numbered pages.
     *  [language] fences the pages (`bash` → Telegram's code highlight,
     *  v2 §5; null = the plain `<pre>` the non-shell pages always had).
     *  [png] is the image door's recipe (25/11): the code/command behind
     *  the box, rendered by silicon only when the owner taps 📸. */
    data class Pages(
        val header: String,
        val pages: List<String>,
        val language: String? = null,
        val png: PngRender.Shot? = null,
        /** The SECOND door (25/11): the command a shell box ran. */
        val pngCommand: PngRender.Shot? = null,
    )

    /** The page budget — well under Telegram's 4096 with the header aboard. */
    const val PAGE_CP = 2800

    /** The page ceiling — an output this long is beyond a phone reply. */
    const val MAX_PAGES = 60

    /** Below this, a file body is not worth a tap (bash output always is). */
    const val INLINE_READ_CP = 800

    /**
     * The close's own command fence cap (code points) — esc-safe under
     * Telegram's 4096 even in the pathological all-`&` case; a longer
     * command shows its head and points at the feed (the open law already
     * spoke it whole — TranscriptFeed's long-command law).
     */
    const val COMMAND_CLOSE_CP = 1000

    /**
     * Split into pages of at most [pageCp] code points. [numbered] = true adds
     * the 1-based gutter ([startLine] for a read begun at an offset) — the
     * OUTPUT pages' law. `false` is PURE CODE (the owner, 24/11): the file's
     * lines verbatim, no gutter, so a long line scrolls instead of wrapping
     * into a numbered wall. A single line over a page is still HARD-SPLIT
     * (the message cap), never truncated.
     */
    fun pages(text: String, startLine: Int = 1, pageCp: Int = PAGE_CP, numbered: Boolean = true): List<String> {
        val lines = if (numbered) {
            PyText.splitLines(text).flatMapIndexed { i, line -> fragment(line, i + startLine, pageCp) }
        } else {
            PyText.splitLines(text).flatMap { line -> rawFragment(line, pageCp) }
        }
        val pages = mutableListOf<String>()
        val current = StringBuilder()
        for (line in lines) {
            val sep = if (current.isEmpty()) 0 else 1
            if (current.isNotEmpty() && PyText.cpLength(current.toString()) + sep + PyText.cpLength(line) > pageCp) {
                pages += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
        }
        if (current.isNotEmpty()) pages += current.toString()
        if (pages.size > MAX_PAGES) {
            val rest = "✂️ the rest of the output does not fit in Telegram — run the command on the Mac"
            return pages.take(MAX_PAGES) + listOf(rest)
        }
        return pages.ifEmpty { listOf("") }
    }

    /**
     * The collapsed header's honest subtitle — the count always speaks. A
     * PATH-shaped detail elides per the name-first law (v2 §7 — the file's
     * name leads, one directory of context behind); anything else (a
     * command) previews its first line only (v2 §7 — multiline never
     * collapses into one blob).
     *
     * [fence] (a SHELL command) never rides inline: it travels as its own
     * fenced bash block under the header — the complete command when it
     * fits [COMMAND_CLOSE_CP], the head + «✂️» when it doesn't (the open
     * already spoke it whole). [language] is that block's fence language.
     */
    fun headerOf(
        kind: String,
        detail: String,
        lines: Int,
        fence: String? = null,
        language: String? = null,
    ): String {
        val noun = if (lines == 1) "1 line" else "$lines lines"
        if (fence != null) {
            // THE SHAPED COMMAND (25/11): the client wraps every code block,
            // so the pipes own the line breaks here — never an arbitrary cut
            val shown = CommandShape.shape(fence)
            val block = when {
                PyText.cpLength(shown) <= COMMAND_CLOSE_CP ->
                    "<pre><code class=\"language-$language\">${PyText.esc(shown)}</code></pre>"

                else ->
                    "<pre><code class=\"language-$language\">" +
                        PyText.esc(PyText.take(shown, COMMAND_CLOSE_CP)) +
                        "</code></pre>\n✂️ … the full command goes in the feed"
            }
            return "$kind · $noun\n<i>📸 tap to see the image</i>\n$block"
        }
        val shown = PathName.shaped(detail, HEADER_DETAIL_CP)
        val suffix = if (shown.isNotEmpty()) " — <code>${PyText.esc(shown)}</code>" else ""
        return "$kind$suffix · $noun\n<i>📸 tap to see the image</i>"
    }

    /**
     * The opened page: the header + the numbered body. Shell pages ride
     * Telegram's bash highlight (v2 §5 — the closest to «fondo negro,
     * letras verdes» a message can honestly get); non-shell pages keep the
     * plain `<pre>`. All content through [PyText.esc], as always.
     */
    fun renderPage(header: String, pages: List<String>, index: Int, language: String? = null): String {
        val page = PyText.esc(pages.getOrElse(index) { "" })
        val body = when (language) {
            null -> "<pre>$page</pre>"
            else -> "<pre><code class=\"language-$language\">$page</code></pre>"
        }
        return "$header\n$body"
    }

    /** PURE CODE: the line verbatim, split only when it alone exceeds the page. */
    private fun rawFragment(line: String, pageCp: Int): List<String> {
        if (PyText.cpLength(line) <= pageCp) return listOf(line)
        val out = mutableListOf<String>()
        var rest = line
        while (rest.isNotEmpty()) {
            val piece = PyText.take(rest, pageCp)
            out += piece
            rest = rest.substring(piece.length)
        }
        return out
    }

    private fun fragment(line: String, number: Int, pageCp: Int): List<String> {
        val prefix = number.toString().padStart(NUMBER_WIDTH) + NUMBER_SEP
        val room = (pageCp - PyText.cpLength(prefix)).coerceAtLeast(1)
        if (PyText.cpLength(line) <= room) return listOf(prefix + line)
        val out = mutableListOf<String>()
        var rest = line
        var first = true
        while (rest.isNotEmpty()) {
            val piece = PyText.take(rest, room)
            out += (if (first) prefix else BLANK_PREFIX) + piece
            rest = rest.substring(piece.length)
            first = false
        }
        return out
    }

    private const val NUMBER_WIDTH = 4
    private const val NUMBER_SEP = "│ "
    private const val BLANK_PREFIX = "    │ "
    private const val HEADER_DETAIL_CP = 120
}
