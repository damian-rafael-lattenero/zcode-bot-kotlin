package dev.lattenero.bot.contracts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The bus WIRE (bot ADR-019): the producer half of [BusEvent]. Everything
 * here is pure — no file, no clock of its own (the timestamp and the id are
 * injected) — so the golden vectors pin the exact line shape:
 *
 * `{"id","ts","kind","sid","turn","source","payload"}` — all SEVEN fields,
 * in the data class's declaration order, `payload` last. The old Python
 * publisher (`bus.py`) wrote `json.dumps(..., ensure_ascii=False)` with
 * spaces after the separators; JSON whitespace is not part of the contract
 * (agent-brain parses each line as JSON), but the FIELDS, their order and
 * their types are.
 *
 * `ts` is ISO-8601 UTC with exactly three fraction digits and a literal
 * `Z` (Python's `%Y-%m-%dT%H:%M:%S.%03dZ`) — never ISO_INSTANT's variable
 * precision. Free-text payload fields are clipped to [TEXT_CAP] code points
 * plus `…` so one append is one small line (the tailer's torn-line rule).
 */
object BusWire {

    /** Python's PAYLOAD_TEXT_CAP, verbatim. */
    const val TEXT_CAP = 4000

    private const val ELLIPSIS = "…"

    /**
     * encodeDefaults is the contract: `sid`/`turn`/`source`/`payload` have
     * defaults in the data class, and the fixture always carries all seven.
     */
    private val json = Json { encodeDefaults = true }

    private val TS_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** Python's `_now_iso()`: millisecond precision, UTC, literal Z. */
    fun timestamp(instant: Instant): String = TS_FORMAT.format(instant)

    /** uuid4().hex — 32 lowercase hex chars, no dashes. */
    fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    /** Python's `bus.clip`: code-point cap plus the ellipsis, never a surrogate split. */
    fun clip(text: String?): String {
        val s = text ?: ""
        return if (PyText.cpLength(s) <= TEXT_CAP) s else PyText.take(s, TEXT_CAP) + ELLIPSIS
    }

    /** One event, fully injected (ts + id are the caller's): the golden vectors' builder. */
    fun event(
        kind: String,
        sid: String,
        turn: String,
        payload: JsonObject = JsonObject(emptyMap()),
        ts: Instant,
        id: String,
        source: String = "bot",
    ): BusEvent {
        require(kind in BusEvents.KINDS) { "unknown bus event kind: $kind" }
        require(source in BusEvents.SOURCES) { "unknown bus source: $source" }
        return BusEvent(
            id = id,
            ts = timestamp(ts),
            kind = kind,
            sid = sid,
            turn = turn,
            source = source,
            payload = payload,
        )
    }

    /** The JSON object of one event — no newline. */
    fun encode(event: BusEvent): String = json.encodeToString(BusEvent.serializer(), event)

    /** The exact appendable line: the JSON plus its terminating `\n`. */
    fun line(event: BusEvent): String = encode(event) + "\n"
}
