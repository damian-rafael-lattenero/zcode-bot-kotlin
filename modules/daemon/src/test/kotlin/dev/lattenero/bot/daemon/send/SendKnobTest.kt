package dev.lattenero.bot.daemon.send

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cutover knob's reading rules: the missing file is SHADOW (a daemon
 * deployed before the cutover existed), a bad value is SHADOW LOUDLY (a
 * typo must never silently send — nor silently flip), the spellings are
 * case/underscore tolerant.
 */
class SendKnobTest {

    @TempDir
    lateinit var dir: File

    private fun knob(text: String?): KnobReading {
        val f = File(dir, SendKnob.FILE_NAME)
        if (text != null) f.writeText(text)
        return SendKnob.load(f)
    }

    @Test
    fun `missing file is shadow with no complaint`() {
        val r = knob(text = null)
        assertEquals(SendMode.SHADOW, r.mode)
        assertNull(r.warning)
    }

    @Test
    fun `the three positions`() {
        assertEquals(SendMode.SHADOW, knob("""{"send": "shadow"}""").mode)
        assertEquals(SendMode.DRY_RUN, knob("""{"send": "dry-run"}""").mode)
        assertEquals(SendMode.LIVE, knob("""{"send": "live"}""").mode)
    }

    @Test
    fun `spelling tolerance - case, trim, underscores`() {
        assertEquals(SendMode.DRY_RUN, knob("""{"send": " Dry-Run "}""").mode)
        assertEquals(SendMode.DRY_RUN, knob("""{"send": "dry_run"}""").mode)
        assertEquals(SendMode.DRY_RUN, knob("""{"send": "DRYRUN"}""").mode)
        assertEquals(SendMode.LIVE, knob("""{"send": "Live"}""").mode)
    }

    @Test
    fun `unknown value stays shadow and says so`() {
        val r = knob("""{"send": "prod"}""")
        assertEquals(SendMode.SHADOW, r.mode)
        assertTrue(r.warning!!.contains("prod"), "the complaint names the bad value")
    }

    @Test
    fun `broken json stays shadow and says so`() {
        val r = knob("""{"send": "li""")
        assertEquals(SendMode.SHADOW, r.mode)
        assertTrue(r.warning != null)
    }

    @Test
    fun `no send key stays shadow`() {
        val r = knob("""{"other": 1}""")
        assertEquals(SendMode.SHADOW, r.mode)
        assertTrue(r.warning != null)
    }

    @Test
    fun `parse covers the knob card exactly`() {
        assertNull(parseSendMode("prod"))
        assertNull(parseSendMode(""))
    }
}
