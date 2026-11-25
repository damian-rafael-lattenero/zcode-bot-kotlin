package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.PngRender
import dev.lattenero.bot.channel.PngShots
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 📸 tap's transaction: unknown id = the honest miss (no render, no
 * upload), a render uploads once, an unknown slug retries WITHOUT the
 * language, and the temp files never survive the tap.
 */
class PngTapsTest {

    private val dir: File = Files.createTempDirectory("png-taps-test").toFile()

    @AfterTest
    fun clean() {
        dir.deleteRecursively()
    }

    private fun taps(
        shots: PngShots,
        exit: List<Int> = listOf(0),
        uploaded: MutableList<File> = mutableListOf(),
        calls: MutableList<List<String>> = mutableListOf(),
        uploadFails: Boolean = false,
    ): PngTaps {
        var i = 0
        return PngTaps(
            shots = shots,
            upload = { _, file ->
                if (uploadFails) throw IllegalStateException("boom")
                uploaded += file
            },
            exec = { argv ->
                calls += argv
                val code = exit.getOrElse(i++) { exit.last() }
                if (code == 0) File(argv[argv.indexOf("--output") + 1]).writeText("png")
                code
            },
            chatId = { "555" },
            tmpDir = dir,
            clock = { 1_000L },
        )
    }

    @Test
    fun `an unknown id is the miss and nothing runs`() {
        val uploaded = mutableListOf<File>()
        val calls = mutableListOf<List<String>>()
        val answer = runBlocking { taps(PngShots(), uploaded = uploaded, calls = calls).shoot(404) }
        assertEquals(PngShots.MISS, answer)
        assertTrue(calls.isEmpty(), "no render for a stale id")
        assertTrue(uploaded.isEmpty(), "no upload for a stale id")
    }

    @Test
    fun `a registered shot renders once and uploads once`() {
        val shots = PngShots()
        val id = shots.register(PngRender.Shot("ls | first 3", "nu"))
        val uploaded = mutableListOf<File>()
        val calls = mutableListOf<List<String>>()
        val answer = runBlocking { taps(shots, uploaded = uploaded, calls = calls).shoot(id) }
        assertNull(answer, "the image IS the feedback")
        assertEquals(1, calls.size)
        assertEquals("sh", calls.first()[calls.first().indexOf("--language") + 1], "nu rides the shell grammar")
        assertEquals(1, uploaded.size)
        assertTrue(dir.listFiles().orEmpty().isEmpty(), "the temp source and PNG die with the tap")
    }

    @Test
    fun `an unknown slug retries without the language and still flies`() {
        val shots = PngShots()
        val id = shots.register(PngRender.Shot("weird", "weird-dsl"))
        val uploaded = mutableListOf<File>()
        val calls = mutableListOf<List<String>>()
        val answer = runBlocking {
            taps(shots, exit = listOf(1, 0), uploaded = uploaded, calls = calls).shoot(id)
        }
        assertNull(answer)
        assertEquals(2, calls.size, "one refused render, one plain retry")
        assertTrue("--language" !in calls.last(), "the retry drops the highlighter")
        assertEquals(1, uploaded.size)
    }

    @Test
    fun `a render that never succeeds speaks instead of uploading`() {
        val shots = PngShots()
        val id = shots.register(PngRender.Shot("x", "kotlin"))
        val uploaded = mutableListOf<File>()
        val answer = runBlocking {
            taps(shots, exit = listOf(1, 1), uploaded = uploaded).shoot(id)
        }
        assertTrue(answer!!.contains("silicon"), "the miss names the renderer: $answer")
        assertTrue(uploaded.isEmpty(), "no half-baked upload")
        assertTrue(dir.listFiles().orEmpty().isEmpty(), "the temp files die even on failure")
    }

    @Test
    fun `an upload failure speaks with its reason`() {
        val shots = PngShots()
        val id = shots.register(PngRender.Shot("x", "kotlin"))
        val answer = runBlocking { taps(shots, uploadFails = true).shoot(id) }
        assertTrue(answer!!.contains("boom"), "the reason rides the words: $answer")
    }
}
