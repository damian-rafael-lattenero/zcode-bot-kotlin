package dev.lattenero.bot.transport

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * tg-last-ph (F84b): the newest message_id ANY process of this Mac sent
 * to the chat — one file is the whole truth, best-effort on both ends.
 */
class LastPhTest {

    @TempDir
    lateinit var dir: File

    private val path: File get() = File(dir, "tg-last-ph")

    @Test
    fun readIsTheIdOrNothing() {
        assertNull(LastPh({ null }).read())
        assertNull(LastPh({ path }).read(), "missing file → null")
        path.writeText("12345")
        assertEquals(12345L, LastPh({ path }).read())
        path.writeText("  12345\n")
        assertEquals(12345L, LastPh({ path }).read(), "int(f.read().strip())")
        path.writeText("0")
        assertNull(LastPh({ path }).read(), "0 or None — the float's own gate")
        path.writeText("")
        assertNull(LastPh({ path }).read(), "empty → 0 → None")
        path.writeText("abc")
        assertNull(LastPh({ path }).read(), "ValueError → None")
        assertNull(LastPh({ dir }).read(), "unreadable → None")
    }

    @Test
    fun noteRecordsTheBareId() {
        val ph = LastPh({ path })
        ph.note(987654)
        assertEquals("987654", path.readText(), "str(mid), no newline")
        assertEquals(987654L, ph.read())
    }

    @Test
    fun noteIsBestEffortAndNeverRaises() {
        LastPh({ null }).note(1)
        LastPh({ File(dir, "no-parent-dir/x") }).note(2) // silent-by-design
        assertNull(LastPh({ path }).read())
    }
}
