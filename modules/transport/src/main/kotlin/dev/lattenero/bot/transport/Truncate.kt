package dev.lattenero.bot.transport

import dev.lattenero.bot.contracts.PyText

/** limits.py CH_REPLY: the per-message cap of send() and the worker's long replies. */
const val CH_REPLY: Int = 3900

private val TAG_RE = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)[^>]*>")

/**
 * html_truncate (utils/formatting.py, deep audit 20/10): cuts an HTML
 * message to Telegram's cap WITHOUT breaking parsing — send()'s plain
 * truncation could land with an open <pre> and Telegram rejects the
 * WHOLE message with 400 «Unclosed tag» (and the outbox retries it
 * forever). Also trims an entity (&amp;) or a tag left half-open by the
 * cut, and closes whatever tags remain open, innermost first.
 */
fun htmlTruncate(text: String, cap: Int): String {
    if (PyText.cpLength(text) <= cap) return text
    var t = PyText.take(text, cap)
    val lt = t.lastIndexOf('<')
    val gt = t.lastIndexOf('>')
    if (lt > gt) t = t.substring(0, lt) // a tag was left half-open (<pr…): drop it
    val amp = t.lastIndexOf('&')
    val sem = t.lastIndexOf(';')
    if (amp > sem) t = t.substring(0, amp) // a split entity (&am…): drop it
    val openTags = mutableListOf<String>()
    for (m in TAG_RE.findAll(t)) {
        val name = m.groupValues[TAG_NAME].lowercase()
        if (m.groupValues[TAG_CLOSING] == "/") {
            val i = openTags.lastIndexOf(name)
            if (i >= 0) openTags.removeAt(i)
        } else if (!m.value.trimEnd('>').endsWith("/")) {
            openTags.add(name)
        }
    }
    return t + openTags.reversed().joinToString("") { "</$it>" }
}

private const val TAG_CLOSING = 1
private const val TAG_NAME = 2
