package dev.lattenero.bot.channel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE OPEN MENU MIRROR's pure half: the registry's laws (one menu per
 * task, the latest wins, the TTL and the seal are the only deaths), the
 * shouldEdit gate (never re-edit an unchanged menu), and the parse of the
 * fork's `config_option_update` event — valid, foreign, missing: the
 * additive rule is IGNORE, never a crash.
 */
class MenuMirrorTest {

    private val t0 = 1_000_000L
    private val chat = kotlinx.serialization.json.JsonPrimitive(7L)
    private val mark = MenuMirror.Mark("task-77", "/ws", MenuMirror.Kind.MODE)

    private fun entry(text: String = "old", atMs: Long = t0) = MenuMirror.Entry(
        kind = MenuMirror.Kind.MODE,
        messageId = 42L,
        chatId = chat,
        sentAtMs = atMs,
        lastRenderedText = text,
        ws = "/ws",
    )

    // ── the registry ──

    @Test
    fun theRegisteredMenuIsOpenWhileYoung() {
        val s = MenuMirror.State().register("task-77", entry())
        val open = s.open("task-77", nowMs = t0 + MenuMirror.TTL_MS)
        assertEquals(42L, open?.messageId)
        assertEquals(MenuMirror.Kind.MODE, open?.kind)
        assertEquals("/ws", open?.ws)
    }

    @Test
    fun theMenuDiesAtItsTtlAndNeverBefore() {
        val s = MenuMirror.State().register("task-77", entry())
        // exactly at the TTL: still open (the boundary is inclusive)
        assertEquals(42L, s.open("task-77", nowMs = t0 + MenuMirror.TTL_MS)?.messageId)
        // one ms past: gone
        assertNull(s.open("task-77", nowMs = t0 + MenuMirror.TTL_MS + 1))
        // and the sweep answers the same truth as a value
        val swept = s.expire(nowMs = t0 + MenuMirror.TTL_MS + 1)
        assertNull(swept.open("task-77", nowMs = t0 + MenuMirror.TTL_MS + 1))
    }

    @Test
    fun theLatestMenuOfATaskWinsAndOtherTasksStandAlone() {
        val s = MenuMirror.State()
            .register("task-77", entry(messageIdHolder = 1L))
            .register("task-77", entry(messageIdHolder = 2L))
            .register("task-88", entry(messageIdHolder = 3L))
        assertEquals(2L, s.open("task-77", t0)?.messageId)
        assertEquals(3L, s.open("task-88", t0)?.messageId)
        assertNull(s.open("task-99", t0))
    }

    @Test
    fun theSealDropsOnlyTheSealedTasksMenu() {
        val s = MenuMirror.State()
            .register("task-77", entry())
            .register("task-88", entry())
            .seal("task-77")
        assertNull(s.open("task-77", t0))
        assertEquals(42L, s.open("task-88", t0)?.messageId)
    }

    private fun entry(messageIdHolder: Long): MenuMirror.Entry = entry().copy(messageId = messageIdHolder)

    // ── the edit's gate ──

    @Test
    fun theSameTextIsNeverReEdited() {
        assertFalse(MenuMirror.shouldEdit("🎯 «x»\n🎛 Modo: <b>yolo</b>", "🎯 «x»\n🎛 Modo: <b>yolo</b>"))
    }

    @Test
    fun aChangedTextEdits() {
        assertTrue(MenuMirror.shouldEdit("🎛 Modo: <b>yolo</b>", "🎛 Modo: <b>plan</b>"))
        assertTrue(MenuMirror.shouldEdit(null, "anything")) // no baseline yet — the first render edits
    }

    @Test
    fun nothingToSayNeverEdits() {
        assertFalse(MenuMirror.shouldEdit("old", null))
    }

    // ── the fork's event parse ──

    @Test
    fun theValidEventParsesItsOptions() {
        val event = Json.parseToJsonElement(
            """{"type":"config_option_update","configOptions":[
               {"id":"model","currentValue":"account:zai-start-plan/GLM-5.3-Flash/max"},
               {"id":"mode","currentValue":"plan"},
               {"id":"thought_level","other":"field"}
             ]}""",
        ).jsonObject
        val options = ConfigOptionsUpdate.parse(event)!!
        assertEquals(3, options.size)
        assertEquals(ConfigOptionsUpdate.Option("model", "account:zai-start-plan/GLM-5.3-Flash/max"), options[0])
        assertEquals(ConfigOptionsUpdate.Option("mode", "plan"), options[1])
        // a row without currentValue carries null — the field is additive
        assertEquals(ConfigOptionsUpdate.Option("thought_level", null), options[2])
    }

    @Test
    fun aForeignShapeIsIgnored() {
        // no configOptions at all
        assertNull(ConfigOptionsUpdate.parse(Json.parseToJsonElement("""{"type":"config_option_update"}""").jsonObject))
        // configOptions not an array
        assertNull(
            ConfigOptionsUpdate.parse(
                Json.parseToJsonElement("""{"type":"config_option_update","configOptions":{"id":"mode"}}""").jsonObject,
            ),
        )
        // rows that are not objects, or without an id — no usable option rows,
        // the parse answers an EMPTY list (a trigger with nothing named in it)
        assertEquals(
            emptyList<ConfigOptionsUpdate.Option>(),
            ConfigOptionsUpdate.parse(
                Json.parseToJsonElement("""{"type":"config_option_update","configOptions":[42,"x"]}""").jsonObject,
            ),
        )
        // a row without an id is dropped by the map — the parse stays an EMPTY list (a
        // trigger with nothing named in it), never an invented option and never a crash
        assertEquals(
            emptyList<ConfigOptionsUpdate.Option>(),
            ConfigOptionsUpdate.parse(
                Json.parseToJsonElement("""{"type":"config_option_update","configOptions":[{"currentValue":"plan"}]}""")
                    .jsonObject,
            ),
        )
    }

    @Test
    fun theEventRidesTheStreamFrameVerbatim() {
        // the CHANNEL's additive law end to end: the frame decodes (it always
        // did — stream_event carries the union verbatim), the spine folds it
        // as freshness, and the mirror's parse reads the options off it
        val line =
            """{"type":"stream_event","taskId":"task-77","seq":9,"at":123,"event":
               {"type":"config_option_update","configOptions":[{"id":"mode","currentValue":"plan"}]}}"""
        val frame = decodeServerLine(line) as? ServerFrame.StreamEvent
        assertEquals("task-77", frame?.taskId)
        assertEquals(9L, frame?.seq)
        val options = frame?.let { ConfigOptionsUpdate.parse(it.event) }
        assertEquals(listOf(ConfigOptionsUpdate.Option("mode", "plan")), options)
        // and the spine keeps folding it as an additive freshness frame
        val render = frame?.let { classifyFrame(it, nowMs = 5_000L) } as? RenderEvent.Frame
        assertEquals("config_option_update", render?.type)
        assertTrue(render != null)
    }
}
