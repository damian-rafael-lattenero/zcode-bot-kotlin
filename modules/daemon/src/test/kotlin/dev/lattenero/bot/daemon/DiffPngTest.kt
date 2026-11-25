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
    fun `a diff with one side only collapses to one column`() {
        // a fresh file is all additions: an empty left column would waste
        // half the image on the phone
        val adds = File(dir, "adds.png")
        val both = File(dir, "both.png")
        assertTrue(DiffPng.render("+ 7 │ val fresh = true", adds))
        assertTrue(DiffPng.render("- 1 │ val a = true\n+ 1 │ val b = false\n- 2 │ val c = 1\n+ 2 │ val d = 2", both))
        val single = PngRender.dimensions(adds.readBytes())!!
        val pair = PngRender.dimensions(both.readBytes())!!
        assertTrue(single.first < pair.first, "the one-sided diff is narrower: ${single.first} < ${pair.first}")
        assertTrue(single.second < pair.second, "and shorter than two rows")
    }

    @Test
    fun `a long line wraps inside its row instead of ending in dots`() {
        val long = File(dir, "long.png")
        val short = File(dir, "short.png")
        assertTrue(DiffPng.render("+ 1 │ " + "x".repeat(400), long))
        assertTrue(DiffPng.render("+ 1 │ " + "x".repeat(30), short))
        val (longW, longH) = PngRender.dimensions(long.readBytes())!!
        val (shortW, shortH) = PngRender.dimensions(short.readBytes())!!
        assertTrue(longW > shortW, "wider content earns a wider column: $longW > $shortW")
        assertTrue(longH > shortH, "the extra characters become lines, not dots: $longH > $shortH")
        assertTrue(PngRender.fitsPhoto(long.readBytes()), "and the canvas is still a legal photo")
    }

    @Test
    fun `a small diff is painted at a zoomable resolution`() {
        val out = File(dir, "small.png")
        assertTrue(DiffPng.render("- 1 │ a\n+ 1 │ b", out))
        val (width, height) = PngRender.dimensions(out.readBytes())!!
        assertTrue(width > 1_000, "the canvas is painted larger than its base grid: $width")
        assertTrue(width + height <= 9_000, "and inside Telegram's pixel ceiling: $width+$height")
    }
}
