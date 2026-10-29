package dev.lattenero.bot.contracts

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The bus contract: field-for-field the mirror of agent-brain's BusEvent (bot ADR-019). */
class BusEventContractTest {

    private val line =
        """{"id":"abc123","ts":"2026-10-29T12:00:00.123Z","kind":"user.prompt","sid":"ses_x","turn":"1793200000000","source":"bot","payload":{"q":"hi"}}"""

    @Test
    fun `a well-formed line parses with all seven fields`() {
        val parsed = BusEvents.parse(line)
        assertTrue(parsed is BusEvents.Parsed.Ok, "expected Ok, got $parsed")
        val e = (parsed as BusEvents.Parsed.Ok).event
        assertEquals("abc123", e.id)
        assertEquals("user.prompt", e.kind)
        assertEquals("ses_x", e.sid)
        assertEquals("bot", e.source)
        assertEquals("\"hi\"", e.payload["q"].toString()) // payload survived as JSON
    }

    @Test
    fun `unknown fields and missing optionals are tolerated`() {
        val padded = line.removeSuffix("}") + ""","extra":{"any":[1,2]}}"""
        val e = (BusEvents.parse(padded) as BusEvents.Parsed.Ok).event
        assertEquals("1793200000000", e.turn)   // the known fields survived the extra one
        val minimal = """{"id":"a","ts":"2026-10-29T12:00:00Z","kind":"user.prompt"}"""
        val m = (BusEvents.parse(minimal) as BusEvents.Parsed.Ok).event
        assertTrue(m.turn.isEmpty() && m.source == "bot" && m.payload.isEmpty())
    }

    @Test
    fun `bad kind, bad source, bad ts and empty id are reported, never thrown`() {
        assertTrue(BusEvents.parse("""{"id":"","ts":"2026-10-29T12:00:00Z","kind":"user.prompt"}""") is BusEvents.Parsed.Bad)
        assertTrue(BusEvents.parse("""{"id":"a","ts":"2026-10-29T12:00:00Z","kind":"nope"}""") is BusEvents.Parsed.Bad)
        assertTrue(BusEvents.parse("""{"id":"a","ts":"2026-10-29T12:00:00Z","kind":"user.prompt","source":"alien"}""") is BusEvents.Parsed.Bad)
        assertTrue(BusEvents.parse("""{"id":"a","ts":"not-a-time","kind":"user.prompt"}""") is BusEvents.Parsed.Bad)
        assertTrue(BusEvents.parse("garbage") is BusEvents.Parsed.Bad)
    }

    @Test
    fun `encoding emits exactly the seven contract keys`() {
        val e = (BusEvents.parse(line) as BusEvents.Parsed.Ok).event
        // encodeDefaults: kotlinx omits values equal to their default (source
        // "bot"); force them on to see the full wire shape.
        val encoded = Json { encodeDefaults = true }.encodeToString(e)
        val keys = Json.parseToJsonElement(encoded).jsonObject.keys
        assertEquals(setOf("id", "ts", "kind", "sid", "turn", "source", "payload"), keys)
    }
}
