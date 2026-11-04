package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The bot's configured owner — the taps the suite sends come from him. */
private const val OWNER = 7L

/**
 * The ⏹️ tap's contract (the owner's priority: stop a runaway subagent
 * FROM the phone, live):
 *
 * - the PURE resolution (SubagentStop): a lane claims the tail first
 *   (the sa: attach's own order) and only a launch-ACKed lane yields its
 *   full `sess_subagent_<agentId>`; a store row answers while fresh, a
 *   ghost never; nothing alive → Gone.
 * - the KEYBOARDS: every open lane and every fresh store row carries the
 *   ⏹️ beside its ▶️/✍️; ghosts and closed lanes earn none (a stop over a
 *   dead child is decoration — the honest-indicators law).
 * - the TAP on the dispatcher: `sst:<tail>` flies the SAME awaited stop
 *   a /stop uses, addressed to the child's FULL session id; the ack is
 *   the fork's real word — and a stray tail NEVER rides to the agent as
 *   free text (the callbackText fallback's graver bug).
 */
class SubagentStopTest {

    private val now = 2_000_000_000L

    // ---- fixtures -------------------------------------------------------------

    private class FakeOps : ChannelOps {
        var available = true
        val queries = mutableListOf<ChannelQuery>()
        val inbounds = mutableListOf<String>()
        var stopAnswer: QueryAnswer = QueryAnswer.Ok("""{"stopped":["sess_subagent_agent-9"]}""")

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            queries.add(query)
            if (!available) return QueryAnswer.Unavailable
            return if (query is ChannelQuery.Stop) stopAnswer
            else QueryAnswer.Failed("internal", "unused in subagent-stop tests")
        }

        override suspend fun sendInbound(
            text: String,
            messageId: String,
            receivedAt: Long,
            attachments: List<InboundAttachment>,
        ): InboundReceipt {
            inbounds.add(text)
            return InboundReceipt.FIRED
        }

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
            bindTaskId: String?,
        ) = InboundAnswer.Answered(duplicate = false, replies = emptyList())

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long) = 0

        override suspend fun subscribe(taskId: String, workspacePath: String) {}
    }

    private class FakeHistory(private val running: List<RunningSubagent> = emptyList()) : TasksHistory {
        override suspend fun ctxTasks(limit: Int) = emptyList<TaskRow>()
        override suspend fun modelSelection(sid: String): ModelSel? = null
        override suspend fun freshestTask(workspacePath: String): TaskRow? = null
        override suspend fun taskAlive(sid: String) = false
        override suspend fun subagentsRunning(parentSid: String) = running
    }

    /** A ToolLive lane as the dispatch stream builds it. */
    private fun lane(
        toolId: String,
        agentId: String? = null,
        status: String? = null,
        name: String = "Agent",
    ) = ToolLive(
        toolId = toolId,
        name = name,
        subagentAgentId = agentId,
        status = status,
        openedAtMs = now,
    )

    private fun bc(o: FakeOps, hub: LiveHub, history: TasksHistory = FakeHistory()): BotCommands =
        BotCommands(
            ops = o,
            hub = hub,
            daemonReset = { "" },
            ownerUserId = { OWNER },
            clock = { now },
            history = history,
            defaultWorkspacePath = "",
        )

    /** One stream event folded into the hub (the raw wire shape the fork sends). */
    private fun feed(
        hub: LiveHub,
        seq: Long,
        vararg fields: Pair<String, String>,
        taskId: String = "task-77",
    ) =
        runBlocking {
            hub.apply(
                ServerFrame.StreamEvent(
                    taskId = taskId,
                    seq = seq,
                    at = now,
                    event = buildJsonObject { fields.forEach { (k, v) -> put(k, v) } },
                ),
                now,
            )
        }

    /** The keyboard's button datas, row by row (SubagentsCommandTest's reader, raw). */
    private fun buttonDatas(markup: JsonObject?): List<List<String>> {
        val keyboard = markup ?: return emptyList()
        val rows = keyboard["inline_keyboard"] as? JsonArray ?: return emptyList()
        return rows.map { row ->
            (row as? JsonArray)?.toList().orEmpty().mapNotNull { b ->
                (b as? JsonObject)?.get("callback_data")?.jsonPrimitive?.contentOrNull
            }
        }
    }

    // ---- the pure half ----------------------------------------------------------

    @Test
    fun anAliveNamedLaneResolvesToItsFullSessionId() {
        val target = SubagentStop.resolve(
            lanes = listOf(lane("toolu_abc123def4567890", agentId = "agent-42")),
            dbRunning = emptyList(),
            tail = "toolu_abc123def4567890".takeLast(Keyboards.LANE_TAIL),
            nowMs = now,
        )
        val ready = assertIs<SubagentStopTarget.Ready>(target)
        assertEquals("sess_subagent_agent-42", ready.sid, "the child's FULL session id")
        assertEquals("Agent", ready.name)
    }

    @Test
    fun anAliveLaneWithoutLaunchAckRefusesHonestly() {
        // the fork has not named the child yet — a stop against a GUESSED
        // session is the one unrecoverable mistake, so the answer is the
        // refuso, never a sid
        val target = SubagentStop.resolve(
            lanes = listOf(lane("toolu_unnamed")),
            dbRunning = emptyList(),
            tail = "toolu_unnamed",
            nowMs = now,
        )
        val unnamed = assertIs<SubagentStopTarget.Unnamed>(target)
        assertEquals("Agent", unnamed.name)
    }

    @Test
    fun aClosedLanesTailIsGoneEvenWhenTheStoreStillListsIt() {
        val target = SubagentStop.resolve(
            lanes = listOf(lane("toolu_done", agentId = "agent-1", status = "completed")),
            dbRunning = listOf(RunningSubagent("sess_subagent_agent-1", "done one", lastMs = now)),
            tail = "toolu_done",
            nowMs = now,
        )
        assertEquals(SubagentStopTarget.Gone, target, "the child already ended — stopping is decoration")
    }

    @Test
    fun aFreshStoreRowResolvesByItsSidTail() {
        val sid = "sess_subagent_agent-77"
        val target = SubagentStop.resolve(
            lanes = emptyList(),
            dbRunning = listOf(RunningSubagent(sid, "root-cause hunter", lastMs = now - 60_000)),
            tail = sid.takeLast(Keyboards.LANE_TAIL),
            nowMs = now,
        )
        val ready = assertIs<SubagentStopTarget.Ready>(target)
        assertEquals(sid, ready.sid)
        assertEquals("root-cause hunter", ready.name)
    }

    @Test
    fun aFreshTitlelessStoreRowFallsBackToTheSidTail() {
        val sid = "sess_subagent_agent-77"
        val target = SubagentStop.resolve(
            lanes = emptyList(),
            dbRunning = listOf(RunningSubagent(sid, title = "", lastMs = now)),
            tail = sid.takeLast(Keyboards.LANE_TAIL),
            nowMs = now,
        )
        val ready = assertIs<SubagentStopTarget.Ready>(target)
        assertEquals(sid.takeLast(Keyboards.LANE_TAIL), ready.name)
    }

    @Test
    fun aGhostStoreRowIsGone() {
        // the ⏸️ hace 20m row IS the dead agent it renders as — its stop
        // would be decoration (the honest-indicators law)
        val sid = "sess_subagent_killed"
        val target = SubagentStop.resolve(
            lanes = emptyList(),
            dbRunning = listOf(RunningSubagent(sid, "killed box agent", lastMs = now - 20 * 60_000)),
            tail = sid.takeLast(Keyboards.LANE_TAIL),
            nowMs = now,
        )
        assertEquals(SubagentStopTarget.Gone, target)
    }

    @Test
    fun anUnknownTailIsGone() {
        val target = SubagentStop.resolve(
            lanes = listOf(lane("toolu_other", agentId = "agent-1")),
            dbRunning = listOf(RunningSubagent("sess_subagent_agent-2", "x", lastMs = now)),
            tail = "zzzzzzzzzzzzzzzz",
            nowMs = now,
        )
        assertEquals(SubagentStopTarget.Gone, target)
    }

    // ---- the keyboards ----------------------------------------------------------

    @Test
    fun theSubagentsKeyboardStopsLiveLanesAndFreshRowsOnly() {
        val reply = Keyboards.subagentsKeyboard(
            lanes = listOf(lane("toolu_alive", agentId = "agent-1"), lane("toolu_dead", status = "completed")),
            dbRunning = listOf(
                RunningSubagent("sess_subagent_agent-3", "fresh row", lastMs = now - 60_000),
                RunningSubagent("sess_subagent_killed", "ghost row", lastMs = now - 20 * 60_000),
            ),
            inactiveView = false,
            nowMs = now,
        )
        val rows = buttonDatas(reply)
        // alive lane: attach + order + stop — the closed lane: no ⏹️
        assertEquals(listOf("sn:toolu_alive", "so:toolu_alive", "sst:toolu_alive"), rows[0])
        // a DEAD lane's row: the name card survives as a memory; the ✍️
        // and the ⏹️ die with it (dead-end buttons are decoration)
        assertEquals(listOf("sn:toolu_dead"), rows[1])
        // a FRESH store row is running now: it earns both actions
        val freshTail = "sess_subagent_agent-3".takeLast(Keyboards.LANE_TAIL)
        assertEquals(listOf("sn:$freshTail", "so:$freshTail", "sst:$freshTail"), rows[2])
        // the GHOST row is NOT here at all (owner, 2/11 22:38: the 432m
        // rows were noise — only what runs NOW belongs to the active view)
        assertEquals(listOf("sao:"), rows[3])
        assertEquals(4, rows.size, "2 lanes + 1 fresh row + the switch — the ghost is gone")
        // the datas are tiny — the 64-byte law never even breathes
        assertTrue(Keyboards.callbackDataValid(rows[2].last()))
    }

    @Test
    fun theTasksKeyboardStopsItsLiveSubagentRowsToo() {
        val kb = Keyboards.tasksKeyboard(
            rows = emptyList(),
            running = emptySet(),
            activeSid = null,
            subagents = listOf(lane("toolu_alive", agentId = "agent-1")),
            dbRunning = listOf(RunningSubagent("sess_subagent_agent-3", "fresh row", lastMs = now - 60_000)),
            nowMs = now,
        )!!
        val rows = buttonDatas(kb)
        assertEquals(listOf("sn:toolu_alive", "sst:toolu_alive"), rows[0])
        assertEquals(
            listOf("sn:" + "sess_subagent_agent-3".takeLast(Keyboards.LANE_TAIL),
                "sst:" + "sess_subagent_agent-3".takeLast(Keyboards.LANE_TAIL)),
            rows[1],
        )
    }

    // ---- the tap on the dispatcher ----------------------------------------------

    @Test
    fun theStopTapFliesTheAwaitedStopToTheChildSession() {
        val hub = LiveHub()
        feed(hub, 1, "type" to "tool_call", "toolId" to "dispatch-1", "toolName" to "Agent",
            "subagentAgentId" to "agent-9")
        val o = FakeOps()
        val reply = runBlocking { bc(o, hub).onCallback("sst:dispatch-1", 1L, OWNER)!! }
        assertEquals(
            listOf<ChannelQuery>(ChannelQuery.Stop("sess_subagent_agent-9")),
            o.queries,
            "the stop addresses the child's FULL session id",
        )
        assertTrue("generación detenida" in reply.text, reply.text)
        assertTrue("Agent" in reply.text, reply.text)
    }

    @Test
    fun theStoreRowStopTapFliesToTheSidTheStoreHolds() {
        val hub = LiveHub()
        feed(hub, 1, "type" to "agent_thought_chunk", "content" to "main")
        val sid = "sess_subagent_agent-77"
        val o = FakeOps()
        val tail = sid.takeLast(Keyboards.LANE_TAIL)
        val reply = runBlocking {
            bc(o, hub, FakeHistory(listOf(RunningSubagent(sid, "hunter", lastMs = now))))
                .onCallback("sst:$tail", 1L, OWNER)!!
        }
        assertEquals(listOf<ChannelQuery>(ChannelQuery.Stop(sid)), o.queries)
        assertTrue("generación detenida" in reply.text, reply.text)
        assertTrue("hunter" in reply.text, reply.text)
    }

    @Test
    fun aFailedStopSpeaksTheRealError() {
        val hub = LiveHub()
        feed(hub, 1, "type" to "tool_call", "toolId" to "dispatch-1", "toolName" to "Agent",
            "subagentAgentId" to "agent-9")
        val o = FakeOps().apply { stopAnswer = QueryAnswer.Failed("bad_request", "no such session") }
        val reply = runBlocking { bc(o, hub).onCallback("sst:dispatch-1", 1L, OWNER)!! }
        assertTrue("⚠️ No pude detener" in reply.text, reply.text)
        assertTrue("bad_request" in reply.text, reply.text)
        assertTrue("no such session" in reply.text, reply.text)
    }

    @Test
    fun aDownChannelSpeaksTheDegradedCard() {
        val hub = LiveHub()
        feed(hub, 1, "type" to "tool_call", "toolId" to "dispatch-1", "toolName" to "Agent",
            "subagentAgentId" to "agent-9")
        val o = FakeOps().apply { available = false }
        val reply = runBlocking { bc(o, hub).onCallback("sst:dispatch-1", 1L, OWNER)!! }
        assertEquals(BotCommands.DEGRADED, reply.text)
    }

    @Test
    fun anUnnamedLaneAnswersTheRefusoWithoutTouchingTheChannel() {
        val hub = LiveHub()
        feed(hub, 1, "type" to "tool_call", "toolId" to "dispatch-1", "toolName" to "Agent")
        val o = FakeOps()
        val reply = runBlocking { bc(o, hub).onCallback("sst:dispatch-1", 1L, OWNER)!! }
        assertTrue("todavía no sé su sesión" in reply.text, reply.text)
        assertTrue(o.queries.isEmpty(), "no session, no stop — never a guessed one")
    }

    @Test
    fun aDeadTailNeverRidesAsFreeText() {
        // the graver bug the sst: interception exists for: a tail that
        // resolves to nothing must answer the refuso HERE — falling
        // through to callbackText would send «sst:…» to the agent as the
        // owner's own words
        val hub = LiveHub()
        val o = FakeOps()
        val reply = runBlocking { bc(o, hub).onCallback("sst:zzzzzzzzzzzzzzzz", 1L, OWNER)!! }
        assertTrue("ya no está vivo" in reply.text, reply.text)
        assertTrue(o.queries.none { it is ChannelQuery.Stop }, "no session, no stop query")
        assertTrue(o.inbounds.isEmpty(), "the tail NEVER rides to the agent as the owner's words")
    }

    // ---- the ORDER's pulse (the ✍️ gate — the 2/11 swallow the owner proved live) ---

    private val sid = "sess_subagent_agent-9"

    @Test
    fun anOpenLaneAloneNeverVouchesTheInbox() {
        // the sa: pin freezes the parent's fold — a lane can read open
        // forever; only the store's fresh row speaks life
        val pulse = SubagentStop.orderTarget(
            lanes = listOf(lane("dispatch-1", agentId = "agent-9")),
            dbRunning = emptyList(),
            sid = sid,
            nowMs = now,
        )
        assertEquals(OrderPulse.Unknown, pulse)
    }

    @Test
    fun aClosedLaneIsTheDeathCertificate() {
        val pulse = SubagentStop.orderTarget(
            lanes = listOf(lane("dispatch-1", agentId = "agent-9", status = "completed")),
            dbRunning = emptyList(),
            sid = sid,
            nowMs = now,
        )
        assertEquals(OrderPulse.Ended, pulse)
    }

    @Test
    fun aFreshStoreRowPulsesAliveWithoutAnyLane() {
        val pulse = SubagentStop.orderTarget(
            lanes = emptyList(),
            dbRunning = listOf(RunningSubagent(sid, "hunter", lastMs = now - 60_000)),
            sid = sid,
            nowMs = now,
        )
        assertEquals(OrderPulse.Alive, pulse)
    }

    @Test
    fun aStaleRowIsSilenceNotProof() {
        // the child may legitimately grind past the freshness window —
        // refusing to guess is the honest shape (Unknown, never Ended)
        val pulse = SubagentStop.orderTarget(
            lanes = emptyList(),
            dbRunning = listOf(RunningSubagent(sid, "hunter", lastMs = now - 20 * 60_000)),
            sid = sid,
            nowMs = now,
        )
        assertEquals(OrderPulse.Unknown, pulse)
    }

    @Test
    fun noSignalAtAllIsUnknown() {
        val pulse = SubagentStop.orderTarget(
            lanes = emptyList(),
            dbRunning = emptyList(),
            sid = sid,
            nowMs = now,
        )
        assertEquals(OrderPulse.Unknown, pulse)
    }

    @Test
    fun taskConversationsAlwaysPulseAlive() {
        // a task session resumes; the gate is for CHILDREN only
        val pulse = SubagentStop.orderTarget(
            lanes = emptyList(),
            dbRunning = emptyList(),
            sid = "task-77",
            nowMs = now,
        )
        assertEquals(OrderPulse.Alive, pulse)
    }
}
