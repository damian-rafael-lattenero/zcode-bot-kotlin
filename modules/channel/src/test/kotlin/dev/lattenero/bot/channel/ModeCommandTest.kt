package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bot's configured owner — the taps the suite sends come from him. */
private const val OWNER = 7L

/**
 * /mode — the conversation's collaboration mode (Python's `_cmd_mode`),
 * now through the fork's OWN switch (the A4 `setMode` cap). The pure half
 * is pinned here too: the app's vocabulary/labels, the snapshot's
 * `meta.mode` projection (the adapter's aliases), the tap rows, and every
 * honest outcome — no conversation, an old host, an unknown word (never a
 * silent downgrade), the channel down.
 */
class ModeCommandTest {

    /** The fake ops: canned answers by kind, the SetMode recorder. */
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
        .map { row ->
            val b = row.jsonArray[0].jsonObject
            b["text"]!!.jsonPrimitive.content to b["callback_data"]!!.jsonPrimitive.content
        }

    // ── the wire ──

    @Test
    fun theWriteIsTheForksSetModeQuery() {
        val line = encodeClient(ClientFrame.Query("q1", ChannelQuery.SetMode("task-77", "plan")))
        assertEquals(
            """{"type":"query","id":"q1","query":{"kind":"setMode","taskId":"task-77","mode":"plan"}}""",
            line,
        )
    }

    @Test
    fun theSnapshotReadAsksForOneMessageOnly() {
        val line = encodeClient(
            ClientFrame.Query("q2", ChannelQuery.TaskSnapshot("task-77", "/ws", messageLimit = 1)),
        )
        assertEquals(
            """{"type":"query","id":"q2","query":{"kind":"taskSnapshot","taskId":"task-77","workspacePath":"/ws","messageLimit":1}}""",
            line,
            "/mode reads meta — a one-message window is the cheap read",
        )
    }

    // ── the pure half ──

    @Test
    fun theVocabularyIsTheForksFiveWithTheAppsOwnLabels() {
        assertEquals(
            listOf("plan", "yolo", "build", "edit", "guarded"),
            ModeMenu.MODES.map { it.id },
            "Python's two first, then the fork's rest — the app's own vocabulary",
        )
        assertEquals("🗺 Plan mode", ModeMenu.MODES[0].label)
        assertEquals("🔓 Full access", ModeMenu.MODES[1].label)
    }

    @Test
    fun theSnapshotsLegacyAliasesProjectLikeTheAdapter() {
        assertEquals("build", ModeMenu.normalize("default"))
        assertEquals("build", ModeMenu.normalize("acceptEdits"))
        assertEquals("yolo", ModeMenu.normalize("bypassPermissions"))
        assertEquals("yolo", ModeMenu.normalize("dontAsk"))
        assertEquals("guarded", ModeMenu.normalize("GUARDED"), "case-insensitive")
        assertNull(ModeMenu.normalize("turbo"), "outside the five is an unknown — never a guess")
        assertNull(ModeMenu.normalize(null))
    }

    @Test
    fun theCurrentModeIsReadFromTheSnapshotsMeta() {
        assertEquals("yolo", ModeMenu.currentFrom("""{"meta":{"mode":"yolo"}}"""))
        assertEquals("build", ModeMenu.currentFrom("""{"meta":{"mode":"default"}}"""))
        assertNull(ModeMenu.currentFrom("""{"meta":{}}"""))
        assertNull(ModeMenu.currentFrom("broken {"))
    }

    // ── bare /mode: the card and the five rows ──

    @Test
    fun bareModeShowsTheModeInForceAndTheFiveTaps() = runBlocking {
        val o = ops(current = "yolo")
        val reply = commands(o).handle("/mode", 1L)!!
        assertTrue("🎛 Modo: <b>🔓 Full access</b>" in reply.text, reply.text)
        assertTrue("«arreglá el login»" in reply.text)
        val r = rows(reply)
        assertEquals(
            listOf("mo:plan", "mo:yolo", "mo:build", "mo:edit", "mo:guarded"),
            r.map { it.second },
        )
        assertTrue(r[1].first.startsWith("🎯"), "the mode in force carries 🎯: ${r[1].first}")
        assertFalse(r[0].first.startsWith("🎯"))
        assertTrue(
            o.queries.filterIsInstance<ChannelQuery.TaskSnapshot>().single().messageLimit == 1,
            "the read is the cheap one-message snapshot",
        )
    }

    @Test
    fun aLegacySnapshotAliasMarksTheRightButton() = runBlocking {
        val reply = commands(ops(current = "default")).handle("/mode", 1L)!!
        val r = rows(reply)
        assertTrue(r[2].first.startsWith("🎯"), "default projects to build: ${r[2].first}")
        assertTrue("🛠 Ask before changes" in r[2].first)
    }

    @Test
    fun aSnapshotWithoutModeSaysSoAndPinsNothing() = runBlocking {
        val reply = commands(ops(current = null)).handle("/mode", 1L)!!
        assertTrue("🎛 Modo: <b>—</b>" in reply.text, reply.text)
        assertTrue(rows(reply).none { it.first.startsWith("🎯") }, "an unknown is never a pick")
    }

    // ── the write ──

    @Test
    fun typedModeAppliesThroughTheForkAndSpeaksItsOwnWord() = runBlocking {
        val o = ops()
        val reply = commands(o).handle("/mode plan", 2L)!!
        assertEquals("task-77" to "plan", o.setModes.single())
        assertTrue("🎛 Modo de «arreglá el login»: <b>🗺 Plan mode</b> ✅" in reply.text, reply.text)
        assertTrue("aplica al próximo turno" in reply.text)
        assertTrue(rows(reply)[0].first.startsWith("🎯"), "the applied mode becomes the marked one")
    }

    @Test
    fun aTapRunsTheSameWrite() = runBlocking {
        val o = ops()
        val reply = commands(o).onCallback("mo:edit", 3L, OWNER)!!
        assertEquals("task-77" to "edit", o.setModes.single())
        assertTrue("📝 Edit automatically" in reply.text, reply.text)
    }

    @Test
    fun anUnknownWordIsRefusedWithTheRealVocabularyAndNothingIsSent() = runBlocking {
        val o = ops()
        val reply = commands(o).handle("/mode bananas", 4L)!!
        assertTrue("⚠️ Modos: plan · yolo · build · edit · guarded" in reply.text, reply.text)
        assertTrue("/mode &lt;modo&gt;" in reply.text, "the usage's bracket is ESCAPED (the 2/11 poison law)")
        assertEquals(0, o.setModes.size, "a typo never degrades the conversation")
        assertTrue(reply.markup != null, "the refusal still offers the honest rows")
    }

    @Test
    fun anOldHostIsToldTheForkDeployIsTheFix() = runBlocking {
        val o = ops().apply { setModeAnswer = QueryAnswer.Failed("unknown_query", "unknown query kind: setMode") }
        val reply = commands(o).handle("/mode plan", 5L)!!
        assertEquals(BotCommands.MODE_OLD_FORK, reply.text)
    }

    @Test
    fun theForksBadRequestSpeaksItsOwnWords() = runBlocking {
        val o = ops().apply {
            setModeAnswer = QueryAnswer.Failed("bad_request", "unknown mode: \"x\" — expected one of build, edit, plan, yolo, guarded")
        }
        val reply = commands(o).handle("/mode plan", 6L)!!
        assertTrue("bad_request" in reply.text && "expected one of" in reply.text, reply.text)
    }

    // ── the honest refusals ──

    @Test
    fun noConversationIsTheMailboxNotAnError() = runBlocking {
        val o = ops().apply { answers["botStatus"] = """{"bot":{"enabled":true},"context":null}""" }
        assertEquals(BotCommands.MODE_NO_CONTEXT, commands(o).handle("/mode", 7L)!!.text)
    }

    @Test
    fun theChannelDownIsTheAntenna() = runBlocking {
        val o = ops().apply { available = false }
        assertEquals(BotCommands.DEGRADED, commands(o).handle("/mode", 8L)!!.text)
    }

    // ── the menu ──

    @Test
    fun modeSitsRightAfterPlanOnTheMenu() {
        val names = BotCommands.MENU.map { it.first }
        assertEquals(names.indexOf("plan_model_effort") + 1, names.indexOf("mode"))
        assertTrue("/mode — the conversation's mode" in BotCommands.HELP)
    }

    @Test
    fun theKeyboardDataStaysInsideTelegramsLaw() {
        val kb = ModeMenu.keyboard("yolo")
        val datas = kb.jsonObject["inline_keyboard"]!!.jsonArray.map {
            it.jsonArray[0].jsonObject["callback_data"]!!.jsonPrimitive.content
        }
        datas.forEach {
            assertTrue(it.length <= 64, "callback_data ≤ 64B: $it")
            assertFalse(it.any { c -> c.isWhitespace() }, "never whitespace: $it")
        }
        assertEquals(datas, datas.distinct(), "one intent per row")
        assertTrue(Json.parseToJsonElement(kb.toString()).jsonObject["inline_keyboard"] != null)
    }
}
