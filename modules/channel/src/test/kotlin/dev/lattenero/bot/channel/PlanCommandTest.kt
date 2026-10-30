package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * /plan — the NINTH command's reply contract (the pure half is
 * PlanMenuTest's): the menu renders from the fork's modelSelection answer,
 * the degraded fork message speaks when the frame is unknown, and each
 * tap (pl:/pm:/pe:/pd:) fires ONE single-field set and re-renders from
 * the SET'S OWN answer — the server's word is the truth.
 */
class PlanCommandTest {

    /** The fake ops: canned answers by query kind + the query recorder. */
    private class FakeOps : ChannelOps {
        var available = true
        val queries = mutableListOf<ChannelQuery>()
        val answers = mutableMapOf<String, String>()

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            queries.add(query)
            if (!available) return QueryAnswer.Unavailable
            return answers[queryKind(query)]?.let { QueryAnswer.Ok(it) }
                ?: QueryAnswer.Failed("unknown_query", "no canned answer")
        }

        override suspend fun sendInbound(text: String, messageId: String, receivedAt: Long) = InboundReceipt.FIRED

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
        ) = InboundAnswer.Answered(duplicate = false, replies = emptyList())

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long) = 0

        override suspend fun subscribe(taskId: String, workspacePath: String) {}
    }

    private class FakeHistory(
        private val rows: List<TaskRow>,
    ) : TasksHistory {
        override suspend fun ctxTasks(limit: Int): List<TaskRow> = rows.take(limit)
        override suspend fun modelSelection(sid: String): ModelSel? = null
        override suspend fun freshestTask(workspacePath: String): TaskRow? = rows.firstOrNull()
        override suspend fun taskAlive(sid: String): Boolean = true
    }

    private val app = "/Users/damian/Projects/app"
    private val rows = listOf(TaskRow(sid = "task-77", title = "arreglá el login", ws = app))

    /** The fork's modelSelection answer (3d07cace's shape). */
    private fun msJson(plan: String = "account:zai-individual", model: String = "glm-5.3-flash") = """
    {
      "scope": "conversation",
      "selection": {"providerId": "$plan", "modelId": "$model", "reasoningLevel": "high"},
      "default": {"providerId": "account:zai-individual", "modelId": "glm-5.3-flash"},
      "plans": [
        {"providerId": "account:zai-individual", "label": "Z.ai (individual)",
         "models": [{"id": "glm-5.3-flash", "label": "GLM 5.3 Flash"}, {"id": "glm-5.3", "label": "GLM 5.3"}]},
        {"providerId": "account:zai-start-plan", "label": "Start Plan (free)",
         "models": [{"id": "glm-start", "label": "GLM Start"}]}
      ]
    }
    """.trimIndent()

    private val boundStatus = """
        {"bot":{"id":"daemon-local","enabled":true},
         "context":{"workspacePath":"$app","activeTaskId":"task-77","mode":"task"}}
    """.trimIndent()

    private fun ops(ms: String? = msJson()) = FakeOps().apply {
        answers["botStatus"] = boundStatus
        ms?.let { answers["modelSelection"] = it }
        answers["setModelSelection"] = msJson(plan = "account:zai-start-plan", model = "glm-start")
    }

    private fun commands(o: FakeOps): BotCommands = BotCommands(
        ops = o,
        hub = LiveHub(),
        daemonReset = { "fake ladder" },
        clock = { 1_000_000L },
        history = FakeHistory(rows),
        defaultWorkspacePath = "",
    )

    private suspend fun BotCommands.reply(text: String): String? = handle(text, 1L)?.text

    @Test
    fun planRendersTheMenuWithTheKeyboard() = runBlocking {
        val o = ops()
        val reply = commands(o).handle("/plan", 1L)!!
        assertTrue("«arreglá el login»" in reply.text, "the title comes from the history")
        assertTrue("GLM 5.3 Flash" in reply.text)
        assertTrue("Effort: <b>high</b>" in reply.text)
        val markup = reply.markup.toString()
        assertTrue("pl:account:zai-individual" in markup)
        assertTrue("pm:glm-5.3-flash" in markup)
        assertTrue("pe:high" in markup)
        assertTrue("pd:" in markup, "the default-copy button is there when a plan is set")
        // the read rode the context's workspace
        val read = o.queries.filterIsInstance<ChannelQuery.ModelSelection>().single()
        assertEquals(app, read.workspacePath)
    }

    @Test
    fun planWithoutAForkDeploySpeaksTheHonestDegrade() = runBlocking {
        val o = ops(ms = null) // no canned answer -> unknown_query, the old server's word
        val reply = commands(o).reply("/plan")!!
        assertTrue("fork está deployado" in reply, reply)
        assertTrue("3d07cace" in reply)
    }

    @Test
    fun planTapFiresASingleFieldSetAndRerendersFromItsOwnAnswer() = runBlocking {
        val o = ops()
        val reply = commands(o).onCallback("pl:account:zai-start-plan", 2L)!!
        assertTrue(reply.text.startsWith("✅ aplicado"), reply.text)
        // the SET's own answer is the truth: the new plan now carries the 🎯
        assertTrue("🎯 Start Plan (free)" in reply.markup.toString())
        assertTrue("🟢 Z.ai (individual)" in reply.markup.toString())
        val set = o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().single()
        assertEquals<String?>("conversation", set.scope)
        assertEquals("account:zai-start-plan", set.plan)
        assertNull(set.model, "ONE field per tap — the server merges the partial update")
        assertNull(set.effort)
        assertEquals(app, set.workspacePath)
    }

    @Test
    fun modelAndEffortTapsSetTheirOneField() = runBlocking {
        val o = ops()
        val bc = commands(o)
        bc.onCallback("pm:glm-start", 3L)
        bc.onCallback("pe:low", 4L)
        val sets = o.queries.filterIsInstance<ChannelQuery.SetModelSelection>()
        assertEquals(2, sets.size)
        assertEquals<String?>("glm-start", sets[0].model)
        assertNull(sets[0].plan, "ONE field per tap — the server merges the partial update")
        assertEquals<String?>("low", sets[1].effort)
        assertNull(sets[1].model, "ONE field per tap — the server merges the partial update")
    }

    @Test
    fun theDefaultTapCopiesTheWholeConversationSelection() = runBlocking {
        val o = ops()
        val reply = commands(o).onCallback("pd:", 5L)!!
        assertTrue("✅ aplicado" in reply.text)
        val set = o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().single()
        assertEquals("default", set.scope, "pd: writes the APP's default, not the conversation's")
        assertEquals("account:zai-individual", set.plan)
        assertEquals("glm-5.3-flash", set.model)
        assertEquals("high", set.effort)
    }

    @Test
    fun aRefusedSetSaysSoWithItsCode() = runBlocking {
        val o = FakeOps().apply {
            available = true
            answers["botStatus"] = boundStatus
            answers["modelSelection"] = msJson()
            // setModelSelection deliberately MISSING -> unknown_query
        }
        val reply = commands(o).onCallback("pe:low", 6L)!!.text
        assertTrue("⚠️ No pude aplicar (unknown_query)" in reply, reply)
    }

    @Test
    fun aDownChannelDegradesTheTaps() = runBlocking {
        val o = ops().apply { available = false }
        val reply = commands(o).onCallback("pe:low", 7L)!!.text
        assertTrue("channel to the app is down" in reply, reply)
    }
}
