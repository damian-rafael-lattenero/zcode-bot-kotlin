package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The /tasks row tap (`t:<sid>`): ATTACH to that task's live pipe — the
 * owner's 30/10 rule («yo quiero seleccionar cualquier task y empezar a
 * leer la tubería... es el feature principal casi»). The watch is pure
 * reading: no ownership, refused by nothing, even mid-turn of ANOTHER
 * conversation; the bind attempt rides along and may refuse honestly.
 * The subscribe frame is the contract — the fork streams ANY subscribed
 * task (daemonChannel.ts handleSubscribe has no gate).
 */
class TaskAttachTest {

    /** The fake ops: canned answers by query kind + the recorders. */
    private class FakeOps(startStatus: String) : ChannelOps {
        var botStatusJson: String = startStatus
        val answers = mutableMapOf<String, String>()
        val subscribed = mutableListOf<Pair<String, String>>()
        val inbound = mutableListOf<String>()

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            val key = queryKind(query)
            if (key == "botStatus") return QueryAnswer.Ok(botStatusJson)
            return answers[key]?.let { QueryAnswer.Ok(it) } ?: QueryAnswer.Failed("internal", "no canned answer")
        }

        override suspend fun sendInbound(text: String, messageId: String, receivedAt: Long): InboundReceipt {
            inbound.add(text)
            return InboundReceipt.FIRED
        }

        override suspend fun sendInboundAwait(text: String, messageId: String, receivedAt: Long): InboundAnswer {
            inbound.add(text)
            // the app's /task bind effect: the conversation now points there
            if (text.startsWith("/task ")) {
                botStatusJson = botStatusJson.replace(
                    "\"activeTaskId\":\"task-02\"",
                    "\"activeTaskId\":\"${text.removePrefix("/task ")}\"",
                ).replace("\"activeTaskId\":null", "\"activeTaskId\":\"${text.removePrefix("/task ")}\"")
            }
            return InboundAnswer.Answered(duplicate = false, replies = emptyList())
        }

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long): Int = 1

        override suspend fun subscribe(taskId: String, workspacePath: String) {
            subscribed.add(taskId to workspacePath)
        }

        val orders = mutableListOf<Pair<String, String>>() // text to taskId

        override suspend fun sendOrder(
            text: String,
            messageId: String,
            receivedAt: Long,
            taskId: String,
        ): InboundReceipt {
            orders.add(text to taskId)
            return InboundReceipt.FIRED
        }
    }

    private class FakeHistory(private val rows: List<TaskRow>) : TasksHistory {
        /** The store's running children — the sa: attach's verification set. */
        var runningSubs: List<String> = emptyList()

        /** The store's TITLED running children — the ACTIVE view's stream-independent rows. */
        var runningTitled: List<RunningSubagent> = emptyList()

        override suspend fun ctxTasks(limit: Int): List<TaskRow> = rows.take(limit)
        override suspend fun modelSelection(sid: String): ModelSel? = null
        override suspend fun freshestTask(workspacePath: String): TaskRow? = rows.firstOrNull()
        override suspend fun taskAlive(sid: String): Boolean = true
        override suspend fun subagentSessionIds(parentSid: String): List<String> = runningSubs
        override suspend fun subagentsRunning(parentSid: String): List<RunningSubagent> = runningTitled
    }

    private val app = "/Users/damian/Projects/app"

    private val rows = listOf(
        TaskRow(sid = "task-77", title = "unificador", ws = app),
        TaskRow(sid = "task-02", title = "otra", ws = app),
    )

    private val boundStatus = """
        {"bot":{"id":"daemon-local","enabled":true},
         "context":{"workspacePath":"$app","activeTaskId":"task-02","mode":"full_access"}}
    """.trimIndent()

    private val draftStatus = """
        {"bot":{"id":"daemon-local","enabled":true},
         "context":{"workspacePath":"$app","activeTaskId":null,"mode":"full_access"}}
    """.trimIndent()

    private val noContextStatus = """
        {"bot":{"id":"daemon-local","enabled":true},"context":null}
    """.trimIndent()

    private fun commands(ops: FakeOps): BotCommands = BotCommands(
        ops = ops,
        hub = LiveHub(),
        daemonReset = { "fake ladder" },
        clock = { 1_000_000L },
        history = FakeHistory(rows),
        defaultWorkspacePath = "/Users/damian/.zcode/workspace/default",
    )

    @Test
    fun tapAttachesThePipeOfARunningTaskThenBinds() = runBlocking {
        val ops = FakeOps(boundStatus).apply {
            botStatusJson = boundStatus.replace("\"task-02\"", "\"task-77\"")
            answers["activeTaskIds"] = """["task-77"]"""
        }
        val reply = commands(ops).onCallback("t:task-77", 5L)!!
        // THE feature: the pipe attach happened, whatever the bind did
        assertEquals(listOf("task-77" to app), ops.subscribed)
        assertTrue("👁" in reply.text)
        assertTrue("«unificador» en vivo" in reply.text)
        assertTrue("/last" in reply.text) // the honest history note
        assertTrue("🎯" in reply.text) // the bind confirmed (it was already the active task)
    }

    @Test
    fun tapOnAnIdleTaskSpeaksThePatientWording() = runBlocking {
        // no conversation bound, nothing running: the watch lands, the bind
        // re-arms the next text into the freshly watched task
        val ops = FakeOps(draftStatus).apply { answers["activeTaskIds"] = """[]""" }
        val reply = commands(ops).onCallback("t:task-77", 5L)!!
        assertEquals(listOf("task-77" to app), ops.subscribed)
        assertTrue("«unificador» suscripto" in reply.text)
        assertTrue("próximo turno" in reply.text)
        assertTrue("🎯" in reply.text)
    }

    @Test
    fun attachHappensEvenWhenTheBindRefuses() = runBlocking {
        // task-02 owns the conversation and is RUNNING: the old tap died
        // right here («/stop first») — the watch must survive the refusal
        val ops = FakeOps(boundStatus).apply { answers["activeTaskIds"] = """["task-02"]""" }
        val reply = commands(ops).onCallback("t:task-77", 5L)!!
        assertEquals(listOf("task-77" to app), ops.subscribed)
        assertTrue("👁" in reply.text)
        assertTrue("⏳" in reply.text) // the bind's honest refusal rides along
    }

    @Test
    fun tapOnAnUnknownSidWithoutContextIsHonest() = runBlocking {
        val ops = FakeOps(noContextStatus)
        val reply = commands(ops).onCallback("t:task-99", 5L)!!
        assertTrue(ops.subscribed.isEmpty()) // nothing to attach to — no silent fake watch
        assertTrue("🤷" in reply.text)
        assertTrue("/tasks" in reply.text)
    }

    // ---- subagent lanes: subagent implements task (the owner's model) -------

    private val laneId = "toolu_aaaabbbbccccdddd" // a Task tool's tool_use_id = the lane key
    private val laneTail = laneId.takeLast(16) // what the sa: button carries (the 64B law)

    private fun streamEvent(taskId: String, json: String, seq: Long): ServerFrame.StreamEvent =
        ServerFrame.StreamEvent(
            taskId = taskId,
            seq = seq,
            at = 1_000_000L,
            event = kotlinx.serialization.json.Json.parseToJsonElement(json)
                as kotlinx.serialization.json.JsonObject,
        )

    @Test
    fun laneScopeFoldsOnlyThatSubagentStream() = runBlocking {
        val hub = LiveHub()
        val t = "task-77"
        hub.apply(streamEvent(t, """{"type":"tool_call","toolId":"$laneId","toolName":"Task"}""", 1), 1_000_000L)
        hub.attachLane(laneId)
        // the PARENT's own narration: dropped — the card speaks only the lane
        hub.apply(
            streamEvent(t, """{"type":"agent_thought_chunk","content":"soy el padre","parentToolUseId":null}""", 2),
            1_000_001L,
        )
        assertEquals(0, hub.peek()!!.thoughtChars)
        // the watched lane's thought: the MAIN narration of this card
        hub.apply(
            streamEvent(t, """{"type":"agent_thought_chunk","content":"soy el sub","parentToolUseId":"$laneId"}""", 3),
            1_000_002L,
        )
        val after = hub.peek()!!
        assertEquals(10, after.thoughtChars)
        assertTrue("soy el sub" in after.cotTail)
        assertEquals(laneId, hub.watchedLaneId())
    }

    @Test
    fun watchedRootToolCloseSealsTheLaneCard() = runBlocking {
        val hub = LiveHub()
        val t = "task-77"
        hub.apply(streamEvent(t, """{"type":"tool_call","toolId":"$laneId","toolName":"Task"}""", 1), 1_000_000L)
        hub.attachLane(laneId)
        val sealed = hub.apply(
            streamEvent(t, """{"type":"tool_call_update","toolId":"$laneId","status":"completed"}""", 2),
            1_000_003L,
        )!!
        assertTrue(sealed.phase is TurnPhase.Done)
        assertEquals("subagent completed", (sealed.phase as TurnPhase.Done).stopReason)
    }

    @Test
    fun laneTapResolvesTheTailAndScopesTheCard() = runBlocking {
        val ops = FakeOps(boundStatus).apply { answers["activeTaskIds"] = """[]""" }
        val hub = LiveHub()
        val bc = BotCommands(
            ops = ops,
            hub = hub,
            daemonReset = { "fake ladder" },
            clock = { 1_000_000L },
            history = FakeHistory(rows),
            defaultWorkspacePath = "/Users/damian/.zcode/workspace/default",
        )
        // the parent turn is live with the subagent lane open (the stream's own truth)
        hub.apply(streamEvent("task-02", """{"type":"tool_call","toolId":"$laneId","toolName":"Task"}""", 1), 1_000_000L)
        val reply = bc.onCallback("sa:$laneTail", 7L)!!
        assertEquals(laneId, hub.watchedLaneId())
        assertTrue("Subagente" in reply.text)
        assertTrue("misma carta que una task" in reply.text)
    }

    @Test
    fun laneTapOnAGoneSubagentIsHonest() = runBlocking {
        val ops = FakeOps(boundStatus)
        val reply = commands(ops).onCallback("sa:deadbeefdeadbeef", 7L)!!
        assertTrue("🤷" in reply.text)
        assertTrue("/tasks" in reply.text)
    }

    // ---- the FULL form: the subagent's OWN session stream -------------------

    @Test
    fun laneTapWithAKnownAgentIdSubscribesTheChildSession() = runBlocking {
        val agentId = "agent_419b5d75-bcf3-4343-9b9f-64c718249330"
        val ops = FakeOps(boundStatus).apply { answers["activeTaskIds"] = """[]""" }
        val history = FakeHistory(rows).apply { runningSubs = listOf("sess_subagent_$agentId") }
        val hub = LiveHub()
        val bc = BotCommands(
            ops = ops,
            hub = hub,
            daemonReset = { "fake ladder" },
            clock = { 1_000_000L },
            history = history,
            defaultWorkspacePath = "/Users/damian/.zcode/workspace/default",
        )
        // the parent's dispatch lane carries the fork's additive launch-ACK field
        hub.apply(
            streamEvent(
                "task-02",
                """{"type":"tool_call","toolId":"$laneId","toolName":"Task","subagentAgentId":"$agentId"}""",
                1,
            ),
            1_000_000L,
        )
        val reply = bc.onCallback("sa:$laneTail", 7L)!!
        // the REAL link: subscribed to the CHILD's session, card pinned to it
        assertEquals(listOf("sess_subagent_$agentId" to app), ops.subscribed)
        assertEquals("sess_subagent_$agentId", hub.peekPin())
        assertTrue("su turno COMPLETO" in reply.text)
    }

    @Test
    fun laneTapWithAnUnknownSessionFallsBackToLaneScope() = runBlocking {
        // the store doesn't hold the child (old fork / ended): no subscribe,
        // no pin — the honest sparser lane-scope view instead
        val agentId = "agent_80116bbd-c21b-4988-9c1f-11d7c2130164"
        val ops = FakeOps(boundStatus).apply { answers["activeTaskIds"] = """[]""" }
        val hub = LiveHub()
        val bc = BotCommands(
            ops = ops,
            hub = hub,
            daemonReset = { "fake ladder" },
            clock = { 1_000_000L },
            history = FakeHistory(rows), // runningSubs stays empty
            defaultWorkspacePath = "/Users/damian/.zcode/workspace/default",
        )
        hub.apply(
            streamEvent(
                "task-02",
                """{"type":"tool_call","toolId":"$laneId","toolName":"Task","subagentAgentId":"$agentId"}""",
                1,
            ),
            1_000_000L,
        )
        val reply = bc.onCallback("sa:$laneTail", 7L)!!
        assertTrue(ops.subscribed.isEmpty())
        assertEquals(laneId, hub.watchedLaneId())
        assertNull(hub.peekPin())
        assertTrue("mirando solo su stream" in reply.text)
    }

    @Test
    fun pinDropsOtherTasksEventsAndFoldsThePinnedOnes() = runBlocking {
        val hub = LiveHub()
        val pinned = "sess_subagent_agent_x"
        hub.pin(pinned)
        // the parent keeps talking while the card watches the child: dropped
        hub.apply(
            streamEvent("task-02", """{"type":"agent_thought_chunk","content":"padre","parentToolUseId":null}""", 1),
            1_000_000L,
        )
        assertNull(hub.peek())
        // the pinned session's own thought: folds as a FRESH turn (identical UI)
        val folded = hub.apply(
            streamEvent(pinned, """{"type":"agent_thought_chunk","content":"soy el sub","parentToolUseId":null}""", 2),
            1_000_001L,
        )!!
        assertEquals(pinned, folded.taskId)
        assertEquals(10, folded.thoughtChars)
        // a foreign task AFTER the pin's card exists: still dropped
        hub.apply(
            streamEvent("task-77", """{"type":"agent_thought_chunk","content":"otro","parentToolUseId":null}""", 3),
            1_000_002L,
        )
        assertEquals(10, hub.peek()!!.thoughtChars)
    }

    @Test
    fun clearLiftsThePinSoTheParentReLights() = runBlocking {
        val hub = LiveHub()
        hub.pin("sess_subagent_agent_x")
        hub.clear() // the seal's own path
        assertNull(hub.peekPin())
    }

    // ---- the 30/10 button bugs: real names, /tasks dispatch-only, /subagents rows ---

    private fun laneWith(name: String, toolId: String, description: String? = null): ToolLive {
        val input = description?.let {
            kotlinx.serialization.json.buildJsonObject {
                put("description", kotlinx.serialization.json.JsonPrimitive(it))
            }
        }
        return ToolLive(
            toolId = toolId,
            name = name,
            title = name,
            input = input,
            openedAtMs = 1_000_000L,
        )
    }

    @Test
    fun subagentRowsSpeakTheRealNameAndCarryTheAttachTap() {
        val kb = Keyboards.subagentsKeyboard(
            lanes = listOf(
                laneWith("Agent", "toolu_dispatchaaaa1111", "fix the /plan menu"),
                laneWith("Task", "toolu_dispatchbbbb2222"),
            ),
            inactiveView = false,
        ).toString()
        // the NAME the owner gave it — never the bare tool name (the «bash, bash» bug)
        assertTrue("fix the /plan menu" in kb)
        assertTrue("Task" in kb) // no description → the honest fallback
        // the rows ARE the attach taps (same vocabulary the dispatcher owns)
        assertTrue("sa:${"toolu_dispatchaaaa1111".takeLast(16)}" in kb)
        assertTrue("sa:${"toolu_dispatchbbbb2222".takeLast(16)}" in kb)
        assertTrue("sao:" in kb) // the view switch rides along
    }

    // ---- the ✍️ armed order: the loop closes (read the stream → write the order) —

    @Test
    fun orderTapAttachesAndArmsTheNextFreeText() = runBlocking {
        val ops = FakeOps(boundStatus).apply { answers["activeTaskIds"] = """[]""" }
        val history = FakeHistory(rows).apply { runningSubs = listOf("sess_subagent_agent_x") }
        val hub = LiveHub()
        val bc = BotCommands(
            ops = ops,
            hub = hub,
            daemonReset = { "fake ladder" },
            clock = { 1_000_000L },
            history = history,
            defaultWorkspacePath = "/Users/damian/.zcode/workspace/default",
        )
        hub.apply(
            streamEvent(
                "task-02",
                """{"type":"tool_call","toolId":"$laneId","toolName":"Task","subagentAgentId":"agent_x"}""",
                1,
            ),
            1_000_000L,
        )
        val arm = bc.onCallback("so:$laneTail", 9L)!!
        assertTrue("Armado" in arm.text) // the arm rides the attach
        assertEquals(1, ops.subscribed.size) // the tap ALSO attaches
        // the FIRST free text after the arm is the ORDER — direct to the session
        val ack = bc.handle("cambiale el título a la carta", 10L)!!
        assertTrue("Orden entregada" in ack.text, "got: $ack")
        assertEquals(listOf("cambiale el título a la carta" to "sess_subagent_agent_x"), ops.orders)
        // the arm is ONE shot: the next text rides the normal routing again
        val second = bc.handle("esto ya va a la conversación", 11L)
        assertEquals(1, ops.orders.size)
        assertTrue("Orden entregada" !in (second?.text ?: ""))
    }

    @Test
    fun theStoreRunningSubagentsAreVisibleAndAttachable() = runBlocking {
        // the bus died before the lane streamed: the ACTIVE view still shows
        // the running subagent FROM THE STORE and its tap attaches the session
        val ops = FakeOps(boundStatus).apply { answers["activeTaskIds"] = """[]""" }
        val sess = "sess_subagent_agent_2e587e61"
        val history = FakeHistory(rows).apply {
            runningSubs = listOf(sess)
            runningTitled = listOf(RunningSubagent(sess, "cazar la causa raíz del bus"))
        }
        val hub = LiveHub() // an EMPTY hub — the stream never saw the lane
        val bc = BotCommands(
            ops = ops,
            hub = hub,
            daemonReset = { "fake ladder" },
            clock = { 1_000_000L },
            history = history,
            defaultWorkspacePath = "/Users/damian/.zcode/workspace/default",
        )
        val reply = bc.handle("/subagents", 3L)!!
        val data = reply.markup.toString()
        assertTrue("cazar la causa raíz" in data, "the store's row must render: $data")
        assertTrue("sa:${sess.takeLast(16)}" in data) // the attach tap carries the session tail
        // the tap: subscribed to the CHILD session + pinned, no lane needed
        val attach = bc.onCallback("sa:${sess.takeLast(16)}", 4L)!!
        assertEquals(listOf(sess to app), ops.subscribed)
        assertEquals(sess, hub.peekPin())
        assertTrue("attachado" in attach.text)
    }

    @Test
    fun taskRowOrderTapArmsTheTaskSession() = runBlocking {
        val ops = FakeOps(boundStatus).apply { answers["activeTaskIds"] = """[]""" }
        val bc = commands(ops)
        val arm = bc.onCallback("so:task-77", 8L)!!
        // the task's OWN session: subscribed (the pipe) + armed (the order)
        assertEquals(listOf("task-77" to app), ops.subscribed)
        assertTrue("sin bind" in arm.text)
        val ack = bc.handle("la orden directa", 9L)!!
        assertTrue("Orden entregada" in ack.text)
        assertEquals(listOf("la orden directa" to "task-77"), ops.orders)
    }

    @Test
    fun taskKeyboardSubagentRowsAreDispatchLanesOnly() {
        // the 30/10 bug: the agents' inner bash/read tools leaked into the
        // /tasks keyboard — the wiring's filter is the dispatch-only law
        val inner = ToolLive(
            toolId = "toolu_inner_bashhh111",
            name = "Bash",
            title = "Bash",
            parentToolUseId = laneId,
            openedAtMs = 1_000_000L,
        )
        val dispatch = laneWith("Agent", laneId, "research plugins")
        val rows = listOf(inner, dispatch).filter {
            it.parentToolUseId == null && it.status == null && it.name in setOf("Task", "Agent")
        }
        assertEquals(listOf(dispatch), rows)
        assertTrue("research plugins" in Keyboards.subagentLabel(dispatch))
        assertTrue("Bash" !in Keyboards.subagentLabel(dispatch))
    }
}
