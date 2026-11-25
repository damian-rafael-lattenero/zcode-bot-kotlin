package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.DiffShot
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
    private const val COL_CHARS = 64
    private const val TITLE_COLS = COL_CHARS * 2

    private val CANVAS = Color(0x0B, 0x3B, 0x3C)
    private val TEXT = Color(0xD8, 0xE8, 0xE6)
    private val GUTTER = Color(0x7F, 0xA6, 0xA2)
    private val TITLE = Color(0xF2, 0xC9, 0x8A)
    private val DEL_CELL = Color(0x3A, 0x1E, 0x22)
    private val ADD_CELL = Color(0x1E, 0x3A, 0x28)
    private val DEL_HOT = Color(0x7A, 0x2E, 0x36)
    private val ADD_HOT = Color(0x2E, 0x6B, 0x45)

    /** Draws the diff into [target]; false when the text carries no rows. */
    fun render(diff: String, target: File, title: String? = null): Boolean {
        val plan = DiffShot.rows(diff)
        if (plan.rows.isEmpty()) return false
        val layout = Layout(title)
        val canvas = BufferedImage(layout.width, layout.height(plan), BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        try {
            paint(g, plan, title, layout)
        } finally {
            g.dispose()
        }
        return ImageIO.write(canvas, "png", target)
    }

    private fun paint(g: Graphics2D, plan: DiffShot.Plan, title: String?, layout: Layout) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = CANVAS
        g.fillRect(0, 0, layout.width, layout.height(plan))
        g.font = layout.font
        var y = PAD + layout.ascent
        if (title != null) {
            g.color = TITLE
            g.drawString(clip(title, TITLE_COLS), PAD, y)
            y += layout.titleH
        }
        plan.rows.forEach { row ->
            drawRow(g, row, layout, y)
            y += LINE_HEIGHT
        }
        if (plan.truncated) {
            g.color = GUTTER
            g.drawString("… (más cambios en el texto)", PAD, y)
        }
    }

    private fun drawRow(g: Graphics2D, row: DiffShot.Row, layout: Layout, y: Int) {
        if (!row.same) {
            row.old?.let {
                g.color = DEL_CELL
                g.fillRect(PAD, y - layout.ascent, layout.gutterW + layout.colW, LINE_HEIGHT)
            }
            row.new?.let {
                g.color = ADD_CELL
                g.fillRect(layout.rightX - layout.gutterW, y - layout.ascent, layout.gutterW + layout.colW, LINE_HEIGHT)
            }
        }
        g.color = GUTTER
        row.oldNo?.let { g.drawString(it.toString(), PAD, y) }
        row.newNo?.let { g.drawString(it.toString(), layout.rightX - layout.gutterW, y) }
        val spans = spansOf(row)
        drawSide(g, row.old, layout.leftX, y, spans.first, if (row.same) null else DEL_HOT, layout)
        drawSide(g, row.new, layout.rightX, y, spans.second, if (row.same) null else ADD_HOT, layout)
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

    /** The line in grey plus its changed middle on a hotter cell ([hot] null = context). */
    private fun drawSide(
        g: Graphics2D,
        line: String?,
        x: Int,
        y: Int,
        span: IntRange,
        hot: Color?,
        layout: Layout,
    ) {
        if (line == null) return
        val shown = clip(line, COL_CHARS)
        if (hot != null && !span.isEmpty() && span.last < shown.length) {
            g.color = hot
            g.fillRect(
                x - 2 + span.first * layout.charW,
                y - layout.ascent,
                (span.last - span.first + 1) * layout.charW,
                LINE_HEIGHT,
            )
        }
        g.color = TEXT
        g.drawString(shown, x, y)
    }

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    /** The one measurement pass: font metrics and every x/y the rows use. */
    private class Layout(title: String?) {
        val font: Font = Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE)
        private val metrics = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().also {
            it.font = font
        }.fontMetrics
        val charW: Int = metrics.charWidth('M')
        val ascent: Int = metrics.ascent
        val colW: Int = charW * COL_CHARS
        val gutterW: Int = charW * GUTTER_CHARS
        val titleH: Int = if (title == null) 0 else LINE_HEIGHT + 8
        val width: Int = PAD * 2 + gutterW * 2 + colW * 2 + GAP
        val leftX: Int = PAD + gutterW
        val rightX: Int = leftX + colW + GAP + gutterW

        fun height(plan: DiffShot.Plan): Int =
            PAD * 2 + titleH + (plan.rows.size + if (plan.truncated) 1 else 0) * LINE_HEIGHT
    }
}
