package dev.lattenero.bot.transport

import java.io.File
import java.io.IOException

/**
 * The shared cooldown window (utils/http.py F8/F83): Telegram's 429 names
 * a window addressed to the CHAT, not to the process that hit it — the
 * shared file's MTIME is the expiry epoch, every process reads the same
 * truth with one stat, and the window only ever WIDENS. The read and
 * write paths are injectable so the shadow daemon can READ Python's
 * window while writing its own (cutover points both at the same file).
 */
class Cooldown(
    private val now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
    private val readFile: () -> File?,
    private val writeFile: () -> File? = readFile,
) {
    // this process's fast path (the private window of _cooldown_until)
    private var memUntil = 0.0

    /**
     * True while the window lasts — for THIS process or ANY OTHER. A
     * missing/unreadable file is no window (File.lastModified() == 0 on
     * error, which never beats now).
     */
    @Suppress("ReturnCount") // one guard per line — _cooldown_active's exact shape
    fun active(): Boolean {
        val t = now()
        if (t >= memUntil) {
            val p = readFile() ?: return false
            return p.lastModified() / MILLIS_PER_SECOND > t
        }
        return true
    }

    /**
     * Widens the window — never narrows. Creates the file if missing
     * (the mtime IS the state) and pushes it to the expiry epoch when
     * that lies in the future.
     */
    fun extend(raSeconds: Int) {
        val exp = now() + raSeconds
        if (exp > memUntil) memUntil = exp
        try {
            val p = writeFile() ?: return
            p.createNewFile()
            if (exp > p.lastModified() / MILLIS_PER_SECOND) {
                p.setLastModified((exp * MILLIS_PER_SECOND).toLong())
            }
        } catch (_: IOException) {
            // silent-by-design: worst case only this process throttles
        }
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1000.0
    }
}
