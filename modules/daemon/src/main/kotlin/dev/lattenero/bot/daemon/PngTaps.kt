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
            source.writeText(shot.text)
            val first = exec(PngRender.arguments(shot, source.absolutePath, output.absolutePath))
            val rendered = if (first == 0) {
                true
            } else {
                // the retry drops the language: a slug silicon does not know
                // must never cost the tap
                exec(PngRender.arguments(shot.copy(language = null), source.absolutePath, output.absolutePath)) == 0
            }
            if (!rendered) return RENDER_FAILED
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
        const val RENDER_FAILED =
            "🖼️ No pude dibujar ese bloque (silicon falló) — el texto de arriba sigue siendo la verdad."
        const val UPLOAD_FAILED = "📡 El PNG no voló: %s — probá de nuevo en un momento."
    }
}
