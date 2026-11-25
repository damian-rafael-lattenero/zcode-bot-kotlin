package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.PngRender
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The side-by-side drawer's smoke: a real diff becomes a real PNG, a
 * row-less text draws nothing, and the canvas stays inside the photo
 * ceilings Telegram enforces.
 */
class DiffPngTest {

    private val dir: File = Files.createTempDirectory("diff-png-test").toFile()

    @AfterTest
    fun clean() {
        dir.deleteRecursively()
    }

    @Test
    fun `a diff becomes a side-by-side png inside the photo limits`() {
        val out = File(dir, "diff.png")
        val diff = "- 4 │ val old = 1\n+ 5 │ val new = 2\n  6 │ context line"
        assertTrue(DiffPng.render(diff, out, title = "CodeLang.kt"), "the drawer writes a file")
        val bytes = out.readBytes()
        assertTrue(bytes.isNotEmpty())
        assertTrue(PngRender.fitsPhoto(bytes), "the image is a legal photo")
    }

    @Test
    fun `text with no diff rows draws nothing`() {
        assertFalse(DiffPng.render("", File(dir, "empty.png")), "an empty diff has no image")
    }

    @Test
    fun `a wide diff clips instead of blowing the canvas`() {
        val wide = "+ 1 │ " + "x".repeat(400)
        val out = File(dir, "wide.png")
        assertTrue(DiffPng.render(wide, out))
        val (width, height) = PngRender.dimensions(out.readBytes())!!
        assertTrue(width < 4000, "the canvas clips the line: $width")
        assertTrue(height > 0)
    }
}
