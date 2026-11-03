package dev.lattenero.bot.channel

/**
 * THE PARK MACHINE (the owner's ⏸️, 3/11 — owner-approved behavior of the
 * live view, landed daemon-side ahead of U5c's `Park` event per
 * docs/u5-render-design.md): which taskIds are parked, as a small tested
 * machine. The publisher holds the one instance; the tap asks [park], the
 * edit path asks [isParked].
 *
 * THE RULES (one-shot per card):
 * - A park is only ever THE CURRENT CARD's ([park]'s `cardTaskId`): an
 *   unknown or expired card (sealed, replaced, never born) answers
 *   [Verdict.UNKNOWN] — the honest refusal, never a lie.
 * - A second tap on an already-parked card is an honest no-op
 *   ([Verdict.ALREADY]) — nothing re-parks, nothing re-edits.
 * - The park DIES WITH ITS CARD: [endCard] at the seal and at the
 *   replaced-seal, [worldEnded] when the hub goes empty (a ☢️ nuclear).
 *   A NEW turn for the same task is therefore never parked — by
 *   construction, not by cleanup discipline: the seal that ends the
 *   parked turn is the same seal that drops the entry.
 * - There is NO unpark: a new phone message re-binds and a fresh card is
 *   born; the parked card keeps no button promise beyond the 🔔 at seal.
 */
class ParkGate {

    /** The tap's three honest outcomes. */
    enum class Verdict {
        /** Parked now: the final render lands, the edits stop, the 🔔 speaks at seal. */
        PARKED,

        /** Already parked: an honest no-op — the ack just confirms. */
        ALREADY,

        /** No live card behind this tap: the refusal. */
        UNKNOWN,
    }

    /** The parked taskIds — in-memory only; a restart un-parks the world. */
    private val parked = mutableSetOf<String>()

    /**
     * The tap's verdict: only the CURRENT card parks ([cardTaskId] is the
     * publisher's own card task — null when no card is alive).
     */
    fun park(taskId: String, cardTaskId: String?): Verdict = when {
        cardTaskId == null || cardTaskId != taskId -> Verdict.UNKNOWN
        taskId in parked -> Verdict.ALREADY
        else -> {
            parked += taskId
            Verdict.PARKED
        }
    }

    /** The edit-path gate: a parked card receives NO more edits. */
    fun isParked(taskId: String): Boolean = taskId in parked

    /** The card ended (sealed or replaced): its park dies with it — the one-shot. */
    fun endCard(taskId: String) {
        parked -= taskId
    }

    /** The hub went empty (a nuclear, a spent seal): no park outlives the world. */
    fun worldEnded() {
        parked.clear()
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
