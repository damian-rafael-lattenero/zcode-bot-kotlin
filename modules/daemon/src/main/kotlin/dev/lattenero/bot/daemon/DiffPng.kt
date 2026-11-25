package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.DiffShot
import dev.lattenero.bot.channel.PngRender
import dev.lattenero.bot.contracts.PyText
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * THE SIDE-BY-SIDE DRAWER — the owner's diff-ask look, painted in the JVM
 * (the owner, 25/11): two columns, line numbers, red/green cells and the
 * CHANGED MIDDLE emphasised, on the same teal canvas the other image doors
 * use. No browser, no external binary — AWT only, headless.
 *
 * NOTHING IS EVER CUT (the owner, 25/11: a line that does not fit must never
 * end in dots): the column width comes from the longest line in the plan, and
 * a line past [MAX_COLS] WRAPS inside its own row — the row grows to as many
 * visual lines as the longest side needs. A diff that only adds (a fresh
 * file) or only deletes carries ONE column: the empty side would waste half
 * the image.
 *
 * `render` is the edge: the pure [DiffShot.Plan] goes in, a PNG comes out.
 * [paint] and [drawRow] place the pixels; [Layout] measures once so the same
 * diff always draws the same image.
 */
object DiffPng {

    private const val FONT_SIZE = 13
    private const val LINE_HEIGHT = 19
    private const val PAD = 26
    private const val GAP = 22
    private const val GUTTER_CHARS = 5

    /** The narrowest a column may get: below this the gutter eats the code. */
    private const val MIN_COLS = 28

    /** The widest a column may get: past it the line wraps instead. */
    private const val MAX_COLS = 120

    /**
     * THE RESOLUTION LAW (the owner, 25/11: let it look small, he zooms): the
     * canvas is painted at the LARGEST integer scale that still fits
     * Telegram's pixel ceiling, so a zoom finds real glyphs and not blocks.
     */
    private const val MAX_SCALE = 3
    private const val PHOTO_CEILING = 9_000

    /**
     * The tallest a SHORT diff may look: a one-row diff would otherwise be a
     * 20:1 banner (Telegram rejects photos past 20:1) that the phone opens
     * zoomed and cropped. The canvas pads below the rows instead.
     */
    private const val MAX_ASPECT = 8

    private val CANVAS = Color(0x0B, 0x3B, 0x3C)
    private val TEXT = Color(0xD8, 0xE8, 0xE6)
    private val GUTTER = Color(0x7F, 0xA6, 0xA2)
    private val TITLE = Color(0xF2, 0xC9, 0x8A)
    private val DEL_CELL = Color(0x3A, 0x1E, 0x22)
    private val ADD_CELL = Color(0x1E, 0x3A, 0x28)
    private val DEL_HOT = Color(0x7A, 0x2E, 0x36)
    private val ADD_HOT = Color(0x2E, 0x6B, 0x45)

    /** Which columns a plan earns: both, or the only side that carries lines. */
    private enum class Sides { BOTH, OLD, NEW }

    private fun sidesOf(plan: DiffShot.Plan): Sides = when {
        plan.rows.none { it.old != null } -> Sides.NEW
        plan.rows.none { it.new != null } -> Sides.OLD
        else -> Sides.BOTH
    }

    /** THE WIDTH LAW: the longest line sets the column, clamped both ways. */
    private fun colsOf(plan: DiffShot.Plan): Int =
        plan.rows
            .maxOf { maxOf(PyText.cpLength(it.old ?: ""), PyText.cpLength(it.new ?: "")) }
            .coerceIn(MIN_COLS, MAX_COLS)

    /** Draws the diff into [target]; false when the text carries no rows. */
    fun render(diff: String, target: File, title: String? = null): Boolean {
        val plan = DiffShot.rows(diff)
        if (plan.rows.isEmpty()) return false
        val layout = Layout(title, sidesOf(plan), colsOf(plan))
        val scale = scaleFor(layout, plan)
        val canvas = BufferedImage(layout.width * scale, layout.height(plan) * scale, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        try {
            // every coordinate stays in base units: the transform makes the pixels
            g.scale(scale.toDouble(), scale.toDouble())
            paint(g, plan, title, layout)
        } finally {
            g.dispose()
        }
        return ImageIO.write(canvas, "png", target)
    }

    /** The sharpest render Telegram still accepts as one photo. */
    private fun scaleFor(layout: Layout, plan: DiffShot.Plan): Int {
        val sum = layout.width + layout.height(plan)
        return (MAX_SCALE downTo 1).firstOrNull { sum * it <= PHOTO_CEILING } ?: 1
    }

    private fun paint(g: Graphics2D, plan: DiffShot.Plan, title: String?, layout: Layout) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = CANVAS
        g.fillRect(0, 0, layout.width, layout.height(plan))
        g.font = layout.font
        var y = PAD + layout.ascent
        if (title != null) {
            g.color = TITLE
            g.drawString(title, PAD, y)
            y += layout.titleH
        }
        plan.rows.forEach { row ->
            drawRow(g, row, layout, y)
            y += layout.linesOf(row) * LINE_HEIGHT
        }
        if (plan.truncated) {
            g.color = GUTTER
            g.drawString("… (más cambios en el texto)", PAD, y)
        }
    }

    /** Paints one row and answers how many visual lines it took. */
    private fun drawRow(g: Graphics2D, row: DiffShot.Row, layout: Layout, y: Int): Int {
        val lines = layout.linesOf(row)
        val oldChunks = chunksOf(row.old, layout.cols)
        val nextChunks = chunksOf(row.new, layout.cols)
        val changed = !row.same
        // the hot middle needs one line per side: a wrapped side is tinted whole
        val spans = if (changed && oldChunks.size <= 1 && nextChunks.size <= 1) {
            spansOf(row)
        } else {
            IntRange.EMPTY to IntRange.EMPTY
        }
        if (changed && layout.showsOld && row.old != null) {
            fillCell(g, DEL_CELL, layout.oldGutterX, layout, y, lines)
        }
        if (changed && layout.showsNew && row.new != null) {
            fillCell(g, ADD_CELL, layout.newGutterX, layout, y, lines)
        }
        if (layout.showsOld) {
            row.oldNo?.let { drawNumber(g, it, layout.oldGutterX, y) }
            drawChunks(g, oldChunks, layout.oldX, layout, y, spans.first, DEL_HOT)
        }
        if (layout.showsNew) {
            row.newNo?.let { drawNumber(g, it, layout.newGutterX, y) }
            drawChunks(g, nextChunks, layout.newX, layout, y, spans.second, ADD_HOT)
        }
        return lines
    }

    private fun drawNumber(g: Graphics2D, no: Int, gutterX: Int, y: Int) {
        g.color = GUTTER
        g.drawString(no.toString(), gutterX, y)
    }

    private fun fillCell(g: Graphics2D, cell: Color, gutterX: Int, layout: Layout, y: Int, lines: Int) {
        g.color = cell
        g.fillRect(gutterX, y - layout.ascent, layout.gutterW + layout.colW, lines * LINE_HEIGHT)
    }

    /** The pair's changed middles; a lone side has none (its whole cell is hot). */
    private fun spansOf(row: DiffShot.Row): Pair<IntRange, IntRange> {
        // bound to locals: the row's properties are another module's API
        val old = row.old
        val next = row.new
        return if (old != null && next != null) {
            DiffShot.changedSpan(old, next)
        } else {
            IntRange.EMPTY to IntRange.EMPTY
        }
    }

    /** A side's visuals: one chunk per visual line, wrapped — never clipped. */
    private fun chunksOf(line: String?, cols: Int): List<String> =
        if (line == null) emptyList() else PngRender.wrap(line, cols).split('\n')

    /** Every chunk of one side; the hot middle only ever rides the first. */
    private fun drawChunks(
        g: Graphics2D,
        chunks: List<String>,
        x: Int,
        layout: Layout,
        y: Int,
        span: IntRange,
        hot: Color,
    ) {
        chunks.forEachIndexed { index, chunk ->
            val lineY = y + index * LINE_HEIGHT
            if (index == 0 && !span.isEmpty() && span.last < chunk.length) {
                g.color = hot
                g.fillRect(
                    x - 2 + span.first * layout.charW,
                    lineY - layout.ascent,
                    (span.last - span.first + 1) * layout.charW,
                    LINE_HEIGHT,
                )
            }
            g.color = TEXT
            g.drawString(chunk, x, lineY)
        }
    }

    /** The one measurement pass: font metrics and every x/y the rows use. */
    private class Layout(title: String?, sides: Sides, val cols: Int) {
        val font: Font = Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE)
        private val metrics = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().also {
            it.font = font
        }.fontMetrics
        val charW: Int = metrics.charWidth('M')
        val ascent: Int = metrics.ascent
        val colW: Int = charW * cols
        val gutterW: Int = charW * GUTTER_CHARS
        val titleH: Int = if (title == null) 0 else LINE_HEIGHT + 8
        val showsOld: Boolean = sides != Sides.NEW
        val showsNew: Boolean = sides != Sides.OLD
        val oldGutterX: Int = PAD
        val oldX: Int = PAD + gutterW
        val newGutterX: Int = if (showsOld) oldX + colW + GAP else PAD
        val newX: Int = newGutterX + gutterW
        val width: Int = PAD * 2 + column(showsOld) + (if (showsOld && showsNew) GAP else 0) + column(showsNew)

        private fun column(shown: Boolean): Int = if (shown) gutterW + colW else 0

        /** How many visual lines a row needs: the longest side after wrapping. */
        fun linesOf(row: DiffShot.Row): Int =
            maxOf(chunkCount(row.old), chunkCount(row.new)).coerceAtLeast(1)

        private fun chunkCount(line: String?): Int =
            if (line == null) 0 else PngRender.wrap(line, cols).split('\n').size

        fun height(plan: DiffShot.Plan): Int {
            val content = PAD * 2 + titleH + plan.rows.sumOf { linesOf(it) } * LINE_HEIGHT +
                if (plan.truncated) LINE_HEIGHT else 0
            // a short diff pads down to a sane aspect: never a rejected banner
            return maxOf(content, width / MAX_ASPECT)
        }
    }
}
