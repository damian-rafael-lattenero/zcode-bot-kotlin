package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.PngRender
import dev.lattenero.bot.channel.PngShots
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * THE 📸 TAP'S EDGE — the registry holds the text, this turns it into an
 * image and hands the bytes to Telegram.
 *
 * The meaning: `shoot(id)` is a small transaction — look up the shot, write
 * it to a temp source, run silicon (absolute path: launchd's PATH is
 * narrow), upload the PNG, delete both temp files. A successful send answers
 * null (the image IS the feedback); every miss answers the honest words the
 * tap speaks as a toast.
 *
 * The one graceful degradation: syntect may not know a slug the fleet emits
 * (`CodeLang` passes unknown words through on purpose) — silicon exits
 * non-zero, and the retry drops `--language`: plain monospace, still themed,
 * never a lost tap.
 */
class PngTaps(
    private val shots: PngShots,
    /** The upload edge — production binds it to `TgApi.upload("sendPhoto", …)`. */
    private val upload: suspend (fields: Map<String, String>, file: File) -> Unit,
    /** The exec edge — production binds it to silicon via ProcessBuilder. */
    private val exec: (argv: List<String>) -> Int = { argv ->
        ProcessBuilder(argv).redirectErrorStream(true).start().waitFor()
    },
    private val chatId: () -> String,
    private val tmpDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Renders and sends the shot behind [id]. Null = the photo flew; a
     * string = what the tap says out loud (a stale id, a failed render, a
     * rejected upload).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount") // one exit per honest miss
    suspend fun shoot(id: Int): String? {
        val shot = shots.get(id) ?: return PngShots.MISS
        val stamp = clock()
        val source = File(tmpDir, "png-$id-$stamp.txt")
        val output = File(tmpDir, "png-$id-$stamp.png")
        return try {
            // WHOLE LINES, ALWAYS (the owner, 25/11): the image grows as wide
            // as its longest line — silicon draws the line complete and the
            // owner zooms. Wrapping is the LAST RESORT, applied only when the
            // photo could not exist at that width (the diff drawer's own law).
            val body = PngRender.fit(shot.text)
            val compact = body.count { it == '\n' } + 1 > PngRender.COMPACT_AFTER
            source.writeText(body)
            // A DIFF rides the SIDE-BY-SIDE drawer (the owner, 25/11): two
            // columns and the changed middle, not silicon's unified stream.
            // A DIFF rides the SIDE-BY-SIDE drawer (the owner, 25/11): two
            // columns and the changed middle, not silicon's unified stream.
            when (imageOf(shot, source, output, compact)) {
                Drawn.IMAGE -> Unit
                // THE SILENT ZERO (measured 25/11): silicon exits 0 and writes
                // NOTHING when it cannot read its input — the exit code alone
                // is not a receipt, so the FILE is the verdict
                Drawn.NONE -> {
                    logger.warn {
                        "png $id: no image (file=${output.exists()} " +
                            "source=${source.exists()} bytes=${source.length()})"
                    }
                    return RENDER_FAILED
                }
                // THE PHOTO CEILINGS (10 MB, w+h ≤ 10 000, ratio ≤ 20): every
                // whole-line scale was refused, so the text wraps and silicon
                // draws once more — a wrapped line beats a lost tap
                Drawn.TOO_BIG -> {
                    if (!wrapped(shot, source, output, compact)) {
                        logger.warn { "png $id: the wrapped retry wrote no image" }
                        return RENDER_FAILED
                    }
                    if (!PngRender.fitsPhoto(output.readBytes())) return TOO_BIG
                }
            }
            // THE PHOTO CEILINGS (10 MB, w+h ≤ 10 000, ratio ≤ 20): only when
            // the whole-line render passes them does the text wrap and the
            // render run again — a wrapped line beats a lost tap
            if (!PngRender.fitsPhoto(output.readBytes())) {
                if (!wrapped(shot, source, output, compact)) {
                    logger.warn { "png $id: the wrapped retry wrote no image" }
                    return RENDER_FAILED
                }
                if (!PngRender.fitsPhoto(output.readBytes())) return TOO_BIG
            }
            upload(fieldsOf(id), output)
            null
        } catch (e: CancellationException) {
            throw e // structured concurrency: a shutdown is never an upload failure
        } catch (e: Exception) {
            logger.warn(e) { "png shot $id: upload failed" }
            UPLOAD_FAILED.format(e.message ?: e::class.simpleName.orEmpty())
        } finally {
            source.delete()
            output.delete()
        }
    }

    /**
     * THE IMAGE: a diff rides the side-by-side drawer; everything else rides
     * silicon at the LARGEST scale Telegram still accepts (the resolution
     * ladder: 3x when it fits, 2x next, 1x last). True = a legal file landed.
     */
    /** What the image attempt answered: a legal photo, a photo too big, or nothing. */
    private enum class Drawn { IMAGE, TOO_BIG, NONE }

    /**
     * THE IMAGE: a diff rides the side-by-side drawer; everything else rides
     * silicon at the LARGEST scale Telegram still accepts (the resolution
     * ladder: 3x when it fits, 2x next, 1x last).
     */
    @Suppress("ReturnCount") // three honest exits: the diff, a legal scale, or the verdict
    private fun imageOf(shot: PngRender.Shot, source: File, output: File, compact: Boolean): Drawn {
        if (shot.language == DIFF_LANGUAGE) {
            return if (DiffPng.render(shot.text, output)) Drawn.IMAGE else Drawn.NONE
        }
        var anyFile = false
        for (scale in PngRender.MAX_SCALE downTo 1) {
            val drew = draw(shot, source, output, compact, scale) == 0 && output.exists()
            if (!drew) continue
            anyFile = true
            if (PngRender.fitsPhoto(output.readBytes())) return Drawn.IMAGE
        }
        return if (anyFile) Drawn.TOO_BIG else Drawn.NONE
    }

    /**
     * ONE render at [scale]; silicon's exit code, with the last-resort retry
     * on the plain grammar when the slug is one silicon does not know (a bad
     * language must never cost the tap).
     */
    private fun draw(shot: PngRender.Shot, source: File, output: File, compact: Boolean, scale: Int): Int {
        output.delete() // a stale file from the previous scale is not a receipt
        val code = exec(PngRender.arguments(shot, source.absolutePath, output.absolutePath, compact, scale))
        if (code == 0 && output.exists()) return 0
        val plain = shot.copy(language = null)
        return exec(PngRender.arguments(plain, source.absolutePath, output.absolutePath, compact, scale))
    }

    /**
     * THE LAST RESORT (the owner, 25/11): the whole-line render came out past
     * the photo ceilings, so the text wraps where the image cannot exist and
     * silicon draws again. True = a file landed.
     */
    private fun wrapped(shot: PngRender.Shot, source: File, output: File, compact: Boolean): Boolean {
        source.writeText(PngRender.fit(PngRender.wrap(shot.text)))
        val code = exec(PngRender.arguments(shot, source.absolutePath, output.absolutePath, compact))
        return code == 0 && output.exists()
    }

    /**
     * The send's plain fields — plus the ANCHOR when the button's message is
     * known: the photo replies TO THAT MESSAGE, so Telegram quotes the box's
     * title/command right above the image and the picture never drowns at
     * the bottom of the thread. `allow_sending_without_reply` keeps the
     * upload alive if the owner deleted the box meanwhile.
     */
    private fun fieldsOf(id: Int): Map<String, String> = buildMap {
        put("chat_id", chatId())
        put("disable_notification", "true")
        shots.anchor(id)?.let {
            put("reply_parameters", """{"message_id":$it,"allow_sending_without_reply":true}""")
        }
    }

    private companion object {
        /** The diff's own slug — the side-by-side drawer owns it, never silicon. */
        const val DIFF_LANGUAGE = "diff"

        const val RENDER_FAILED =
            "🖼️ No pude dibujar ese bloque (silicon falló) — el texto de arriba sigue siendo la verdad."
        const val TOO_BIG =
            "🖼️ Ese bloque no entra como foto (muy alto o muy ancho para Telegram) — el texto de arriba es la verdad."
        const val UPLOAD_FAILED = "📡 El PNG no voló: %s — probá de nuevo en un momento."
    }
}
