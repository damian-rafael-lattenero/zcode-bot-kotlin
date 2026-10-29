package dev.lattenero.bot.transport

import kap.Schedule
import kotlin.time.Duration.Companion.seconds

/**
 * utils/retry.retry_call's frozen send contract, ported as schedule
 * algebra: backoffs (0, 1, 3) — one-off network resets don't lose the
 * notification. The schedule says Done when the attempts run out or
 * shouldRetry stops the loop NOW (4xx≠429: retrying a bad request is
 * hammering with extra steps; 429: the server NAMED the window, the
 * cooldown holds it — sleeping it out inside one call just races every
 * other sender re-heating the same bucket).
 */
object SendPolicy {

    private val BACKOFFS_S = doubleArrayOf(0.0, 1.0, 3.0)

    /**
     * The retry_after override (ra + 0.5, capped) is kept for parity
     * with retry_call but is DORMANT in the send path: a 429 never
     * continues — shouldRetry is false whenever retryAfterOf is set —
     * so the outer catch extends the cooldown instead, exactly like
     * Python's send().
     */
    fun schedule(
        shouldRetry: (Throwable) -> Boolean = ::tgRetryable,
        retryAfterOf: (Throwable) -> Int? = ::retryAfterOf,
        retryAfterCapS: Double = DEFAULT_RETRY_AFTER_CAP_S,
    ): Schedule<Throwable> = Schedule { attempt, error ->
        when {
            attempt + 1 >= BACKOFFS_S.size -> Schedule.Decision.Done
            !shouldRetry(error) -> Schedule.Decision.Done
            else -> {
                val ra = retryAfterOf(error)
                val delayS = if (ra != null) {
                    minOf(maxOf(ra.toDouble(), 0.0), retryAfterCapS) + RETRY_AFTER_MARGIN
                } else {
                    BACKOFFS_S[attempt + 1]
                }
                Schedule.Decision.Continue(delayS.seconds)
            }
        }
    }

    /**
     * _tg_retryable: 4xx (but not 429) fails fast; anything carrying a
     * retry_after (a 429) parks; the rest (5xx, network resets) retries.
     */
    fun tgRetryable(t: Throwable): Boolean {
        val code = (t as? TgHttpException)?.code
        if (code != null && code in CLIENT_ERROR_RANGE && code != TOO_MANY) return false
        return retryAfterOf(t) == null
    }

    const val DEFAULT_RETRY_AFTER_CAP_S = 60.0
    private val CLIENT_ERROR_RANGE = 400..499
    private const val RETRY_AFTER_MARGIN = 0.5
    private const val TOO_MANY = 429
}
