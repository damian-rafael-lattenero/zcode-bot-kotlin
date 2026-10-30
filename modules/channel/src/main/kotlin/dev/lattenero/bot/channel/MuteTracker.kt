package dev.lattenero.bot.channel

import java.time.LocalTime
import java.time.ZoneId

/**
 * The mute-window tracker — the bot explains its own silences (the
 * coordinator's rule, 29/10's flood night: the bot was mute 23:25→23:50
 * and the owner learned why from a post-mortem instead of from the bot).
 *
 * The poller KNOWS when getUpdates fails and when it recovers; this pure
 * machine turns that into the window: the first failure opens it, the
 * first success closes it, and only windows long enough to matter
 * ([REPORT_AFTER_MS]) are worth a message — a 2s blip is noise.
 */
class MuteTracker(private val clock: () -> Long = System::currentTimeMillis) {

    /** The first failure of the current window (null = healthy). */
    internal var failedSinceMs: Long? = null
        private set

    /** One getUpdates attempt failed — open or extend the window. */
    fun onFetchFailed() {
        if (failedSinceMs == null) failedSinceMs = clock()
    }

    /**
     * A fetch succeeded: close the window if one was open. Answers the
     * window when it was long enough to report, null otherwise.
     */
    fun onFetchOk(): MuteWindow? {
        val from = failedSinceMs ?: return null
        failedSinceMs = null
        val to = clock()
        return if (to - from >= REPORT_AFTER_MS) MuteWindow(from, to) else null
    }

    companion object {
        /** Below this the silence was a blip — the notice would be noise. */
        const val REPORT_AFTER_MS = 60_000L
    }
}

/** One deaf window, start to end — the apology writes itself. */
data class MuteWindow(val fromMs: Long, val toMs: Long, private val zone: ZoneId = ZoneId.systemDefault()) {

    /**
     * «🔌 estuve desconectado 23:25→23:31 (6 min)» — the owner's wording.
     * Telegram held the updates through the window (the offset never
     * confirmed what wasn't read), so nothing was LOST — only delayed.
     */
    fun render(): String {
        val from = LocalTime.ofInstant(java.time.Instant.ofEpochMilli(fromMs), zone)
        val to = LocalTime.ofInstant(java.time.Instant.ofEpochMilli(toMs), zone)
        val mins = (toMs - fromMs) / MS_PER_MIN
        val lasted = if (mins >= MINS_PER_HOUR) {
            "${mins / MINS_PER_HOUR}h${mins % MINS_PER_HOUR}min"
        } else {
            "${mins} min"
        }
        return "🔌 estuve desconectado ${from.format(TIME)}→${to.format(TIME)} " +
            "($lasted — lo que escribiste en ese rato llega igual, Telegram lo guardó)"
    }

    private companion object {
        val TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        const val MS_PER_MIN = 60_000L
        const val MINS_PER_HOUR = 60L
    }
}
