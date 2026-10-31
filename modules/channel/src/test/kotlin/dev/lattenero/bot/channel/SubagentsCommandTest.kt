package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * /subagents's NEW contract (the owner's order, 30/10: «que muestre el
 * subagente activo — y que no mezcle los inactivos»):
 *
 * - DEFAULT: ONLY the ACTIVE subagent groups of the watched turn — the
 *   live one is the open Agent/Task DISPATCH lane (parentToolUseId ==
 *   null, the gap that hid it); completed lanes never mix in.
 * - ONE button «inactivos» (`sao:`) → TODAY's ENDED subagents, max 10,
 *   newest first, from the cli db's own session rows (no invented ones).
 * - Text args force the view (inactivos/old · activos/now).
 */
class SubagentsCommandTest {

    /** The fake ops: only botStatus matters here (the inactive view's parent fallback). */
    private class FakeOps : ChannelOps {
        var botStatusJson: String? = null

        override suspend fun query(query: ChannelQuery): QueryAnswer = when {
            query == ChannelQuery.BotStatus && botStatusJson != null -> QueryAnswer.Ok(botStatusJson!!)
            query == ChannelQuery.BotStatus -> QueryAnswer.Failed("internal", "no context")
            else -> QueryAnswer.Failed("internal", "unused in subagents tests")
        }

        override suspend fun sendInbound(text: String, messageId: String, receivedAt: Long, attachments: List<InboundAttachment>): InboundReceipt =
            InboundReceipt.FIRED

        override suspend fun sendInboundAwait(text: String, messageId: String, receivedAt: Long) =
            InboundAnswer.Answered(duplicate = false, replies = emptyList())

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long) = 0

        override suspend fun subscribe(taskId: String, workspacePath: String) {}
    }

    /** The fake history: today's ended subagents, with the call's args recorded. */
    private class FakeHistory(
        private val rows: List<SubagentRow> = emptyList(),
    ) : TasksHistory {
        var askedParent: String? = null
        var askedDayStart: Long? = null

        override suspend fun ctxTasks(limit: Int): List<TaskRow> = emptyList()
        override suspend fun modelSelection(sid: String): ModelSel? = null
        override suspend fun freshestTask(workspacePath: String): TaskRow? = null
        override suspend fun taskAlive(sid: String): Boolean = false

        override suspend fun subagentsToday(parentSid: String?, dayStartMs: Long): List<SubagentRow> {
            askedParent = parentSid
            askedDayStart = dayStartMs
            return rows
        }
    }

    private val now = 1_798_000_000_000L // a fixed «now» — every render derives from it

    private fun bc(hub: LiveHub, history: TasksHistory = FakeHistory()): BotCommands = BotCommands(
        ops = FakeOps(),
        hub = hub,
        daemonReset = { "" },
        clock = { now },
        history = history,
        defaultWorkspacePath = "",
    )

    /** One stream event folded into the hub (the raw wire shape the fork sends). */
    private fun feed(hub: LiveHub, seq: Long, vararg fields: Pair<String, String>) = runBlocking {
        hub.apply(
            ServerFrame.StreamEvent(
                taskId = "task-77",
                seq = seq,
                at = now,
                event = buildJsonObject {
                    fields.forEach { (k, v) -> put(k, v) }
                },
            ),
            now,
        )
    }

    /** Local midnight of [now] — the same formula the command must use. */
    private fun expectedDayStart(): Long {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone)
            .toInstant().toEpochMilli()
    }

    /** The view-switch button's callback_data (the keyboard's ONE row). */
    private fun buttonData(reply: BotReply): List<String> {
        val keyboard = reply.markup ?: return emptyList()
        val rows = keyboard["inline_keyboard"] as? JsonArray ?: return emptyList()
        return rows.flatMap { row -> (row as? JsonArray)?.toList().orEmpty() }
            .mapNotNull { b ->
                (b as? JsonObject)?.get("callback_data")?.jsonPrimitive?.contentOrNull
            }
    }

    // ---- the default view: ACTIVE only --------------------------------------

    @Test
    fun defaultShowsTheRunningDispatchLaneAndNeverTheCompletedOnes() {
        val hub = LiveHub()
        // the LIVE subagent: the Agent dispatch lane (parentToolUseId == null)
        feed(hub, 1, "type" to "tool_call", "toolId" to "dispatch-1", "toolName" to "Agent")
        // a COMPLETED inner lane (nested-transport shape) — must NOT mix in
        feed(hub, 2, "type" to "tool_call", "toolId" to "inner-1", "toolName" to "Grep",
            "parentToolUseId" to "tooluse-x")
        feed(hub, 3, "type" to "tool_call_update", "toolId" to "inner-1", "status" to "completed")
        // a COMPLETED top-level Bash — must NOT mix in either
        feed(hub, 4, "type" to "tool_call", "toolId" to "bash-1", "toolName" to "Bash")
        feed(hub, 5, "type" to "tool_call_update", "toolId" to "bash-1", "status" to "completed")

        val reply = runBlocking { bc(hub).handle("/subagents", 1L)!! }
        assertTrue("Agent" in reply.text, "the live dispatch lane must show: ${reply.text}")
        assertTrue("running" in reply.text)
        assertTrue("subagents" in reply.text)
        assertTrue("Grep" !in reply.text, "completed inner lanes must not mix in: ${reply.text}")
        assertTrue("Bash" !in reply.text, "completed top-level lanes must not mix in: ${reply.text}")
        // the live dispatch lane's row: the 🔵 attach + the ✍️ order arm,
        // then the view switch (30/10: the owner taps a subagent to READ its
        // stream and to WRITE it an order)
        assertEquals(
            listOf("sa:dispatch-1", "so:dispatch-1", "sao:"),
            buttonData(reply),
            "attach + order arm + the switch",
        )
    }

    @Test
    fun aDispatchLaneAppearsTheMomentItOpens() {
        val hub = LiveHub()
        // tool_input_start → tool_call with EMPTY input: the lane exists already
        feed(hub, 1, "type" to "tool_call", "toolId" to "dispatch-1", "toolName" to "Task")
        val reply = runBlocking { bc(hub).handle("/subagents", 1L)!! }
        assertTrue("Task" in reply.text, "${reply.text}")
        assertTrue("running" in reply.text)
    }

    @Test
    fun nestedTransportLanesGroupUnderTheirParent() {
        val hub = LiveHub()
        feed(hub, 1, "type" to "tool_call", "toolId" to "inner-1", "toolName" to "Read",
            "parentToolUseId" to "tooluse-9")
        feed(hub, 2, "type" to "agent_thought_chunk", "content" to "sub piensa",
            "parentToolUseId" to "tooluse-9")
        val reply = runBlocking { bc(hub).handle("/subagents", 1L)!! }
        assertTrue("tooluse-9".takeLast(8) in reply.text, "the parent tail: ${reply.text}")
        assertTrue("Read" in reply.text)
        assertTrue("10 chars thought" in reply.text)
        assertEquals(listOf("sao:"), buttonData(reply))
    }

    @Test
    fun noActiveSubagentsIsHonestAndStillOffersInactivos() {
        val hub = LiveHub()
        feed(hub, 1, "type" to "agent_thought_chunk", "content" to "main agent alone")
        val reply = runBlocking { bc(hub).handle("/subagents", 1L)!! }
        assertTrue("No ACTIVE subagents" in reply.text, "${reply.text}")
        assertEquals(listOf("sao:"), buttonData(reply))
    }

    @Test
    fun noTurnIsHonestAndStillOffersInactivos() {
        val reply = runBlocking { bc(LiveHub()).handle("/subagents", 1L)!! }
        assertTrue("No turn is being watched" in reply.text)
        assertEquals(listOf("sao:"), buttonData(reply))
    }

    // ---- the «inactivos» view ------------------------------------------------

    @Test
    fun inactivosRendersTodaysEndedRowsNewestFirst() {
        val history = FakeHistory(
            listOf(
                SubagentRow("older one", "completed", startedMs = now - 100_000, endedMs = now - 50_000),
                SubagentRow("newest one", "interrupted", startedMs = now - 40_000, endedMs = now - 10_000),
            ),
        )
        val reply = runBlocking { bc(LiveHub(), history).handle("/subagents inactivos", 1L)!! }
        val olderAt = reply.text.indexOf("older one")
        val newestAt = reply.text.indexOf("newest one")
        assertTrue(newestAt in 0 until olderAt, "newest first: ${reply.text}")
        assertTrue("✅" in reply.text && "⛔" in reply.text, "the fork's own outcome words stamped")
        assertEquals(listOf("saa:"), buttonData(reply), "the way back")
        assertEquals(null, history.askedParent, "no watched turn → the whole day's rows")
        assertEquals(expectedDayStart(), history.askedDayStart, "TODAY is the daemon's local midnight")
    }

    @Test
    fun inactivosScopesToTheWatchedTaskWhenOneIsLive() {
        val hub = LiveHub()
        feed(hub, 1, "type" to "agent_thought_chunk", "content" to "main")
        val history = FakeHistory()
        runBlocking { bc(hub, history).handle("/subagents old", 1L) }
        assertEquals("task-77", history.askedParent, "the watched turn scopes the rows")
    }

    @Test
    fun inactivosCapsAtTenRows() {
        val rows = (1..12).map { i ->
            SubagentRow("sub-$i", "completed", startedMs = now - i * 10_000, endedMs = now - i * 5_000)
        }
        val reply = runBlocking { bc(LiveHub(), FakeHistory(rows)).handle("/subagents inactivos", 1L)!! }
        assertTrue("(10)" in reply.text, "the MAX 10 clamp: ${reply.text}")
        assertTrue("sub-1 " in reply.text, "the newest survives: ${reply.text}")
        assertTrue("sub-11" !in reply.text, "the 11th never renders: ${reply.text}")
        assertTrue("sub-12" !in reply.text, "the oldest is cut first: ${reply.text}")
    }

    @Test
    fun inactivosEmptyIsHonest() {
        val reply = runBlocking { bc(LiveHub(), FakeHistory()).handle("/subagents inactivos", 1L)!! }
        assertTrue("No subagents ended today" in reply.text, "${reply.text}")
        assertEquals(listOf("saa:"), buttonData(reply))
    }

    // ---- the taps and the arg parse ------------------------------------------

    @Test
    fun saoTapSwitchesToInactivosAndSaaBack() {
        val history = FakeHistory(listOf(SubagentRow("one", "completed", now - 9_000, now - 1_000)))
        val commands = bc(LiveHub(), history)
        val inactive = runBlocking { commands.onCallback("sao:", 1L)!! }
        assertTrue("ended today" in inactive.text, "${inactive.text}")
        val active = runBlocking { commands.onCallback("saa:", 2L)!! }
        assertTrue("No turn is being watched" in active.text, "${active.text}")
    }

    @Test
    fun unknownArgSpeaksTheUsageAndStaysActive() {
        val reply = runBlocking { bc(LiveHub()).handle("/subagents wat", 1L)!! }
        assertTrue("Args:" in reply.text, "${reply.text}")
        assertEquals(listOf("sao:"), buttonData(reply))
    }

    @Test
    fun menuSpeaksTheNewDescriptionOnce() {
        // the TOTAL count is BotCommandsTest's own pin (the menu is a shared
        // surface — this test only owns the subagents entry)
        assertEquals(1, BotCommands.MENU.count { it.first == "subagents" })
        val desc = BotCommands.MENU.first { it.first == "subagents" }.second
        assertTrue("inactivos" in desc, "the /help entry documents the switch: $desc")
    }
}
