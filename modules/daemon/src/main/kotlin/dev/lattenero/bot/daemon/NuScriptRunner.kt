package dev.lattenero.bot.daemon

import dev.lattenero.bot.contracts.PyText
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * The pinned-nu subprocess runner — the SECOND machine command's arrival
 * (/ram_clean was the first) folded the shape into one home: the pinned
 * interpreter (launchd's PATH is narrow, the VoiceTranscribe law), the
 * budget (the poller is ONE consumer — a stuck script is destroyed, never
 * allowed to starve the feed), the last-run log capture, and the phone
 * render (the script's words escaped in one `<pre>`; when they do not fit,
 * the head AND the tail survive with the cut said — never a silent
 * truncation). The COMMANDS own their words (plan, gate, usage, the
 * timeout/error sentences); this home owns only the shared machinery.
 */
internal object NuScriptRunner {

    /** The pinned interpreter — launchd's PATH is narrow (the VoiceTranscribe law). */
    const val NU = "/opt/homebrew/bin/nu"

    /** The edge's hard ceiling: the dispatch loop is SERIAL — a stuck script never starves the feed. */
    const val BUDGET_SECONDS = 90L

    /** One subprocess run's outcome, tagged — the caller renders per arm. */
    internal sealed interface Run {
        /** The script finished: its exit code, its duration, its merged output. */
        data class Ran(val exit: Int, val tookMs: Long, val out: String) : Run

        /** The budget burned — the script was destroyed. */
        data object TimedOut : Run

        /** The spawn itself failed (missing binary, unwritable log). */
        data class Errored(val reason: String) : Run
    }

    /**
     * The subprocess's PATH: launchd's default is narrow (/usr/bin:/bin:
     * /usr/sbin:/sbin) and the fleet's homebrew tools — and each other
     * (colima shells out to limactl, measured under the narrow PATH — the
     * colima leg fails honestly, the fix never fires) — live in
     * /opt/homebrew/bin. The scripts ALSO resolve their own entry points
     * absolutely (the ram-clean ollama law); this is the second net.
     */
    const val LAUNCHD_SAFE_PATH = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

    /**
     * Run [script] with [argv]: merged stdout+stderr, the full words land
     * in [logFile] (last run = the whole truth), the outcome comes back
     * tagged. Never throws — a spawn failure is an [Run.Errored]; only
     * cancellation rides through (no lies on shutdown).
     */
    @Suppress("TooGenericExceptionCaught") // the spawn edge: any failure speaks honestly, never crashes the poller
    fun run(script: String, argv: List<String>, logFile: File): Run = try {
        logFile.parentFile?.mkdirs()
        val startedAt = System.nanoTime()
        val process = ProcessBuilder(listOf(NU, script) + argv)
            .redirectErrorStream(true)
            .apply { environment()["PATH"] = LAUNCHD_SAFE_PATH }
            .start()
        if (!process.waitFor(BUDGET_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            Run.TimedOut
        } else {
            val out = process.inputStream.bufferedReader().readText()
            val tookMs = (System.nanoTime() - startedAt) / NANOS_PER_MS
            logFile.writeText(out)
            Run.Ran(process.exitValue(), tookMs, out)
        }
    } catch (e: CancellationException) {
        throw e // cancellation is not a script failure — shutdown stays honest
    } catch (e: Exception) {
        logger.warn(e) { "nu script edge failed ($script): ${e.message}" }
        Run.Errored(e.message ?: e::class.simpleName.orEmpty())
    }

    /**
     * The phone reply: one header line (emoji + label + mode · seconds, or
     * the exit code when the script itself failed), the script's words in
     * one `<pre>`, and — only when they do not fit — the cut said with the
     * full log's path. Overlong output keeps the HEAD and the TAIL (the
     * verdict line and the receipts live at the end): the middle is what
     * the log is for.
     */
    internal fun render(
        emoji: String,
        label: String,
        mode: String,
        exit: Int,
        tookMs: Long,
        out: String,
        logPath: String,
    ): String {
        val head = if (exit == 0) {
            "$emoji <b>$label</b> ($mode) · ${tookMs / 1000}s\n"
        } else {
            "⚠️ $label ($mode) salió con código $exit — el log dice el resto:\n"
        }
        val body = PyText.esc(out.trimEnd())
        if (PyText.cpLength(body) <= BODY_CAP) return head + "<pre>" + body + "</pre>"
        val headPart = PyText.take(body, BODY_CAP * HEAD_SHARE / 100)
        val tailPart = takeTail(body, BODY_CAP * (100 - HEAD_SHARE) / 100)
        return head + "<pre>" + headPart + "\n…✂️…\n" + tailPart + "</pre>" +
            "\n✂️ no entró entero — log completo: <code>" + PyText.esc(logPath) + "</code> (/file lo trae)"
    }

    /** The LAST [n] code points — the tail half of the capped render. */
    private fun takeTail(s: String, n: Int): String {
        val total = PyText.cpLength(s)
        if (n >= total) return s
        return s.substring(s.offsetByCodePoints(0, total - n))
    }

    /** The `<pre>` body's HTML budget (DIFF_REPLY_CAP's law: the reply has a roof). */
    private const val BODY_CAP = 3300

    /** How the capped budget splits: the head earns more (tables), the tail keeps verdicts + receipts. */
    private const val HEAD_SHARE = 60

    /** The pump clock's unit step: nanoTime is monotonic, the metric speaks ms. */
    private const val NANOS_PER_MS = 1_000_000L
}
