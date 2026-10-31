package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.FeedVerbosity
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The /verbosity level's persistence: the owner's choice SURVIVES the
 * restarts (a new instance over the same file re-reads it), the default
 * holds when the file is absent or corrupt, and the write is atomic
 * (tmp + fsync + rename — no torn line ever).
 */
class FileVerbositySettingTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun theChoiceSurvivesTheRestartRoundTrip() {
        val file = File(dir, "verbosity.txt")
        val setting = FileVerbositySetting(file)
        setting.set(FeedVerbosity.SUMMARY)
        assertEquals(FeedVerbosity.SUMMARY, setting.current())
        // a NEW instance (the restart's boot): the file's truth comes back
        assertEquals(FeedVerbosity.SUMMARY, FileVerbositySetting(file).current())
        assertTrue(file.readText() == "SUMMARY", "the file carries the canonical name, one line")
        // no tmp litter left behind
        assertTrue(!File(dir, "verbosity.txt.tmp").exists())
    }

    @Test
    fun anAbsentOrCorruptFileIsTheOwnersDefault() {
        assertEquals(FeedVerbosity.DEFAULT, FileVerbositySetting(File(dir, "absent.txt")).current())
        val corrupt = File(dir, "corrupt.txt")
        corrupt.writeText("RUIDOSO")
        assertEquals(FeedVerbosity.DEFAULT, FileVerbositySetting(corrupt).current())
    }

    @Test
    fun theMemoryHoldsEvenWhenTheDiskRefuses() {
        // a directory IN PLACE of the file: the write itself refuses
        val blocker = File(dir, "blocked")
        val setting = FileVerbositySetting(blocker)
        assertTrue(blocker.mkdirs(), "the 'file' is a directory — the write dies")
        setting.set(FeedVerbosity.NORMAL)
        assertEquals(FeedVerbosity.NORMAL, setting.current(), "the level holds in memory")
    }
}
