package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.PngRender
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `a long line never wraps - the column takes it whole`() {
        // the owner, 25/11: no wrapping, ever. Twenty rows of 150 chars keep
        // ONE visual line each: the canvas grows sideways instead.
        val long = File(dir, "long.png")
        val short = File(dir, "short.png")
        assertTrue(DiffPng.render(rowsOf(150), long))
        assertTrue(DiffPng.render(rowsOf(30), short))
        val (longW, longH) = PngRender.dimensions(long.readBytes())!!
        val (shortW, shortH) = PngRender.dimensions(short.readBytes())!!
        assertTrue(longW > shortW, "wider content earns a wider column: $longW > $shortW")
        assertEquals(shortH, longH, "but every row keeps ONE visual line: no wrap")
        assertTrue(PngRender.fitsPhoto(long.readBytes()), "and the canvas is still a legal photo")
    }

    @Test
    fun `a line past the photo ceiling wraps as the last resort`() {
        val huge = File(dir, "huge.png")
        val plain = File(dir, "plain.png")
        assertTrue(DiffPng.render("+ 1 │ " + "x".repeat(700), huge))
        assertTrue(DiffPng.render("+ 1 │ x", plain))
        val (hugeW, hugeH) = PngRender.dimensions(huge.readBytes())!!
        val (_, plainH) = PngRender.dimensions(plain.readBytes())!!
        assertTrue(hugeH > plainH, "700 chars cannot be one photo line: $hugeH > $plainH")
        assertTrue(hugeW + hugeH <= 10_000, "the fallback keeps the image legal: $hugeW+$hugeH")
    }

    /** [count] addition rows, each carrying [width] characters. */
    private fun rowsOf(width: Int): String =
        (1..20).joinToString("\n") { "+ $it │ " + "x".repeat(width) }

    @Test
    fun `a small diff is painted at a zoomable resolution`() {
        val out = File(dir, "small.png")
        assertTrue(DiffPng.render("- 1 │ a\n+ 1 │ b", out))
        val (width, height) = PngRender.dimensions(out.readBytes())!!
        assertTrue(width > 1_000, "the canvas is painted larger than its base grid: $width")
        assertTrue(width + height <= 9_000, "and inside Telegram's pixel ceiling: $width+$height")
    }
}
