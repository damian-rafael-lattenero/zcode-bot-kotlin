package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PngRender — the recipe law. The kernel only shapes silicon's argv; these
 * pins keep the owner's palette (probe A) and the shell's honest grammar
 * (syntect has no Nushell: `nu` rides `sh`).
 */
class PngRenderTest {

    private fun argv(language: String?) =
        PngRender.arguments(PngRender.Shot("ls | first 3", language), "/tmp/in.txt", "/tmp/out.png")

    @Test
    fun `the palette is the owner's pick and the binary is absolute`() {
        val args = argv("kotlin")
        assertEquals(PngRender.SILICON, args.first(), "the edge execs the absolute path — launchd's PATH is narrow")
        assertTrue("/opt/homebrew/bin/silicon" == PngRender.SILICON, "silicon lives outside the launchd PATH")
        assertEquals("OneHalfDark", args[args.indexOf("--theme") + 1])
        assertEquals("#0B3B3C", args[args.indexOf("--background") + 1])
        assertEquals("48", args[args.indexOf("--pad-horiz") + 1])
        assertEquals("34", args[args.indexOf("--pad-vert") + 1])
        assertTrue("--no-line-number" in args, "the text bubble keeps the gutter; the image is the pretty view")
    }

    @Test
    fun `a known language rides the flag and the paths close the argv`() {
        val args = argv("kotlin")
        assertEquals("kotlin", args[args.indexOf("--language") + 1])
        assertEquals("/tmp/in.txt", args.last(), "the input is the last positional")
        assertEquals("/tmp/out.png", args[args.indexOf("--output") + 1])
    }

    @Test
    fun `nushell has no syntect grammar and rides the closest shell`() {
        assertEquals("sh", PngRender.grammar("nu"))
        assertEquals("sh", PngRender.grammar("Nushell"))
        assertEquals("sh", argv("nu")[argv("nu").indexOf("--language") + 1])
    }

    @Test
    fun `a blank language omits the flag - plain monospace, still themed`() {
        assertFalse("--language" in argv(null), "no flag means no highlighter")
        assertFalse("--language" in argv(""), "blank is blank")
        assertEquals(null, PngRender.grammar(null))
        assertEquals(null, PngRender.grammar("  "))
    }

    @Test
    fun `an unknown slug passes through - the edge's retry is the fallback`() {
        assertEquals("weird-dsl", PngRender.grammar("weird-dsl"))
        assertEquals("weird-dsl", argv("weird-dsl")[argv("weird-dsl").indexOf("--language") + 1])
    }

    @Test
    fun `a long line wraps at the width law and a short one never moves`() {
        val long = "x".repeat(250)
        val wrapped = PngRender.wrap("$long\ncorta").split('\n')
        assertEquals(listOf("x".repeat(100), "x".repeat(100), "x".repeat(50), "corta"), wrapped)
        assertEquals("corta", PngRender.wrap("corta"), "a line under the law is untouched")
    }

    @Test
    fun `a tall body goes dense and past the budget it says how much stayed`() {
        val dense = PngRender.arguments(PngRender.Shot("x", null), "in", "out", compact = true)
        assertEquals("Hack=14", dense[dense.indexOf("--font") + 1], "the dense pass rides the measured font")
        assertFalse("--font" in PngRender.arguments(PngRender.Shot("x", null), "in", "out"), "short bodies keep the default")

        val tall = (1..400).joinToString("\n") { "line $it" }
        val fitted = PngRender.fit(tall).split('\n')
        assertEquals(301, fitted.size, "300 lines plus the honest remainder note")
        assertTrue(fitted.last().contains("100 líneas más"), "the note counts what stayed behind: ${fitted.last()}")
        assertEquals("a\nb", PngRender.fit("a\nb"), "a short body passes untouched")
    }

    @Test
    fun `the wrap never splits an emoji`() {
        val line = "🙂".repeat(120) // 120 code points, 240 UTF-16 units
        val wrapped = PngRender.wrap(line).split('\n')
        assertTrue(wrapped.all { it.length % 2 == 0 }, "a surrogate pair is never cut: ${wrapped.map { it.length }}")
        assertEquals(120, wrapped.sumOf { it.length / 2 }, "nothing is lost")
    }

    @Test
    fun `the photo ceilings read the PNG's own header`() {
        assertTrue(PngRender.fitsPhoto(pngOf(800, 400)), "a normal code image is a photo")
        assertTrue(PngRender.fitsPhoto(pngOf(1000, 9000)), "w+h at the ceiling still fits")
        assertFalse(PngRender.fitsPhoto(pngOf(200, 5000)), "ratio 25 > 20 — Telegram rejects it")
        assertFalse(PngRender.fitsPhoto(pngOf(6000, 5000)), "w+h 11000 > 10000 — dozens of lines")
        assertTrue(PngRender.fitsPhoto(byteArrayOf(1, 2, 3)), "an unreadable header defers to the upload")
    }

    /** A minimal PNG: signature + IHDR length + type + width + height. */
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
}
