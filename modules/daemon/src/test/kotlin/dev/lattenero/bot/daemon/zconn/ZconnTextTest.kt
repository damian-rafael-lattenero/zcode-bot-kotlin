package dev.lattenero.bot.daemon.zconn

import dev.lattenero.bot.daemon.shadow.TimeIso
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** The texts are the parity contract — byte-identical to Python's _notify rendering. */
class ZconnTextTest {

    @Test
    fun downTextWithThePill() {
        assertEquals(
            "⚠️ <b>z.ai connection lost</b>\nCannot connect to API: read ETIMEDOUT\n(attempt 3/11, next retry 8s)",
            ZconnText.downText("Cannot connect to API: read ETIMEDOUT", "attempt 3/11, next retry 8s"),
        )
    }

    @Test
    fun downTextWithoutThePill() {
        assertEquals("⚠️ <b>z.ai connection lost</b>\nENOTFOUND", ZconnText.downText("ENOTFOUND", ""))
    }

    @Test
    fun upTextCarriesTheDuration() {
        assertEquals("✅ <b>z.ai back</b> — the cut lasted 42s", ZconnText.upText(100.0, 142.0))
    }

    @Test
    fun pillVocabDefaultsWhenContextIsMissing() {
        assertEquals("attempt 1/11, next retry 2s", ZconnText.pillVocab(null))
        assertEquals("attempt 1/11, next retry 2s", ZconnText.pillVocab(buildJsonObject { put("other", 1) }))
    }

    @Test
    fun pillVocabReadsTheForkFields() {
        val ctx = buildJsonObject {
            put("attempt", JsonPrimitive(3))
            put("maxAttempts", JsonPrimitive(11))
        }
        assertEquals("attempt 3/11, next retry 8s", ZconnText.pillVocab(ctx))
    }

    @Test
    fun pillVocabCapsTheCurveAt1024() {
        val ctx = buildJsonObject {
            put("attempt", JsonPrimitive(12))
            put("maxAttempts", JsonPrimitive(11))
        }
        assertEquals("attempt 12/11, next retry 1024s", ZconnText.pillVocab(ctx))
    }

    @Test
    fun durations() {
        assertEquals("42s", ZconnText.fmtDur(42))
        assertEquals("3m 20s", ZconnText.fmtDur(200))
        assertEquals("59s", ZconnText.fmtDur(59))
        assertEquals("1m 00s", ZconnText.fmtDur(60))
    }

    @Test
    fun isoTimestamps() {
        assertEquals("1970-01-01T00:01:40.000Z", TimeIso.format(100.0))
        val iso = "2026-10-29T10:01:10.200Z"
        assertEquals(iso, TimeIso.format(java.time.Instant.parse(iso).toEpochMilli() / 1000.0))
    }
}
