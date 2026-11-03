package dev.lattenero.bot.contracts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bus WIRE (bot ADR-019): every produced line is field-for-field the
 * golden fixture's shape (agent-brain's copy, `bus/sample-bot-publisher.jsonl`).
 * JSON whitespace is not part of the contract; the seven fields, their
 * order and their types are.
 */
class BusWireTest {

    private fun fixtureLines(): List<String> =
        checkNotNull(javaClass.getResourceAsStream("/bus/sample-bot-publisher.jsonl")) { "fixture missing" }
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }

    @Test
    fun oneLineIsTheSevenFieldsInContractOrder() {
        val event = BusWire.event(
            kind = "turn.started",
            sid = "ses_fixture01",
            turn = "1761685200123",
            payload = buildJsonObject {
                put("title", "CV")
                put("model", "glm-5.3")
                put("ws", "/Users/x/Projects/cv")
                put("prompt_chars", 17)
                put("prompt", "reorder my cv ñandú 🧠")
            },
            ts = Instant.parse("2026-10-28T20:47:20.859Z"),
            id = "b5157eae43294447b1e1ada61e2874d2",
        )
        val line = BusWire.encode(event)
        val expected = """{"id":"b5157eae43294447b1e1ada61e2874d2","ts":"2026-10-28T20:47:20.859Z",""" +
            """"kind":"turn.started","sid":"ses_fixture01","turn":"1761685200123","source":"bot",""" +
            """"payload":{"title":"CV","model":"glm-5.3","ws":"/Users/x/Projects/cv",""" +
            """"prompt_chars":17,"prompt":"reorder my cv ñandú 🧠"}}"""
        assertEquals(expected, line)

        // and the field ORDER is the fixture's, not just the set
        val keys = listOf("id", "ts", "kind", "sid", "turn", "source", "payload")
        val indexes = keys.map { line.indexOf("\"$it\":") }
        assertEquals(indexes.sorted(), indexes, "fields must appear in contract order: $line")
        assertTrue(BusWire.line(event).endsWith("\n"))
    }

    @Test
    fun everyFixtureLineParsesAndReencodesWithTheSameFieldsInTheSameOrder() {
        val lines = fixtureLines()
        assertEquals(6, lines.size) // the six kinds, one each
        lines.forEach { line ->
            val parsed = BusEvents.parse(line)
            val event = (parsed as? BusEvents.Parsed.Ok)?.event ?: error("fixture line must parse: $parsed")
            val original = Json.parseToJsonElement(line).jsonObject
            val reencoded = Json.parseToJsonElement(BusWire.encode(event)).jsonObject
            assertEquals(original.keys.toList(), reencoded.keys.toList(), "field order drifted")
            assertEquals(original, reencoded, "values drifted: $line")
            assertTrue(BusWire.encode(event).endsWith("}") && !BusWire.encode(event).contains("\n"))
        }
    }

    @Test
    fun everyKindTheFixtureCarriesIsAKindTheProducerMayEmit() {
        val kinds = fixtureLines().map { (BusEvents.parse(it) as BusEvents.Parsed.Ok).event.kind }
        assertEquals(
            listOf("turn.started", "tool.call", "tool.result", "turn.completed", "user.prompt", "turn.failed"),
            kinds,
        )
        assertEquals(BusEvents.KINDS, kinds.toSet())
    }

    @Test
    fun timestampAlwaysCarriesMillisAndZulu() {
        assertEquals("2026-10-28T20:47:20.859Z", BusWire.timestamp(Instant.parse("2026-10-28T20:47:20.859Z")))
        assertEquals("2026-10-28T20:47:20.000Z", BusWire.timestamp(Instant.parse("2026-10-28T20:47:20Z")))
        // nanos are TRUNCATED to millis — never a 6- or 9-digit fraction
        assertEquals(
            "2026-10-28T20:47:20.859Z",
            BusWire.timestamp(Instant.parse("2026-10-28T20:47:20.859999999Z")),
        )
    }

    @Test
    fun clipCapsAtFourThousandCodePointsWithoutBreakingSurrogates() {
        val long = "🧠".repeat(BusWire.TEXT_CAP + 10)
        val clipped = BusWire.clip(long)
        assertEquals(BusWire.TEXT_CAP + 1, PyText.cpLength(clipped)) // cap + the ellipsis
        assertTrue(clipped.endsWith("…"))
        assertEquals("short", BusWire.clip("short"))
        assertEquals("", BusWire.clip(null))
    }

    @Test
    fun idsAreThirtyTwoLowercaseHexChars() {
        repeat(20) {
            val id = BusWire.newId()
            assertEquals(32, id.length)
            assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' }, "not uuid4 hex: $id")
        }
    }

    @Test
    fun unknownKindOrSourceIsAProgrammerError() {
        val payload = JsonObject(emptyMap())
        assertTrue(
            runCatching {
                BusWire.event("nope", "", "", payload, Instant.EPOCH, "a")
            }.isFailure,
        )
        assertTrue(
            runCatching {
                BusWire.event("user.prompt", "", "", payload, Instant.EPOCH, "a", source = "alien")
            }.isFailure,
        )
    }
}
