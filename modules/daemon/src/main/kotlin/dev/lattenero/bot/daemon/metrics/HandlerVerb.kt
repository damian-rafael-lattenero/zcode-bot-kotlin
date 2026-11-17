package dev.lattenero.bot.daemon.metrics

/**
 * The dispatch verbs the handler timer tags with — ONE bounded vocabulary
 * derived from the update itself, so the metric never invents a name and
 * the label cardinality stays the dispatcher's own list. PURE: the wiring
 * reads it around a handler, the suite pins it.
 */
object HandlerVerb {

    /** A text update's verb: the command token (`/tasks`), or `text` for free words. */
    fun ofText(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith('/')) return TEXT
        // Telegram appends @BotName in groups — the verb is the bare command
        val token = trimmed.substringBefore(' ').substringBefore('@').lowercase()
        // a lone "/" is the slash without a command: it rides as free text
        return if (token.isBlank() || token == "/") TEXT else token
    }

    /** A tap's verb: the callback prefix (`t:`), or a bounded `cb:<data>` for bare data. */
    fun ofCallback(data: String): String {
        val prefix = data.substringBefore(':')
        return if (':' in data && prefix.isNotEmpty()) {
            "${prefix.lowercase()}:"
        } else {
            CALLBACK_PREFIX + data.take(BARE_CAP)
        }
    }

    const val TEXT: String = "text"
    const val ATTACHMENT: String = "attachment"
    private const val CALLBACK_PREFIX = "cb:"
    private const val BARE_CAP = 12
}
