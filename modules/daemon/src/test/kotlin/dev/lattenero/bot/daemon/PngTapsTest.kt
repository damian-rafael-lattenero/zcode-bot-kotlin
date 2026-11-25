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
        writesFile: Boolean = true,
        bytes: MutableList<ByteArray> = mutableListOf(),
        pngSeq: List<ByteArray> = emptyList(),
    ): PngTaps {
        var i = 0
        return PngTaps(
            shots = shots,
            upload = { sent, file ->
                if (uploadFails) throw IllegalStateException("boom")
                fields += sent
                uploaded += file
                bytes += file.readBytes() // the temp dies right after the tap
            },
            exec = { argv ->
                calls += argv
                sources += File(argv.last()).readText() // read BEFORE the temp dies
                val call = i
                val code = exit.getOrElse(i++) { exit.last() }
                if (code == 0 && writesFile) {
                    File(argv[argv.indexOf("--output") + 1]).writeBytes(pngSeq.getOrNull(call) ?: png)
                }
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
    fun `a diff rides the drawer, never silicon`() {
        // the owner, 25/11: an edit's image IS the side-by-side view — the
        // drawer paints it in the JVM, so silicon never even runs
        val shots = PngShots()
        val id = shots.register(PngRender.Shot("- 4 │ val old = 1\n+ 5 │ val new = 2", "diff"))
        val uploaded = mutableListOf<File>()
        val calls = mutableListOf<List<String>>()
        val bytes = mutableListOf<ByteArray>()
        val answer = runBlocking {
            taps(shots, uploaded = uploaded, calls = calls, bytes = bytes).shoot(id)
        }
        assertNull(answer, "the image IS the feedback")
        assertTrue(calls.isEmpty(), "the diff never reaches silicon")
        assertEquals(1, uploaded.size)
        assertEquals(0x89.toByte(), bytes.single()[0], "the upload starts with the PNG signature")
        assertTrue(PngRender.fitsPhoto(bytes.single()), "the canvas is a legal photo")
        assertTrue(dir.listFiles().orEmpty().isEmpty(), "both temp files die with the tap")
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
        // the LAST RESORT walked in reverse: a wrap is never applied up front
        // — `the source silicon sees is whole` owns that law now.
        val shots = PngShots()
        val id = shots.register(PngRender.Shot("y".repeat(250), "kotlin"))
        val sources = mutableListOf<String>()
        val calls = mutableListOf<List<String>>()
        runBlocking {
            taps(shots, sources = sources, calls = calls, pngSeq = listOf(pngOf(9000, 200), pngOf(1200, 400))).shoot(id)
        }
        assertEquals(3, sources.last().split('\n').size, "the retry wraps at the width law")
        assertEquals(250, sources.last().replace("\n", "").length, "wrapping never loses a character")
    }
    @Test
    fun `the source silicon sees is whole - no wrapping up front`() {
        // the owner, 25/11: no image is wrapped. The render grows as wide as
        // its longest line; the text above keeps the same line, copyable.
        val shots = PngShots()
        val id = shots.register(PngRender.Shot("y".repeat(250), "kotlin"))
        val sources = mutableListOf<String>()
        runBlocking { taps(shots, sources = sources).shoot(id) }
        assertEquals(1, sources.single().split('\n').size, "one whole line: ${sources.single().length}")
        assertEquals(250, sources.single().trim().length, "every character reached the renderer")
    }

    @Test
    fun `a render past the photo ceilings wraps and renders again`() {
        // the last resort only: this render is 45:1 (Telegram refuses it), so
        // the retry wraps at the width law and the tap still flies
        val shots = PngShots()
        val id = shots.register(PngRender.Shot("y".repeat(250), "kotlin"))
        val sources = mutableListOf<String>()
        val calls = mutableListOf<List<String>>()
        val uploaded = mutableListOf<File>()
        val answer = runBlocking {
            taps(
                shots,
                uploaded = uploaded,
                calls = calls,
                sources = sources,
                pngSeq = listOf(pngOf(9000, 200), pngOf(1200, 400)),
            ).shoot(id)
        }
        assertNull(answer, "the tap still flew")
        assertEquals(2, calls.size, "the refused render was retried")
        assertEquals(3, sources.last().split('\n').size, "the retry wrapped at 100 columns")
        assertEquals(1, uploaded.size)
    }

    @Test
    fun `silicon exiting zero without a file is a failed render, not an upload`() {
        // MEASURED 25/11: a missing input makes silicon exit 0 and write
        // NOTHING — the exit code alone lied and the tap died in the upload
        val shots = PngShots()
        val id = shots.register(PngRender.Shot("ls", "nu"))
        val uploaded = mutableListOf<File>()
        val answer = runBlocking { taps(shots, uploaded = uploaded, writesFile = false).shoot(id) }
        assertTrue(answer!!.contains("silicon"), "the miss names the renderer: $answer")
        assertTrue(uploaded.isEmpty(), "no file, no upload")
        assertTrue(dir.listFiles().orEmpty().isEmpty(), "the temp files die")
    }
}
