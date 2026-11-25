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
}
