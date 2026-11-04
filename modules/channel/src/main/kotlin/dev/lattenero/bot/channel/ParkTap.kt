package dev.lattenero.bot.channel

/**
 * THE PARK TAP'S LAW (U5c, the owner's ⏸️, 3/11) — the `bg:` button's
 * verdict as a PURE function of the fold. The old park machine lived beside
 * the render; the park is STATE now ([TurnLive.parked], folded by
 * [RenderEvent.Park]/[RenderEvent.ParkLanded]) and this object only answers
 * WHAT THE TAP IS, from the state it reads:
 *
 * - A park is only ever THE LIVE CARD's: a tap whose task is not the
 *   watched turn's, or whose card is not alive ([cardTaskId]), answers
 *   [Verdict.UNKNOWN] — the honest refusal, never a lie.
 * - A second tap on an already-parked card is an honest no-op
 *   ([Verdict.ALREADY]) — nothing re-parks, nothing re-edits.
 * - The park DIES WITH ITS STATE: the seal's [RenderEvent.Clear], a task
 *   switch and a ☢️ nuclear take it with the world. A NEW turn for the same
 *   task is therefore never parked — by construction, not by cleanup
 *   discipline: the state that ends the parked turn is the state that
 *   carried the park.
 * - There is NO unpark: a new phone message re-binds and a fresh card is
 *   born; the parked card keeps no button promise beyond the 🔔 at seal.
 */
object ParkTap {

    /** The tap's three honest outcomes. */
    enum class Verdict {
        /** Parked now: the final render lands, the edits stop, the 🔔 speaks at seal. */
        PARKED,

        /** Already parked: an honest no-op — the ack just confirms. */
        ALREADY,

        /** No live card behind this tap: the refusal. */
        UNKNOWN,
    }

    /**
     * The verdict for a tap on [taskId]: [cardTaskId] is the card's OWN task
     * (the publisher's message pointer — null when no card is alive) and
     * [state] is the fold's current turn. Both must name the tapped task: a
     * button from another turn is expired, and an expired button never
     * parks a card that is not its own.
     */
    fun verdict(state: RenderState?, cardTaskId: String?, taskId: String): Verdict = when {
        state == null || state.taskId != taskId -> Verdict.UNKNOWN
        cardTaskId == null || cardTaskId != taskId -> Verdict.UNKNOWN
        state.parked != null -> Verdict.ALREADY
        else -> Verdict.PARKED
    }
}

/**
 * The park tap's OWN WORDS — one home: the publisher's toasts and the
 * wiring's unwired refusal share them (the ack is the owner-approved
 * wording, verbatim). Public: the wiring lives in the daemon module.
 */
object ParkTapTexts {
    /** The tap's toast (the owner-approved wording). */
    const val ACK = "▶️ A background — te aviso al terminar"

    /** The second tap's toast — an honest no-op, nothing re-parked. */
    const val ALREADY = "▶️ Ya está en background — te aviso al terminar."

    /** The unknown/expired card's refusal — it names what did NOT happen. */
    const val REFUSAL = "🤷 Ese botón quedó de otro turno — no hay cartel vivo que aparcar."

    /** The no-Telegram refusal (the taps' house wording, park's own). */
    const val NO_TELEGRAM = "📡 Sin Telegram configurado — no hay cartel que aparcar."
}
