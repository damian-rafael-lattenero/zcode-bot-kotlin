package dev.lattenero.bot.daemon

import dev.lattenero.bot.daemon.send.SendKnob
import dev.lattenero.bot.daemon.send.SendMode
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The poll knob's reading rules: OFF is the shipped state, OFF is the
 * degrade of every bad reading (a typo must never start a SECOND poller
 * next to Python's — the 409 the single-reader rule exists to prevent).
 */
class PollKnobTest {

    private fun knob(json: String?): PollKnobReading {
        val f = Files.createTempDirectory("knob").resolve("daemon.json").toFile()
        if (json != null) f.writeText(json)
        return PollKnob.load(f)
    }

    @Test
    fun missingFileAndMissingKeyAreSilentlyOff() {
        assertEquals(PollKnobReading(false), knob(null))
        assertEquals(PollKnobReading(false), knob("""{"send":"live"}"""))
    }

    @Test
    fun trueTurnsItOn() {
        assertTrue(knob("""{"send":"live","poll":true}""").on)
        assertTrue(knob("""{"poll":true}""").on)
    }

    @Test
    fun falseAndSpellings() {
        assertFalse(knob("""{"poll":false}""").on)
        assertTrue(knob("""{"poll":"on"}""").on)
        assertTrue(knob("""{"poll":"YES"}""").on)
        assertFalse(knob("""{"poll":"off"}""").on)
    }

    @Test
    fun badValuesAreOffLoudly() {
        val typo = knob("""{"poll":"mañana"}""")
        assertFalse(typo.on)
        assertEquals("unknown 'poll' value \"mañana\" — staying OFF", typo.warning)

        val number = knob("""{"poll":3}""")
        assertFalse(number.on)
        assertEquals("unknown 'poll' value 3 — staying OFF", number.warning)

        val array = knob("""{"poll":[true]}""")
        assertFalse(array.on)
        assertEquals("'poll' is not a boolean — staying OFF", array.warning)

        val broken = knob("""{"poll":tru""")
        assertFalse(broken.on)
        assertTrue(broken.warning!!.startsWith("unreadable"))
    }

    @Test
    fun theKnobSharesTheSendKnobsFile() {
        // one daemon.json, two knobs: the file the LaunchAgent documents
        assertEquals("poll", PollKnob.KEY)
        assertEquals("daemon.json", SendKnob.FILE_NAME)
        val dir = Files.createTempDirectory("knob").toFile()
        val f = File(dir, "daemon.json")
        f.writeText("""{"send":"live","poll":true}""")
        assertEquals(SendMode.LIVE, SendKnob.load(f).mode)
        assertTrue(PollKnob.load(f).on)
    }

    @Test
    fun noWarningOnCleanReadings() {
        assertNull(knob("""{"poll":true}""").warning)
        assertNull(knob("""{"poll":false}""").warning)
    }
}
