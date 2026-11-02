package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * /ctx against a fake channel: ONE line per usage-backed conversation of
 * the tasks-index, each riding the fork's REAL contextWindow meter (never
 * Python's estimated windows, never a stale pre-compact number). The emoji
 * law is Python's (🔴 ≥80 · 🟡 ≥50 · 🟢), the numbers obey the owner's law
 * (tokenRaw: 820000000 → «820M», 340000000 → «340M»), the percents are
 * pinned exact. Honest degradation: a conversation that reports nothing
 * reads ❔ sin datos, a window-less one shows NO invented percent, an old
 * host refuses the query BY NAME and the whole card says the fork deploy
 * is the fix.
 */
class CtxCommandTest {

    /** The fake ops: canned by kind, per-task canned for the per-row reads, queries recorded. */
    private class FakeOps : ChannelOps {
        val answers = mutableMapOf<String, String>()

        /** Per-task contextWindow answers — the keyed can (one row each, real payloads). */
        val byTask = mutableMapOf<String, String>()
        val queries = mutableListOf<ChannelQuery>()
        var available = true

        /** When set, every UNCANNED query fails with this code (the old-host shape). */
        var failCode: String? = null

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            queries.add(query)
            if (!available) return QueryAnswer.Unavailable
            answers[queryKind(query)]?.let { return QueryAnswer.Ok(it) }
            if (query is ChannelQuery.ContextWindow) byTask[query.taskId]?.let { return QueryAnswer.Ok(it) }
            failCode?.let { return QueryAnswer.Failed(it, "boom") }
            return QueryAnswer.Failed("internal", "no canned answer")
        }

        override suspend fun sendInbound(
            text: String,
            messageId: String,
            receivedAt: Long,
            attachments: List<InboundAttachment>,
        ): InboundReceipt = InboundReceipt.FIRED

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
            bindTaskId: String?,
        ): InboundAnswer = InboundAnswer.Answered(duplicate = false, replies = emptyList())

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long): Int = 0

        override suspend fun subscribe(taskId: String, workspacePath: String) {
            // reads ride queries; the fake needs no stream
        }
    }

    private class RowHistory(private val rows: List<TaskRow>) : TasksHistory {
        override suspend fun ctxTasks(limit: Int): List<TaskRow> = rows.take(limit)
        override suspend fun modelSelection(sid: String): ModelSel? = null
        override suspend fun freshestTask(workspacePath: String): TaskRow? = null
        override suspend fun taskAlive(sid: String): Boolean = true
    }

    private val status = """
        {"bot":{"id":"daemon-local","enabled":true},
         "context":{"workspacePath":"/ws","activeTaskId":"task-a1","mode":"full_access"}}
    """.trimIndent()

    private val noContext = """{"bot":{"id":"daemon-local","enabled":true},"context":null}"""

    private fun commands(ops: FakeOps, rows: List<TaskRow>): BotCommands = BotCommands(
        ops = ops,
        hub = LiveHub(),
        daemonReset = { "fake" },
        clock = { 1_000_000L },
        history = RowHistory(rows),
    )

    private fun ops(): FakeOps = FakeOps().apply {
        answers["botStatus"] = status
    }

    /** REAL magnitudes: the owner's models are 1M-window class — a long session sits in the hundreds of millions. */
    private val rows = listOf(
        TaskRow(sid = "task-a1", title = "unificador", ws = "/ws", whenMs = 4L),
        TaskRow(sid = "task-b2", title = "retry fork", ws = "/ws", whenMs = 3L),
        TaskRow(sid = "task-c3", title = "experimento", ws = "/other", whenMs = 2L),
        TaskRow(sid = "task-d4", title = "sin reporte", ws = "/ws", whenMs = 1L),
    )

    private fun FakeOps.canRealMeters() {
        byTask["task-a1"] = """{"model":"GLM","usedTokens":820000000,"windowTokens":1000000000}"""
        byTask["task-b2"] = """{"model":"GLM","usedTokens":530000000,"windowTokens":1000000000}"""
        byTask["task-c3"] = """{"model":"GLM","usedTokens":340000000,"windowTokens":1000000000}"""
        byTask["task-d4"] = """{"model":"GLM","usedTokens":null,"windowTokens":null}"""
    }

    // ---- the happy list -------------------------------------------------------

    @Test
    fun theListSpeaksOneRealMeterLinePerConversation() = runBlocking {
        val o = ops()
        o.canRealMeters()
        val text = commands(o, rows).handle("/ctx", 1L)!!.text
        assertEquals(
            "🧠 <b>Contexto por conversación</b> (el medidor real del fork)\n" +
                "1. 🔴 820M (82%) — unificador\n" +
                "2. 🟡 530M (53%) — retry fork\n" +
                "3. 🟢 340M (34%) · 📁other — experimento\n" +
                "4. ❔ sin datos — sin reporte\n" +
                BotCommands.CTX_FOOTER,
            text,
        )
        // the queries rode each row's OWN sid + workspace
        val sent = o.queries.filterIsInstance<ChannelQuery.ContextWindow>()
        assertEquals(listOf("task-a1", "task-b2", "task-c3", "task-d4"), sent.map { it.taskId })
        assertEquals("/other", sent[2].workspacePath)
    }

    @Test
    fun theEmojiLawIsPythons() = runBlocking {
        val o = ops()
        o.byTask["task-a1"] = """{"model":"m","usedTokens":800000000,"windowTokens":1000000000}"""
        o.byTask["task-b2"] = """{"model":"m","usedTokens":500000000,"windowTokens":1000000000}"""
        o.byTask["task-c3"] = """{"model":"m","usedTokens":450,"windowTokens":1000000000}"""
        val text = commands(o, rows.take(3)).handle("/ctx", 1L)!!.text
        assertTrue("\n1. 🔴 800M (80%)" in text) // exactly AT 80 is hot
        assertTrue("\n2. 🟡 500M (50%)" in text) // exactly AT 50 is warm
        assertTrue("\n3. 🟢 450 (0%)" in text) // sub-K raw counts stay plain
    }

    @Test
    fun theOwnersCalibrationRendersApproximate() = runBlocking {
        val o = ops()
        o.byTask["task-a1"] =
            """{"model":"m","usedTokens":200000,"windowTokens":1000000,"windowSource":"owner_default"}"""
        o.byTask["task-b2"] = """{"model":"m","usedTokens":200000,"windowTokens":1000000,"windowSource":"model"}"""
        val text = commands(o, rows.take(2)).handle("/ctx", 1L)!!.text
        assertTrue("1. 🟢 200K (20%) (~)" in text) // the (~) marks the owner_default window
        assertTrue("2. 🟢 200K (20%) —" in text) // a REAL window never carries it
    }

    // ---- the honesty ladder ---------------------------------------------------

    @Test
    fun windowlessConversationsShowNoInventedPercent() = runBlocking {
        val o = ops()
        o.byTask["task-a1"] = """{"model":"m","usedTokens":45300,"windowTokens":null}"""
        val text = commands(o, rows.take(1)).handle("/ctx", 1L)!!.text
        assertTrue("1. 45.3K · ventana ?" in text)
        assertTrue("%" !in text)
    }

    @Test
    fun failedReadsAreNoDataLinesNotInventedNumbers() = runBlocking {
        val o = ops() // nothing canned per task -> Failed("internal") per row
        val text = commands(o, rows.take(2)).handle("/ctx", 1L)!!.text
        assertTrue("1. ${BotCommands.CTX_NO_DATA}" in text)
        assertTrue("2. ${BotCommands.CTX_NO_DATA}" in text)
    }

    @Test
    fun anOldHostDegradesTheWholeCardIntoTheForkDeployNote() = runBlocking {
        val o = ops()
        o.failCode = BotCommands.UNKNOWN_QUERY_CODE // the fork's by-name refusal
        val text = commands(o, rows.take(1)).handle("/ctx", 1L)!!.text
        assertTrue(text.startsWith("⚠️"))
        assertTrue("deployar el fork" in text)
        assertTrue("9cfb39a9" in text)
        assertTrue("❔" !in text) // one loud truth, not 8 quiet ones
    }

    @Test
    fun noRowsIsTheHonestEmpty() = runBlocking {
        val o = ops()
        assertEquals(
            BotCommands.CTX_NO_ROWS,
            commands(o, emptyList()).handle("/ctx", 1L)!!.text,
        )
    }

    @Test
    fun channelDownIsTheAntenna() = runBlocking {
        val o = ops()
        o.available = false
        assertTrue("📡" in commands(o, rows).handle("/ctx", 1L)!!.text)
    }

    @Test
    fun withoutABoundContextTheListStillRendersWithTags() = runBlocking {
        val o = ops()
        o.answers["botStatus"] = noContext
        o.canRealMeters()
        val text = commands(o, rows.take(2)).handle("/ctx", 1L)!!.text
        // no active workspace: every non-empty ws reads as «another» (Python's own parity)
        assertTrue("1. 🔴 820M (82%) · 📁ws — unificador" in text)
    }

    @Test
    fun theCapIsPythonsEight() = runBlocking {
        val o = ops()
        val many = (1..12).map { TaskRow(sid = "task-n$it", title = "t$it", ws = "/ws", whenMs = it.toLong()) }
        commands(o, many).handle("/ctx", 1L)
        assertEquals(8, o.queries.filterIsInstance<ChannelQuery.ContextWindow>().size)
    }

    // ---- the menu contract ----------------------------------------------------

    @Test
    fun menuCarriesCtxBesideUsage() {
        val names = BotCommands.MENU.map { it.first }
        assertTrue("ctx" in names)
        assertEquals(names.indexOf("usage") + 1, names.indexOf("ctx")) // Python's usage→ctx adjacency
        assertTrue("/ctx — context % per conversation" in BotCommands.HELP)
    }
}
