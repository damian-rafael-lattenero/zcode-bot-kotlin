package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText

/**
 * THE OUTPUT PAGER (the owner, 3/11): a shell's output is TOO VERBOSE for
 * the chat — it travels COMPRESSED behind one button and opens in pages of
 * NUMBERED lines. The numbering is the owner's other law: every line the
 * bot shows carries its real position, so a snippet is never anonymous
 * («no sé bien lo que estoy leyendo a qué línea corresponde»).
 *
 * Pure: text in → header + pages out. The state that owns message ids and
 * pages is the fold's ([RenderState.boxes], U5b); this object never touches
 * Telegram.
 */
object ToolOutputPages {

    /** One collapsed output: the header (always visible) + numbered pages.
     *  [language] fences the pages (`bash` → Telegram's code highlight,
     *  v2 §5; null = the plain `<pre>` the non-shell pages always had). */
    data class Pages(val header: String, val pages: List<String>, val language: String? = null)

    /** The page budget — well under Telegram's 4096 with the header aboard. */
    const val PAGE_CP = 2800

    /** The page ceiling — an output this long is beyond a phone reply. */
    const val MAX_PAGES = 60

    /** Below this, a file body is not worth a tap (bash output always is). */
    const val INLINE_READ_CP = 800

    /**
     * Number every line (1-based; [startLine] for a read begun at an
     * offset) and split into pages of at most [pageCp] code points. A
     * single line longer than a page is HARD-SPLIT — its continuations
     * carry a blank number column — rather than truncated: the owner reads
     * the whole output, page by page.
     */
    fun pages(text: String, startLine: Int = 1, pageCp: Int = PAGE_CP): List<String> {
        val numbered = PyText.splitLines(text).flatMapIndexed { i, line -> fragment(line, i + startLine, pageCp) }
        val pages = mutableListOf<String>()
        val current = StringBuilder()
        for (line in numbered) {
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
            val rest = "✂️ el resto del output no entra en Telegram — corré el comando en la Mac"
            return pages.take(MAX_PAGES) + listOf(rest)
        }
        return pages.ifEmpty { listOf("") }
    }

    /**
     * The collapsed header's honest subtitle — the count always speaks. A
     * PATH-shaped detail elides per the filename law (v2 §6 — the basename
     * + extension never cut); anything else (a command) previews as today.
     */
    fun headerOf(kind: String, detail: String, lines: Int): String {
        val shown = when {
            detail.isNotEmpty() && detail.none { it.isWhitespace() } && '/' in detail ->
                PyText.elidePath(detail, HEADER_DETAIL_CP)

            else -> PyText.preview(detail, HEADER_DETAIL_CP)
        }
        val suffix = if (shown.isNotEmpty()) " — <code>${PyText.esc(shown)}</code>" else ""
        val noun = if (lines == 1) "1 línea" else "$lines líneas"
        return "$kind$suffix · $noun\n<i>📜 tocar para ver</i>"
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
