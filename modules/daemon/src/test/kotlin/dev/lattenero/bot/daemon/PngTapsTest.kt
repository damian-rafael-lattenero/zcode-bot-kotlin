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
        fields: MutableList<Map<String, String>> = mutableListOf(),
        uploadFails: Boolean = false,
        png: ByteArray = "png".toByteArray(),
        sources: MutableList<String> = mutableListOf(),
    ): PngTaps {
        var i = 0
        return PngTaps(
            shots = shots,
            upload = { sent, file ->
                if (uploadFails) throw IllegalStateException("boom")
                fields += sent
                uploaded += file
            },
            exec = { argv ->
                calls += argv
                sources += File(argv.last()).readText() // read BEFORE the temp dies
                val code = exit.getOrElse(i++) { exit.last() }
                if (code == 0) File(argv[argv.indexOf("--output") + 1]).writeBytes(png)
                code
            },
            chatId = { "555" },
            tmpDir = dir,
            clock = { 1_000L },
        )
    }

    /** A minimal PNG header: signature + IHDR length + type + width + height. */
    private fun pngOf(width: Int, height: Int): ByteArray = ByteArray(24).also { b ->
        b[0] = 0x89.toByte()
        b[1] = 'P'.code.toByte()
        listOf(16 to width, 20 to height).forEach { (at, value) ->
            b[at] = (value shr 24).toByte()
            b[at + 1] = (value shr 16).toByte()
            b[at + 2] = (value shr 8).toByte()
            b[at + 3] = value.toByte()
        }
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

    @Test
    fun `an anchored shot replies to the button's own message`() {
        val shots = PngShots()
        val id = shots.register(PngRender.Shot("ls | first 3", "nu"))
        shots.attach(id, 73123)
        val fields = mutableListOf<Map<String, String>>()
        runBlocking { taps(shots, fields = fields).shoot(id) }
        val reply = fields.single()["reply_parameters"]
        assertTrue(reply != null && "73123" in reply, "the photo quotes the box above it: $reply")
        assertTrue("allow_sending_without_reply" in reply!!, "a deleted box never blocks the upload")
    }

    @Test
    fun `a shot with no anchor uploads bare`() {
        val shots = PngShots()
        val id = shots.register(PngRender.Shot("ls", "nu"))
        val fields = mutableListOf<Map<String, String>>()
        runBlocking { taps(shots, fields = fields).shoot(id) }
        assertTrue("reply_parameters" !in fields.single(), "no anchor, no quote: ${fields.single()}")
    }

    @Test
    fun `an image past the photo ceilings speaks instead of uploading`() {
        val shots = PngShots()
        val id = shots.register(PngRender.Shot("x".repeat(5000), "kotlin"))
        val uploaded = mutableListOf<File>()
        val answer = runBlocking {
            taps(shots, uploaded = uploaded, png = pngOf(200, 5000)).shoot(id)
        }
        assertTrue(answer!!.contains("no entra como foto"), "the reason names the ceiling: $answer")
        assertTrue(uploaded.isEmpty(), "a doomed upload never flies")
        assertTrue(dir.listFiles().orEmpty().isEmpty(), "the temp files die")
    }

    @Test
    fun `the source silicon sees is already wrapped`() {
        val shots = PngShots()
        val id = shots.register(PngRender.Shot("y".repeat(250), "kotlin"))
        val sources = mutableListOf<String>()
        runBlocking { taps(shots, sources = sources).shoot(id) }
        assertEquals(3, sources.single().split('\n').size, "the width law ran BEFORE the render: ${sources.single().length}")
        assertEquals(250, sources.single().replace("\n", "").length, "wrapping never loses a character")
    }
}
