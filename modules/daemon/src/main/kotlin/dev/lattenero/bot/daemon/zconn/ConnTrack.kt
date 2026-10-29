package dev.lattenero.bot.daemon.zconn

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The z.ai connection's episode machine — a pure port of the Python bot's
 * `zconn.ConnTrack` (F85): feed DOWN/UP log events, speak on TRANSITIONS
 * only. One ⚠️ when the cut starts (with the FIRST reason — the diagnosis,
 * never a later consequence: the 13:00 incident ended up telling «Model
 * request was cancelled», which was the effect, not the cause), one ✅
 * when it heals (with how long the WHOLE episode lasted). Sputters of the
 * same sick wire stay quiet and the UP keeps the episode's first down as
 * its anchor.
 *
 * B3b+ (the error inventory): `model.network.retry_scheduled` speaks its
 * own notice when the announced wait is LONG — the 1308 incident: a
 * 5-hour usage-limit window was announced «next retry 2s» because the
 * synthesized 2^attempt curve never read the real delayMs. The real delay
 * comes from the log line; nextAttemptAtMs is NOT logged, so the deadline
 * is `timestamp + delayMs`. And a user's ⌘-stop (reason/status
 * «cancelled») is NOT a cut — it never enters the machine.
 */
data class ConnTrack(
    val down: Boolean = false,
    val firstDown: Double? = null,
    val lastUp: Double? = null,
    val reason: String = "",
    /** The retry attempt whose LONG wait was last announced (one notice per attempt). */
    val announcedRetry: Long? = null,
)

sealed interface ZconnNotice {
    val ts: Double

    /** The cut started, with the episode's first reason. */
    data class Down(val reason: String, override val ts: Double, val pill: String) : ZconnNotice

    /** The wire is back; [firstDown] is the episode's anchor, sputters included. */
    data class Up(val firstDown: Double, override val ts: Double) : ZconnNotice

    /** An honestly-long retry wait: the pill the fork's UI shows, with the REAL countdown. */
    data class RetryWait(val pill: String, override val ts: Double) : ZconnNotice
}

object ConnTrackMachine {

    /** A re-fail this close to a recovery is the same episode (F85). */
    const val SPUTTER_S = 120.0

    val DOWN_EVENTS = setOf("model.network.failed", "model.request.failed")
    val UP_EVENTS = setOf("model.network.completed")

    /** The honest-countdown needles (core + adapter spellings of the same event). */
    val RETRY_EVENTS = setOf("model.network.retry_scheduled", "model.retry.scheduled")
    val NEEDLES = DOWN_EVENTS + UP_EVENTS + RETRY_EVENTS

    /** Waits shorter than this are the routine backoff sputter — the Down notice already spoke. */
    const val LOUD_RETRY_WAIT_MS = 60_000.0

    private const val DEFAULT_REASON = "connection failed"
    private const val DEFAULT_MAX_ATTEMPTS = 11L
    private const val FIRST_ATTEMPT = 1L

    /**
     * A ⌘-stop is not an outage: `model.network.failed` with
     * reason/status «cancelled» is the USER ending a generation — feeding
     * it used to announce a false «z.ai connection lost».
     */
    fun isCancelledDown(event: String, context: JsonObject?): Boolean {
        if (event !in DOWN_EVENTS) return false
        val status = (context?.get("status") as? JsonPrimitive)?.content
        val reason = (context?.get("reason") as? JsonPrimitive)?.content
        return status == "cancelled" || reason == "cancelled"
    }

    /**
     * Pure transition: `(state, event) -> (state', notices)`. Every branch
     * mirrors the Python machine line for line; the laws live in
     * `ConnTrackLawsTest` (episode alternation, first-down anchoring,
     * sputter silence).
     */
    fun feed(
        track: ConnTrack,
        ts: Double,
        event: String,
        reason: String,
        pill: String,
    ): Pair<ConnTrack, List<ZconnNotice>> = when {
        event in DOWN_EVENTS && !track.down -> {
            val anchor = track.firstDown
            val lastUp = track.lastUp
            val fresh = anchor == null || lastUp == null || ts - lastUp >= SPUTTER_S
            if (fresh) {
                val next = track.copy(down = true, firstDown = ts, reason = reason.ifEmpty { DEFAULT_REASON })
                next to listOf(ZconnNotice.Down(next.reason, ts, pill))
            } else {
                // sputter: the chat already knows this episode
                track.copy(down = true) to emptyList()
            }
        }

        event in DOWN_EVENTS ->
            // the episode KEEPS its first reason; later failures stay silent
            track to emptyList()

        event in UP_EVENTS -> {
            val anchor = track.firstDown
            if (track.down && anchor != null) {
                track.copy(down = false, lastUp = ts, announcedRetry = null) to
                    listOf(ZconnNotice.Up(anchor, ts))
            } else {
                track.copy(lastUp = ts, announcedRetry = null) to emptyList()
            }
        }

        else -> track to emptyList()
    }

    /**
     * Pure transition for the retry needles: speak ONCE per attempt when
     * the real wait is long ([LOUD_RETRY_WAIT_MS]); the routine 2^n
     * backoff sputter (≤ a minute) stays quiet — the Down notice already
     * spoke, and a retry storm must not become a notification storm.
     */
    fun feedRetry(track: ConnTrack, ts: Double, scheduled: RetryScheduled): Pair<ConnTrack, List<ZconnNotice>> {
        if (scheduled.delayMs < LOUD_RETRY_WAIT_MS || track.announcedRetry == scheduled.attempt) {
            return track to emptyList()
        }
        val pill = ZconnText.retryPill(
            reason = scheduled.reason,
            attempt = scheduled.attempt,
            maxAttempts = scheduled.maxAttempts,
            ts = ts,
            delayMs = scheduled.delayMs,
        )
        return track.copy(announcedRetry = scheduled.attempt) to listOf(ZconnNotice.RetryWait(pill, ts))
    }

    /**
     * The retry_scheduled line's context: attempt (nextAttempt-1 — the
     * UI's own arithmetic), maxAttempts, the REAL delayMs and the reason.
     */
    data class RetryScheduled(
        val attempt: Long,
        val maxAttempts: Long,
        val delayMs: Double,
        val reason: String?,
    )

    fun retryContextOf(context: JsonObject?): RetryScheduled? {
        val delayMs = (context?.get("delayMs") as? JsonPrimitive)?.longOrNull?.toDouble() ?: return null
        val attempt = context.longOrDefault(
            key = "attempt",
            default = (context.longOrDefault("nextAttempt", FIRST_ATTEMPT + 1) - FIRST_ATTEMPT)
                .coerceAtLeast(FIRST_ATTEMPT),
        )
        val max = context.longOrDefault("maxAttempts", DEFAULT_MAX_ATTEMPTS)
        val reason = (context["reason"] as? JsonPrimitive)?.content
        return RetryScheduled(attempt, max, delayMs, reason)
    }

    private fun JsonObject?.longOrDefault(key: String, default: Long): Long =
        (this?.get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it != 0L } ?: default
}
