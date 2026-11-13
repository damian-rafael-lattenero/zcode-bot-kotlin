package dev.lattenero.bot.channel

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * THE VOICE LAW (13/11, the owner's live drill: the voice note arrived as
 * a FILE, not as words): an audio attachment is HEARD, not just stored —
 * the local whisper speaks it first and the transcript rides as the
 * prompt's REAL text (the audio still travels as the file for the agent's
 * own tools).
 *
 * The engine is the machine's own `uvx whisper-ctranslate2` (proven live:
 * it needs no system ffmpeg — PyAV carries its own — and auto-detects the
 * clip's language). A transcription that fails or takes too long degrades
 * HONESTLY: the caption rides alone, the log speaks, nothing invented.
 *
 * Pure at the merge: [promptOf] decides what the prompt says; the
 * subprocess is the single effect, confined behind [VoiceTranscriber].
 */
fun interface VoiceTranscriber {
    suspend fun transcribe(file: File): String?
}

object VoiceTranscribe {

    private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger("voice-transcribe")

    /** The pinned interpreter (launchd's PATH is narrow — never bare `uvx`). */
    private const val UVX = "/Users/lattenero/.local/bin/uvx"

    /** The model proven live on the owner's voice (Spanish, p=1.0, 21 KB in ~5 s). */
    private const val MODEL = "small"

    /** The hard ceiling: the dispatch loop is SERIAL — a voice never starves the feed. */
    private const val BUDGET_SECONDS = 120L

    /**
     * The prompt's words: the transcript IS what the owner said. A caption
     * that also exists rides first (its own paragraph); no transcript =
     * the caption alone (the old world — honest, never invented words).
     */
    fun promptOf(caption: String, transcript: String?): String = when {
        transcript.isNullOrBlank() -> caption
        caption.isBlank() -> transcript
        else -> "$caption\n\n$transcript"
    }

    /** The machine's own transcription: files in, words out, null on any failure. */
    val DEFAULT: VoiceTranscriber = VoiceTranscriber { file -> subprocess(file) }

    @Suppress("TooGenericExceptionCaught") // any tool failure = the honest null, the caller's log speaks
    private fun subprocess(file: File): String? = try {
        val outDir = Files.createTempDirectory("voice-transcribe").toFile()
        val process = ProcessBuilder(
            UVX,
            "whisper-ctranslate2",
            "--model",
            MODEL,
            "--output_format",
            "txt",
            "--output_dir",
            outDir.absolutePath,
            file.absolutePath,
        ).start()
        val finished = process.waitFor(BUDGET_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return null
        }
        File(outDir, file.nameWithoutExtension + ".txt")
            .takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        logger.warn(e) { "voice transcription failed — the caption rides alone" }
        null
    }
}
