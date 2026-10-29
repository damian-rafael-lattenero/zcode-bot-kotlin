package dev.lattenero.bot.daemon.spectator

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Python text primitives the renderer is built on, ported exactly —
 * the parity gate diffs these strings byte for byte against what the
 * Python bot sends (utils/formatting.py, messages.py).
 */
// One function per Python primitive — the count is the port's shape.
@Suppress("TooManyFunctions")
internal object PyText {

    /** esc(): HTML for Telegram's parse_mode. Single source, verbatim. */
    fun esc(s: String?): String =
        (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Python len() counts code points; Kotlin's length counts UTF-16 units. */
    fun cpLength(s: String): Int = if (s.isEmpty()) 0 else s.codePointCount(0, s.length)

    /** Python slicing s[:n] — never splits a surrogate pair. */
    fun take(s: String, n: Int): String {
        if (n >= cpLength(s)) return s
        val sb = StringBuilder()
        var i = 0
        var count = 0
        while (i < s.length && count < n) {
            val cp = s.codePointAt(i)
            sb.appendCodePoint(cp)
            i += Character.charCount(cp)
            count++
        }
        return sb.toString()
    }

    /** str.splitlines(): the FULL boundary set, not just \n. */
    private val LINE_BOUNDARIES = charArrayOf(
        '\n', '\r', '\u000B', '\u000C', '\u001C', '\u001D', '\u001E', '\u0085', '\u2028', '\u2029',
    )

    fun splitLines(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c in LINE_BOUNDARIES) {
                out.add(sb.toString())
                sb.clear()
                if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') i++
            } else {
                sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** Python str.strip() / s.isspace() whitespace set. */
    private val PY_WS = charArrayOf(
        ' ', '\t', '\n', '\u000B', '\u000C', '\r', '\u001C', '\u001D', '\u001E', '\u001F', '\u0085',
        '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006',
        '\u2007', '\u2008', '\u2009', '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000',
    )

    fun strip(s: String): String = s.trim { it in PY_WS }

    /** re.sub(r"\s+", " ", s) with Python's \s semantics (the full unicode set). */
    private val PY_WS_RUN = Regex(
        "[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000" +
            "\\u0085\\x1c-\\x1f]+",
    )

    fun preview(s: String, limit: Int): String {
        val collapsed = PY_WS_RUN.replace(s, " ").trim { it in PY_WS }
        return if (cpLength(collapsed) > limit) take(collapsed, limit) + "…" else collapsed
    }

    /** The longest RAW prefix whose ESCAPE fits [capHtml] (audit 20/10): binary search, esc() is monotone. */
    fun htmlClip(text: String, capHtml: Int): String {
        if (cpLength(esc(text)) <= capHtml) return text
        var low = 0
        var high = cpLength(text)
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (cpLength(esc(take(text, mid))) <= capHtml) low = mid else high = mid - 1
        }
        return take(text, low)
    }

    /** str(x or ""): null/JSON-null → "", strings as-is, numbers as their literal. */
    fun pyStr(el: JsonElement?): String = when {
        el == null || el is JsonNull -> ""
        el is JsonPrimitive && el.isString -> el.content
        el is JsonPrimitive -> el.content
        else -> el.toString()
    }

    /** Python truthiness: JSON false/null/""/0 are falsy, everything else truthy. */
    fun truthy(el: JsonElement?): Boolean = when (el) {
        null, JsonNull -> false
        is JsonPrimitive -> when {
            el.isString -> el.content.isNotEmpty()
            el.content == "false" -> false
            el.content == "0" || el.content == "0.0" -> false
            else -> true
        }

        else -> true
    }

    /** json.dumps(x, ensure_ascii=False): separators are ", " and ": ", non-ASCII stays raw. */
    fun jsonDumps(el: JsonElement): String = when (el) {
        is JsonObject -> el.entries.joinToString(", ", "{", "}") { "${pyQuote(it.key)}: ${jsonDumps(it.value)}" }

        is JsonArray -> el.joinToString(", ", "[", "]") { jsonDumps(it) }

        is JsonPrimitive -> if (el.isString) pyQuote(el.content) else el.content
    }

    private fun pyQuote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < SPACE_CP) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }

    private const val SPACE_CP = 0x20
}
