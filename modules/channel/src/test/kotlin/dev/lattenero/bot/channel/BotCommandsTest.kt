package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bot's configured owner — the taps the suite sends come from him. */
private const val OWNER = 7L

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

        /** Per-command awaited replies (the /project → /task ladder needs both). */
        var awaitReplyFor: (String) -> List<String> = { _ -> awaitReplies }

        /** Server-side effect of an inbound command (tests flip botStatus on /task). */
        var onInbound: (String) -> Unit = {}

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            asked.add(query)
            if (!available) return QueryAnswer.Unavailable
            val key = queryKind(query)
            return answers[key]?.let { QueryAnswer.Ok(it) } ?: QueryAnswer.Failed("internal", "no canned answer")
        }

        override suspend fun sendInbound(
            text: String,
            messageId: String,
            receivedAt: Long,
            attachments: List<InboundAttachment>,
        ): InboundReceipt {
            if (!available) return InboundReceipt.UNAVAILABLE
            inbound.add(text to messageId)
            onInbound(text)
            return InboundReceipt.FIRED
        }

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
            bindTaskId: String?,
        ): InboundAnswer {
            if (!available) return InboundAnswer.Unavailable
            awaited.add(text to messageId)
            if (bindTaskId != null) atomicBinds.add(bindTaskId to text)
            onInbound(text)
            return InboundAnswer.Answered(duplicate = false, replies = awaitReplyFor(text))
        }

        /** The atomic binds (bindTaskId → text) — empty unless the wiring speaks the cap. */
        val atomicBinds = mutableListOf<Pair<String, String>>()
        var bindCap = false

        override fun supportsBind(): Boolean = bindCap

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
            ownerUserId = { OWNER },
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
            💬 <b>Conversations by project</b> — tap a row to watch it live
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
    fun tasksNumericArgIsRetiredTheListIsTheAnswer() = runBlocking {
        val o = ops().apply { answers["activeTaskIds"] = """["task-77"]""" }
        val (bc, _) = commands(o)
        val reply = bc.handle("/tasks 2", 7L)!!
        assertTrue("Conversations by project" in reply.text, "a number just re-shows the tappable list")
        assertTrue(reply.markup != null, "the list is never buttonless")
        assertTrue("Sin números" in reply.text, "the retirement hint rides along")
        assertTrue(o.inbound.isEmpty() && o.awaited.isEmpty(), "no bind ever fires from a number")
    }

    @Test
    fun allTasksNumericArgIsRetiredToo() = runBlocking {
        val (bc, _) = commands(ops())
        val reply = bc.handle("/all_tasks 5", 1L)!!
        assertTrue("All conversations" in reply.text)
        assertTrue(reply.markup != null, "the all-list is tappable")
        assertTrue("Sin números" in reply.text)
    }

    @Test
    fun tasksCarriesTheKeyboardEvenWithoutTheChannelsContext() = runBlocking {
        // the 2/11 incident: a channel bounce made botStatus context-less and
        // the list went out BUTTONLESS — the rows and taps are LOCAL data, the
        // keyboard survives every context loss (only a dead channel degrades)
        val (bc, _) = commands(ops(status = """{"bot":{"id":"daemon-local","enabled":true}}"""))
        val reply = bc.handle("/tasks", 1L)!!
        assertTrue("Conversations by project" in reply.text)
        assertTrue(reply.markup != null, "buttons ride on the local session store, not on the channel's context")
        assertTrue(reply.markup.toString().contains("\"t:task-77\""))
    }

    @Test
    fun pickBySidWhileThePreviousTaskRunsSaysStopFirst() = runBlocking {
        val o = ops().apply { answers["activeTaskIds"] = """["task-77"]""" }
        val (bc, _) = commands(o)
        assertEquals(
            "⏳ The previous task's turn is still running — the switch did NOT happen. " +
                "/stop first, then pick again.",
            bc.reply("/tasks @task-02", 1L),
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
    fun theAtomicBindFliesAsOneFrameWhenTheServerSpeaksIt() = runBlocking {
        // the fork's cap «bind» (d8510c23): bind + text in ONE awaited frame —
        // no "/task" command, no second send, the refusal would ride the same
        // inbound_result
        val o = ops().apply {
            botStatusJson = draftStatus
            answers["botStatus"] = draftStatus
            bindCap = true
            // the REAL host re-points the context during the atomic bind —
            // the frame's own side effect (the old path flipped it on "/task")
            onInbound = { answers["botStatus"] = boundStatus }
        }
        val (bc, _) = commands(o)
        assertNull(bc.reply("probando probando", 10L))
        assertTrue(o.inbound.isEmpty(), "the text went INSIDE the awaited frame, not as a second send")
        assertEquals(
            listOf("probando probando" to "tg-10"),
            o.awaited, // ONE awaited frame — the text itself, never a "/task" command
        )
        assertEquals(listOf("task-77" to "probando probando"), o.atomicBinds)
        assertEquals(listOf("task-77" to app), o.subscribed)
    }

    @Test
    fun theAtomicBindRefusalSpeaksAndNeverDeliversTwice() = runBlocking {
        // the host's taskRunning verdict rides the SAME inbound_result — the
        // text did NOT fly, the owner hears it, nothing re-sends
        val o = ops().apply {
            botStatusJson = draftStatus
            answers["botStatus"] = draftStatus
            bindCap = true
            awaitReplies = listOf("⏳ task still running")
        }
        val (bc, _) = commands(o)
        val reply = bc.reply("probando probando", 11L)
        assertTrue(reply != null, "the refusal speaks")
        assertTrue(o.inbound.isEmpty(), "the refused text is never delivered by a second send")
        assertEquals(1, o.atomicBinds.size)
    }

    @Test
    fun theTapIsIntentTheTextFliesAtItEvenWhenTheIndexDisagrees() = runBlocking {
        // THE 16:05 CASE (the owner's report, measured live): he tapped B,
        // but the tasks-index said another conversation was «open» — the
        // router's freshest-row law re-bound his text to «go_to_background».
        // THE SELECTION LAW: an explicit tap outranks every heuristic — the
        // atomic bindTaskId carries HIS sid on the wire, always.
        val boundTo02 = """
            {"bot":{"id":"daemon-local","enabled":true},
             "context":{"workspacePath":"$botWs","activeTaskId":"task-02","mode":"task"}}
        """.trimIndent()
        val o = ops().apply {
            botStatusJson = boundStatus // the fork context still says task-77
            answers["botStatus"] = boundStatus
            bindCap = true
            awaitReplyFor = { text ->
                if (text.startsWith("/task ")) listOf("Workspace: bot\n------\nTask: second (task-02)\nState: idle") else emptyList()
            }
        }
        val (bc, _) = commands(o)
        bc.onCallback("t:task-02", 20L, OWNER)!! // the tap: CONFIRMED, intent armed
        bc.reply("hola elegida", 21L)
        assertEquals(
            listOf("task-02" to "hola elegida"), // (bindTaskId, text) — the wire's order
            o.atomicBinds,
            "the text flies AT the tapped task, whatever the index's freshest row says",
        )
        // the status speaks the standing choice
        assertTrue("🎯 elegida: «second»" in bc.reply("/status", 22L)!!)
    }

    @Test
    fun theTappedTaskMidTurnParksTheTextOnIt() = runBlocking {
        val o = ops().apply {
            botStatusJson = boundStatus
            answers["botStatus"] = boundStatus
            answers["activeTaskIds"] = """["task-02"]""" // the CHOSEN task is mid-turn
            bindCap = true
            awaitReplyFor = { text ->
                if (text.startsWith("/task ")) listOf("Task: second (task-02)\nState: running") else emptyList()
            }
        }
        val (bc, _) = commands(o)
        bc.onCallback("t:task-02", 30L, OWNER)!!
        val reply = bc.reply("esto para cuando termine", 31L)!!
        assertTrue(o.queued.isNotEmpty(), "the text parked (F17) on the CHOSEN conversation")
        assertTrue("second" in reply, "the park names the chosen task: $reply")
    }

    @Test
    fun theSelectionThatDiesFallsBackToTheStandingLaws() = runBlocking {
        val o = ops().apply {
            botStatusJson = draftStatus
            answers["botStatus"] = draftStatus
            bindCap = true
            awaitReplyFor = { text ->
                if (text.startsWith("/task ")) listOf("Task: second (task-02)") else emptyList()
            }
        }
        val history = FakeHistory(rows, alive = { it != "task-02" }) // the tap's task dies after
        val bc = BotCommands(
            ops = o, hub = LiveHub(), daemonReset = { "x" }, ownerUserId = { OWNER },
            clock = { 1_000_000L }, history = history, defaultWorkspacePath = defaultWs,
        )
        bc.onCallback("t:task-02", 40L, OWNER)!! // armed... then the task vanishes
        bc.reply("hola", 41L)
        assertEquals(
            listOf("task-77" to "hola"),
            o.atomicBinds,
            "a dead selection never fires at a ghost — the standing laws resume",
        )
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
        val reply = bc.onCallback("t:task-02", 99L, OWNER)!!
        // the owner's 5/11 law: the tap's next message is a 100%-sure
        // confirmation, read back from the fork's own state
        assertTrue("✅ CONFIRMADO: «second» es la conversación activa" in reply.text)
        assertTrue("(verificado leyendo el fork" in reply.text)
        assertEquals(listOf("/task task-02" to "tg-99-bind"), o.awaited)
    }

    @Test
    fun nuclearIsPythonsThreeStep() = runBlocking {
        var resets = 0
        val o = ops()
        val bc = BotCommands(
            ops = o, hub = LiveHub(), daemonReset = { resets += 1; "fake ladder: 2 stopped" },
            ownerUserId = { OWNER },
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
        assertTrue("📅 Weekly: 88,000/88,000 credits (90%)" in text) // the owner's law: separator, unit never moves
        assertTrue("⚠️" in text) // ≥75% carries the alert
    }

    @Test
    fun quotaSpeaksTheStartPlanBucketsWithTheUsedPercent() {
        // the owner's 30/10 order, with the 2/11 law v4: the API numbers are
        // RAW token counts — «300,000,000 → 300,000K → 300M» (the fork's
        // builder carries usage/unit raw; its percentage is REMAINING-based,
        // so the used % is computed here)
        val json = """
            {"authenticated":true,
             "provider":{"id":"account:zai-start-plan","name":"Start Plan"},
             "remaining":{"count":160000,"isShow":true,"percentage":0.32},
             "quota":{"level":"Start","limits":[
               {"type":"tok_5h","meter":"tokens","period":"5 hours","unit":500000000,"usage":340000000,"remaining":160000000,"percentage":0.32,"nextResetTime":3600000},
               {"type":"tok_week","meter":"tokens","period":"weekly","unit":4000000000,"usage":900000000,"remaining":3100000000,"percentage":0.775,"nextResetTime":500000000}]}
            }
        """.trimIndent()
        val text = BotCommands.renderQuota(json, nowMs = 0L)
        assertTrue("📊 <b>Start plan</b>" in text)
        assertTrue("• 5 hours: 340M/500M usado 68%" in text)
        assertTrue("• weekly: 900M/4,000M usado 22%" in text)
        assertTrue("⚠️" !in text) // 68% and 22% are below the 75% alert
    }

    @Test
    fun quotaBucketBelowAMillionTokensStaysK() {
        // the ladder's bottom rung: raw 850,000 tokens → «850K», never «0.85M»
        val json = """
            {"authenticated":true,
             "provider":{"id":"account:zai-start-plan","name":"Start Plan"},
             "quota":{"level":"Start","limits":[
               {"type":"tok_5h","meter":"tokens","period":"5 hours","unit":500000000,"usage":850000,"remaining":499150000,"percentage":0.9993,"nextResetTime":3600000}]}
            }
        """.trimIndent()
        val text = BotCommands.renderQuota(json, nowMs = 0L)
        assertTrue("• 5 hours: 850K/500M usado 0%" in text, "got: $text")
    }

    @Test
    fun quotaSpeaksTheGiftBucketWithItsSeparators() {
        // the owner's law verbatim (2/11): the gift is 300M tokens — RAW
        // 300,000,000 → «300M»; the used side 166,603,400 → «166.6M»
        // (2 decimals, trailing zeros off, sistema americano)
        val json = """
            {"authenticated":true,
             "provider":{"id":"account:zai-start-plan","name":"Start Plan"},
             "quota":{"level":"Start","limits":[
               {"type":"tok_one_time","meter":"tokens","period":"one_time","unit":300000000,"usage":166603400,"nextResetTime":3600000}]}
            }
        """.trimIndent()
        val text = BotCommands.renderQuota(json, nowMs = 0L)
        assertTrue("• one_time: 166.6M/300M usado 55%" in text, "got: $text")
    }

    @Test
    fun quotaStartPlanBucketsAlertAtSeventyFive() {
        val json = """
            {"authenticated":true,
             "quota":{"level":"Start","limits":[
               {"type":"tok_5h","meter":"tokens","period":"5 hours","unit":400000000,"usage":340000000,"nextResetTime":3600000}]}
            }
        """.trimIndent()
        val text = BotCommands.renderQuota(json, nowMs = 0L)
        assertTrue("340M/400M usado 85%" in text)
        assertTrue("⚠️" in text)
    }

    @Test
    fun helpListsExactlyTheMenu() {
        assertNotNull(BotCommands.HELP)
        assertTrue(BotCommands.MENU.size >= 14) // steer landed; the count is MENU.size-driven now
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
            ownerUserId = { OWNER },
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

    // ---- the audit's reply-contract gaps (30/10) ---------------------------

    @Test
    fun startAliasSpeaksTheHelp() = runBlocking {
        val (bc, _) = commands(ops())
        assertTrue("The ${BotCommands.MENU.size}" in bc.reply("/start", 1L)!!)
        assertTrue("The ${BotCommands.MENU.size}" in bc.reply("/help", 2L)!!)
    }

    @Test
    fun statusWithoutContextAndDownAreHonest() = runBlocking {
        val (bc, _) = commands(ops(status = """{"bot":{"id":"daemon-local","enabled":true},"context":null}"""))
        val none = bc.reply("/status", 1L)!!
        assertTrue("🎯 Active: none" in none, none)
        assertTrue("running now" !in none, "no context, no running line")
        val (bcDown, _) = commands(ops().apply { available = false })
        assertTrue("channel to the app is down" in bcDown.reply("/status", 2L)!!)
    }

    @Test
    fun stopWithNothingActiveDoesNotFireAQuery() = runBlocking {
        val o = ops(status = draftStatus).apply { answers["botStatus"] = draftStatus }
        val (bc, _) = commands(o)
        assertEquals(
            "🤷 Nothing to stop — the bot's context has no active task.",
            bc.reply("/stop", 1L),
        )
        assertTrue(o.asked.filterIsInstance<ChannelQuery.Stop>().isEmpty())
    }

    @Test
    fun quotaSpeaksWhenTheChannelFails() = runBlocking {
        val (bcDown, _) = commands(ops().apply { available = false })
        assertTrue("channel to the app is down" in bcDown.reply("/quota", 1L)!!)
        val (bcFail, _) = commands(ops().apply { answers.remove("quota") })
        assertTrue("⚠️ Quota failed (internal)" in bcFail.reply("/quota", 2L)!!)
    }

    @Test
    fun tasksNewWithAProjectTagIsRefused() = runBlocking {
        val o = ops(status = draftStatus).apply { answers["botStatus"] = draftStatus }
        val (bc, _) = commands(o)
        val reply = bc.reply("/tasks new otro-proyecto", 1L)!!
        assertTrue(reply.startsWith("⚠️ /tasks new opens it in the ACTIVE project"), reply)
        assertTrue(o.inbound.none { it.first == "/new" }, "the refusal fires nothing")
    }

    @Test
    fun tasksAtSidPicksDirectlyLikeTheButton() = runBlocking {
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
        val reply = bc.reply("/tasks @task-02", 8L)!!
        assertTrue("✅ CONFIRMADO: «second» es la conversación activa" in reply)
        assertEquals(listOf("/task task-02" to "tg-8-bind"), o.awaited)
    }

    @Test
    fun allTasksNumericArgIsJustTheList() = runBlocking {
        val (bc, _) = commands(ops())
        val reply = bc.reply("/all_tasks 9", 1L)!!
        assertTrue("All conversations" in reply, "a number never errors — it re-shows the list")
    }

    @Test
    fun tasksZeroIsJustTheListToo() = runBlocking {
        val (bc, _) = commands(ops())
        val reply = bc.reply("/tasks 0", 1L)!!
        assertTrue("Conversations by project" in reply)
    }

    /** The 5/11 dead end, now a ladder: «Task not found» → /project → /task → read-back ✅. */
    @Test
    fun theTapLadderMovesTheForkToTheTaskProjectAndConfirms() = runBlocking {
        val boundTo02InBot = """
            {"bot":{"id":"daemon-local","enabled":true},
             "context":{"workspacePath":"$botWs","activeTaskId":"task-02","mode":"task"}}
        """.trimIndent()
        var switched = false
        val o = ops().apply {
            botStatusJson = boundStatus
            answers["botStatus"] = boundStatus
            onInbound = { text ->
                if (text.startsWith("/project ")) {
                    switched = true
                    answers["botStatus"] = boundStatus
                }
                if (text.startsWith("/task ")) answers["botStatus"] = boundTo02InBot
            }
            awaitReplyFor = { text ->
                when {
                    text.startsWith("/task ") && !switched -> listOf("Task not found.")
                    text.startsWith("/task ") ->
                        listOf("Workspace: bot\nModel: x\n------\nTask: second (task-02)\nState: idle")
                    text.startsWith("/project ") -> listOf("Workspace: bot\nModel: x\n------\nTask: draft\nState: draft")
                    else -> emptyList()
                }
            }
        }
        val (bc, _) = commands(o)
        val reply = bc.onCallback("t:task-02", 42L, OWNER)!!
        assertTrue("✅ CONFIRMADO: «second» es la conversación activa" in reply.text)
        assertTrue("moví el fork a su proyecto «bot»" in reply.text)
        assertEquals(
            listOf(
                "/task task-02" to "tg-42-bind",
                "/project $botWs" to "tg-42-ws",
                "/task task-02" to "tg-42-bind",
            ),
            o.awaited,
        )
    }

    /** The fork's own word when the workspace switch hits a running turn — reported, never lied. */
    @Test
    fun theTapLadderReportsTheForksTaskRunningWord() = runBlocking {
        val o = ops().apply {
            botStatusJson = boundStatus
            answers["botStatus"] = boundStatus
            awaitReplyFor = { text ->
                when {
                    text.startsWith("/task ") -> listOf("Task not found.")
                    text.startsWith("/project ") ->
                        listOf("The current task is still running. Try again later, or use **/stop** to stop the current task.")
                    else -> emptyList()
                }
            }
        }
        val (bc, _) = commands(o)
        val reply = bc.onCallback("t:task-02", 43L, OWNER)!!
        assertTrue("⏳ Para activar «second» tengo que mudar el fork a su proyecto" in reply.text)
        assertTrue("/stop primero" in reply.text)
        assertEquals(listOf("/task task-02" to "tg-43-bind", "/project $botWs" to "tg-43-ws"), o.awaited)
    }

    /** A bind whose read-back contradicts the command is a FAILURE — never a decorated ✅. */
    @Test
    fun theBindThatTheReadbackContradictsIsHonest() = runBlocking {
        val o = ops().apply {
            botStatusJson = boundStatus
            answers["botStatus"] = boundStatus // activeTaskId stays task-77 no matter what fires
            awaitReplyFor = { text -> if (text.startsWith("/task ")) listOf("Workspace: app\nModel: x") else emptyList() }
        }
        val (bc, _) = commands(o)
        val reply = bc.onCallback("t:task-02", 44L, OWNER)!!
        assertTrue("⚠️ El bind no pudo confirmarse" in reply.text)
        assertTrue("task-77" in reply.text, "the contradiction names what actually stayed active")
    }

    /** The host accepted the bind while the read-back stayed silent — the reply says so, no «verificado». */
    @Test
    fun theUnverifiableBindSaysSo() = runBlocking {
        val o = ops().apply {
            answers.remove("botStatus") // the poll can't run at all
            awaitReplyFor = { text -> if (text.startsWith("/task ")) listOf("Workspace: app\nModel: x") else emptyList() }
        }
        val (bc, _) = commands(o)
        val reply = bc.onCallback("t:task-02", 45L, OWNER)!!
        assertTrue("🎯 Tu texto va a «second»" in reply.text)
        assertTrue("no dejó releer el estado" in reply.text)
        assertTrue("✅ CONFIRMADO" !in reply.text)
    }
}
