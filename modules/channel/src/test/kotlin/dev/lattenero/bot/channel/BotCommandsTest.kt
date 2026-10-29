package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The eight commands against a FAKE channel (the queries recorded, the
 * replies pinned) — the dispatcher is pure decision on query answers.
 */
class BotCommandsTest {

    /** The fake ops: canned answers by query kind + the recorder. */
    private class FakeOps : ChannelOps {
        val asked = mutableListOf<ChannelQuery>()
        var botStatusJson: String? = null
        var answers: MutableMap<String, String> = mutableMapOf()
        var available = true
        var inbound: MutableList<Pair<String, String>> = mutableListOf()
        var subscribed: MutableList<Pair<String, String>> = mutableListOf()

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            asked.add(query)
            if (!available) return QueryAnswer.Unavailable
            val key = queryKind(query)
            return answers[key]?.let { QueryAnswer.Ok(it) } ?: QueryAnswer.Failed("internal", "no canned answer")
        }

        override suspend fun sendInbound(text: String, messageId: String, receivedAt: Long): InboundReceipt {
            if (!available) return InboundReceipt.UNAVAILABLE
            inbound.add(text to messageId)
            return InboundReceipt.FIRED
        }

        override suspend fun subscribe(taskId: String, workspacePath: String) {
            subscribed.add(taskId to workspacePath)
        }
    }

    private val botStatus = """
        {"bot":{"id":"daemon-local","enabled":true},
         "context":{"workspacePath":"/Users/damian/Projects/app","activeTaskId":"task-77","mode":"full_access"}}
    """.trimIndent()

    private fun ops(status: String? = botStatus) = FakeOps().apply {
        botStatusJson = status
        answers["botStatus"] = status ?: "null"
        answers["listTasks"] = """[
            {"taskId":"task-77","title":"arreglá el login","updatedAt":${System.currentTimeMillis() - 60_000}},
            {"taskId":"task-02","title":"second","updatedAt":${System.currentTimeMillis() - 3_600_000}}
        ]"""
        answers["taskList"] = """{"items":[{"taskId":"task-77","title":"arreglá el login","updatedAt":${System.currentTimeMillis() - 60_000}}],"total":1,"hasMore":false}"""
        answers["activeTaskIds"] = """["task-77"]"""
        answers["quota"] = """
            {"generatedAt":1,"authenticated":true,"provider":{"id":"glm","name":"GLM"},
             "remaining":{"count":72,"isShow":true,"percentage":41,"nextResetTime":4700000}}
        """.trimIndent()
        answers["stop"] = """{"stopped":["task-77"]}"""
        answers["stopAll"] = """{"stopped":["task-77","task-02"]}"""
    }

    private fun commands(o: FakeOps, resetCalls: Int = 0): Pair<BotCommands, FakeOps> {
        var resets = 0
        val bc = BotCommands(ops = o, hub = LiveHub(), daemonReset = { resets += 1 }, clock = { 1_000_000L })
        return bc to o
    }

    @Test
    fun parseCommandStripsBotNameAndCase() {
        assertEquals("tasks" to "5", BotCommands.parseCommand("/Tasks@glm_bot 5"))
        assertEquals("all_tasks" to "", BotCommands.parseCommand("/all_tasks"))
        assertEquals("stop" to "now", BotCommands.parseCommand("/stop now"))
        assertNull(BotCommands.parseCommand("hello there"))
        assertNull(BotCommands.parseCommand("/"))
    }

    @Test
    fun tasksRendersTheFocusedList() = runBlocking {
        val (bc, o) = commands(ops())
        val reply = bc.handle("/tasks", 1L)!!
        assertTrue("conversations (project)" in reply)
        assertTrue("«arreglá el login»" in reply)
        assertTrue("🎯" in reply) // the active task is marked
        assertTrue(o.asked.filterIsInstance<ChannelQuery.ListTasks>().isNotEmpty())
    }

    @Test
    fun allTasksRendersTheTimeline() = runBlocking {
        val (bc, _) = commands(ops())
        val reply = bc.handle("/all_tasks", 1L)!!
        assertTrue("all conversations" in reply)
        assertTrue("«arreglá el login»" in reply)
    }

    @Test
    fun statusSaysWhatRunsNow() = runBlocking {
        val (bc, _) = commands(ops())
        val reply = bc.handle("/status", 1L)!!
        assertTrue("running now: 1" in reply)
        assertTrue("task-77" in reply)
        assertTrue("context: app" in reply)
    }

    @Test
    fun stopStopsTheActiveTask() = runBlocking {
        val (bc, o) = commands(ops())
        val reply = bc.handle("/stop", 1L)!!
        assertTrue("Stop sent" in reply)
        val stop = o.asked.filterIsInstance<ChannelQuery.Stop>().single()
        assertEquals("task-77", stop.taskId)
    }

    @Test
    fun nuclearStopsAllAndResetsTheDaemon() = runBlocking {
        val o = ops()
        var resets = 0
        val bc = BotCommands(ops = o, hub = LiveHub(), daemonReset = { resets += 1 }, clock = { 1_000_000L })
        val reply = bc.handle("/nuclear", 1L)!!
        assertTrue("stopped 2 run(s)" in reply)
        assertTrue("⌘Q of the app stays YOURS" in reply)
        assertEquals(1, resets)
        assertTrue(o.asked.filterIsInstance<ChannelQuery.StopAll>().isNotEmpty())
    }

    @Test
    fun quotaRendersThePoolAndReset() = runBlocking {
        val (bc, _) = commands(ops())
        val reply = bc.handle("/quota", 1L)!!
        assertTrue("41% remaining" in reply)
        assertTrue("resets in 1h" in reply)
    }

    @Test
    fun helpListsTheEight() = runBlocking {
        val (bc, _) = commands(ops())
        val reply = bc.handle("/help", 1L)!!
        listOf("/tasks", "/all_tasks", "/status", "/stop", "/nuclear", "/quota", "/subagents", "/help")
            .forEach { cmd -> assertTrue(cmd in reply, "$cmd missing from /help") }
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
        val bc = BotCommands(ops = o, hub = hub, daemonReset = {}, clock = { 2_000L })
        val reply = bc.handle("/subagents", 1L)!!
        assertTrue("subagents" in reply)
        assertTrue("Task" in reply)
        assertTrue("10 chars thought" in reply)
    }

    @Test
    fun subagentsWithNoTurnIsHonest() = runBlocking {
        val (bc, _) = commands(ops())
        val reply = bc.handle("/subagents", 1L)!!
        assertTrue("No turn is being watched" in reply)
    }

    @Test
    fun unknownCommandGetsTheBanner() = runBlocking {
        val (bc, _) = commands(ops())
        assertTrue("Unknown command" in bc.handle("/deploy", 1L)!!)
    }

    @Test
    fun freeTextGoesToTheRealConversationAndSubscribes() = runBlocking {
        val (bc, o) = commands(ops())
        val reply = bc.handle("arreglá el login del bot", 4242L)
        assertNull(reply) // no ack: the live view and the final render speak
        assertEquals(listOf("arreglá el login del bot" to "tg-4242"), o.inbound)
        assertEquals(listOf("task-77" to "/Users/damian/Projects/app"), o.subscribed)
    }

    @Test
    fun freeTextWithTheChannelDownApologizes() = runBlocking {
        val o = ops().apply { available = false }
        val (bc, _) = commands(o)
        val reply = bc.handle("hola", 1L)!!
        assertTrue("channel to the app is down" in reply)
    }

    @Test
    fun commandsWithTheChannelDownExplainThemselves() = runBlocking {
        val o = ops().apply { available = false }
        val (bc, _) = commands(o)
        assertTrue("channel is down" in bc.handle("/tasks", 1L)!!)
        assertTrue("channel to the app is down" in bc.handle("/stop", 1L)!!)
    }

    @Test
    fun noContextMeansTheHonestApology() = runBlocking {
        val (bc, _) = commands(ops(status = """{"bot":{"id":"daemon-local","enabled":true},"context":null}"""))
        assertTrue("No active conversation context" in bc.handle("/tasks", 1L)!!)
    }

    @Test
    fun disabledBotIsAClosedGate() = runBlocking {
        val (bc, _) = commands(ops(status = """{"bot":{"id":"daemon-local","enabled":false},"context":{"workspacePath":"/w"}}"""))
        assertTrue("No active conversation context" in bc.handle("/tasks", 1L)!!)
    }

    @Test
    fun taskArrayRendersEmptyAndTheMoreLine() {
        assertEquals("📭 No conversations in the active project.", BotCommands.renderTaskArray("[]", null, true, 0L))
        val many = (1..25).joinToString(",", "[", "]") {
            """{"taskId":"t$it","title":"task $it","updatedAt":$it}"""
        }
        val text = BotCommands.renderTaskArray(many, "t1", false, nowMs = 100_000L)
        assertTrue("··· 5 more" in text)
        assertTrue("25." !in text) // capped at 20
    }

    @Test
    fun quotaRenderIsTolerant() {
        assertEquals("📊 Quota unavailable: not_authenticated", BotCommands.renderQuota("""{"unavailableReason":"not_authenticated"}""", 0L))
        assertEquals(
            "📊 Quota: no pool info (the app is not authenticated?)",
            BotCommands.renderQuota("""{"authenticated":false}""", 0L),
        )
        val plain = BotCommands.renderQuota("""{"remaining":{"count":9,"isShow":true}}""", 0L)
        assertTrue("9 uses remaining" in plain)
    }

    @Test
    fun theHelpMentionsLiveText() {
        assertNotNull(BotCommands.HELP)
        assertTrue("REAL conversation" in BotCommands.HELP)
    }
}
