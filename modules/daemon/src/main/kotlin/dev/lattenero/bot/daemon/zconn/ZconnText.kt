package dev.lattenero.bot.daemon.zconn

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The exact strings the Python bot sends on zconn transitions (`zconn._notify`,
 * `_pill_vocab`, `_fmt_dur`). Byte-identical on purpose: the shadow decisions
 * these render are diffed against what the Python side actually sends — the
 * B1 parity gate.
 */
object ZconnText {

    private const val DEFAULT_MAX_ATTEMPTS = 11L
    private const val RETRY_CAP_S = 1024L
    private const val SECONDS_PER_MINUTE = 60
    private const val RETRY_EXPONENT_CAP = 10L

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

    fun fmtDur(seconds: Int): String =
        if (seconds < SECONDS_PER_MINUTE) {
            "${seconds}s"
        } else {
            "%dm %02ds".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
        }

    /** `min(2^attempt, 1024)` — the retry curve the fork's pill shows. */
    private fun nextRetryS(attempt: Long): Long =
        minOf(1L shl attempt.coerceAtMost(RETRY_EXPONENT_CAP).toInt(), RETRY_CAP_S)

    private fun JsonObject?.longOrDefault(key: String, default: Long): Long =
        (this?.get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it != 0L } ?: default
}
