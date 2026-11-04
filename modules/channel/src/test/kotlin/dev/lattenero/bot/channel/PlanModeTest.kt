package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The bot's configured owner — the taps the suite sends come from him. */
private const val OWNER = 7L

/**
 * /plan_mode — THE SEPARATE TOGGLE (4/11): plan left /mode's keyboard and
 * lives here. Pinned: the card with the CURRENT state marked (READ from
 * the snapshot's meta.mode, never remembered), the taps firing the right
 * SetMode (ON → plan, OFF → the default Full access), the typed on/off,
 * the unknown word refused (the card, no write), and the honest tiers
 * (no conversation, channel down, old host). A mode switch is NOT
 * destructive: no owner-gate vocabulary — any owner-path tap toggles.
 */
class PlanModeTest {

    /** The fake ops: canned answers by kind, the SetMode recorder (ModeCommandTest's exact pattern). */
    private class FakeOps : ChannelOps {
        var available = true
        val queries = mutableListOf<ChannelQuery>()
        val answers = mutableMapOf<String, String>()
        val setModes = mutableListOf<Pair<String, String>>()
        var setModeAnswer: QueryAnswer = QueryAnswer.Ok("""{"taskId":"task-77","mode":"plan"}""")

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            queries.add(query)
            if (query is ChannelQuery.SetMode) {
                setModes.add(query.taskId to query.mode)
                return setModeAnswer
            }
            if (!available) return QueryAnswer.Unavailable
            return answers[queryKind(query)]?.let { QueryAnswer.Ok(it) }
                ?: QueryAnswer.Failed("unknown_query", "no canned answer")
        }

        override suspend fun sendInbound(
            text: String,
            messageId: String,
            receivedAt: Long,
            attachments: List<InboundAttachment>,
        ) = InboundReceipt.FIRED

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
            bindTaskId: String?,
        ) = InboundAnswer.Answered(duplicate = false, replies = emptyList())

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long) = 0

        override suspend fun subscribe(taskId: String, workspacePath: String) {}
    }

    private class FakeHistory : TasksHistory {
        override suspend fun ctxTasks(limit: Int): List<TaskRow> =
            listOf(TaskRow(sid = "task-77", title = "arreglá el login", ws = "/ws"))

        override suspend fun modelSelection(sid: String): ModelSel? = null
        override suspend fun freshestTask(workspacePath: String): TaskRow? = null
        override suspend fun taskAlive(sid: String): Boolean = true
    }

    private val status =
        """{"bot":{"id":"daemon-local","enabled":true},"context":{"workspacePath":"/ws","activeTaskId":"task-77","mode":"task"}}"""

    private fun ops(current: String? = "yolo"): FakeOps = FakeOps().apply {
        answers["botStatus"] = status
        if (current != null) answers["taskSnapshot"] = """{"meta":{"mode":"$current"},"messages":[]}"""
    }

    private fun commands(o: FakeOps): BotCommands = BotCommands(
        ops = o,
        hub = LiveHub(),
        daemonReset = { "" },
        ownerUserId = { OWNER },
        history = FakeHistory(),
    )

    /** The reply's keyboard rows as (text, callback) pairs. */
    private fun rows(reply: BotReply): List<Pair<String, String>> = reply.markup!!
        .jsonObject["inline_keyboard"]!!.jsonArray
        .flatMap { row ->
            row.jsonArray.map { b ->
                val obj = b.jsonObject
                obj["text"]!!.jsonPrimitive.content to obj["callback_data"]!!.jsonPrimitive.content
            }
        }

    // ── the card: the state READ, never remembered ──

    @Test
    fun bareCardShowsOnWhenTheSnapshotSaysPlan() = runBlocking {
        val reply = commands(ops(current = "plan")).handle("/plan_mode", 1L)!!
        assertTrue("🧭 Plan mode de «arreglá el login»" in reply.text, reply.text)
        assertTrue("Estado: <b>✅ ON</b>" in reply.text, reply.text)
        val r = rows(reply)
        assertEquals(listOf("pmd:on", "pmd:off"), r.map { it.second })
        assertTrue(r[0].first.startsWith("🎯"), "the CURRENT state carries 🎯: ${r[0].first}")
        assertFalse(r[1].first.startsWith("🎯"))
    }

    @Test
    fun bareCardShowsOffForAnyOtherMode() = runBlocking {
        val reply = commands(ops(current = "yolo")).handle("/plan_mode", 1L)!!
        assertTrue("Estado: <b>⬜ OFF</b>" in reply.text, reply.text)
        assertTrue("Full access" in reply.text, "the default law is ON the card")
        val r = rows(reply)
        assertTrue(r[1].first.startsWith("🎯"), "OFF is the current state: ${r[1].first}")
        assertFalse(r[0].first.startsWith("🎯"))
    }

    @Test
    fun aSnapshotWithoutModeReadsAsOff() = runBlocking {
        // meta.mode absent → the default (Full access) is in force → OFF
        val reply = commands(ops(current = null)).handle("/plan_mode", 1L)!!
        assertTrue("Estado: <b>⬜ OFF</b>" in reply.text, reply.text)
        assertTrue(rows(reply)[1].first.startsWith("🎯"))
    }

    // ── the writes ──

    @Test
    fun typedOnWritesPlanThroughTheSameSwitch() = runBlocking {
        val o = ops()
        val reply = commands(o).handle("/plan_mode on", 2L)!!
        assertEquals("task-77" to "plan", o.setModes.single())
        assertTrue("🧭 Plan mode de «arreglá el login»: <b>✅ ON</b> ✅" in reply.text, reply.text)
        assertTrue("aplica al próximo turno" in reply.text, "/mode's promise, the toggle's voice")
        assertTrue(rows(reply)[0].first.startsWith("🎯"), "the keyboard answers ON-marked")
    }

    @Test
    fun typedOffWritesTheDefaultFullAccess() = runBlocking {
        val o = ops(current = "plan")
        val reply = commands(o).handle("/plan_mode off", 3L)!!
        assertEquals("task-77" to "yolo", o.setModes.single(), "OFF is the DEFAULT law: back to Full access")
        assertTrue("⬜ OFF</b> ✅ — volvemos a 🔓 Full access" in reply.text, reply.text)
        assertTrue(rows(reply)[1].first.startsWith("🎯"))
    }

    @Test
    fun theTapsRunTheSameWrite() = runBlocking {
        val o = ops()
        val reply = commands(o).onCallback("pmd:on", 4L, OWNER)!!
        assertEquals("task-77" to "plan", o.setModes.single())
        assertTrue("✅ ON" in reply.text, reply.text)
        val o2 = ops(current = "plan")
        val reply2 = commands(o2).onCallback("pmd:off", 5L, OWNER)!!
        assertEquals("task-77" to "yolo", o2.setModes.single())
        assertTrue("Full access" in reply2.text, reply2.text)
    }

    @Test
    fun anUnknownWordNeverWritesItShowsTheCard() = runBlocking {
        val o = ops()
        val reply = commands(o).handle("/plan_mode bananas", 6L)!!
        assertEquals(0, o.setModes.size, "a typo never writes a mode")
        assertTrue("🧭 Plan mode de «arreglá el login»" in reply.text, "the card again")
        assertTrue(reply.markup != null, "the card still offers the toggle")
    }

    @Test
    fun anOldHostIsToldTheForkDeployIsTheFix() = runBlocking {
        val o = ops().apply { setModeAnswer = QueryAnswer.Failed("unknown_query", "unknown query kind: setMode") }
        assertEquals(BotCommands.MODE_OLD_FORK, commands(o).handle("/plan_mode on", 7L)!!.text)
    }

    @Test
    fun theChannelDownIsTheAntenna() = runBlocking {
        val o = ops().apply { available = false }
        assertEquals(BotCommands.DEGRADED, commands(o).handle("/plan_mode", 8L)!!.text)
    }

    @Test
    fun noConversationIsTheMailboxNotAnError() = runBlocking {
        val o = ops().apply { answers["botStatus"] = """{"bot":{"enabled":true},"context":null}""" }
        assertEquals(BotCommands.MODE_NO_CONTEXT, commands(o).handle("/plan_mode", 9L)!!.text)
    }

    // ── the registration + the tap law ──

    @Test
    fun planModeSitsRightAfterModeOnTheMenu() {
        val names = BotCommands.MENU.map { it.first }
        assertEquals(names.indexOf("mode") + 1, names.indexOf("plan_mode"), "the toggle lives beside /mode")
        assertTrue("/plan_mode — Plan mode ON/OFF" in BotCommands.HELP)
    }

    @Test
    fun theToggleIsNotDestructive() {
        // a mode switch is not destructive: no owner-gate vocabulary, no
        // suicidal pre-commit — the tap rides the ordinary law
        assertFalse(isDestructiveTap("pmd:on"))
        assertFalse(isDestructiveTap("pmd:off"))
        assertFalse(isSuicidal(InboundUpdate.Callback(1L, 7L, "cb", "pmd:on")))
    }

    @Test
    fun theKeyboardDataStaysInsideTelegramsLaw() {
        val kb = PlanMode.keyboard(true)
        val datas = kb.jsonObject["inline_keyboard"]!!.jsonArray.flatMap {
            it.jsonArray.map { b -> b.jsonObject["callback_data"]!!.jsonPrimitive.content }
        }
        assertEquals(listOf("pmd:on", "pmd:off"), datas)
        datas.forEach {
            assertTrue(it.length <= 64, "callback_data ≤ 64B: $it")
            assertFalse(it.any { c -> c.isWhitespace() }, "never whitespace: $it")
        }
    }
}
