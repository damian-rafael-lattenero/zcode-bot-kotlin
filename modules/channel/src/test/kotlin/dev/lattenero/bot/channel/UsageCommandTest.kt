package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * /usage against a fake channel: the card speaks the fork's REAL taskUsage
 * aggregate (the agent usage db — payload shape pinned here against the
 * strict zod schema of zcode-protocol's zcodeTaskTokenUsageResultSchema)
 * and the owner's number law is law — every token count through tokenRaw
 * («166.6M», «850.5K»), request counts grouped and NEVER laddered. Honest
 * degradation: no bound conversation → 📭, channel down → 📡, zero total →
 * the fresh-conversation truth, a broken shape → the ⚠️ — zero invented
 * numbers, ever. Python's /usage was today-across-sessions; the query has
 * no time filter, so the card says «acumulado» — the honest word.
 */
class UsageCommandTest {

    /** The fake ops: canned answers by kind + the queries recorded + injectable failure. */
    private class FakeOps : ChannelOps {
        val answers = mutableMapOf<String, String>()
        val queries = mutableListOf<ChannelQuery>()
        var available = true

        /** When set, EVERY query fails with this code (the broken-host shape). */
        var failCode: String? = null

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            queries.add(query)
            if (!available) return QueryAnswer.Unavailable
            answers[queryKind(query)]?.let { return QueryAnswer.Ok(it) }
            failCode?.let { return QueryAnswer.Failed(it, "boom") }
            return QueryAnswer.Failed("internal", "no canned answer")
        }

        override suspend fun sendInbound(
            text: String,
            messageId: String,
            receivedAt: Long,
            attachments: List<InboundAttachment>,
        ): InboundReceipt {
            if (!available) return InboundReceipt.UNAVAILABLE
            return InboundReceipt.FIRED
        }

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
            bindTaskId: String?,
        ): InboundAnswer = InboundAnswer.Answered(duplicate = false, replies = emptyList())

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long): Int = 0

        override suspend fun subscribe(taskId: String, workspacePath: String) {
            // the read rides the query; the fake needs no stream
        }
    }

    private class NoHistory : TasksHistory {
        override suspend fun ctxTasks(limit: Int): List<TaskRow> = emptyList()
        override suspend fun modelSelection(sid: String): ModelSel? = null
        override suspend fun freshestTask(workspacePath: String): TaskRow? = null
        override suspend fun taskAlive(sid: String): Boolean = true
    }

    /** A history with ONE real row — the title lookup's source («B4 pair»). */
    private class OneRowHistory : TasksHistory {
        override suspend fun ctxTasks(limit: Int): List<TaskRow> = listOf(
            TaskRow(sid = "task-77", title = "B4 pair", ws = "/ws", whenMs = 1L),
        )

        override suspend fun modelSelection(sid: String): ModelSel? = null
        override suspend fun freshestTask(workspacePath: String): TaskRow? = null
        override suspend fun taskAlive(sid: String): Boolean = true
    }

    private val status = """
        {"bot":{"id":"daemon-local","enabled":true},
         "context":{"workspacePath":"/ws","activeTaskId":"task-77","mode":"full_access"}}
    """.trimIndent()

    private fun commands(ops: FakeOps, history: TasksHistory = NoHistory()): BotCommands = BotCommands(
        ops = ops,
        hub = LiveHub(),
        daemonReset = { "fake" },
        clock = { 1_000_000L },
        history = history,
    )

    private fun ops(): FakeOps = FakeOps().apply {
        answers["botStatus"] = status
    }

    /** REAL magnitudes — a long session's aggregate, the schema's exact shape. */
    private val usage = """
        {"sessionId":"task-77","totalTokens":166603400,"inputTokens":30200000,
         "outputTokens":5240000,"reasoningTokens":12000000,"cacheCreationTokens":90000000,
         "cacheReadTokens":29163400,"modelRequestCount":1234,"modelErrorCount":0,
         "inputBaselineBySource":{"cli":30200000}}
    """.trimIndent().replace("\n", "")

    // ---- the happy card -------------------------------------------------------

    @Test
    fun happyCardSpeaksTheRealAggregateThroughTheNumberLaw() = runBlocking {
        val o = ops()
        o.answers["taskUsage"] = usage
        val text = commands(o, OneRowHistory()).handle("/usage", 1L)!!.text
        assertEquals(
            "📈 <b>Uso de «B4 pair»</b> — acumulado de la conversación\n" +
                "• input 30.2M · output 5.24M · reasoning 12M\n" +
                "• cache creación 90M · lectura 29.16M\n" +
                "<b>Total: 1,234 req · 166.6M tokens</b>",
            text,
        )
    }

    @Test
    fun kMagnitudeClimbsTheSameLadder() {
        val text = BotCommands.renderUsage(
            """{"sessionId":"s","totalTokens":850500,"inputTokens":340000000,"outputTokens":0,
                "reasoningTokens":0,"cacheCreationTokens":0,"cacheReadTokens":0,
                "modelRequestCount":42,"modelErrorCount":0}""",
            "t",
        )
        // 340M (the mission's example) in the breakdown, 850.5K in the total
        assertTrue("input 340M" in text)
        assertTrue("850.5K tokens" in text)
        assertTrue("42 req" in text)
    }

    @Test
    fun requestCountsAreGroupedNotLaddered() {
        val text = BotCommands.renderUsage(
            """{"sessionId":"s","totalTokens":300000000,"inputTokens":0,"outputTokens":0,
                "reasoningTokens":0,"cacheCreationTokens":0,"cacheReadTokens":0,
                "modelRequestCount":1234567,"modelErrorCount":0}""",
            "t",
        )
        assertTrue("Total: 1,234,567 req · 300M tokens" in text)
        // the grouping never ladders: requests carry no K/M suffix
        assertFalse("1,235K" in text)
    }

    @Test
    fun errorsAppearOnlyWhenNonZero() {
        val clean = BotCommands.renderUsage(usage, "t")
        assertFalse("errores" in clean)
        val failing = BotCommands.renderUsage(
            usage.replace("\"modelErrorCount\":0", "\"modelErrorCount\":7"),
            "t",
        )
        assertTrue("· 7 errores" in failing)
    }

    // ---- the honesty ladder ---------------------------------------------------

    @Test
    fun missingBreakdownFieldsRenderTheHonestQuestionMark() {
        val text = BotCommands.renderUsage(
            """{"sessionId":"s","totalTokens":166603400,"modelRequestCount":9}""",
            "t",
        )
        assertTrue("input ?" in text)
        assertTrue("Total: 9 req · 166.6M tokens" in text)
        assertFalse("0M" in text) // a missing field is NEVER an invented zero
    }

    @Test
    fun zeroTotalIsTheFreshConversationTruth() {
        val text = BotCommands.renderUsage(
            """{"sessionId":"s","totalTokens":0,"inputTokens":0,"outputTokens":0,
                "reasoningTokens":0,"cacheCreationTokens":0,"cacheReadTokens":0,
                "modelRequestCount":0,"modelErrorCount":0}""",
            "t",
        )
        assertEquals(
            "📈 <b>Uso de «t»</b>\n" + BotCommands.USAGE_EMPTY,
            text,
        )
    }

    @Test
    fun unparseableAndMissingTotalSpeakTheWarn() {
        assertTrue(BotCommands.renderUsage("broken {", "t").startsWith("⚠️"))
        assertTrue(BotCommands.renderUsage("""{"sessionId":"s"}""", "t").startsWith("⚠️"))
        // a negative total is not a number the schema can produce — same warn
        assertTrue(
            BotCommands.renderUsage("""{"sessionId":"s","totalTokens":-5}""", "t").startsWith("⚠️"),
        )
        assertEquals(BotCommands.USAGE_UNPARSEABLE, BotCommands.renderUsage("broken {", "t"))
    }

    // ---- the command's wiring -------------------------------------------------

    @Test
    fun theQueryRidesTheBoundConversation() = runBlocking {
        val o = ops()
        o.answers["taskUsage"] = usage
        commands(o).handle("/usage", 1L)
        val sent = o.queries.filterIsInstance<ChannelQuery.TaskUsage>().single()
        assertEquals("task-77", sent.taskId)
        assertEquals("/ws", sent.workspacePath)
    }

    @Test
    fun noBoundConversationIsTheMailbox() = runBlocking {
        val o = ops()
        o.answers["botStatus"] = """{"bot":{"enabled":true},"context":null}"""
        assertTrue("📭" in commands(o).handle("/usage", 1L)!!.text)
        // a context WITHOUT a task is the same honest mailbox
        val o2 = ops()
        o2.answers["botStatus"] =
            """{"bot":{"enabled":true},"context":{"workspacePath":"/ws","activeTaskId":null,"mode":"full_access"}}"""
        assertTrue("📭" in commands(o2).handle("/usage", 1L)!!.text)
    }

    @Test
    fun channelDownIsTheAntenna() = runBlocking {
        val o = ops()
        o.available = false
        assertTrue("📡" in commands(o).handle("/usage", 1L)!!.text)
    }

    @Test
    fun failedQuerySpeaksTheCode() = runBlocking {
        val o = ops() // taskUsage not canned -> Failed("internal", ...)
        assertTrue(
            "⚠️ Usage failed (internal)" in commands(o).handle("/usage", 1L)!!.text,
        )
    }

    // ---- the wire frame -------------------------------------------------------

    @Test
    fun theWireFrameIsTheForksTaskUsageQuery() {
        val line = encodeClient(ClientFrame.Query("q1", ChannelQuery.TaskUsage("task-9", "/ws")))
        assertEquals(
            """{"type":"query","id":"q1","query":{"kind":"taskUsage","taskId":"task-9","workspacePath":"/ws"}}""",
            line,
        )
    }

    // ---- the menu contract ----------------------------------------------------

    @Test
    fun menuCarriesUsageBesideQuota() {
        val names = BotCommands.MENU.map { it.first }
        assertTrue("usage" in names)
        assertEquals(names.indexOf("quota") + 1, names.indexOf("usage")) // Python's quota→usage adjacency
        assertTrue("/usage — token usage of the active conversation" in BotCommands.HELP)
    }
}
