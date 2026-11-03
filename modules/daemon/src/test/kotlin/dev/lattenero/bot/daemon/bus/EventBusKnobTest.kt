package dev.lattenero.bot.daemon.bus

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** U1b's knob: absent/typo ⇒ OFF without breaking anything; the accepted spellings ON. */
class EventBusKnobTest {

    private fun knobFile(text: String?): File {
        val dir = Files.createTempDirectory("event-bus-knob").toFile()
        val file = File(dir, "daemon.json")
        if (text != null) file.writeText(text)
        return file
    }

    @Test
    fun missingFileIsOffWithoutAComplaint() {
        val reading = EventBusKnob.load(File(knobFile(null).parentFile, "nope.json"))
        assertFalse(reading.on)
        assertEquals(null, reading.warning)
    }

    @Test
    fun absentKeyIsOffWithoutAComplaint() {
        val reading = EventBusKnob.load(knobFile("""{"send":"live","poll":true}"""))
        assertFalse(reading.on)
        assertEquals(null, reading.warning)
    }

    @Test
    fun theAcceptedSpellings() {
        listOf("true", "\"true\"", "\"on\"", "\"YES\"", "\"1\"").forEach { spelling ->
            val reading = EventBusKnob.load(knobFile("""{"event_bus":$spelling}"""))
            assertTrue(reading.on, "expected ON for $spelling")
        }
        listOf("false", "\"false\"", "\"off\"", "\"no\"", "\"0\"").forEach { spelling ->
            val reading = EventBusKnob.load(knobFile("""{"event_bus":$spelling}"""))
            assertFalse(reading.on, "expected OFF for $spelling")
        }
    }

    @Test
    fun aBareNumberIsOffLoudlyLikePollKnob() {
        // PollKnob's exact rule: only JSON true/false or the spelled strings count
        val reading = EventBusKnob.load(knobFile("""{"event_bus":1}"""))
        assertFalse(reading.on)
        assertTrue(reading.warning!!.contains("unknown 'event_bus' value"))
    }

    @Test
    fun aTypoIsOffLoudly() {
        val reading = EventBusKnob.load(knobFile("""{"event_bus":"treu"}"""))
        assertFalse(reading.on)
        assertTrue(reading.warning!!.contains("treu"))
    }

    @Test
    fun aNonBooleanIsOffLoudly() {
        val reading = EventBusKnob.load(knobFile("""{"event_bus":{"nested":true}}"""))
        assertFalse(reading.on)
        assertTrue(reading.warning!!.contains("not a boolean"))
    }

    @Test
    fun anUnreadableFileIsOffLoudly() {
        val reading = EventBusKnob.load(knobFile("not json at all"))
        assertFalse(reading.on)
        assertTrue(reading.warning!!.contains("unreadable"))
    }

    @Test
    fun theOtherKnobsAreNotConfusedWithThisOne() {
        val reading = EventBusKnob.load(knobFile("""{"poll":true,"send":"live","memory_inject":true}"""))
        assertFalse(reading.on)
        assertEquals(null, reading.warning)
    }
}
