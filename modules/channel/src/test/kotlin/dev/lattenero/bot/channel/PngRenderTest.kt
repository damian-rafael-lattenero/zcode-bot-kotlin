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
        assertEquals(PngRender.PAD_HORIZ.toString(), args[args.indexOf("--pad-horiz") + 1])
        assertEquals(PngRender.PAD_VERT.toString(), args[args.indexOf("--pad-vert") + 1])
        assertEquals(PngRender.LINE_PAD.toString(), args[args.indexOf("--line-pad") + 1])
        assertEquals(PngRender.CODE_PAD_RIGHT.toString(), args[args.indexOf("--code-pad-right") + 1])
        assertTrue("--line-offset" in args, "the image numbers its lines (the owner, 26/11)")
        assertTrue("--no-window-controls" in args, "no traffic lights: a clean card, like the diff drawer")
    }

    @Test
    fun `a known language rides the flag and the paths close the argv`() {
        val args = argv("kotlin")
        assertEquals("kotlin", args[args.indexOf("--language") + 1])
        assertEquals("/tmp/in.txt", args.last(), "the input is the last positional")
        assertEquals("/tmp/out.png", args[args.indexOf("--output") + 1])
    }

    @Test
    fun `a body taller than a page splits into pages that keep every line`() {
        val tall = (1..(PngRender.PAGE_LINES * 2 + 5)).joinToString("\n") { "line $it" }
        val pages = PngRender.pages(tall)
        assertEquals(3, pages.size, "two full pages and the remainder")
        assertEquals(tall, pages.joinToString("\n"), "paging loses nothing")
        assertEquals(listOf("a\nb"), PngRender.pages("a\nb"), "a short body stays whole")
    }

    @Test
    fun `the code images carry their line numbers and a paged body keeps counting`() {
        // the owner, 26/11: every code image shows its line numbers
        val clean = PngRender.arguments(PngRender.Shot("fun main() = 1", "kotlin"), "in", "out", lineOffset = 121)
        assertTrue("--line-offset" in clean, "the gutter rides: $clean")
        assertEquals("121", clean[clean.indexOf("--line-offset") + 1], "page 2 opens at 121")
    }

    @Test
    fun `a text that already carries a gutter keeps silicon's numbers off`() {
        // a Read arrives numbered — a second number column would read as a bug
        val read = (1..10).joinToString("\n") { "$it\tval line$it = $it" }
        assertTrue(PngRender.guttered(read), "ten numbered lines are a gutter")
        assertFalse(PngRender.guttered("fun a() = 1\nfun b() = 2"), "code is not a gutter")
        assertFalse(PngRender.guttered(""), "nothing is not a gutter")
        assertFalse(PngRender.guttered("1\tone\nfun b() = 2"), "one numbered line is not a gutter")
        val argv = PngRender.arguments(PngRender.Shot(read, "kotlin"), "in", "out")
        assertTrue("--no-line-number" in argv, "the text's own numbers stay the only ones: $argv")
    }

    @Test
    fun `no flag rides the argv twice`() {
        // MEASURED 25/11: silicon answers "The argument '--language <LANG>'
        // was provided more than once" with exit 1 and NO file — a duplicated
        // flag is a dead tap at every scale, not a warning
        val flags = argv("kotlin").filter { it.startsWith("--") }
        assertEquals(flags.size, flags.distinct().size, "a flag repeated is a dead tap: $flags")
        assertEquals(1, flags.count { it == "--language" }, "the language rides once: $flags")
    }

    @Test
    fun `nushell has no syntect grammar and rides the closest shell`() {
        assertEquals("sh", PngRender.grammar("nu"))
        assertEquals("sh", PngRender.grammar("Nushell"))
        assertEquals("sh", argv("nu")[argv("nu").indexOf("--language") + 1])
    }

    @Test
    fun `a blank language rides the plain grammar - silicon cannot draw without one`() {
        // measured 25/11: silicon fails to detect a language from a `.txt`
        // source and has no plain grammar (txt/text/plain are Unsupported);
        // it exits 0, writes nothing, and the tap dies. `log` is the least
        // opinionated grammar it accepts.
        assertEquals(PngRender.PLAIN, PngRender.grammar(null))
        assertEquals(PngRender.PLAIN, PngRender.grammar("  "))
        assertEquals(PngRender.PLAIN, argv(null)[argv(null).indexOf("--language") + 1])
        assertEquals(PngRender.PLAIN, argv("")[argv("").indexOf("--language") + 1], "blank is blank")
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
        assertEquals("Hack=26", argv("kotlin")[argv("kotlin").indexOf("--font") + 1], "the default face is explicit")

        val tall = (1..(PngRender.MAX_LINES + 100)).joinToString("\n") { "line $it" }
        val fitted = PngRender.fit(tall).split('\n')
        assertEquals(PngRender.MAX_LINES + 1, fitted.size, "the budget plus the honest remainder note")
        assertTrue(fitted.last().contains("100 more lines"), "the note counts what stayed behind: ${fitted.last()}")
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
