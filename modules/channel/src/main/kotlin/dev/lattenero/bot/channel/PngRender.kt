package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText

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
    fun arguments(shot: Shot, source: String, output: String, compact: Boolean = false): List<String> = buildList {
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
        // THE DENSE FONT (the owner, 25/11): a tall block rides a smaller size
        // so one photo carries ~300 lines instead of ~190 (≈28 px/line at 14
        // vs 45 at 26) — the owner zooms, the image still fits the ceiling.
        if (compact) {
            add("--font")
            add(COMPACT_FONT)
        }
        // ALWAYS a language (measured 25/11): silicon cannot detect one from
        // a `.txt` source, so omitting the flag is a silent dead tap
        add("--language")
        add(grammar(shot.language))
        add("--output")
        add(output)
        add(source)
    }

    /**
     * THE LINE BUDGET (the owner, 25/11: the image may show up to a point and
     * then stop): it keeps the first [MAX_LINES] lines and says how many
     * stayed behind — the text message above is always whole.
     */
    fun fit(text: String, maxLines: Int = MAX_LINES): String {
        val lines = text.split('\n')
        if (lines.size <= maxLines) return text
        val rest = lines.size - maxLines
        return lines.take(maxLines).joinToString("\n") + "\n… ($rest líneas más en el texto de arriba)"
    }

    /** Past this many lines the render goes dense. */
    const val COMPACT_AFTER: Int = 100

    /** The dense pass's font — measured on the 25/11 probe (≈28 px per line). */
    const val COMPACT_FONT: String = "Hack=14"

    /** The dense pass's line budget — ~300 lines inside the 10 000-px ceiling. */
    const val MAX_LINES: Int = 300

    /**
     * THE PLAIN GRAMMAR (measured 25/11): silicon cannot detect a language
     * from the `.txt` source and has NO plain-text grammar (`txt`, `text`,
     * `plain` all answer *Unsupported*) — it exits 0, writes nothing, and the
     * tap would die. `log` is the least opinionated grammar it accepts: prose
     * stays near-monospace and the theme still paints it.
     */
    const val PLAIN: String = "log"

    /**
     * The slug silicon understands: `nu`/`nushell` have no syntect grammar →
     * `sh`; a blank one rides [PLAIN] (silicon cannot draw without one); a
     * known slug passes as-is. An unknown slug is NOT filtered here — the
     * edge's retry-without-language is the honest fallback (silicon knows more
     * languages than this object ever will).
     */
    fun grammar(language: String?): String =
        when (val slug = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }) {
            null -> PLAIN
            "nu", "nushell" -> "sh"
            else -> slug
        }

    /**
     * THE LAST RESORT (the owner, 25/11: no image is wrapped): a render is
     * never wrapped up front — the image grows to its longest line and the
     * owner zooms. This runs only when the whole-line render came out past
     * Telegram's photo ceilings, wrapping at [MAX_COLS]: a wrapped line beats
     * a missing image. The text message above keeps the original, copyable,
     * untouched.
     */
    fun wrap(text: String, cols: Int = MAX_COLS): String = text.split('\n').joinToString("\n") { line ->
        if (PyText.cpLength(line) <= cols) {
            line
        } else {
            val pieces = mutableListOf<String>()
            var rest = line
            while (PyText.cpLength(rest) > cols) {
                val piece = PyText.take(rest, cols)
                pieces += piece
                rest = rest.substring(piece.length)
            }
            pieces += rest
            pieces.joinToString("\n")
        }
    }

    /**
     * DOES THE RENDER FIT AS A PHOTO? Telegram's ceilings: ≤10 MB, width +
     * height ≤ 10 000 px, ratio ≤ 20:1. The size comes from the PNG's own
     * IHDR — no guessing. An unreadable header answers true (the upload's
     * rejection stays the honest verdict).
     */
    fun fitsPhoto(png: ByteArray): Boolean {
        val dims = dimensions(png)
        val small = png.size <= MAX_PHOTO_BYTES
        return if (dims == null) {
            // an unreadable header defers to the upload's own verdict
            small
        } else {
            val (width, height) = dims
            small &&
                width + height <= MAX_PHOTO_PIXELS &&
                (width <= 0 || height <= 0 || ratioOf(width, height) <= MAX_PHOTO_RATIO)
        }
    }

    private fun ratioOf(width: Int, height: Int): Double =
        maxOf(width, height).toDouble() / minOf(width, height)

    /** The PNG's width/height from its IHDR chunk (signature + length + type + w + h). */
    fun dimensions(png: ByteArray): Pair<Int, Int>? {
        val looksLikePng = png.size >= IHDR_END && png[0] == PNG_MAGIC[0] && png[1] == PNG_MAGIC[1]
        return if (looksLikePng) beInt(png, IHDR_WIDTH) to beInt(png, IHDR_HEIGHT) else null
    }

    @Suppress("MagicNumber") // the shifts ARE the big-endian layout — naming them hides the math
    private fun beInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    /** The last resort's wrap width — ~1400 px at silicon's default size. */
    const val MAX_COLS: Int = 100

    private const val MAX_PHOTO_PIXELS: Int = 10_000
    private const val MAX_PHOTO_RATIO: Double = 20.0
    private const val MAX_PHOTO_BYTES: Int = 10 * 1024 * 1024
    private const val IHDR_WIDTH: Int = 16
    private const val IHDR_HEIGHT: Int = 20
    private const val IHDR_END: Int = 24
    private val PNG_MAGIC: ByteArray = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte())
}
