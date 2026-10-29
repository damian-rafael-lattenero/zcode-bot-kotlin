package dev.lattenero.bot.transport

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * tg-offset (updates.py): the getUpdates cursor. current_offset()'s
 * zero-on-anything and save_offset()'s ATOMIC write (tmp + fsync +
 * rename, the tmp carrying the pid — the 20/10 18:10 ENOENT lesson).
 */
class TgOffsetTest {

    @TempDir
    lateinit var dir: File

    private val path: File get() = File(dir, "tg-offset")

    @Test
    fun currentIsTheStoredUpdateIdOrZero() {
        assertEquals(0L, TgOffset.current(path), "missing file → 0")
        path.writeText("8801\n")
        assertEquals(8801L, TgOffset.current(path), "int(f.read().strip())")
        path.writeText("  8802  ")
        assertEquals(8802L, TgOffset.current(path))
        path.writeText("not a number")
        assertEquals(0L, TgOffset.current(path), "ValueError → 0 (the truncated-write story)")
        path.writeText("")
        assertEquals(0L, TgOffset.current(path))
    }

    @Test
    fun saveWritesTheDecimalAndLeavesNoTmpBehind() {
        TgOffset.save(path, 12345)
        assertEquals("12345", path.readText(), "str(value), no newline — os.replace of the fsynced tmp")
        TgOffset.save(path, 67890)
        assertEquals("67890", path.readText(), "overwrite in place")
        val leftovers = dir.listFiles().orEmpty().map { it.name }
        assertEquals(listOf("tg-offset"), leftovers, "the pid-suffixed tmp is gone: only the cursor remains")
    }

    @Test
    fun saveCreatesTheStateDirOnDemand() {
        val nested = File(dir, "state/nested/tg-offset")
        TgOffset.save(nested, 7)
        assertEquals("7", nested.readText(), "os.makedirs(exist_ok=True)")
    }
}
