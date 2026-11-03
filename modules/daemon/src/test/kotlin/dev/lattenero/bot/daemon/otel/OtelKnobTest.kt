package dev.lattenero.bot.daemon.otel

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E3's knob pair: absent/typo ⇒ OFF without breaking anything; the
 * endpoint falls back to the OTLP default loudly — whatever the switch
 * says (a typo must be loud even with the feature off).
 */
class OtelKnobTest {

    private fun knobFile(text: String?): File {
        val dir = Files.createTempDirectory("otel-knob").toFile()
        val file = File(dir, "daemon.json")
        if (text != null) file.writeText(text)
        return file
    }

    @Test
    fun missingFileIsOffAtTheDefaultEndpointWithoutAComplaint() {
        val reading = OtelKnob.load(File(knobFile(null).parentFile, "nope.json"))
        assertFalse(reading.on)
        assertEquals(OtelKnob.DEFAULT_ENDPOINT, reading.endpoint)
        assertNull(reading.warning)
    }

    @Test
    fun absentKeysAreOffAtTheDefaultEndpointWithoutAComplaint() {
        val reading = OtelKnob.load(knobFile("""{"send":"live","poll":true}"""))
        assertFalse(reading.on)
        assertEquals(OtelKnob.DEFAULT_ENDPOINT, reading.endpoint)
        assertNull(reading.warning)
    }

    @Test
    fun theAcceptedSpellings() {
        listOf("true", "\"true\"", "\"on\"", "\"YES\"", "\"1\"").forEach { spelling ->
            val reading = OtelKnob.load(knobFile("""{"otel":$spelling}"""))
            assertTrue(reading.on, "expected ON for $spelling")
        }
        listOf("false", "\"false\"", "\"off\"", "\"no\"", "\"0\"").forEach { spelling ->
            val reading = OtelKnob.load(knobFile("""{"otel":$spelling}"""))
            assertFalse(reading.on, "expected OFF for $spelling")
        }
    }

    @Test
    fun aTypoIsOffLoudly() {
        val reading = OtelKnob.load(knobFile("""{"otel":"treu"}"""))
        assertFalse(reading.on)
        assertTrue(reading.warning!!.contains("treu"))
    }

    @Test
    fun anExplicitEndpointIsKept() {
        val reading = OtelKnob.load(
            knobFile("""{"otel":true,"otel_endpoint":"http://127.0.0.1:4318/v1/traces"}"""),
        )
        assertTrue(reading.on)
        assertEquals("http://127.0.0.1:4318/v1/traces", reading.endpoint)
        assertNull(reading.warning)
    }

    @Test
    fun anAbsentEndpointIsTheOtlpDefaultSilently() {
        val reading = OtelKnob.load(knobFile("""{"otel":true}"""))
        assertTrue(reading.on)
        assertEquals(OtelKnob.DEFAULT_ENDPOINT, reading.endpoint)
        assertNull(reading.warning)
    }

    @Test
    fun aGarbageEndpointFallsBackToTheDefaultLoudly() {
        val reading = OtelKnob.load(knobFile("""{"otel":true,"otel_endpoint":"127.0.0.1:4318"}"""))
        assertTrue(reading.on)
        assertEquals(OtelKnob.DEFAULT_ENDPOINT, reading.endpoint)
        assertTrue(reading.warning!!.contains("not an http(s) URL"))
    }

    @Test
    fun aNonHttpSchemeFallsBackToTheDefaultLoudly() {
        val reading = OtelKnob.load(knobFile("""{"otel":true,"otel_endpoint":"grpc://collector:4317"}"""))
        assertEquals(OtelKnob.DEFAULT_ENDPOINT, reading.endpoint)
        assertTrue(reading.warning!!.contains("grpc://collector:4317"))
    }

    @Test
    fun aNonStringEndpointFallsBackToTheDefaultLoudly() {
        val reading = OtelKnob.load(knobFile("""{"otel":true,"otel_endpoint":4318}"""))
        assertEquals(OtelKnob.DEFAULT_ENDPOINT, reading.endpoint)
        assertTrue(reading.warning!!.contains("not a string"))
    }

    @Test
    fun theEndpointIsReadEvenWithTheSwitchOff() {
        // a typo must be loud whatever the switch says
        val reading = OtelKnob.load(knobFile("""{"otel":false,"otel_endpoint":"no-scheme"}"""))
        assertFalse(reading.on)
        assertTrue(reading.warning!!.contains("not an http(s) URL"))
    }

    @Test
    fun bothWarningsJoinIntoOneLine() {
        val reading = OtelKnob.load(knobFile("""{"otel":"treu","otel_endpoint":42}"""))
        assertFalse(reading.on)
        assertTrue(reading.warning!!.contains("treu"))
        assertTrue(reading.warning.contains("not a string"))
    }

    @Test
    fun anHttpsEndpointIsAccepted() {
        val reading = OtelKnob.load(
            knobFile("""{"otel":true,"otel_endpoint":"https://collector.internal:4318/v1/traces"}"""),
        )
        assertTrue(reading.on)
        assertEquals("https://collector.internal:4318/v1/traces", reading.endpoint)
        assertNull(reading.warning)
    }
}
