package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bot's configured owner — the taps the suite sends come from him. */
private const val OWNER = 7L

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

        override suspend fun sendInbound(
            text: String,
            messageId: String,
            receivedAt: Long,
            attachments: List<InboundAttachment>,
        ): InboundReceipt {
            inbound.add(text)
            return InboundReceipt.FIRED
        }

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
            bindTaskId: String?,
        ): InboundAnswer {
            inbound.add(text)
            // the app's /task bind effect: the conversation now points there
            if (text.startsWith("/task ")) {
                botStatusJson = botStatusJson.replace(
                    "\"activeTaskId\":\"task-02\"",
                    "\"activeTaskId\":\"${text.removePrefix("/task ")}\"",
                ).replace("\"activeTaskId\":null", "\"activeTaskId\":\"${text.removePrefix("/task ")}\"")
            }
            return InboundAnswer.Answered(duplicate = false, replies = awaitReplyFor(text))
        }

        /** The host's canned bind verdict (empty = confirmed silently). */
        var cannedBindReplies: List<String> = emptyList()

        /** Per-command verdicts — the /project → /task ladder needs both. */
        var awaitReplyFor: (String) -> List<String> = { _ -> cannedBindReplies }

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long): Int = 1

        override suspend fun subscribe(taskId: String, workspacePath: String) {
            subscribed.add(taskId to workspacePath)
        }

        val orders = mutableListOf<Pair<String, String>>() // text to taskId

        /** The fork's verdict for the next order — the awaited truth. */
        var orderAnswer: InboundAnswer = InboundAnswer.Answered(duplicate = false, replies = emptyList())

        override suspend fun sendOrder(
            text: String,
            messageId: String,
            receivedAt: Long,
            taskId: String,
        ): InboundAnswer {
            orders.add(text to taskId)
            return orderAnswer
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

    private fun commands(ops: FakeOps, history: TasksHistory = FakeHistory(rows)): BotCommands = BotCommands(
        ops = ops,
        hub = LiveHub(),
        daemonReset = { "fake ladder" },
        ownerUserId = { OWNER },
        clock = { 1_000_000L },
        history = history,
        defaultWorkspacePath = "/Users/damian/.zcode/workspace/default",
    )

    @Test
    fun tapAttachesThePipeOfARunningTaskThenBinds() = runBlocking {
        val ops = FakeOps(boundStatus).apply {
            botStatusJson = boundStatus.replace("\"task-02\"", "\"task-77\"")
            answers["activeTaskIds"] = """["task-77"]"""
        }
        val reply = commands(ops).onCallback("t:task-77", 5L, OWNER)!!
        // THE feature: the pipe attach happened, whatever the bind did
        assertEquals(listOf("task-77" to app), ops.subscribed)
        assertTrue("👁" in reply.text)
        assertTrue("«unificador» en vivo" in reply.text)
        assertTrue("/last" in reply.text) // the honest history note
        // the bind confirmed BY READ-BACK (it was already the active task —
        // the 5/11 law: the tap's next message is the 100%-sure confirmation)
        assertTrue("✅ CONFIRMADO: «unificador» es la conversación activa" in reply.text)
    }

    @Test
    fun tapOnAnIdleTaskSpeaksThePatientWording() = runBlocking {
        // no conversation bound, nothing running: the watch lands, the bind
        // re-arms the next text into the freshly watched task
        val ops = FakeOps(draftStatus).apply { answers["activeTaskIds"] = """[]""" }
        val reply = commands(ops).onCallback("t:task-77", 5L, OWNER)!!
        assertEquals(listOf("task-77" to app), ops.subscribed)
        assertTrue("«unificador» suscripto" in reply.text)
        assertTrue("próximo turno" in reply.text)
        assertTrue("✅ CONFIRMADO" in reply.text) // the bind read back from the fork's own state
    }

    @Test
    fun attachHappensEvenWhenTheBindRefuses() = runBlocking {
        // task-02 owns the conversation and is RUNNING: the old tap died
        // right here («/stop first») — the watch must survive the refusal
        val ops = FakeOps(boundStatus).apply {
            // the HOST is the referee now: its taskRunning refusal rides the
            // awaited reply (the bot-side pre-check is gone from the tap)
            cannedBindReplies = listOf("⏳ The previous task's turn is still running")
        }
        val reply = commands(ops).onCallback("t:task-77", 5L, OWNER)!!
        assertEquals(listOf("task-77" to app), ops.subscribed)
        assertTrue("👁" in reply.text)
        assertTrue("⏳" in reply.text) // the host's honest refusal rides along
    }

    @Test
    fun tapOnAnUnknownSidWithoutContextIsHonest() = runBlocking {
        val ops = FakeOps(noContextStatus)
        val reply = commands(ops).onCallback("t:task-99", 5L, OWNER)!!
        assertTrue(ops.subscribed.isEmpty()) // nothing to attach to — no silent fake watch
        assertTrue("🤷" in reply.text)
        assertTrue("/tasks" in reply.text)
    }

    @Test
    fun aContextlessTapBindsByTheHostsVerdictNeverTheRoutingBoilerplate() = runBlocking {
        // the owner's 2/11: context None + a REAL sid answered «send text
        // first» — the bind needs no bot-side context, the HOST decides. The
        // 5/11 ladder: «Task not found» fires /project first; when even the
        // workspace is unknown to the fork, the boundary speaks — honestly.
        val ops = FakeOps(noContextStatus).apply {
            awaitReplyFor = { text ->
                when {
                    text.startsWith("/task ") -> listOf("Task not found.")
                    text.startsWith("/project ") -> listOf("No available workspace found.")
                    else -> emptyList()
                }
            }
        }
        val reply = commands(ops).onCallback("t:task-77", 5L, OWNER)!!
        assertEquals(listOf("task-77" to app), ops.subscribed) // the attach happened
        assertTrue("👁" in reply.text)
        assertTrue("🚧" in reply.text, "the project boundary speaks, not the routing boilerplate")
        assertTrue("no reconoce ese workspace" in reply.text)
        assertTrue(ops.inbound.any { it.startsWith("/project ") }, "the ladder tried the workspace move")
        assertTrue("send text first" !in reply.text, "the router's line NEVER rides a tap")
    }

    @Test
    fun aContextlessTapInTheSameWorkspaceBindsFine() = runBlocking {
        val ops = FakeOps(noContextStatus) // the fake's default: the host confirms silently
        val reply = commands(ops).onCallback("t:task-77", 5L, OWNER)!!
        assertTrue("🎯" in reply.text, "the host confirmed the bind — context not needed")
        assertTrue("send text first" !in reply.text)
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
    fun theExplicitAttachHoldsAgainstThePreviousTasksTraffic() = runBlocking {
        // THE 5/11 FLIP-FLOP (the owner: «funciona horrible, no se desuscribe
        // de lo anterior», «⛓️ turno reemplazado» en bucle): tapping task B
        // while task A still streams let A's next event STEAL the card back —
        // the fold flipped on every frame of the still-subscribed task. THE
        // HOLD: an explicit attach is the owner's choice; foreign frames are
        // dropped until he attaches elsewhere.
        val hub = LiveHub()
        val a = "task-77"
        val b = "task-02"
        // A is live and streaming (the busy previous conversation)
        hub.apply(streamEvent(a, """{"type":"agent_thought_chunk","content":"A piensa"}""", 1), 1_000_000L)
        // THE OWNER TAPS B: the explicit attach — the instant switch
        hub.watchTaskId(b, 1_000_100L)
        assertEquals(b, hub.peek()?.taskId, "the card is B the moment he taps")
        // A's traffic arrives (it is still subscribed): DROPPED — the card holds
        hub.apply(streamEvent(a, """{"type":"agent_thought_chunk","content":"A sigue"}""", 2), 1_000_200L)
        assertEquals(b, hub.peek()?.taskId, "A's traffic cannot steal the card back")
        assertEquals(0, hub.peek()!!.thoughtChars, "A's words never touch B's card")
        // B's own events fold normally
        hub.apply(streamEvent(b, """{"type":"agent_thought_chunk","content":"B habla"}""", 3), 1_000_300L)
        assertEquals(b, hub.peek()?.taskId)
        assertTrue("B habla" in hub.peek()!!.cotTail)
        // the owner taps A again: the hold MOVES with his choice
        hub.watchTaskId(a, 1_000_400L)
        hub.apply(streamEvent(b, """{"type":"agent_thought_chunk","content":"B insiste"}""", 4), 1_000_500L)
        assertEquals(a, hub.peek()?.taskId, "the hold follows the newest attach")
        assertTrue("B insiste" !in hub.peek()!!.cotTail)
    }

    @Test
    fun theHoldSurvivesTheWatchedTurnsSeal() = runBlocking {
        // THE 16:09 LOOP (the fold-borne hold died with every seal): the
        // watched turn sealed, the fold nulled, the traffic re-seeded the
        // card and the flip returned. The hold is HUB state: a seal NEVER
        // lifts it — only the owner's next tap (or ☢️) moves it.
        val hub = LiveHub()
        val a = "task-77"
        val b = "task-02"
        hub.watchTaskId(b, 1_000_000L)
        hub.apply(streamEvent(b, """{"type":"agent_thought_chunk","content":"B trabaja"}""", 1), 1_000_100L)
        // B's turn completes and the publisher's seal clears the fold
        hub.apply(streamEvent(b, """{"type":"task_complete","stopReason":"end_turn"}""", 2), 1_000_200L)
        hub.clear(b)
        assertEquals(null, hub.peek(), "the seal spent the fold")
        // A's traffic arrives after the seal: STILL dropped — the hold lives
        hub.apply(streamEvent(a, """{"type":"agent_thought_chunk","content":"A aprovecha"}""", 3), 1_000_300L)
        assertEquals(null, hub.peek(), "A's traffic cannot re-seed the card past the hold")
        // B's next turn re-lights the card (its frames pass the hold)
        hub.apply(streamEvent(b, """{"type":"task_run_started","startedAt":1000400}""", 4), 1_000_400L)
        assertEquals(b, hub.peek()?.taskId, "the held task's own turn returns the card")
        // only the ☢️ nuclear wipes the hold
        hub.clear()
        hub.apply(streamEvent(a, """{"type":"agent_thought_chunk","content":"A vuelve"}""", 5), 1_000_500L)
        assertEquals(a, hub.peek()?.taskId, "nuclear lifted the hold — traffic rules again")
    }

    @Test
    fun theImplicitWorldStillFollowsTraffic() = runBlocking {
        // no explicit attach yet (hold=false): a foreign task's event still
        // replaces the watched turn — the 4/11 instant-switch law intact
        val hub = LiveHub()
        hub.apply(streamEvent("task-77", """{"type":"agent_thought_chunk","content":"uno"}""", 1), 1_000_000L)
        val flipped = hub.apply(
            streamEvent("task-02", """{"type":"agent_thought_chunk","content":"dos"}""", 2),
            1_000_100L,
        )
        assertEquals("task-02", flipped?.taskId, "no attach, no hold — traffic still rules")
    }

    @Test
    fun detachReturnsTheCardToTheWholeTasksStream() = runBlocking {
        val hub = LiveHub()
        val t = "task-77"
        hub.apply(streamEvent(t, """{"type":"tool_call","toolId":"$laneId","toolName":"Task"}""", 1), 1_000_000L)
        hub.attachLane(laneId)
        // scoped: the parent's own narration is dropped
        hub.apply(
            streamEvent(t, """{"type":"agent_thought_chunk","content":"soy el padre","parentToolUseId":null}""", 2),
            1_000_001L,
        )
        assertEquals(0, hub.peek()!!.thoughtChars)
        // THE DETACH (a t: attach clears the lane scope): the whole stream folds again
        hub.watchTask()
        assertNull(hub.watchedLaneId(), "the scope is the fold's state — the detach clears it")
        hub.apply(
            streamEvent(t, """{"type":"agent_thought_chunk","content":"el padre habla","parentToolUseId":null}""", 3),
            1_000_002L,
        )
        assertEquals("el padre habla".length, hub.peek()!!.thoughtChars, "the whole stream is back")
        assertTrue("el padre habla" in hub.peek()!!.cotTail)
    }

    @Test
    fun aLaneScopeDiesWithItsTaskAndAForeignEventFoldsFresh() = runBlocking {
        val hub = LiveHub()
        hub.apply(streamEvent("task-77", """{"type":"tool_call","toolId":"$laneId","toolName":"Task"}""", 1), 1_000_000L)
        hub.attachLane(laneId)
        // THE U5a LAW: another task's event replaces the watched turn — the
        // scope is the FOLD's, so the fresh seed carries none: the foreign
        // event is never gated by (nor leaks into) the old task's lane
        val fresh = hub.apply(
            streamEvent("task-02", """{"type":"agent_thought_chunk","content":"otra task","parentToolUseId":null}""", 2),
            1_000_001L,
        )!!
        assertEquals("task-02", fresh.taskId)
        assertEquals("otra task".length, fresh.thoughtChars, "a foreign event folds whole")
        assertNull(fresh.watchedLane, "the scope died with its task")
        assertNull(hub.watchedLaneId())
    }

    @Test
    fun laneTapResolvesTheTailAndScopesTheCard() = runBlocking {
        val ops = FakeOps(boundStatus).apply { answers["activeTaskIds"] = """[]""" }
        val hub = LiveHub()
        val bc = BotCommands(
            ops = ops,
            hub = hub,
            daemonReset = { "fake ladder" },
            ownerUserId = { OWNER },
            clock = { 1_000_000L },
            history = FakeHistory(rows),
            defaultWorkspacePath = "/Users/damian/.zcode/workspace/default",
        )
        // the parent turn is live with the subagent lane open (the stream's own truth)
        hub.apply(streamEvent("task-02", """{"type":"tool_call","toolId":"$laneId","toolName":"Task"}""", 1), 1_000_000L)
        val reply = bc.onCallback("sa:$laneTail", 7L, OWNER)!!
        assertEquals(laneId, hub.watchedLaneId())
        assertTrue("Subagente" in reply.text)
        assertTrue("misma carta que una task" in reply.text)
    }

    @Test
    fun laneTapOnAGoneSubagentIsHonest() = runBlocking {
        val ops = FakeOps(boundStatus)
        val reply = commands(ops).onCallback("sa:deadbeefdeadbeef", 7L, OWNER)!!
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
            ownerUserId = { OWNER },
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
        val reply = bc.onCallback("sa:$laneTail", 7L, OWNER)!!
        // the REAL link: subscribed to the CHILD's session, card pinned to it
        assertEquals(listOf("sess_subagent_$agentId" to app), ops.subscribed)
        assertEquals("sess_subagent_$agentId", hub.peekPin())
        assertTrue("su turno COMPLETO" in reply.text)
    }

    @Test
    fun anAttachToAGhostRowIsRefusedInsteadOfPinningSilence() = runBlocking {
        // measured 4/11: a KILLED agent's store row never closes — the attach
        // pinned it, the card was born BLANK («queda en blanco»), and no event
        // would ever fill it. THE GHOST LAW: a row frozen past the ghost mark
        // is a dead agent, not a stream — the honest refusal.
        val ops = FakeOps(boundStatus)
        val history = FakeHistory(rows).apply {
            runningSubs = listOf("sess_subagent_ghost")
            runningTitled = listOf(RunningSubagent(sid = "sess_subagent_ghost", title = "mision", lastMs = 0))
        }
        val hub = LiveHub()
        val bc = BotCommands(
            ops = ops,
            hub = hub,
            daemonReset = { "fake ladder" },
            ownerUserId = { OWNER },
            clock = { 1_000_000L },
            history = history,
            defaultWorkspacePath = "/Users/damian/.zcode/workspace/default",
        )
        val reply = bc.onCallback("sa:ghost", 5L, OWNER)!!
        assertTrue("💀" in reply.text && "murió sin cerrar" in reply.text)
        assertEquals(emptyList(), ops.subscribed, "nothing subscribes to a dead agent")
        assertNull(hub.peekPin(), "the silence is never pinned")
    }

    @Test
    fun aFreshStoredChildAttachSwitchesTheCardToItInstantly() = runBlocking {
        // THE INSTANT SWITCH (4/11): a quiet child never streams first —
        // without the fold's switch the card stayed the PARENT's until the
        // child's next event (the owner's attach felt dead). The tap must
        // point the card at the child NOW.
        val ops = FakeOps(boundStatus)
        val history = FakeHistory(rows).apply {
            runningSubs = listOf("sess_subagent_fresh")
            runningTitled = listOf(RunningSubagent(sid = "sess_subagent_fresh", title = "vivo", lastMs = 999_000))
        }
        val hub = LiveHub()
        val bc = BotCommands(
            ops = ops,
            hub = hub,
            daemonReset = { "fake ladder" },
            ownerUserId = { OWNER },
            clock = { 1_000_000L },
            history = history,
            defaultWorkspacePath = "/Users/damian/.zcode/workspace/default",
        )
        val reply = bc.onCallback("sa:fresh", 5L, OWNER)!!
        assertTrue("👁" in reply.text)
        assertEquals(listOf("sess_subagent_fresh" to app), ops.subscribed)
        assertEquals("sess_subagent_fresh", hub.peekPin())
        assertEquals(
            "sess_subagent_fresh",
            hub.peek()?.taskId,
            "the fold IS the child — no waiting for its first event",
        )
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
            ownerUserId = { OWNER },
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
        val reply = bc.onCallback("sa:$laneTail", 7L, OWNER)!!
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
    fun subagentRowsSpeakTheRealNameAndCarryTheDetailTap() {
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
        // the name tap opens the DETAIL CARD (the full name + the actions —
        // the row label caps, the missions are longísimos)
        assertTrue("sn:${"toolu_dispatchaaaa1111".takeLast(16)}" in kb)
        assertTrue("sn:${"toolu_dispatchbbbb2222".takeLast(16)}" in kb)
        assertTrue("sao:" in kb) // the view switch rides along
    }

    // ---- the ✍️ armed order: the loop closes (read the stream → write the order) —

    @Test
    fun orderTapAttachesAndArmsTheNextFreeText() = runBlocking {
        val ops = FakeOps(boundStatus).apply { answers["activeTaskIds"] = """[]""" }
        val history = FakeHistory(rows).apply {
            runningSubs = listOf("sess_subagent_agent_x")
            // the pulse gate's fresh row — only the store speaks life
            runningTitled = listOf(RunningSubagent("sess_subagent_agent_x", "helper", lastMs = 1_000_000L))
        }
        val hub = LiveHub()
        val bc = BotCommands(
            ops = ops,
            hub = hub,
            daemonReset = { "fake ladder" },
            ownerUserId = { OWNER },
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
        val arm = bc.onCallback("so:$laneTail", 9L, OWNER)!!
        assertTrue("Armado" in arm.text) // the arm rides the attach
        assertEquals(1, ops.subscribed.size) // the tap ALSO attaches
        // the FIRST free text after the arm is the ORDER — a BUSY child
        // earns the ⚡/⏳ question first, then the tap flies
        val question = bc.handle("cambiale el título a la carta", 10L)!!
        assertTrue("Empujar" in question.text, "got: $question")
        val ack = bc.onCallback("odp:1", 10L, OWNER)!!
        assertTrue("Orden entregada" in ack.text, "got: $ack")
        assertEquals(listOf("cambiale el título a la carta" to "sess_subagent_agent_x"), ops.orders)
        // the arm is ONE shot: the next text rides the normal routing again
        val second = bc.handle("esto ya va a la conversación", 11L)
        assertEquals(1, ops.orders.size)
        assertTrue("Orden entregada" !in (second?.text ?: ""))
    }

    @Test
    fun aBusyChildGetsItsTurnCUTBeforeTheOrder() = runBlocking {
        // the owner's «empujar» (2/11 23:00): a message must STOP what the
        // child is doing — the Stop flies BEFORE the order, and the delivery
        // then finds a terminal task the runtime resumes WITH the text.
        val ops = FakeOps(boundStatus).apply {
            answers["activeTaskIds"] = """[]"""
            answers["stop"] = """{"stopped":true}"""
        }
        val history = FakeHistory(rows).apply {
            runningSubs = listOf("sess_subagent_agent_x")
            runningTitled = listOf(RunningSubagent("sess_subagent_agent_x", "helper", lastMs = 1_000_000L))
        }
        val hub = LiveHub()
        val bc = BotCommands(
            ops = ops,
            hub = hub,
            daemonReset = { "fake ladder" },
            ownerUserId = { OWNER },
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
        bc.onCallback("so:$laneTail", 9L, OWNER)!!
        // BUSY → the bot ASKS (the owner's 23:12 law), never cuts on its own
        val question = bc.handle("cortá y leé esto", 10L)!!
        assertTrue("Empujar" in question.text, "got: $question")
        assertTrue("Encolar" in question.text, question.text)
        assertEquals(0, ops.orders.size, "nothing flew before the owner's choice")
        val ack = bc.onCallback("odp:1", 11L, OWNER)!!
        assertTrue("Le corté el turno" in ack.text, "got: $ack")
        assertTrue("AHORA" in ack.text, ack.text)
        assertEquals(listOf("cortá y leé esto" to "sess_subagent_agent_x"), ops.orders)
    }

    @Test
    fun theQueueTapDeliversWithoutCuttingTheChild() = runBlocking {
        // the ⏳ half of the owner's question: same order, NO interrupt
        val ops = FakeOps(boundStatus).apply {
            answers["activeTaskIds"] = """[]"""
            answers["stop"] = """{"stopped":true}"""
        }
        val history = FakeHistory(rows).apply {
            runningSubs = listOf("sess_subagent_agent_x")
            runningTitled = listOf(RunningSubagent("sess_subagent_agent_x", "helper", lastMs = 1_000_000L))
        }
        val hub = LiveHub()
        val bc = BotCommands(
            ops = ops,
            hub = hub,
            daemonReset = { "fake ladder" },
            ownerUserId = { OWNER },
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
        bc.onCallback("so:$laneTail", 9L, OWNER)!!
        bc.handle("dejalo para después", 10L)!!
        val ack = bc.onCallback("odq:1", 11L, OWNER)!!
        assertTrue("Orden entregada" in ack.text, "got: $ack")
        assertTrue("Le corté el turno" !in ack.text)
        assertEquals(listOf("dejalo para después" to "sess_subagent_agent_x"), ops.orders)
    }

    @Test
    fun theStoreRunningSubagentsAreVisibleAndAttachable() = runBlocking {
        // the bus died before the lane streamed: the ACTIVE view still shows
        // the running subagent FROM THE STORE and its tap attaches the session
        val ops = FakeOps(boundStatus).apply { answers["activeTaskIds"] = """[]""" }
        val sess = "sess_subagent_agent_2e587e61"
        val history = FakeHistory(rows).apply {
            runningSubs = listOf(sess)
            // a REAL running row: the store touches lastMs continuously — a
            // frozen one is the GHOST the ghost law refuses (its own test)
            runningTitled = listOf(RunningSubagent(sess, "cazar la causa raíz del bus", lastMs = 999_000))
        }
        val hub = LiveHub() // an EMPTY hub — the stream never saw the lane
        val bc = BotCommands(
            ops = ops,
            hub = hub,
            daemonReset = { "fake ladder" },
            ownerUserId = { OWNER },
            clock = { 1_000_000L },
            history = history,
            defaultWorkspacePath = "/Users/damian/.zcode/workspace/default",
        )
        val reply = bc.handle("/subagents", 3L)!!
        val data = reply.markup.toString()
        assertTrue("cazar la causa raíz" in data, "the store's row must render: $data")
        assertTrue("sn:${sess.takeLast(16)}" in data) // the name tap carries the session tail
        // the name tap: the DETAIL CARD — then the card's attach subscribes
        val card = bc.onCallback("sn:${sess.takeLast(16)}", 4L, OWNER)!!
        assertTrue("cazar la causa raíz del bus" in card.text, "the full title: ${card.text}")
        val attach = bc.onCallback("sa:${sess.takeLast(16)}", 5L, OWNER)!!
        assertEquals(listOf(sess to app), ops.subscribed)
        assertEquals(sess, hub.peekPin())
        assertTrue("attachado" in attach.text)
    }

    @Test
    fun taskRowOrderTapArmsTheTaskSession() = runBlocking {
        val ops = FakeOps(boundStatus).apply { answers["activeTaskIds"] = """[]""" }
        val bc = commands(ops)
        val arm = bc.onCallback("so:task-77", 8L, OWNER)!!
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

    // ---- the ✍️ pulse gate + the detail card (the 2/11 swallow, proven live:
    // two orders into the ⏹️ agent's dead session, zero bounce) -------------------

    @Test
    fun theArmRefusesAGhostTargetAndNeverSwallowsTheText() = runBlocking {
        // the 20:34 exact shape: the pin points at a FINISHED child — no
        // lane, no fresh row. The arm refuses BEFORE the attach; the next
        // free text rides the normal route, never a dead inbox.
        val ops = FakeOps(boundStatus).apply { answers["activeTaskIds"] = """[]""" }
        val hub = LiveHub()
        hub.pin("sess_subagent_agent_dead")
        val bc = BotCommands(
            ops = ops,
            hub = hub,
            daemonReset = { "fake ladder" },
            ownerUserId = { OWNER },
            clock = { 1_000_000L },
            history = FakeHistory(rows),
            defaultWorkspacePath = "/Users/damian/.zcode/workspace/default",
        )
        val arm = bc.onCallback("so:zzzzzzzzzzzzzzzz", 9L, OWNER)!!
        assertTrue("no da señal viva" in arm.text, "got: ${arm.text}")
        assertTrue(ops.subscribed.isEmpty(), "a refused arm attaches no pipe")
        assertEquals(0, ops.orders.size)
        // the next text is the OWNER's own words, routed normally
        bc.handle("esto va a la conversación abierta", 10L)
        assertEquals(0, ops.orders.size)
        assertTrue(ops.inbound.isNotEmpty(), "the text rode the router, not a ghost inbox")
    }

    @Test
    fun theFireGateHoldsWhenTheChildVanishesWhilePinned() = runBlocking {
        val ops = FakeOps(boundStatus).apply { answers["activeTaskIds"] = """[]""" }
        val hub = LiveHub()
        // the store NAMES the child (the session attach's verification set,
        // and the pulse gate's own fresh row — the SAME store fact)
        val history = FakeHistory(rows).apply {
            runningSubs = listOf("sess_subagent_agent_x")
            runningTitled = listOf(RunningSubagent("sess_subagent_agent_x", "helper", lastMs = 940_000L))
        }
        val bc = BotCommands(
            ops = ops,
            hub = hub,
            daemonReset = { "fake ladder" },
            ownerUserId = { OWNER },
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
        val arm = bc.onCallback("so:$laneTail", 9L, OWNER)!!
        assertTrue("Armado" in arm.text, "got: ${arm.text}")
        // the child ENDS while the arm sits armed: the card is pinned to
        // the child's stream (the sa: full form — the hub drops the
        // parent's close frame whole), so the STORE is the signal that
        // remains, and it goes quiet
        history.runningTitled = emptyList()
        history.runningSubs = emptyList()
        val ack = bc.handle("tarde — ya murió", 10L)!!
        assertTrue("no da señal viva" in ack.text, "got: $ack")
        assertEquals(0, ops.orders.size, "the order NEVER flies into a dead inbox")
        // the shot is spent: the next text rides the router
        bc.handle("y esto ya es texto normal", 11L)
        assertEquals(0, ops.orders.size)
    }

    @Test
    fun theFireGateHoldsWhenTheOnlySignalWentStale() = runBlocking {
        // a row fresh at arm time, stale at fire time: no death certificate,
        // no guess — the honest hold (the child may be grinding a long mission)
        val ops = FakeOps(boundStatus).apply { answers["activeTaskIds"] = """[]""" }
        val sess = "sess_subagent_agent_late"
        val history = FakeHistory(rows)
        val hub = LiveHub()
        hub.pin(sess)
        val bc = BotCommands(
            ops = ops,
            hub = hub,
            daemonReset = { "fake ladder" },
            ownerUserId = { OWNER },
            clock = { 1_000_000L },
            history = history,
            defaultWorkspacePath = "/Users/damian/.zcode/workspace/default",
        )
        history.runningTitled = listOf(RunningSubagent(sess, "mission larga", lastMs = 1_000_000L - 60_000))
        val arm = bc.onCallback("so:zzzzzzzzzzzzzzzz", 9L, OWNER)!!
        assertTrue("Armado" in arm.text, "a fresh row arms: ${arm.text}")
        history.runningTitled = listOf(RunningSubagent(sess, "mission larga", lastMs = 1_000_000L - 16 * 60_000))
        val ack = bc.handle("se te congeló?", 10L)!!
        assertTrue("no da señal viva" in ack.text, "got: $ack")
        assertEquals(0, ops.orders.size)
    }

    @Test
    fun theDetailCardShowsTheFullNameAndTheFreshActionRow() = runBlocking {
        // the 2/11 complaint: the spawn descriptions are longísimos and the
        // row label caps — the sn: tap answers the FULL name + the actions
        val ops = FakeOps(boundStatus).apply { answers["activeTaskIds"] = """[]""" }
        val hub = LiveHub()
        val bc = BotCommands(
            ops = ops,
            hub = hub,
            daemonReset = { "fake ladder" },
            ownerUserId = { OWNER },
            clock = { 1_000_000L },
            history = FakeHistory(rows),
            defaultWorkspacePath = "/Users/damian/.zcode/workspace/default",
        )
        val longMission = "investigar la causa raíz del bus caído y proponer el ratchet del outbox"
        hub.apply(
            streamEvent(
                "task-02",
                """{"type":"tool_call","toolId":"$laneId","toolName":"Task","subagentAgentId":"agent_x",""" +
                    """"input":{"description":"$longMission"}}""",
                1,
            ),
            1_000_000L,
        )
        // the ROW still caps its label; the CARD must not
        val rowLabel = Keyboards.subagentsKeyboard(
            lanes = hub.peek()?.tools?.filter { it.parentToolUseId == null } ?: emptyList(),
            inactiveView = false,
        ).toString()
        assertTrue(longMission !in rowLabel, "the row label stays capped")
        val card = bc.onCallback("sn:$laneTail", 9L, OWNER)!!
        assertTrue(longMission in card.text, "the FULL mission in the card: ${card.text}")
        assertTrue("⏳ vivo hace" in card.text)
        val datas = card.markup.toString()
        assertTrue("sa:${laneId.takeLast(16)}" in datas, "attach lives on the card: $datas")
        assertTrue("so:${laneId.takeLast(16)}" in datas)
        assertTrue("sst:${laneId.takeLast(16)}" in datas)
    }

    @Test
    fun aDeadAgentSCardIsAPureMemory() = runBlocking {
        // the card still reads — the full name survives the agent — but it
        // earns NO buttons: ordering into a dead inbox never happens again
        val ops = FakeOps(boundStatus).apply { answers["activeTaskIds"] = """[]""" }
        val hub = LiveHub()
        val bc = BotCommands(
            ops = ops,
            hub = hub,
            daemonReset = { "fake ladder" },
            ownerUserId = { OWNER },
            clock = { 1_000_000L },
            history = FakeHistory(rows),
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
        hub.apply(
            streamEvent(
                "task-02",
                """{"type":"tool_call_update","toolId":"$laneId","toolName":"Task","status":"completed"}""",
                2,
            ),
            1_000_000L,
        )
        val card = bc.onCallback("sn:$laneTail", 9L, OWNER)!!
        assertTrue("💀 terminó" in card.text, "got: ${card.text}")
        assertEquals(null, card.markup, "a dead card earns no buttons")
    }

    @Test
    fun aForkRejectionRidesBackHonestNeverAsEntregada() = runBlocking {
        // the 2/11 lie, typed: the fork rejected the order 7ms after the
        // wire (proto.sessionNotFound) while the bot said «Orden entregada».
        // The awaited order speaks the fork's verdict VERBATIM instead.
        val ops = FakeOps(boundStatus).apply { answers["activeTaskIds"] = """[]""" }
        val history = FakeHistory(rows).apply {
            runningSubs = listOf("sess_subagent_agent_x")
            runningTitled = listOf(RunningSubagent("sess_subagent_agent_x", "helper", lastMs = 1_000_000L))
        }
        val hub = LiveHub()
        val bc = BotCommands(
            ops = ops,
            hub = hub,
            daemonReset = { "fake ladder" },
            ownerUserId = { OWNER },
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
        val arm = bc.onCallback("so:$laneTail", 9L, OWNER)!!
        assertTrue("Armado" in arm.text)
        ops.orderAnswer = InboundAnswer.Failed(
            code = "internal",
            message = "order to sess_subagent_agent_x failed: v4 command sendText rejected (proto.sessionNotFound)",
        )
        // the child is BUSY: the bot asks first (⚡/⏳) — the ⚡ tap flies
        val question = bc.handle("hace un resumen", 10L)!!
        assertTrue("Empujar" in question.text, "got: $question")
        val ack = bc.onCallback("odp:1", 11L, OWNER)!!
        assertTrue("NO entró" in ack.text, "got: $ack")
        assertTrue("sessionNotFound" in ack.text, "the verbatim rides: ${ack.text}")
        assertTrue("NO voló" in ack.text)
        assertEquals(1, ops.orders.size, "the attempt happened")
    }

    @Test
    fun theParentsSealDoesNotStealTheChildsPin() = runBlocking {
        // measured live 2/11 22:48: the owner attached a child, the PARENT's
        // turn sealed 6 s later, and the pin died — /steer flew to the parent
        val hub = LiveHub()
        hub.pin("sess_subagent_agent_x")
        hub.clear("session_parent")
        assertEquals("sess_subagent_agent_x", hub.peekPin(), "the parent's seal is not the child's")
        // the child's OWN seal lifts its pin
        hub.clear("sess_subagent_agent_x")
        assertEquals(null, hub.peekPin())
        // nuclear (no id) still wipes everything
        hub.pin("sess_subagent_agent_y")
        hub.clear()
        assertEquals(null, hub.peekPin())
    }

    @Test
    fun theDetailTextKeepsTheFullDescriptionUncapped() {
        val longMission = "revisar el teclado completo del bot y unificar el vocabulario de filas"
        val lane = laneWith("Agent", laneId, longMission)
        val text = Keyboards.subagentDetailText(lane, nowMs = 1_060_000L)
        assertTrue(longMission in text, text)
        assertTrue("⏳ vivo hace 1m" in text, text)
    }
}
