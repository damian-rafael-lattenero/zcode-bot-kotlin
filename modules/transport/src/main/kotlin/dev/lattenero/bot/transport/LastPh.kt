package dev.lattenero.bot.transport

import java.io.File
import java.io.IOException

/**
 * tg-last-ph (F84b): the newest message_id ANY process of this Mac sent
 * to the chat. One file is the whole truth; the ll's float goes under
 * it. Best-effort on both ends — races only mis-record a stale ph, the
 * next send corrects.
 */
class LastPh(private val path: () -> File?) {

    /** last_sent_ph(): the id, or null (missing, unreadable, or 0/empty). */
    fun read(): Long? {
        val p = path() ?: return null
        val v = try {
            p.readText().trim().toLongOrNull()
        } catch (_: IOException) {
            null
        }
        return v?.takeIf { it != 0L }
    }

    /** _note_last_ph(mid): records the ph (best-effort, never raises). */
    fun note(mid: Long) {
        try {
            path()?.writeText(mid.toString())
        } catch (_: IOException) {
            // silent-by-design: the float is best-effort
        }
    }
}
