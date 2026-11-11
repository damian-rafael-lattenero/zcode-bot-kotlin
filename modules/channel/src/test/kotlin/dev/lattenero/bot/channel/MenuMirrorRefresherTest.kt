package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE MIRROR'S WIRING LAWS, with fakes: event → live re-read → edit
 * called ONCE with the fresh text; an unchanged menu is never re-edited;
 * a failed/throttled edit does not advance the baseline (the next event
 * re-attempts once — one attempt per event, never a loop); the TTL and
 * the seal are the two deaths; the three plan-picker menus render through
 * the SAME renderers the commands speak (the byte-identical law).
 */
class MenuMirrorRefresherTest {

    private val chat = kotlinx.serialization.json.JsonPrimitive(7L)

    /** The mutable clock — the tests move it by hand. */
    private var now = 1_000_000L

    /** The edit's recorder: every (messageId, text) attempt, and whether it lands. */
    private class EditSpy {
        val calls = mutableListOf<Pair<Long, String>>()
        var land = true

        suspend fun apply(messageId: Long, text: String): Boolean {
            calls.add(messageId to text)
            return land
        }
    }

    private class RefresherHarness(
        var modeNow: String? = "yolo",
        var planRender: MenuRender? = null,
        var modelRender: MenuRender? = null,
        var effortRender: MenuRender? = null,
        var title: String? = "arreglá el login",
        val edit: EditSpy = EditSpy(),
        clock: () -> Long,
    ) {
        val refresher = MenuMirrorRefresher.create(
            readMode = { _, _ -> modeNow },
            titleOf = { _ -> title },
            edit = { _, messageId, text, _ -> edit.apply(messageId, text) },
            clock = clock,
        ).apply {
            bindRenderers(
                plan = { _, _ -> planRender },
                model = { _, _ -> modelRender },
                effort = { _, _ -> effortRender },
            )
        }
    }

    private suspend fun registered(
        kind: MenuMirror.Kind,
        text: String,
        messageId: Long = 42L,
        atMs: Long = 1_000_000L,
    ): RefresherHarness = RefresherHarness(clock = { now }).apply {
        refresher.noteMenu(
            "task-77",
            MenuMirror.Mark("task-77", "/ws", kind),
            messageId = messageId,
            chatId = chat,
            text = text,
        )
    }

    private val options = listOf(ConfigOptionsUpdate.Option("mode", "plan"))

    // ── the happy mirror: event → live read → ONE edit with the fresh render ──

    @Test
    fun theOpenMenuEditsItselfWithTheFreshState() = runBlocking {
        val h = registered(MenuMirror.Kind.MODE, "🎯 «arreglá el login»\n🎛 Modo: <b>yolo</b>")
        h.modeNow = "plan"
        h.refresher.onConfigUpdate("task-77", options)
        assertEquals(1, h.edit.calls.size)
        val (mid, text) = h.edit.calls.single()
        assertEquals(42L, mid)
        assertTrue("🎛 Modo: <b>🗺 Plan mode</b>" in text, "the fresh mode (the app's label), not the payload: $text")
        assertTrue("🎯 «arreglá el login»" in text, "the title, the commands' own source: $text")
    }

    @Test
    fun aSecondUnchangedEventNeverReEdits() = runBlocking {
        val h = registered(MenuMirror.Kind.MODE, "🎯 «arreglá el login»\n🎛 Modo: <b>yolo</b>")
        h.modeNow = "plan" // the fork already wrote it; the FIRST edit renders it
        h.refresher.onConfigUpdate("task-77", options)
        // the fork re-announces the SAME state (a duplicate write, a second option row…)
        h.refresher.onConfigUpdate("task-77", options)
        assertEquals(1, h.edit.calls.size, "the baseline advanced — the unchanged menu is never re-edited")
    }

    @Test
    fun aFailedEditKeepsTheOldBaselineAndTheNextEventRetriesOnce() = runBlocking {
        val h = registered(MenuMirror.Kind.MODE, "🎯 «arreglá el login»\n🎛 Modo: <b>yolo</b>")
        h.modeNow = "plan"
        h.edit.land = false // throttled (the cooldown's own skip) or a Telegram refusal
        h.refresher.onConfigUpdate("task-77", options)
        assertEquals(1, h.edit.calls.size, "ONE attempt per event — never a loop")
        h.edit.land = true
        h.refresher.onConfigUpdate("task-77", options)
        assertEquals(2, h.edit.calls.size, "the baseline did not move — the next event attempts once")
        // and now the truth is on screen: a third unchanged event stays silent
        h.refresher.onConfigUpdate("task-77", options)
        assertEquals(2, h.edit.calls.size)
    }

    // ── the two deaths ──

    @Test
    fun theTtlKillsTheMirrorQuietly() = runBlocking {
        val h = registered(MenuMirror.Kind.MODE, "🎯 «arreglá el login»\n🎛 Modo: <b>yolo</b>")
        h.modeNow = "plan"
        now += MenuMirror.TTL_MS + 1
        h.refresher.onConfigUpdate("task-77", options)
        assertEquals(0, h.edit.calls.size, "an expired menu is off screen — no ghost edits")
    }

    @Test
    fun theTurnSealKillsTheMirror() = runBlocking {
        val h = registered(MenuMirror.Kind.MODE, "🎯 «arreglá el login»\n🎛 Modo: <b>yolo</b>")
        h.modeNow = "plan"
        h.refresher.seal("task-77")
        h.refresher.onConfigUpdate("task-77", options)
        assertEquals(0, h.edit.calls.size)
    }

    @Test
    fun anotherTasksEventTouchesNothing() = runBlocking {
        val h = registered(MenuMirror.Kind.MODE, "🎯 «arreglá el login»\n🎛 Modo: <b>yolo</b>")
        h.modeNow = "plan"
        h.refresher.onConfigUpdate("task-88", options)
        assertEquals(0, h.edit.calls.size, "the mirror is keyed by task — another task's write is not ours")
    }

    // ── the honest degrades ──

    @Test
    fun anUnreadableLiveStateEditsNothing() = runBlocking {
        val h = registered(MenuMirror.Kind.MODE, "🎯 «arreglá el login»\n🎛 Modo: <b>yolo</b>")
        h.modeNow = null // channel down / old fork / foreign snapshot — the read says nothing
        h.refresher.onConfigUpdate("task-77", options)
        assertEquals(0, h.edit.calls.size, "no data → no edit: the message keeps what it shows")
    }

    @Test
    fun aZeroMessageIdIsNeverRegistered() = runBlocking {
        val h = RefresherHarness(clock = { now })
        h.refresher.noteMenu(
            "task-77",
            MenuMirror.Mark("task-77", "/ws", MenuMirror.Kind.MODE),
            messageId = 0L, // the shadow/dry-run legs answer null → 0: nothing flew
            chatId = chat,
            text = "whatever",
        )
        h.modeNow = "plan"
        h.refresher.onConfigUpdate("task-77", options)
        assertEquals(0, h.edit.calls.size)
    }

    // ── the plan picker's three menus: the commands' OWN render path ──

    @Test
    fun thePlanMenuRefreshesThroughTheBoundRenderer() = runBlocking {
        val h = registered(MenuMirror.Kind.PLAN, "🎯 «arreglá el login»\nPlan: <b>Start Plan</b>")
        h.planRender = MenuRender(
            "🎯 «arreglá el login»\nPlan: <b>OpenCode Go</b> · Modelo: <b>kimi-k2</b> · Effort: <b>high</b>",
            null,
        )
        h.refresher.onConfigUpdate(
            "task-77",
            listOf(ConfigOptionsUpdate.Option("model", "opencode-go-chat/kimi-k2")),
        )
        assertEquals(1, h.edit.calls.size)
        val text = h.edit.calls.single().second
        assertTrue("OpenCode Go" in text, "the fresh selection renders: $text")
    }

    @Test
    fun theModelMenuRefreshesThroughItsOwnRenderer() = runBlocking {
        val h = registered(MenuMirror.Kind.MODEL, "🎯 «arreglá el login»\nModelos de <b>Start Plan</b>")
        h.modelRender = MenuRender("🎯 «arreglá el login»\nModelos de <b>OpenCode Go</b>", null)
        h.refresher.onConfigUpdate("task-77", listOf(ConfigOptionsUpdate.Option("model", "opencode-go-chat/kimi-k2")))
        assertEquals(1, h.edit.calls.size)
        assertTrue("Modelos de <b>OpenCode Go</b>" in h.edit.calls.single().second)
    }

    @Test
    fun theEffortMenuRefreshesThroughItsOwnRenderer() = runBlocking {
        val h = registered(MenuMirror.Kind.EFFORT, "🎯 «arreglá el login»\nEffort de <b>GLM-5.3-Flash</b>")
        h.effortRender = MenuRender("🎯 «arreglá el login»\nEffort de <b>kimi-k2</b>", null)
        h.refresher.onConfigUpdate("task-77", listOf(ConfigOptionsUpdate.Option("model", "opencode-go-chat/kimi-k2")))
        assertEquals(1, h.edit.calls.size)
        assertTrue("Effort de <b>kimi-k2</b>" in h.edit.calls.single().second)
    }

    @Test
    fun anUnboundRendererKeepsTheMenuText() = runBlocking {
        val h = registered(MenuMirror.Kind.PLAN, "🎯 «arreglá el login»\nPlan: <b>Start Plan</b>")
        // no bindRenderers call — the wiring's boot order speaks: keep the text, never a ghost edit
        h.refresher.onConfigUpdate("task-77", options)
        assertEquals(0, h.edit.calls.size)
    }

    // ── the mark: the commands' replies carry it (the wiring's register leg) ──

    private class FakeOps : ChannelOps {
        val answers = mutableMapOf<String, String>()

        override suspend fun query(query: ChannelQuery): QueryAnswer =
            answers[queryKind(query)]?.let { QueryAnswer.Ok(it) }
                ?: QueryAnswer.Failed("unknown_query", "no canned answer")

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

    private val boundStatus =
        """{"bot":{"id":"daemon-local","enabled":true},""" +
            """"context":{"workspacePath":"/ws","activeTaskId":"task-77","mode":"task"}}"""

    @Test
    fun theModeMenuReplyCarriesTheMirrorMark() = runBlocking {
        val ops = FakeOps().apply {
            answers["botStatus"] = boundStatus
            answers["taskSnapshot"] = """{"meta":{"mode":"yolo"},"messages":[]}"""
        }
        val commands = BotCommands(ops = ops, hub = LiveHub(), daemonReset = { "" }, history = FakeHistory())
        val reply = commands.handle("/mode", updateId = 1L)!!
        val mark = reply.mirror
        assertEquals(MenuMirror.Kind.MODE, mark?.kind)
        assertEquals("task-77", mark?.taskId)
        assertEquals("/ws", mark?.ws)
    }

    @Test
    fun thePlanMenuReplyCarriesTheMirrorMark() = runBlocking {
        val ops = FakeOps().apply {
            answers["botStatus"] = boundStatus
            answers["modelSelection"] = planViewsJson()
        }
        val commands = BotCommands(ops = ops, hub = LiveHub(), daemonReset = { "" }, history = FakeHistory())
        val reply = commands.handle("/plan", updateId = 2L)!!
        val mark = reply.mirror
        assertEquals(MenuMirror.Kind.PLAN, mark?.kind)
        assertEquals("task-77", mark?.taskId)
    }

    @Test
    fun theModelAndEffortMenuRepliesCarryTheirOwnMarks() = runBlocking {
        val ops = FakeOps().apply {
            answers["botStatus"] = boundStatus
            answers["modelSelection"] = planViewsJson()
        }
        val commands = BotCommands(ops = ops, hub = LiveHub(), daemonReset = { "" }, history = FakeHistory())
        val modelMark = commands.handle("/model", updateId = 3L)!!.mirror
        assertEquals(MenuMirror.Kind.MODEL, modelMark?.kind)
        val effortMark = commands.handle("/effort", updateId = 4L)!!.mirror
        assertEquals(MenuMirror.Kind.EFFORT, effortMark?.kind)
    }

    @Test
    fun anOrdinaryReplyCarriesNoMark() = runBlocking {
        val ops = FakeOps().apply { answers["botStatus"] = boundStatus }
        val commands = BotCommands(ops = ops, hub = LiveHub(), daemonReset = { "" }, history = FakeHistory())
        assertNull(commands.handle("/help", updateId = 3L)?.mirror, "an ordinary reply is never mirrored")
    }

    /** The fork's modelSelection answer the mark tests read. */
    private fun planViewsJson(plan: String = "account:zai-start-plan", model: String = "GLM-5.3-Flash") = """
    {
      "scope": "conversation",
      "selection": {"providerId": "$plan", "modelId": "$model", "reasoningLevel": "high"},
      "default": null,
      "plans": [
        {"providerId": "account:zai-start-plan", "label": "Start Plan",
         "models": [{"id": "GLM-5.3-Flash", "label": "GLM-5.3-Flash", "reasoningLevels": ["low", "high", "max"]}]}
      ]
    }
    """.trimIndent()
}
