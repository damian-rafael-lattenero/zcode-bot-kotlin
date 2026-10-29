package dev.lattenero.bot.contracts

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every persisted format decodes BOTH the current English shape and the
 * legacy Spanish shape into the same canonical value (bilingual forever,
 * per schemas.py), and encodes back with the English keys.
 */
class StateFormatsTest {

    @Test
    fun `bot-session decodes current and focus fields`() {
        val o = StateFiles.parseObject(
            """{"sid":"ses_1","title":"CV","ws":"/Users/x/cv","mode":"yolo","mode_set":"yolo","focus_sid":"ses_1"}""",
        )!!
        val s = BotSession.fromJson(o)
        assertEquals("ses_1", s.sid)
        assertEquals("CV", s.title)
        assertEquals("yolo", s.mode)
        assertEquals("ses_1", s.focusSid)
    }

    @Test
    fun `workers entry reads titulo as title, forever`() {
        val legacy = WorkersEntry.fromJson(StateFiles.parseObject("""{"t0":1793200000.5,"prompt":"p","ws":"w","titulo":"viejo","pid":123}""")!!)
        val modern = WorkersEntry.fromJson(StateFiles.parseObject("""{"t0":1793200000.5,"prompt":"p","ws":"w","title":"viejo","pid":123}""")!!)
        assertEquals(legacy, modern)
        assertEquals("viejo", legacy.title)
    }

    @Test
    fun `active turn reads titulo as title`() {
        val legacy = ActiveTurn.fromJson(StateFiles.parseObject("""{"titulo":"T","t0":1.0,"pid":9,"stop":true}""")!!)
        assertEquals("T", legacy.title)
        assertTrue(legacy.stop)
    }

    @Test
    fun `queue item tolerates missing ts (pre-F30)`() {
        val old = QueueItem.fromJson(StateFiles.parseObject("""{"act":{"sid":"s"},"text":"hi"}""")!!)
        assertEquals(0.0, old.ts)
        assertEquals("hi", old.text)
    }

    @Test
    fun `outbox box parses the parked-reply shape`() {
        val box = OutboxItem.Box.fromJson(
            Json.parseToJsonElement("""[{"ts":1793234806,"text":"hello"},{"ts":1793234809,"text":"again"}]""") as kotlinx.serialization.json.JsonArray,
        )
        assertEquals(listOf(OutboxItem(1793234806, "hello"), OutboxItem(1793234809, "again")), box)
    }

    @Test
    fun `zconn state round-trips the five keys`() {
        val o = StateFiles.parseObject(
            """{"down":false,"first_down":1793225318.162,"last_up":1793250142.441,"reason":"x","since":1793250143.941}""",
        )!!
        val z = ZconnState.fromJson(o)
        assertEquals(false, z.down)
        assertEquals(1793225318.162, z.firstDown)
        assertEquals(1793250143.941, z.since)
    }

    @Test
    fun `audit entry normalizes legacy keys and kind values`() {
        val legacy = AuditEntry.fromJson(StateFiles.parseObject("""{"ts":1793203238.1,"tipo":"envio","detalle":"fallo","clave":"envio|ses|fallos"}""")!!)
        assertEquals("envio", legacy.type)   // fromJson keeps the raw value; normalization is explicit
        assertEquals("fallo", legacy.detail)
        assertEquals("send", legacy.normalizedType)
        // envio is a KIND word (LEGACY_KINDS), not a KEY word: only fallos/cola/fantasma map in keys
        assertEquals("envio|ses|failures", AuditEntry.normalizeKey(legacy.key!!))
    }

    @Test
    fun `cot cache reads all three generations of box keys`() {
        val preF46 = CotCache.fromJson(StateFiles.parseObject("""{"sid":"s","t0":1,"titulo":"T","bloques":["a","b"],"vivo":true}""")!!)
        val p6 = CotCache.fromJson(StateFiles.parseObject("""{"sid":"s","t0":1,"titulo":"T","cajas":["a","b"],"rotulos":["l1","l2"],"finalizado":true}""")!!)
        val modern = CotCache.fromJson(StateFiles.parseObject("""{"sid":"s","t0":1,"title":"T","boxes":["a","b"],"labels":["l1","l2"],"live":true,"phs":[10,20],"perbox":true}""")!!)
        assertEquals(listOf("a", "b"), preF46.boxes)
        assertEquals(listOf("a", "b"), p6.boxes)
        assertEquals(listOf("l1", "l2"), p6.labels)
        assertEquals(true, preF46.live)   // vivo
        assertEquals(true, p6.done)       // finalizado
        assertEquals(listOf(10L, 20L), modern.phs)
        assertEquals(true, modern.perbox)
    }

    @Test
    fun `nuclear report reads frenadas-totales-cola-vivos`() {
        val legacy = NuclearReport.fromJson(StateFiles.parseObject("""{"old_pid":1,"t0":1.0,"dur":5,"frenadas":2,"totales":7,"mac":1,"cola":3,"vivos":4,"app_ok":true,"notes":["n"]}""")!!)
        val modern = NuclearReport.fromJson(StateFiles.parseObject("""{"old_pid":1,"t0":1.0,"dur":5,"stopped":2,"total":7,"mac":1,"queued":3,"alive":4,"app_ok":true,"notes":["n"]}""")!!)
        assertEquals(legacy, modern)
        assertEquals(2, legacy.stopped)
        assertEquals(4, legacy.alive)
    }

    @Test
    fun `encoding uses the English wire keys`() {
        val s = BotSession(sid = "s", title = "t", ws = "w", modeSet = "yolo", focusSid = "s")
        val encoded = Json.encodeToString(s)
        assertTrue(encoded.contains("\"mode_set\""), encoded)
        assertTrue(encoded.contains("\"focus_sid\""), encoded)
        assertTrue(!encoded.contains("modeSet"), encoded)
    }
}
