package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The eight commands against a FAKE channel (the queries recorded, the
 * replies pinned) — the dispatcher is pure decision on query answers.
 * The list renders pin Python's task_list_report format byte-a-byte
 * (reporting.py is the source of behavior, frozen by these tests).
 */
class BotCommandsTest {

    /** The fake ops: canned answers by query kind + the recorder. */
    private class FakeOps : ChannelOps {
        val asked = mutableListOf<ChannelQuery>()
        var botStatusJson: String? = null
        var answers: MutableMap<String, String> = mutableMapOf()
        var available = true
        val inbound = mutableListOf<Pair<String, String>>()
        val awaited = mutableListOf<Pair<String, String>>()
        val queued = mutableListOf<Triple<String, String, Long>>()
        val subscribed = mutableListOf<Pair<String, String>>()

        /** The awaited bind's canned replies (taskRunning/taskMissing texts). */
        var awaitReplies: List<String> = emptyList()

        /** Server-side effect of an inbound command (tests flip botStatus on /task). */
        var onInbound: (String) -> Unit = {}

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            asked.add(query)
            if (!available) return QueryAnswer.Unavailable
            val key = queryKind(query)
            return answers[key]?.let { QueryAnswer.Ok(it) } ?: QueryAnswer.Failed("internal", "no canned answer")
        }

        override suspend fun sendInbound(text: String, messageId: String, receivedAt: Long): InboundReceipt {
            if (!available) return InboundReceipt.UNAVAILABLE
            inbound.add(text to messageId)
            onInbound(text)
            return InboundReceipt.FIRED
        }

        override suspend fun sendInboundAwait(text: String, messageId: String, receivedAt: Long): InboundAnswer {
            if (!available) return InboundAnswer.Unavailable
            awaited.add(text to messageId)
            onInbound(text)
            return InboundAnswer.Answered(duplicate = false, replies = awaitReplies)
        }

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long): Int {
            queued.add(Triple(text, messageId, receivedAt))
            return queued.size
        }

        override suspend fun subscribe(taskId: String, workspacePath: String) {
            subscribed.add(taskId to workspacePath)
        }
    }

    /** Python's ctx_tasks rows — the /tasks fixture (sidebar order, real usage). */
    private class FakeHistory(
        private val rows: List<TaskRow>,
        private val sel: ModelSel? = null,
        private val freshest: TaskRow? = rows.firstOrNull(),
        private val alive: (String) -> Boolean = { true },
    ) : TasksHistory {
        override suspend fun ctxTasks(limit: Int): List<TaskRow> = rows.take(limit)
        override suspend fun modelSelection(sid: String): ModelSel? = sel
        override suspend fun freshestTask(workspacePath: String): TaskRow? = freshest
        override suspend fun taskAlive(sid: String): Boolean = alive(sid)
    }

    private val app = "/Users/damian/Projects/app"
    private val botWs = "/Users/damian/Projects/bot"
    private val defaultWs = "/Users/damian/.zcode/workspace/default"

    private val rows = listOf(
        TaskRow(sid = "task-77", title = "arreglá el login", ws = app),
        TaskRow(sid = "task-02", title = "second", ws = botWs),
        TaskRow(sid = "task-03", title = "", ws = defaultWs),
    )

    private val boundStatus = """
        {"bot":{"id":"daemon-local","enabled":true},
         "context":{"workspacePath":"$app","activeTaskId":"task-77","mode":"full_access"}}
    """.trimIndent()

    private val draftStatus = """
        {"bot":{"id":"daemon-local","enabled":true},
         "context":{"workspacePath":"$app","activeTaskId":null,"mode":"draft"}}
    """.trimIndent()

    private fun ops(status: String? = boundStatus) = FakeOps().apply {
        botStatusJson = status
        answers["botStatus"] = status ?: "null"
        answers["listTasks"] = """[
            {"taskId":"task-77","title":"arreglá el login","updatedAt":900},
            {"taskId":"task-02","title":"second","updatedAt":300}
        ]"""
        answers["taskList"] = """{"items":[{"taskId":"task-77","title":"arreglá el login","updatedAt":900}],"total":1,"hasMore":false}"""
        answers["activeTaskIds"] = """[]"""
        answers["taskMeta"] = """{"taskId":"task-77","title":"arreglá el login"}"""
        answers["quota"] = """
            {"generatedAt":1,"authenticated":true,"provider":{"id":"glm","name":"GLM"},
             "remaining":{"count":72,"isShow":true,"percentage":41,"nextResetTime":4700000}}
        """.trimIndent()
        answers["stop"] = """{"stopped":["task-77"]}"""
        answers["stopAll"] = """{"stopped":["task-77","task-02"]}"""
    }

    private fun commands(
        o: FakeOps,
        history: TasksHistory = FakeHistory(rows),
    ): Pair<BotCommands, FakeOps> {
        var resets = 0
        val bc = BotCommands(
            ops = o,
            hub = LiveHub(),
            daemonReset = { resets += 1; "fake ladder: 2 stopped" },
            clock = { 1_000_000L },
            history = history,
            defaultWorkspacePath = defaultWs,
        )
        return bc to o
    }


    /** The dispatcher's String view — the markup has its own tests (KeyboardsTest). */
    private suspend fun BotCommands.reply(text: String, updateId: Long): String? = handle(text, updateId)?.text

    @Test
    fun parseCommandStripsBotNameAndCase() {
        assertEquals("tasks" to "5", BotCommands.parseCommand("/Tasks@glm_bot 5"))
        assertEquals("all_tasks" to "", BotCommands.parseCommand("/all_tasks"))
        assertEquals("stop" to "now", BotCommands.parseCommand("/stop now"))
        assertNull(BotCommands.parseCommand("hello there"))
        assertNull(BotCommands.parseCommand("/"))
    }

    @Test
    fun tasksRendersPythonsProjectsFormat() = runBlocking {
        val o = ops().apply { answers["activeTaskIds"] = """["task-02"]""" }
        val (bc, _) = commands(o)
        assertEquals(
            """
            💬 <b>Conversations by project</b> — pick one with /tasks n
            🎯 1. arreglá el login
            🔄 2. second · 📁bot

            Free text you send goes to the active conversation (🎯).
            """.trimIndent(),
            bc.reply("/tasks", 1L),
        )
    }

    @Test
    fun allTasksIncludesTheLooseOnes() = runBlocking {
        val o = ops().apply { answers["activeTaskIds"] = """["task-02"]""" }
        val (bc, _) = commands(o)
        val reply = bc.reply("/all_tasks", 1L)!!
        assertTrue("💬 <b>All conversations</b> (loose ones included) — /tasks only projects" in reply)
        assertTrue("🔄 2. second · 📁bot" in reply)
        assertTrue("· 3. task-03 · 📁default" in reply) // loose row kept, sid tail as title
    }

    @Test
    fun tasksWithoutContextStillListsPythonsWay() = runBlocking {
        val (bc, _) = commands(ops(status = """{"bot":{"id":"daemon-local","enabled":true},"context":null}"""))
        val reply = bc.reply("/tasks", 1L)!! // Python: /task is HOW you get an active one
        assertTrue("Conversations by project" in reply)
        assertTrue("· 1. arreglá el login · 📁app" in reply) // no context: every row tagged
    }

    @Test
    fun disabledGateStillListsTheList() = runBlocking {
        val (bc, _) = commands(
            ops(status = """{"bot":{"id":"daemon-local","enabled":false},"context":{"workspacePath":"$app"}}"""),
        )
        assertTrue("Conversations by project" in bc.reply("/tasks", 1L)!!)
    }

    @Test
    fun tasksPickBindsTheActiveConversation() = runBlocking {
        val boundTo02 = """
            {"bot":{"id":"daemon-local","enabled":true},
             "context":{"workspacePath":"$app","activeTaskId":"task-02","mode":"task"}}
        """.trimIndent()
        val o = ops().apply {
            botStatusJson = draftStatus
            answers["botStatus"] = draftStatus
            onInbound = { text ->
                if (text.startsWith("/task ")) answers["botStatus"] = boundTo02
            }
        }
        val (bc, _) = commands(o)
        val reply = bc.reply("/tasks 2", 7L)!!
        assertTrue("🎯 Active conversation: «second»" in reply)
        assertTrue("/task task-02" in o.awaited.map { it.first })
        assertEquals("tg-7-bind", o.awaited.first { it.first == "/task task-02" }.second)
    }

    @Test
    fun pickOutOfListIsPythonsMessage() = runBlocking {
        val (bc, _) = commands(ops())
        assertEquals(
            "⚠️ Number out of the list (1 to 2) — /tasks.",
            bc.reply("/tasks 9", 1L),
        )
    }

    @Test
    fun pickWhileThePreviousTaskRunsSaysStopFirst() = runBlocking {
        val o = ops().apply { answers["activeTaskIds"] = """["task-77"]""" }
        val (bc, _) = commands(o)
        assertEquals(
            "⏳ The previous task's turn is still running — /stop first, then pick again.",
            bc.reply("/tasks 2", 1L),
        )
        assertTrue(o.inbound.isEmpty()) // the doomed bind never fires
    }

    @Test
    fun freeTextGoesToTheBoundConversationAndSubscribes() = runBlocking {
        val (bc, o) = commands(ops())
        val reply = bc.reply("arreglá el login del bot", 4242L)
        assertNull(reply) // no ack: the live view and the final render speak
        assertEquals(listOf("arreglá el login del bot" to "tg-4242"), o.inbound)
        assertEquals(listOf("task-77" to app), o.subscribed)
    }

    @Test
    fun freeTextBindsToTheOpenConversation() = runBlocking {
        val o = ops().apply {
            botStatusJson = draftStatus
            answers["botStatus"] = draftStatus
            onInbound = { text ->
                if (text.startsWith("/task ")) answers["botStatus"] = boundStatus
            }
        }
        val (bc, _) = commands(o)
        assertNull(bc.reply("probando probando", 10L))
        assertEquals(listOf("probando probando" to "tg-10"), o.inbound)
        assertEquals(
            listOf("/task task-77" to "tg-10-bind"),
            o.awaited, // the switch command FIRST (awaited), the text after the confirm
        )
        assertEquals(listOf("task-77" to app), o.subscribed)
    }

    @Test
    fun tombstonedBindNeverCreatesFiresTheRebindInstead() = runBlocking {
        // 29/10's live failure: the context pointed at a DELETED task — naked
        // inbound would tombstone into a fresh conversation
        val boundDead = """
            {"bot":{"id":"daemon-local","enabled":true},
             "context":{"workspacePath":"$app","activeTaskId":"task-dead","mode":"task"}}
        """.trimIndent()
        val o = ops().apply {
            answers["botStatus"] = boundDead
            onInbound = { text ->
                if (text.startsWith("/task ")) answers["botStatus"] = boundStatus
            }
        }
        val history = FakeHistory(rows, alive = { it != "task-dead" })
        val (bc, _) = commands(o, history)
        assertNull(bc.reply("probando probando", 11L))
        assertEquals(listOf("probando probando" to "tg-11"), o.inbound)
        assertEquals(
            listOf("/task task-77" to "tg-11-bind"),
            o.awaited, // the re-aim happened — the text never touched the draft
        )
    }

    @Test
    fun tombstoneWithNothingOpenRefusesHonestly() = runBlocking {
        val boundDead = """
            {"bot":{"id":"daemon-local","enabled":true},
             "context":{"workspacePath":"$app","activeTaskId":"task-dead","mode":"task"}}
        """.trimIndent()
        val o = ops().apply { answers["botStatus"] = boundDead }
        val history = FakeHistory(rows, freshest = null, alive = { it != "task-dead" })
        val (bc, _) = commands(o, history)
        val reply = bc.reply("hola", 1L)!!
        assertTrue("was deleted" in reply)
        assertTrue(o.inbound.isEmpty()) // firing would CREATE — the tombstone guard refuses
    }

    @Test
    fun busyRebindTellsTheOwnerAndParksOnTheRunningTask() = runBlocking {
        // the open conversation moved, but the OLD bound task runs: the
        // botsService refuses the switch (taskRunning) — Python's
        // only_active_task fallback parks the text on what runs
        val boundTo02 = """
            {"bot":{"id":"daemon-local","enabled":true},
             "context":{"workspacePath":"$app","activeTaskId":"task-02","mode":"task"}}
        """.trimIndent()
        val o = ops().apply {
            answers["botStatus"] = boundTo02
            answers["activeTaskIds"] = """["task-02"]"""
        }
        val (bc, _) = commands(o) // freshest = task-77 (open) ≠ bound task-02 (running)
        val reply = bc.reply("re-apuntá", 6L)!!
        assertTrue(reply.startsWith("🔄 task corriendo, no puedo re-apuntar"))
        assertTrue("stayed in the queue (1 waiting)" in reply)
        assertTrue(o.awaited.isEmpty()) // the pre-check refuses BEFORE any bind is fired
        assertEquals(listOf(Triple("re-apuntá", "tg-6", 1_000_000L)), o.queued)
    }

    @Test
    fun bindRefusedMidFlightByTaskRunningAnswersBindFailed() = runBlocking {
        val o = ops().apply {
            botStatusJson = draftStatus
            answers["botStatus"] = draftStatus
            awaitReplies = listOf(
                "The current task is still running. Try again later, or use **/stop** to stop the current task.",
            )
        }
        val (bc, _) = commands(o)
        val reply = bc.reply("probando", 12L)!!
        assertTrue("switch did not land" in reply)
        assertTrue(o.inbound.isEmpty()) // refused bind → the text NEVER fires
        assertTrue(o.awaited.isNotEmpty())
    }

    @Test
    fun freeTextWithNothingOpenRefusesLikePython() = runBlocking {
        val o = ops().apply {
            botStatusJson = draftStatus
            answers["botStatus"] = draftStatus
        }
        val history = FakeHistory(rows, freshest = null) // the index sees nothing open
        val (bc, _) = commands(o, history)
        val reply = bc.reply("hola", 1L)!!
        assertTrue("There is no active conversation yet" in reply)
        assertTrue(o.inbound.isEmpty()) // free text NEVER creates a conversation
    }

    @Test
    fun freeTextMidTurnQueuesLikePythonsF17() = runBlocking {
        val o = ops().apply { answers["activeTaskIds"] = """["task-77"]""" }
        val (bc, _) = commands(o)
        val full = bc.handle("y ahora esto", 5L)!!
        val reply = full.text
        assertTrue("⏳ The Mac is busy with «arreglá el login»" in reply)
        assertTrue("stayed in the queue (1 waiting)" in reply)
        // 30/10: the park ASKS — push-or-enqueue (the owner's «que me
        // pregunte interrumpir o encolar»), the buttons replace the old
        // /stop hint line
        val datas = full.markup.toString()
        assertTrue("intr:" in datas, "the ⚡ push button is there")
        assertTrue("qgo:" in datas, "the ⏳ keep-queued button is there")
        assertTrue(o.inbound.isEmpty()) // parked, not fired
        assertEquals(listOf(Triple("y ahora esto", "tg-5", 1_000_000L)), o.queued)
    }

    @Test
    fun tasksNewArmsTheNextTextToCreate() = runBlocking {
        val o = ops().apply {
            botStatusJson = draftStatus
            answers["botStatus"] = draftStatus
        }
        val (bc, _) = commands(o)
        val reply = bc.reply("/tasks new", 3L)!!
        assertTrue(reply.startsWith("🆕 New conversation in app"))
        assertNull(bc.reply("primer mensaje", 4L))
        assertEquals(2, o.inbound.size)
        assertTrue(o.inbound[0].first == "/new") // the botsService's own draft
        assertTrue(o.inbound.none { it.first.startsWith("/task ") }) // no bind: CREATE intent
    }

    @Test
    fun stopSoftCarriesTheGoHint() = runBlocking {
        val (bc, o) = commands(ops())
        val reply = bc.reply("/stop", 1L)!!
        assertTrue("⏹️ Stop sent" in reply)
        assertTrue(o.asked.filterIsInstance<ChannelQuery.Stop>().isNotEmpty())
    }

    @Test
    fun stopGoIsTheHardStop() = runBlocking {
        val (bc, o) = commands(ops())
        assertEquals(
            "💀 Hard stop: 2 run(s) stopped — every active task's turn.",
            bc.reply("/stop go", 1L),
        )
        assertTrue(o.asked.filterIsInstance<ChannelQuery.StopAll>().isNotEmpty())
    }

    @Test
    fun tasksAndStopsAndNuclearCarryTheirKeyboards() = runBlocking {
        val (bc, _) = commands(ops())
        val tasks = bc.handle("/tasks", 1L)!!
        assertTrue(tasks.markup != null, "/tasks carries the green keyboard")
        assertTrue(tasks.markup.toString().contains("\"t:task-77\""))
        val all = bc.handle("/all_tasks", 2L)!!
        assertTrue(all.markup != null, "/all_tasks too")
        val stop = bc.handle("/stop", 3L)!!
        assertTrue(stop.markup.toString().contains("\"stop:go\""), "bare /stop carries the 💀 confirm")
        val nuclear = bc.handle("/nuclear", 4L)!!
        assertTrue(nuclear.markup.toString().contains("\"nuc:go\""), "bare /nuclear carries ☢️/🙅")
        assertNull(bc.handle("/nuclear no", 5L)!!.markup, "the cancellation carries no buttons")
        assertNull(bc.handle("/quota", 6L)!!.markup, "plain replies stay plain")
    }

    @Test
    fun theTButtonTapRebindsThroughTheSameMachinery() = runBlocking {
        val boundTo02 = """
            {"bot":{"id":"daemon-local","enabled":true},
             "context":{"workspacePath":"$app","activeTaskId":"task-02","mode":"task"}}
        """.trimIndent()
        val o = ops().apply {
            botStatusJson = draftStatus
            answers["botStatus"] = draftStatus
            onInbound = { text -> if (text.startsWith("/task ")) answers["botStatus"] = boundTo02 }
        }
        val (bc, _) = commands(o)
        val reply = bc.onCallback("t:task-02", 99L)!!
        assertTrue("🎯 Active conversation: «second»" in reply.text)
        assertEquals(listOf("/task task-02" to "tg-99-bind"), o.awaited)
    }

    @Test
    fun nuclearIsPythonsThreeStep() = runBlocking {
        var resets = 0
        val o = ops()
        val bc = BotCommands(
            ops = o, hub = LiveHub(), daemonReset = { resets += 1; "fake ladder: 2 stopped" },
            clock = { 1_000_000L }, history = FakeHistory(rows), defaultWorkspacePath = defaultWs,
        )
        val card = bc.reply("/nuclear", 1L)!!
        assertTrue(card.startsWith("☢️ <b>Nuclear</b> — emergency:"))
        assertTrue("⏹️ stops ALL runs" in card)
        assertTrue("Confirm?" in card)
        assertEquals("🙅 Cancelled — nothing was touched.", bc.reply("/nuclear no", 2L))
        val go = bc.reply("/nuclear go", 3L)!!
        assertTrue("fake ladder: 2 stopped" in go) // the ladder's own summary rides the reply
        assertEquals(1, resets)
    }

    @Test
    fun statusShowsTitleModelAndWhatRuns() = runBlocking {
        val o = ops().apply { answers["activeTaskIds"] = """["task-77"]""" }
        val (bc, _) = commands(o, FakeHistory(rows, ModelSel("GLM-5.3", "high")))
        val reply = bc.reply("/status", 1L)!!
        assertTrue("📊 <b>Status</b>" in reply)
        assertTrue("🎯 Active: «arreglá el login»" in reply)
        assertTrue("🤖 GLM-5.3 · 🧠 high · 🎛 🔓 Full access" in reply)
        assertTrue("running now: 1" in reply)
        assertTrue("context: app" in reply)
    }

    @Test
    fun quotaRendersThePoolAndReset() = runBlocking {
        val (bc, _) = commands(ops())
        val reply = bc.reply("/quota", 1L)!!
        assertTrue("41% remaining" in reply)
        assertTrue("resets in 1h" in reply)
    }

    @Test
    fun quotaWindowsRenderPythonsFormat() {
        val json = """
            {"authenticated":true,
             "quota":{"level":"PRO","limits":[
               {"type":"CREDIT_LIMIT","number":1,"currentValue":88000,"usage":88000,"percentage":90,"nextResetTime":1767225600000},
               {"type":"CREDIT_LIMIT","number":5,"currentValue":31,"usage":120,"percentage":26,"nextResetTime":1767225600000}]}}
        """.trimIndent()
        val text = BotCommands.renderQuota(json, nowMs = 0L)
        assertTrue("📊 <b>Coding plan</b>" in text)
        assertTrue("⏱ 5 hours: 31/120 credits (26%)" in text) // 5h first, weekly after
        assertTrue("📅 Weekly: 88K/88K credits (90%)" in text) // Python's fmt_num: K from 10K up
        assertTrue("⚠️" in text) // ≥75% carries the alert
    }

    @Test
    fun helpListsExactlyTheMenu() {
        assertNotNull(BotCommands.HELP)
        assertEquals(8, BotCommands.MENU.size)
        BotCommands.MENU.forEach { (cmd, desc) ->
            assertTrue("/$cmd — $desc" in BotCommands.HELP, "$cmd missing from /help")
        }
    }

    @Test
    fun subagentsReadsTheLiveLanes() = runBlocking {
        val o = ops()
        val hub = LiveHub()
        // a subagent lane: parent tool + a thought chunk under it
        fun ev(type: String, vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>) =
            ServerFrame.StreamEvent(
                taskId = "task-77", seq = 1, at = 1_000L,
                event = kotlinx.serialization.json.buildJsonObject {
                    put("type", type)
                    fields.forEach { (k, v) -> put(k, v) }
                },
            )
        runBlocking {
            hub.apply(
                ev(
                    "tool_call",
                    "toolId" to kotlinx.serialization.json.JsonPrimitive("t1"),
                    "toolName" to kotlinx.serialization.json.JsonPrimitive("Task"),
                    "parentToolUseId" to kotlinx.serialization.json.JsonPrimitive("tooluse-9"),
                ),
                1_000L,
            )
            hub.apply(
                ev(
                    "agent_thought_chunk",
                    "content" to kotlinx.serialization.json.JsonPrimitive("sub piensa"),
                    "parentToolUseId" to kotlinx.serialization.json.JsonPrimitive("tooluse-9"),
                ),
                1_000L,
            )
        }
        val bc = BotCommands(
            ops = o, hub = hub, daemonReset = { "" }, clock = { 2_000L },
            history = FakeHistory(rows), defaultWorkspacePath = defaultWs,
        )
        val reply = bc.reply("/subagents", 1L)!!
        assertTrue("subagents" in reply)
        assertTrue("Task" in reply)
        assertTrue("10 chars thought" in reply)
    }

    @Test
    fun subagentsWithNoTurnIsHonest() = runBlocking {
        val (bc, _) = commands(ops())
        val reply = bc.reply("/subagents", 1L)!!
        assertTrue("No turn is being watched" in reply)
    }

    @Test
    fun unknownCommandGetsTheBanner() = runBlocking {
        val (bc, _) = commands(ops())
        assertTrue("Unknown command" in bc.reply("/deploy", 1L)!!)
    }

    @Test
    fun freeTextWithTheChannelDownApologizes() = runBlocking {
        val o = ops().apply { available = false }
        val (bc, _) = commands(o)
        val reply = bc.reply("hola", 1L)!!
        assertTrue("channel to the app is down" in reply)
    }

    @Test
    fun commandsWithTheChannelDownExplainThemselves() = runBlocking {
        val o = ops().apply { available = false }
        val (bc, _) = commands(o)
        assertTrue("channel is down" in bc.reply("/tasks", 1L)!!)
        assertTrue("channel to the app is down" in bc.reply("/stop", 1L)!!)
    }

    @Test
    fun renderTaskListEmptyMessagesArePythons() {
        assertEquals(
            "🧠 No tasks project — /all_tasks shows all.",
            BotCommands.renderTaskList(emptyList(), emptySet(), null, null, all = false),
        )
        assertEquals(
            "🧠 No tasks — /all_tasks shows all.",
            BotCommands.renderTaskList(emptyList(), emptySet(), null, null, all = true),
        )
    }

    @Test
    fun quotaRenderIsTolerant() {
        assertEquals(
            "📊 Quota unavailable: not_authenticated",
            BotCommands.renderQuota("""{"unavailableReason":"not_authenticated"}""", 0L),
        )
        assertEquals(
            "📊 Quota: no pool info (the app is not authenticated?)",
            BotCommands.renderQuota("""{"authenticated":false}""", 0L),
        )
        val plain = BotCommands.renderQuota("""{"remaining":{"count":9,"isShow":true}}""", 0L)
        assertTrue("9 uses remaining" in plain)
    }

    @Test
    fun scopeRowsDropTheLooseDefaultWorkspace() {
        assertEquals(2, scopeRows(rows, all = false, defaultWs = defaultWs).size)
        assertEquals(3, scopeRows(rows, all = true, defaultWs = defaultWs).size)
    }
}
