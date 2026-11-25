package dev.lattenero.bot.channel

/**
 * THE PNG REGISTRY — the on-demand shots, keyed by a small id because
 * Telegram's callback_data caps at 64 BYTES (the law every tap surface
 * obeys). The button carries `png:<id>`; the text and its language live
 * here, so a block of any size costs the wire six characters.
 *
 * THE ANCHOR (the owner, 25/11: the image must sit by its button, not at
 * the bottom of the thread among newer messages): the button is built
 * BEFORE its message exists, so the id is registered first and the message
 * id is attached after the send lands. The image then travels as a REPLY to
 * that message — Telegram quotes the box (its title/command/filename) right
 * above the photo, the closest the chat allows to «the image lives next to
 * the button».
 *
 * Bounded like the file viewer's registry: the oldest shot dies first. A
 * shot outlives the turn (the chat's buttons keep working after the seal)
 * but not the process — a restart answers the honest miss, never a stale
 * image of text that no longer exists.
 */
class PngShots(private val cap: Int = REGISTRY_CAP) {

    private val shots = LinkedHashMap<Int, Entry>()
    private var nextId = 1

    /** The shot plus the message that wears its button (null until the send lands). */
    private data class Entry(val shot: PngRender.Shot, val messageId: Long? = null)

    /** Registers a shot and answers its id (never 0 — the wire's own truthiness). */
    fun register(shot: PngRender.Shot): Int {
        val id = nextId++
        shots[id] = Entry(shot)
        if (shots.size > cap) shots.remove(shots.keys.first())
        return id
    }

    /**
     * Binds the button's message to its shot: the photo replies to THAT
     * message from then on. A late attach (the send flew but the id was
     * pruned) is a silent no-op — the tap still renders, just unanchored.
     */
    fun attach(id: Int, messageId: Long) {
        val entry = shots[id] ?: return
        shots[id] = entry.copy(messageId = messageId)
    }

    /** The shot behind an id, or null when the registry never knew it (or dropped it). */
    fun get(id: Int): PngRender.Shot? = shots[id]?.shot

    /** The message the photo must reply to, when the attach landed. */
    fun anchor(id: Int): Long? = shots[id]?.messageId

    /** The live count — tests and the miss log read it. */
    fun size(): Int = shots.size

    companion object {
        /** The ceiling: past this, the oldest shot is gone. */
        const val REGISTRY_CAP: Int = 60

        /** The honest miss a stale tap answers (the FileMenu's own words). */
        const val MISS: String = "🤷 Ese botón quedó de otro turno — el registro de PNGs ya no lo tiene."
    }
}
