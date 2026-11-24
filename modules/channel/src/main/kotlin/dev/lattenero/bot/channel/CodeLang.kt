package dev.lattenero.bot.channel

/**
 * CODE LANGUAGES (24/11): Telegram apps apply syntax highlighting when a code
 * block declares its language (`<pre><code class="language-X">` or a rich
 * `pre` block's `language`). The bot already speaks those tags for shell
 * fences; this is the ONE resolver that turns a file path or a fence word
 * into the canonical slug every renderer (pager, file viewer, voice) uses.
 *
 * Unknown extensions answer `null` — a bare `<pre>` is honest; the wrong
 * highlighter is noise. A known alias (`kt`, `sh`, `yml`…) maps to the
 * canonical name Telegram knows.
 */
object CodeLang {

    /**
     * THE SHELL'S LANGUAGE (the owner, 24/11): this fleet's "Bash" tool runs
     * NUSHELL — the code blocks declare `nu`, never `bash`.
     */
    const val SHELL = "nu"

    /** The file's language from its path: extension first, then the known extension-less names. */
    fun ofPath(path: String?): String? = path
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
        ?.let { name ->
            BY_FILENAME[name.lowercase()] ?: BY_EXTENSION[name.substringAfterLast('.', "").lowercase()]
        }

    /**
     * A fence's or a tool's own language word → the canonical slug. A word
     * that is already canonical passes through; an unknown word is KEPT (the
     * client shows the label even without a highlighter) — only a blank
     * answers null.
     */
    fun normalize(raw: String?): String? {
        val word = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return ALIASES[word] ?: word
    }

    /** The extensions the fleet writes most: Kotlin, Nushell, shell, config, docs. */
    private val BY_EXTENSION: Map<String, String> = mapOf(
        "kt" to "kotlin", "kts" to "kotlin",
        "nu" to "nu",
        "sh" to "bash", "bash" to "bash", "zsh" to "bash",
        "py" to "python", "rb" to "ruby",
        "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript",
        "ts" to "typescript", "tsx" to "typescript", "jsx" to "javascript",
        "java" to "java", "go" to "go", "rs" to "rust", "swift" to "swift",
        "c" to "c", "h" to "c", "cpp" to "cpp", "hpp" to "cpp", "cc" to "cpp",
        "cs" to "csharp", "php" to "php", "scala" to "scala", "dart" to "dart",
        "json" to "json", "yaml" to "yaml", "yml" to "yaml", "toml" to "toml",
        "xml" to "xml", "html" to "html", "css" to "css", "sql" to "sql",
        "md" to "markdown", "markdown" to "markdown",
        "diff" to "diff", "patch" to "diff",
        "gradle" to "gradle", "groovy" to "groovy",
        "lua" to "lua", "pl" to "perl", "r" to "r",
        "ps1" to "powershell", "dockerfile" to "docker",
    )

    /** Extension-less names that ARE the language (Makefile, Dockerfile, Jenkinsfile). */
    private val BY_FILENAME: Map<String, String> = mapOf(
        "dockerfile" to "docker",
        "makefile" to "make",
        "jenkinsfile" to "groovy",
        "cmakelists.txt" to "cmake",
    )

    /** The short words a fence or a tool name uses → the canonical slug. */
    private val ALIASES: Map<String, String> = mapOf(
        "kt" to "kotlin", "kts" to "kotlin",
        "sh" to "bash", "shell" to "bash", "zsh" to "bash",
        "js" to "javascript", "ts" to "typescript",
        "py" to "python", "rb" to "ruby",
        "yml" to "yaml", "md" to "markdown",
        "rs" to "rust", "cs" to "csharp", "c++" to "cpp",
    )

    /**
     * The language of a STREAMING tail (the live card's answer preview): the
     * LAST fence marker decides. A marker WITH a word opens a block that is
     * still being written → that language; a bare ` ``` ` closes one (the
     * words after it are prose) → null. This is correct even when the tail
     * starts mid-block: a closing fence is the last marker in both views.
     */
    fun ofStreamingTail(text: String): String? {
        val marker = text.lines().lastOrNull { it.trimStart().startsWith("```") } ?: return null
        val word = marker.trimStart().removePrefix("```").trim().takeWhile { !it.isWhitespace() }
        return normalize(word)
    }
}
