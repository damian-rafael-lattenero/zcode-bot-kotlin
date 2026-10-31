package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * /context_window against a fake channel: the render speaks the REAL
 * meter the fork's contextWindow query hands over (used, window, percent,
 * ▰▱ bar) and the honesty ladder is law — no window → no percent and no
 * bar (you cannot scale without it), no used → the honest ⚠️, an old host
 * → the by-name refusal that asks for the fork deploy. Zero invented
 * numbers, ever.
 */
class ContextWindowTest {

    /** The fake ops: canned answers by kind + an injectable failure code. */
    private class FakeOps : ChannelOps {
        val answers = mutableMapOf<String, String>()
        var available = true

        /** When set, EVERY query fails with this code (the old-host refusal shape). */
        var failCode: String? = null

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            if (!available) return QueryAnswer.Unavailable
            answers[queryKind(query)]?.let { return QueryAnswer.Ok(it) }
            failCode?.let { return QueryAnswer.Failed(it, "boom") }
            return QueryAnswer.Failed("internal", "no canned answer")
        }

        override suspend fun sendInbound(text: String, messageId: String, receivedAt: Long, attachments: List<InboundAttachment>): InboundReceipt {
            if (!available) return InboundReceipt.UNAVAILABLE
            return InboundReceipt.FIRED
        }

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
        ): InboundAnswer = InboundAnswer.Answered(duplicate = false, replies = emptyList())

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long): Int = 0

        override suspend fun subscribe(taskId: String, workspacePath: String) {
            // the meter's read rides the query; the fake needs no stream
        }
    }

    private class NoHistory : TasksHistory {
        override suspend fun ctxTasks(limit: Int): List<TaskRow> = emptyList()
        override suspend fun modelSelection(sid: String): ModelSel? = null
        override suspend fun freshestTask(workspacePath: String): TaskRow? = null
        override suspend fun taskAlive(sid: String): Boolean = true
    }

    private val status = """
        {"bot":{"id":"daemon-local","enabled":true},
         "context":{"workspacePath":"/ws","activeTaskId":"task-77","mode":"full_access"}}
    """.trimIndent()

    private fun commands(ops: FakeOps): BotCommands = BotCommands(
        ops = ops,
        hub = LiveHub(),
        daemonReset = { "fake" },
        clock = { 1_000_000L },
        history = NoHistory(),
    )

    private fun ops(): FakeOps = FakeOps().apply {
        answers["botStatus"] = status
    }

    // ---- the happy meter ------------------------------------------------------

    @Test
    fun happyRenderSpeaksModelUsedWindowPercentAndBar() = runBlocking {
        val o = ops()
        o.answers["contextWindow"] =
            """{"model":"GLM-5.3-Flash","usedTokens":45300,"windowTokens":200000}"""
        val text = commands(o).handle("/context_window", 1L)!!.text
        assertEquals(
            "🪟 <b>Ventana de contexto «task-77»</b>\n" +
                "Modelo <b>GLM-5.3-Flash</b>: 45.3K de 200K (23%)\n" +
                "▰▰▱▱▱▱▱▱▱▱",
            text,
        )
    }

    @Test
    fun wholeKFromOneHundredKUp() {
        val text = BotCommands.renderContextWindow(
            """{"model":"m","usedTokens":123456,"windowTokens":1000000}""",
            "t",
        )
        assertTrue("123K de 1000K (12%)" in text)
    }

    @Test
    fun theOwnersCalibratedWindowRendersApproximate() {
        // windowSource "owner_default" = the 1M calibration (the owner's
        // order 30/10: his models are 1M max, nothing else exposes it) —
        // rendered with the (~) marker, never as a hard fact
        val approx = BotCommands.renderContextWindow(
            """{"model":"m","usedTokens":200000,"windowTokens":1000000,"windowSource":"owner_default"}""",
            "t",
        )
        assertTrue("200K de 1000K (~) (20%)" in approx)
        // a REAL window (model registry / runtime) never carries the marker
        val real = BotCommands.renderContextWindow(
            """{"model":"m","usedTokens":200000,"windowTokens":1000000,"windowSource":"model"}""",
            "t",
        )
        assertTrue("(~)" !in real)
    }

    @Test
    fun theWireFrameIsTheForksContextWindowQuery() {
        val line = encodeClient(ClientFrame.Query("q1", ChannelQuery.ContextWindow("task-77", "/ws")))
        assertEquals(
            """{"type":"query","id":"q1","query":{"kind":"contextWindow","taskId":"task-77","workspacePath":"/ws"}}""",
            line,
        )
    }

    // ---- the honesty ladder ---------------------------------------------------

    @Test
    fun missingWindowMeansNoPercentAndNoBar() = runBlocking {
        val o = ops()
        o.answers["contextWindow"] =
            """{"model":"GLM-5.3-Flash","usedTokens":45300,"windowTokens":null}"""
        val text = commands(o).handle("/context_window", 1L)!!.text
        assertTrue("ventana desconocida" in text)
        assertTrue("45.3K" in text)
        assertFalse("%" in text)
        assertFalse("▰" in text)
    }

    @Test
    fun missingUsedIsTheHonestWarnNotInventedNumbers() {
        assertTrue(
            BotCommands.renderContextWindow(
                """{"model":"m","usedTokens":null,"windowTokens":200000}""",
                "t",
            ).startsWith("⚠️"),
        )
        assertTrue(
            BotCommands.renderContextWindow("""{"model":"m"}""", "t").startsWith("⚠️"),
        )
        assertTrue(BotCommands.renderContextWindow("broken {", "t").startsWith("⚠️"))
    }

    @Test
    fun noConversationIsTheMailboxNotTheAntenna() = runBlocking {
        val o = ops()
        o.answers["botStatus"] = """{"bot":{"enabled":true},"context":null}"""
        assertTrue("📭" in commands(o).handle("/context_window", 1L)!!.text)
    }

    @Test
    fun channelDownIsTheAntenna() = runBlocking {
        val o = ops()
        o.available = false
        assertTrue("📡" in commands(o).handle("/context_window", 1L)!!.text)
    }

    @Test
    fun failedQueriesSpeakTheCode() = runBlocking {
        val o = ops() // contextWindow not canned -> Failed("internal", ...)
        assertTrue(
            "⚠️ Context window failed (internal)" in commands(o).handle("/context_window", 1L)!!.text,
        )
    }

    @Test
    fun anOldHostIsToldTheForkDeployIsTheFix() = runBlocking {
        val o = ops()
        o.failCode = BotCommands.UNKNOWN_QUERY_CODE // the fork's by-name refusal
        val text = commands(o).handle("/context_window", 1L)!!.text
        assertTrue(text.startsWith("⚠️"))
        assertTrue("deployar el fork" in text)
        assertTrue("9cfb39a9" in text)
    }

    // ---- the menu contract ----------------------------------------------------

    @Test
    fun menuListsTheThirteenWithContextWindowBesideCompact() {
        assertTrue(BotCommands.MENU.size >= 14) // steer landed — the count is MENU.size-driven now
        val names = BotCommands.MENU.map { it.first }
        assertTrue("context_window" in names)
        assertEquals(names.indexOf("compact") + 1, names.indexOf("context_window"))
        assertTrue("/context_window — context window used" in BotCommands.HELP)
        assertTrue("The ${BotCommands.MENU.size}" in BotCommands.HELP)
    }
}
