package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText

/**
 * THE NAME-FIRST LAW (v2 §7 — the owner, 12/11: «las rutas son las largas,
 * nunca llego a ver el nombre del archivo»): a path's detail LEADS with the
 * basename — «LiveTurn.kt · …/channel» — the file's name first, ONE
 * directory of context behind, never the long middle. The basename itself
 * is never cut (the filename law, v2 §6, still owns that); the context
 * tail is capped, and a detail too long for both drops the tail, never
 * the name.
 *
 * Its sibling, THE FIRST-LINE LAW: a MULTILINE inline detail (a command on
 * a live line, a blob anywhere the fence can't ride) shows its first
 * non-blank line — the whitespace-collapsed single-line blob the preview
 * used to speak is dead. The COMPLETE multiline text only ever renders
 * inside a fenced `<pre>` block (the shell close's own bash block, v2 §5).
 *
 * Pure: strings in, strings out — raw (never escaped), so the callers
 * keep owning [PyText.esc] as everywhere else.
 */
internal object PathName {

    /** The whole inline detail's budget (code points), name included. */
    private const val DETAIL_CP = 60

    /**
     * «basename · …/parent» — the name leads. A path without a parent
     * directory (a bare name, a root file) rides alone; a degenerate
     * path (ends in '/', empty) falls back to the filename law's own
     * elision rather than inventing a name.
     */
    // one guard per early exit (no slash / degenerate / no parent / the verdict) — the law's own shape
    @Suppress("ReturnCount")
    fun detail(path: String): String {
        if ('/' !in path) return path // substringBeforeLast without a separator is the WHOLE string — never
        val base = path.substringAfterLast('/')
        if (base.isEmpty()) return PyText.elidePath(path, DETAIL_CP)
        val dir = path.substringBeforeLast('/')
        if (dir.isEmpty() || dir == "/" || dir == ".") return base
        val tail = "…/" + dir.substringAfterLast('/')
        val detail = "$base · $tail"
        return if (PyText.cpLength(detail) <= DETAIL_CP) detail else base
    }

    /**
     * The inline detail's shaping: PATH-shaped (no whitespace, has '/'),
     * name-first per [detail]; anything else previews its FIRST non-blank
     * line — multiline text never collapses into one long line again.
     */
    fun shaped(raw: String, maxCp: Int): String =
        when {
            raw.isEmpty() -> ""
            raw.none { it.isWhitespace() } && '/' in raw -> detail(raw)
            else -> PyText.preview(firstLine(raw), maxCp)
        }

    /** The first non-blank line (the blank leading lines never speak). */
    private fun firstLine(raw: String): String =
        raw.lines().firstOrNull { it.isNotBlank() } ?: ""
}
