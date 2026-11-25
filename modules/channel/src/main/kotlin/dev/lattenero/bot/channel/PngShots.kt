package dev.lattenero.bot.channel

/**
 * THE PNG REGISTRY — the on-demand shots, keyed by a small id because
 * Telegram's callback_data caps at 64 BYTES (the law every tap surface
 * obeys). The button carries `png:<id>`; the text and its language live
 * here, so a block of any size costs the wire six characters.
 *
 * Bounded like the file viewer's registry: the oldest shot dies first. A
 * shot outlives the turn (the chat's buttons keep working after the seal)
 * but not the process — a restart answers the honest miss, never a stale
 * image of text that no longer exists.
 */
class PngShots(private val cap: Int = REGISTRY_CAP) {

    private val shots = LinkedHashMap<Int, PngRender.Shot>()
    private var nextId = 1

    /** Registers a shot and answers its id (never 0 — the wire's own truthiness). */
    fun register(shot: PngRender.Shot): Int {
        val id = nextId++
        shots[id] = shot
        if (shots.size > cap) shots.remove(shots.keys.first())
        return id
    }

    /** The shot behind an id, or null when the registry never knew it (or dropped it). */
    fun get(id: Int): PngRender.Shot? = shots[id]

    /** The live count — tests and the miss log read it. */
    fun size(): Int = shots.size

    companion object {
        /** The ceiling: past this, the oldest shot is gone. */
        const val REGISTRY_CAP: Int = 60

        /** The honest miss a stale tap answers (the FileMenu's own words). */
        const val MISS: String = "🤷 Ese botón quedó de otro turno — el registro de PNGs ya no lo tiene."
    }
}
