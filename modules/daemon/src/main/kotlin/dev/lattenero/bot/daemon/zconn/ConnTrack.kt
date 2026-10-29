package dev.lattenero.bot.daemon.zconn

/**
 * The z.ai connection's episode machine — a pure port of the Python bot's
 * `zconn.ConnTrack` (F85): feed DOWN/UP log events, speak on TRANSITIONS
 * only. One ⚠️ when the cut starts (with the FIRST reason — the diagnosis,
 * never a later consequence: the 13:00 incident ended up telling «Model
 * request was cancelled», which was the effect, not the cause), one ✅
 * when it heals (with how long the WHOLE episode lasted). Sputters of the
 * same sick wire stay quiet and the UP keeps the episode's first down as
 * its anchor.
 */
data class ConnTrack(
    val down: Boolean = false,
    val firstDown: Double? = null,
    val lastUp: Double? = null,
    val reason: String = "",
)

sealed interface ZconnNotice {
    val ts: Double

    /** The cut started, with the episode's first reason. */
    data class Down(val reason: String, override val ts: Double, val pill: String) : ZconnNotice

    /** The wire is back; [firstDown] is the episode's anchor, sputters included. */
    data class Up(val firstDown: Double, override val ts: Double) : ZconnNotice
}

object ConnTrackMachine {

    /** A re-fail this close to a recovery is the same episode (F85). */
    const val SPUTTER_S = 120.0

    val DOWN_EVENTS = setOf("model.network.failed", "model.request.failed")
    val UP_EVENTS = setOf("model.network.completed")
    val NEEDLES = DOWN_EVENTS + UP_EVENTS

    private const val DEFAULT_REASON = "connection failed"

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
                track.copy(down = false, lastUp = ts) to listOf(ZconnNotice.Up(anchor, ts))
            } else {
                track.copy(lastUp = ts) to emptyList()
            }
        }

        else -> track to emptyList()
    }
}
