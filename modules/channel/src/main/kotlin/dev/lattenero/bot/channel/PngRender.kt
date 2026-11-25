package dev.lattenero.bot.channel

/**
 * THE PNG SHOT — any structured text rendered as an image on demand (the
 * owner, 25/11: PNGs for code of any language — Nushell, Kotlin, JSON,
 * YAML — in his teal palette).
 *
 * The palette is the owner's pick (probe A): deep teal canvas, dark card,
 * OneHalfDark tokens. The meaning of a shot is a RECIPE — text + language
 * in, argv out; the edge (`PngShots` in the daemon) runs silicon and
 * uploads the bytes. Nothing here touches disk or the network.
 *
 * Honest limit: syntect (silicon's highlighter) has no Nushell grammar, so
 * `nu` rides the Bash syntax — the closest shell, measured on the 25/11
 * samples. Every other slug passes through untouched; an unknown one makes
 * silicon exit non-zero and the edge retries without a language (plain
 * monospace, still themed).
 */
object PngRender {

    /** The owner's palette (A of the 25/11 probe): deep teal + OneHalfDark. */
    const val THEME: String = "OneHalfDark"
    const val BACKGROUND: String = "#0B3B3C"
    const val PAD_HORIZ: Int = 48
    const val PAD_VERT: Int = 34

    /**
     * silicon's binary by ABSOLUTE path: the daemon runs under launchd with
     * a narrow PATH (LAUNCHD_SAFE_PATH does not carry /opt/homebrew/bin).
     */
    const val SILICON: String = "/opt/homebrew/bin/silicon"

    /** One block on its way to an image: the text and its language slug (CodeLang's). */
    data class Shot(val text: String, val language: String?)

    /**
     * silicon's argv for this shot. `--no-line-number`: the text bubble keeps
     * the numbered gutter when it needs one — the image is the pretty view.
     * The file name carries no meaning (the language is explicit).
     */
    fun arguments(shot: Shot, source: String, output: String): List<String> = buildList {
        add(SILICON)
        add("--theme")
        add(THEME)
        add("--background")
        add(BACKGROUND)
        add("--pad-horiz")
        add(PAD_HORIZ.toString())
        add("--pad-vert")
        add(PAD_VERT.toString())
        add("--no-line-number")
        grammar(shot.language)?.let {
            add("--language")
            add(it)
        }
        add("--output")
        add(output)
        add(source)
    }

    /**
     * The slug silicon understands: `nu`/`nushell` have no syntect grammar →
     * `sh`; a known slug passes as-is; a blank one answers null (plain text,
     * no highlighter). An unknown slug is NOT filtered here — the edge's
     * retry-without-language is the honest fallback (silicon knows more
     * languages than this object ever will).
     */
    fun grammar(language: String?): String? =
        when (val slug = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }) {
            null -> null
            "nu", "nushell" -> "sh"
            else -> slug
        }
}
