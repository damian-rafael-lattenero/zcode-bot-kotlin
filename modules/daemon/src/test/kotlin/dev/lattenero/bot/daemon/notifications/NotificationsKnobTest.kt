package dev.lattenero.bot.daemon.notifications

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The notifications knob's law — the INVERSE of the house knobs: the
 * owner's explicit order makes ON the safe default, so absent/missing/
 * garbage all stay ON (loudly) and only an explicit false silences.
 */
class NotificationsKnobTest {

    private fun knobFile(text: String?): File {
        val dir = Files.createTempDirectory("notifications-knob").toFile()
        val file = File(dir, "daemon.json")
        if (text != null) file.writeText(text)
        return file
    }

    @Test
    fun missingFileIsOnWithoutAComplaint() {
        val reading = NotificationsKnob.load(File(knobFile(null).parentFile, "nope.json"))
        assertTrue(reading.on)
        assertNull(reading.warning)
    }

    @Test
    fun absentKeyIsOnWithoutAComplaint() {
        val reading = NotificationsKnob.load(knobFile("""{"send":"live","poll":true}"""))
        assertTrue(reading.on)
        assertNull(reading.warning)
    }

    @Test
    fun theAcceptedSpellings() {
        listOf("true", "\"true\"", "\"on\"", "\"YES\"", "\"1\"").forEach { spelling ->
            val reading = NotificationsKnob.load(knobFile("""{"notifications":$spelling}"""))
            assertTrue(reading.on, "expected ON for $spelling")
        }
        listOf("false", "\"false\"", "\"off\"", "\"no\"", "\"0\"").forEach { spelling ->
            val reading = NotificationsKnob.load(knobFile("""{"notifications":$spelling}"""))
            assertTrue(!reading.on, "expected OFF for $spelling")
        }
    }

    @Test
    fun aBareNumberStaysOnLoudly() {
        val reading = NotificationsKnob.load(knobFile("""{"notifications":1}"""))
        assertTrue(reading.on, "garbage keeps the owner's order: ON")
        assertNotNull(reading.warning)
        assertTrue(reading.warning!!.contains("unknown 'notifications' value"))
    }

    @Test
    fun aTypoStaysOnLoudly() {
        val reading = NotificationsKnob.load(knobFile("""{"notifications":"treu"}"""))
        assertTrue(reading.on)
        assertTrue(reading.warning!!.contains("treu"))
    }

    @Test
    fun aNonBooleanStaysOnLoudly() {
        val reading = NotificationsKnob.load(knobFile("""{"notifications":{"nested":true}}"""))
        assertTrue(reading.on)
        assertTrue(reading.warning!!.contains("not a boolean"))
    }

    @Test
    fun anUnreadableFileStaysOnLoudly() {
        val reading = NotificationsKnob.load(knobFile("not json at all"))
        assertTrue(reading.on)
        assertTrue(reading.warning!!.contains("unreadable"))
    }

    @Test
    fun theOtherKnobsAreNotConfusedWithThisOne() {
        val reading = NotificationsKnob.load(knobFile("""{"poll":true,"send":"live","event_bus":false}"""))
        assertTrue(reading.on)
        assertNull(reading.warning)
    }

    @Test
    fun falseSilencesEvenBetweenOtherKnobs() {
        val reading = NotificationsKnob.load(knobFile("""{"send":"live","notifications":false,"poll":true}"""))
        assertEquals(false, reading.on)
        assertNull(reading.warning)
    }
}
