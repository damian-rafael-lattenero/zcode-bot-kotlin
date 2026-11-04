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
 * the seal are the two deaths; the plan render carries the commands' own
 * draft when the fresh catalog still validates it.
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
        var planNow: PlanMenu.Views? = null,
        var title: String? = "arreglá el login",
        var draft: PlanMenu.Draft? = null,
        val edit: EditSpy = EditSpy(),
        clock: () -> Long,
    ) {
        val refresher = MenuMirrorRefresher(
            readMode = { _, _ -> modeNow },
            readPlan = { _ -> planNow },
            draftOf = { draft },
            titleOf = { _ -> title },
            edit = { _, messageId, text, _ -> edit.apply(messageId, text) },
            clock = clock,
        )
    }

    private fun modeRegistered(
        text: String = "🎯 «arreglá el login»\n🎛 Modo: <b>yolo</b>\nTocá para cambiar (aplica al próximo turno):",
        atMs: Long = 1_000_000L,
    ): RefresherHarness = RefresherHarness(clock = { now }).apply {
        refresher.noteMenu(
            "task-77",
            MenuMirror.Mark("task-77", "/ws", MenuMirror.Kind.MODE),
            messageId = 42L,
            chatId = chat,
            text = text,
        )
    }

    private val options = listOf(ConfigOptionsUpdate.Option("mode", "plan"))

    // ── the happy mirror: event → live read → ONE edit with the fresh render ──

    @Test
    fun theOpenMenuEditsItselfWithTheFreshState() = runBlocking {
        val h = modeRegistered()
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
        val h = modeRegistered()
        h.modeNow = "plan" // the fork already wrote it; the FIRST edit renders it
        h.refresher.onConfigUpdate("task-77", options)
        // the fork re-announces the SAME state (a duplicate write, a second option row…)
        h.refresher.onConfigUpdate("task-77", options)
        assertEquals(1, h.edit.calls.size, "the baseline advanced — the unchanged menu is never re-edited")
    }

    @Test
    fun aFailedEditKeepsTheOldBaselineAndTheNextEventRetriesOnce() = runBlocking {
        val h = modeRegistered()
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
        val h = modeRegistered()
        h.modeNow = "plan"
        now += MenuMirror.TTL_MS + 1
        h.refresher.onConfigUpdate("task-77", options)
        assertEquals(0, h.edit.calls.size, "an expired menu is off screen — no ghost edits")
    }

    @Test
    fun theTurnSealKillsTheMirror() = runBlocking {
        val h = modeRegistered()
        h.modeNow = "plan"
        h.refresher.seal("task-77")
        h.refresher.onConfigUpdate("task-77", options)
        assertEquals(0, h.edit.calls.size)
    }

    @Test
    fun anotherTasksEventTouchesNothing() = runBlocking {
        val h = modeRegistered()
        h.modeNow = "plan"
        h.refresher.onConfigUpdate("task-88", options)
        assertEquals(0, h.edit.calls.size, "the mirror is keyed by task — another task's write is not ours")
    }

    // ── the honest degrades ──

    @Test
    fun anUnreadableLiveStateEditsNothing() = runBlocking {
        val h = modeRegistered()
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

    // ── the plan menu: the fresh catalog + the commands' own draft ──

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

    private fun planRegistered(text: String): RefresherHarness = RefresherHarness(
        planNow = PlanMenu.parse(planViewsJson()),
        clock = { now },
    ).apply {
        refresher.noteMenu(
            "task-77",
            MenuMirror.Mark("task-77", "/ws", MenuMirror.Kind.PLAN),
            messageId = 43L,
            chatId = chat,
            text = text,
        )
    }

    @Test
    fun thePlanMenuRefreshesWithTheFreshSelectionAndTheLiveDraft() = runBlocking {
        val before = PlanMenu.parse(planViewsJson())!!
        val h = planRegistered(PlanMenu.statusLine(before, "arreglá el login", null))
        // the Mac moved the conversation to the OTHER plan/model — the fresh read carries it
        h.planNow = PlanMenu.parse(
            """
            {"scope": "conversation",
             "selection": {"providerId": "opencode-go-chat", "modelId": "kimi-k2", "reasoningLevel": "high"},
             "default": null,
             "plans": [
               {"providerId": "opencode-go-chat", "label": "OpenCode Go",
                "models": [{"id": "kimi-k2", "label": "Kimi K2", "reasoningLevels": ["low", "high"]}]},
               {"providerId": "account:zai-start-plan", "label": "Start Plan",
                "models": [{"id": "GLM-5.3-Flash", "label": "GLM-5.3-Flash", "reasoningLevels": ["low", "high", "max"]}]}
             ]}
            """.trimIndent(),
        )
        h.draft = PlanMenu.Draft("account:zai-start-plan", "GLM-5.3-Flash", "high", atMs = now - 1000)
        h.refresher.onConfigUpdate(
            "task-77",
            listOf(ConfigOptionsUpdate.Option("model", "opencode-go-chat/kimi-k2")),
        )
        assertEquals(1, h.edit.calls.size)
        val text = h.edit.calls.single().second
        assertTrue("Kimi K2" in text, "the fresh selection renders: $text")
        assertTrue("A aplicar" in text, "the draft the fresh catalog still validates renders as 🟡: $text")
    }

    @Test
    fun theDraftSurvivesOnlyWhenTheFreshCatalogStillValidatesIt() = runBlocking {
        val before = PlanMenu.parse(planViewsJson())!!
        val h = planRegistered(PlanMenu.statusLine(before, "arreglá el login", null))
        // the draft points at the OLD model; the fresh catalog no longer exposes it → dropped
        h.draft = PlanMenu.Draft("account:zai-start-plan", "GLM-5.3-Flash", "high", atMs = now - 1000)
        h.planNow = PlanMenu.parse(
            """
            {"scope": "conversation",
             "selection": {"providerId": "account:zai-start-plan", "modelId": "glm-next", "reasoningLevel": "high"},
             "default": null,
             "plans": [{"providerId": "account:zai-start-plan", "label": "Start Plan",
                        "models": [{"id": "glm-next", "label": "GLM Next", "reasoningLevels": ["low", "high"]}]}]}
            """.trimIndent(),
        )
        h.refresher.onConfigUpdate("task-77", listOf(ConfigOptionsUpdate.Option("model", "glm-next")))
        val text = h.edit.calls.single().second
        assertTrue("GLM Next" in text, "the fresh catalog renders: $text")
        assertTrue("A aplicar" !in text, "an invalid draft is dropped, never rendered")
    }

    // ── the mark: the two commands' replies carry it (the wiring's register leg) ──

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
        val reply = commands.handle("/plan_model_effort", updateId = 2L)!!
        val mark = reply.mirror
        assertEquals(MenuMirror.Kind.PLAN, mark?.kind)
        assertEquals("task-77", mark?.taskId)
    }

    @Test
    fun anOrdinaryReplyCarriesNoMark() = runBlocking {
        val ops = FakeOps().apply { answers["botStatus"] = boundStatus }
        val commands = BotCommands(ops = ops, hub = LiveHub(), daemonReset = { "" }, history = FakeHistory())
        assertNull(commands.handle("/help", updateId = 3L)?.mirror, "an ordinary reply is never mirrored")
    }
}
