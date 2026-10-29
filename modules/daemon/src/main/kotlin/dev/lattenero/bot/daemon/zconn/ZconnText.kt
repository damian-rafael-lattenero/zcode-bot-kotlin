package dev.lattenero.bot.daemon.zconn

import dev.lattenero.bot.channel.ApiRetryText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The exact strings the Python bot sends on zconn transitions (`zconn._notify`,
 * `_pill_vocab`, `_fmt_dur`). Byte-identical on purpose where the parity gate
 * still binds (down/up): the shadow decisions these render are diffed against
 * what the Python side actually sends. The NEW retry pill (B3b's error
 * inventory) is post-cutover text — Python never had it, the daemon IS the
 * zconn now, so it speaks the fork-UI labels with the REAL countdown.
 */
object ZconnText {

    private const val DEFAULT_MAX_ATTEMPTS = 11L
    private const val RETRY_CAP_S = 1024L
    private const val SECONDS_PER_MINUTE = 60
    private const val RETRY_EXPONENT_CAP = 10L
    private const val ZAI_PREFIX = "z.ai "
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

    /**
     * The fork's pill vocabulary (f85a67f6: pure 2^n to 1024s) in one tail:
     * `attempt 3/11, next retry 8s`. Missing fields fall back to the same
     * defaults Python's `int(x or d)` picks (absent and 0 both fold).
     */
    fun pillVocab(context: JsonObject?): String {
        val attempt = context.longOrDefault("attempt", 1L)
        val maxAttempts = context.longOrDefault("maxAttempts", DEFAULT_MAX_ATTEMPTS)
        return "attempt $attempt/$maxAttempts, next retry ${nextRetryS(attempt)}s"
    }

    fun downText(reason: String, pill: String): String =
        "⚠️ <b>z.ai connection lost</b>\n$reason" + if (pill.isEmpty()) "" else "\n($pill)"

    fun upText(firstDown: Double, upTs: Double): String =
        "✅ <b>z.ai back</b> — the cut lasted ${fmtDur((upTs - firstDown).toInt())}"

    /**
     * The HONEST retry pill (the 1308 lesson): the fork's own reason label
     * (one vocabulary with the desktop — [ApiRetryText]) and the REAL wait.
     * The deadline is the event's timestamp + delayMs (nextAttemptAtMs is
     * NOT logged); the countdown is the UI's own mm:ss / h:mm:ss format.
     */
    fun retryPill(reason: String?, attempt: Long, maxAttempts: Long, ts: Double, delayMs: Double): String {
        val label = ApiRetryText.label(reason).removePrefix(ZAI_PREFIX)
        val deadlineMs = (ts * MILLIS_PER_SECOND + delayMs).toLong()
        val clock = Instant.ofEpochMilli(deadlineMs).atZone(ZoneId.systemDefault()).format(CLOCK)
        return "z.ai $label — retrying $attempt/$maxAttempts, next try $clock " +
            "(${ApiRetryText.countdown(delayMs.toLong())} from now)"
    }

    fun fmtDur(seconds: Int): String =
        if (seconds < SECONDS_PER_MINUTE) {
            "${seconds}s"
        } else {
            "%dm %02ds".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
        }

    /** `min(2^attempt, 1024)` — the retry curve the fork's pill shows. */
    private fun nextRetryS(attempt: Long): Long =
        minOf(1L shl attempt.coerceAtMost(RETRY_EXPONENT_CAP).toInt(), RETRY_CAP_S)

    private const val MILLIS_PER_SECOND = 1000.0

    private fun JsonObject?.longOrDefault(key: String, default: Long): Long =
        (this?.get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it != 0L } ?: default
}
