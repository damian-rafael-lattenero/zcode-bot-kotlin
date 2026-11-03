package dev.lattenero.bot.daemon.metrics

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** E2's knob: absent/typo ⇒ OFF without breaking anything; the port falls back to 9102 loudly. */
class MetricsKnobTest {

    private fun knobFile(text: String?): File {
        val dir = Files.createTempDirectory("metrics-knob").toFile()
        val file = File(dir, "daemon.json")
        if (text != null) file.writeText(text)
        return file
    }

    @Test
    fun missingFileIsOffAtTheDefaultPortWithoutAComplaint() {
        val reading = MetricsKnob.load(File(knobFile(null).parentFile, "nope.json"))
        assertFalse(reading.on)
        assertEquals(9102, reading.port)
        assertEquals(null, reading.warning)
    }

    @Test
    fun absentKeysAreOffAtTheDefaultPortWithoutAComplaint() {
        val reading = MetricsKnob.load(knobFile("""{"send":"live","poll":true}"""))
        assertFalse(reading.on)
        assertEquals(9102, reading.port)
        assertEquals(null, reading.warning)
    }

    @Test
    fun theAcceptedSpellings() {
        listOf("true", "\"true\"", "\"on\"", "\"YES\"", "\"1\"").forEach { spelling ->
            val reading = MetricsKnob.load(knobFile("""{"metrics":$spelling}"""))
            assertTrue(reading.on, "expected ON for $spelling")
        }
        listOf("false", "\"false\"", "\"off\"", "\"no\"", "\"0\"").forEach { spelling ->
            val reading = MetricsKnob.load(knobFile("""{"metrics":$spelling}"""))
            assertFalse(reading.on, "expected OFF for $spelling")
        }
    }

    @Test
    fun aTypoIsOffLoudly() {
        val reading = MetricsKnob.load(knobFile("""{"metrics":"treu"}"""))
        assertFalse(reading.on)
        assertTrue(reading.warning!!.contains("treu"))
    }

    @Test
    fun aBareNumberSwitchIsOffLoudly() {
        val reading = MetricsKnob.load(knobFile("""{"metrics":1}"""))
        assertFalse(reading.on)
        assertTrue(reading.warning!!.contains("unknown 'metrics' value"))
    }

    @Test
    fun anUnreadableFileIsOffLoudly() {
        val reading = MetricsKnob.load(knobFile("not json at all"))
        assertFalse(reading.on)
        assertEquals(9102, reading.port)
        assertTrue(reading.warning!!.contains("unreadable"))
    }

    @Test
    fun theOtherKnobsAreNotConfusedWithThisOne() {
        val reading = MetricsKnob.load(knobFile("""{"poll":true,"send":"live","memory_inject":true}"""))
        assertFalse(reading.on)
        assertEquals(null, reading.warning)
    }

    @Test
    fun thePortReadsNumbersAndIntegerStrings() {
        assertEquals(9999, MetricsKnob.load(knobFile("""{"metrics_port":9999}""")).port)
        assertEquals(8080, MetricsKnob.load(knobFile("""{"metrics_port":"8080"}""")).port)
        assertEquals(9102, MetricsKnob.load(knobFile("""{"metrics_port": 9102 }""")).port)
    }

    @Test
    fun aGarbagePortFallsBackToTheDefaultLoudly() {
        listOf(
            """{"metrics_port":"nueve-mil"}""",
            """{"metrics_port":9102.5}""",
            """{"metrics_port":true}""",
            """{"metrics_port":[]}""",
            """{"metrics_port":null}""",
        ).forEach { text ->
            val reading = MetricsKnob.load(knobFile(text))
            assertEquals(9102, reading.port, "expected the default port for $text")
            assertTrue(reading.warning != null, "expected a warning for $text")
        }
    }

    @Test
    fun anOutOfRangePortFallsBackToTheDefaultLoudly() {
        val zero = MetricsKnob.load(knobFile("""{"metrics_port":0}"""))
        assertEquals(9102, zero.port)
        assertTrue(zero.warning!!.contains("out of range"))
        val tooBig = MetricsKnob.load(knobFile("""{"metrics_port":70000}"""))
        assertEquals(9102, tooBig.port)
        assertTrue(tooBig.warning!!.contains("out of range"))
    }

    @Test
    fun thePortTypoIsLoudEvenWithTheSwitchOff() {
        val reading = MetricsKnob.load(knobFile("""{"metrics":false,"metrics_port":"oops"}"""))
        assertFalse(reading.on)
        assertTrue(reading.warning!!.contains("metrics_port"))
    }

    @Test
    fun bothWarningsLandTogether() {
        val reading = MetricsKnob.load(knobFile("""{"metrics":"treu","metrics_port":"oops"}"""))
        assertTrue(reading.warning!!.contains("treu"))
        assertTrue(reading.warning.contains("metrics_port"))
    }
}
