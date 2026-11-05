package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

private val logger = KotlinLogging.logger {}

/** The viewer's tap edge — BotCommands' door, the publisher's sibling idiom (CoTTaps/BoxTaps). */
interface FileTaps {
    /** The first tap: read fresh, speak the viewer (page 1). */
    suspend fun open(id: Int): String?

    /** A page tap: re-read fresh, edit the viewer in place. */
    suspend fun page(id: Int, index: Int): String?
}

/**
 * THE FILE VIEWER (the owner, 5/11: «Mensaje final -> terminó de editar,
 * muestro el total y muestro el archivo completo»): every WRITE/EDIT close
 * carries a [📄 archivo completo] button; the tap reads the file FROM DISK
 * AT TAP TIME (his choice — always fresh, later edits included) and opens
 * it in NUMBERED pages ([ToolOutputPages] — the line-number law). Page
 * taps RE-READ the file (each page is the truth of its moment, labeled
 * with the read's clock) and edit the SAME viewer message — one viewer per
 * registry id, never a ladder of page messages.
 *
 * The registry is the publisher's sibling cell: bounded like the CoT one,
 * it outlives the turn (the chat's buttons keep working after the seal).
 */
class FileMenu(
    /** The disk's truth — injected (the daemon reads, tests stub). Null = unreadable. */
    private val read: suspend (path: String) -> String?,
    /** The edit leg — the same cooldown-gated telegram the boxes use. */
    private val edit: suspend (messageId: Long, text: String, markup: JsonObject?) -> Boolean,
    /** The send leg — a NEW viewer message. */
    private val send: suspend (text: String, markup: JsonObject?) -> Long?,
    private val clock: () -> Long = System::currentTimeMillis,
) : FileTaps {
    private val paths = mutableMapOf<Int, String>()
    private var nextId = 1
    private val viewers = mutableMapOf<Int, Long>()

    /**
     * The close message's one-row keyboard — registers the path (idempotent:
     * the same path answers the same id, so repeated closes share a viewer).
     */
    fun buttonFor(path: String): JsonObject {
        val id = paths.entries.firstOrNull { it.value == path }?.key ?: freshId(path)
        return Keyboards.fileOpen(id)
    }

    /** The first tap: read fresh, speak the viewer (page 1), remember its message. */
    @Suppress("ReturnCount") // one exit per honest miss (unknown id, unreadable file, dead send)
    override suspend fun open(id: Int): String? {
        val path = paths[id] ?: return MISS
        val pages = read(path)?.let(ToolOutputPages::pages) ?: return UNREADABLE
        val mid = send(render(path, pages, 0), Keyboards.filePages(id, 0, pages.size)) ?: return SEND_FAILED
        viewers[id] = mid
        logger.info { "file viewer opened: id=$id pages=${pages.size} message_id=$mid" }
        return null // the send IS the feedback
    }

    /** A page tap: RE-READ the file (fresh at every tap — his choice) and edit the viewer. */
    @Suppress("ReturnCount") // one exit per honest miss (unknown id, unreadable file, re-open fallback)
    override suspend fun page(id: Int, index: Int): String? {
        val path = paths[id] ?: return MISS
        val mid = viewers[id] ?: return open(id) // a viewer that died re-opens whole
        val pages = read(path)?.let(ToolOutputPages::pages) ?: return UNREADABLE
        val at = index.coerceIn(0, pages.size - 1)
        edit(mid, render(path, pages, at), Keyboards.filePages(id, at, pages.size))
        return null // a throttled edit keeps the old page — the next tap retried it
    }

    /** The viewer's own header: the read's clock rides — the freshness is the law. */
    private fun render(path: String, pages: List<String>, index: Int): String {
        val readClock = Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault())
            .toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
        val header = "📄 <b>${PyText.esc(path)}</b> · ${pages.size} página(s) · " +
            "leído a las $readClock <i>(fresco al toque — edits posteriores incluidos)</i>"
        return ToolOutputPages.renderPage(header, pages, index)
    }

    private fun freshId(path: String): Int {
        val id = nextId++
        paths[id] = path
        if (paths.size > REGISTRY_CAP) paths.remove(paths.keys.first())
        return id
    }

    private companion object {
        /** The registry's ceiling — old paths die first, like the CoT registry. */
        const val REGISTRY_CAP = 60

        const val MISS = "🤷 Ese botón quedó de otro turno — el registro de archivos ya no lo tiene."
        const val UNREADABLE =
            "⚠️ No pude leer ese archivo (¿se movió o se borró?) — el diff de arriba sigue siendo la verdad del edit."
        const val SEND_FAILED = "📡 El envío no voló — probá de nuevo en un momento."
    }
}
