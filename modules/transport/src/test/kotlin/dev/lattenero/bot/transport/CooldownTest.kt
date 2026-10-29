package dev.lattenero.bot.transport

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared cooldown window (F8/F83): Telegram's 429 names a window
 * addressed to the CHAT, not to the process — the shared file's MTIME
 * is the expiry epoch, every process reads the same truth with one
 * stat, and the window only ever WIDENS. Pinned with an injected clock
 * (tests move time, never sleep).
 */
class CooldownTest {

    @TempDir
    lateinit var dir: File

    private var t = 1000.0
    private val file: File get() = File(dir, "tg-cooldown")

    private fun cooldown(read: File? = file, write: File? = read) =
        Cooldown(now = { t }, readFile = { read }, writeFile = { write })

    /** An existing window file at mtime [mtimeS] seconds — the REAL clock is faked to [t], so the file must be seeded to match (a fresh file's 2026 mtime is infinitely in the future and the never-narrow guard would rightly refuse it). */
    private fun seedWindow(mtimeS: Double): File = file.apply {
        createNewFile()
        setLastModified((mtimeS * 1000).toLong())
    }

    @Test
    fun noFileIsNoWindow() {
        assertFalse(cooldown(read = null).active())
        assertFalse(cooldown().active(), "a missing file is no window")
    }

    @Test
    fun theFilesMtimeIsTheExpiryEpochAnyProcessWrote() {
        seedWindow(t + 30)
        assertTrue(cooldown().active(), "another process's window throttles THIS one too")
        t += 31
        assertFalse(cooldown().active())
    }

    @Test
    fun thePrivateWindowIsTheFastPathAndSkipsTheStat() {
        // extend sets the in-process window: while it lasts, active()
        // does not even look at the file (read returns null — unreadable
        // — and the answer is still true)
        val c = cooldown(read = null, write = null)
        c.extend(10)
        assertTrue(c.active(), "the private window holds with no file at all")
        t += 11
        assertFalse(c.active())
    }

    @Test
    fun extendCreatesTheFileAndPushesItToTheExpiryEpoch() {
        seedWindow(t - 10) // an existing (stale) window file
        cooldown().extend(60)
        assertEquals(1060.0, file.lastModified() / 1000.0, "mtime IS the state")
        assertTrue(file.length() == 0L, "Python's open(p, 'a'): exists, empty")
    }

    @Test
    fun theWindowOnlyEverWidens() {
        seedWindow(t - 10)
        cooldown().extend(60)
        assertEquals(1060.0, file.lastModified() / 1000.0)
        cooldown().extend(5)
        assertEquals(1060.0, file.lastModified() / 1000.0, "a later, shorter demand never narrows the file")
        t += 30
        assertTrue(cooldown().active(), "still inside the 60 s window")
    }

    @Test
    fun extendNeverNarrowsAFileAnotherProcessSetFurther() {
        seedWindow(t + 100)
        cooldown().extend(10)
        assertEquals(1100.0, file.lastModified() / 1000.0, "the guard exp > mtime keeps the wider truth")
    }

    @Test
    fun anUnwritableFileIsSilentWorstCaseOnlyThisProcessThrottles() {
        val c = cooldown(read = null, write = File(dir, "no-such-dir/x/y"))
        c.extend(10) // must not raise
        assertTrue(c.active(), "the private window still holds")
    }
}
