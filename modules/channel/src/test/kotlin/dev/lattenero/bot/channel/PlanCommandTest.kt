package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bot's configured owner — the taps the suite sends come from him. */
private const val OWNER = 7L

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

        /** When set, every query fails with THIS code (the timeout drill's hook). */
        var failCode: String? = null
        val queries = mutableListOf<ChannelQuery>()
        val answers = mutableMapOf<String, String>()

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            queries.add(query)
            if (!available) return QueryAnswer.Unavailable
            failCode?.let { return QueryAnswer.Failed(it, "simulated") }
            return answers[queryKind(query)]?.let { QueryAnswer.Ok(it) }
                ?: QueryAnswer.Failed("unknown_query", "no canned answer")
        }

        override suspend fun sendInbound(
            text: String,
            messageId: String,
            receivedAt: Long,
            attachments: List<InboundAttachment>,
        ) = InboundReceipt.FIRED

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
            bindTaskId: String?,
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

    /**
     * The owner's REAL cross-provider catalog (3/11): a z.ai plan next to
     * OpenCode Go's own models. Lengths and ids are the live ones (the
     * opencode ids come from the fork's own daemonChannel test).
     */
    private fun crossProviderJson(plan: String, model: String) = """
    {
      "scope": "conversation",
      "selection": {"providerId": "$plan", "modelId": "$model", "reasoningLevel": "max"},
      "default": null,
      "plans": [
        {"providerId": "account:zai-start-plan", "label": "Start Plan",
         "models": [{"id": "GLM-5.3-Flash", "label": "GLM-5.3-Flash", "reasoningLevels": ["low", "high", "max"]}]},
        {"providerId": "opencode-go-chat", "label": "OpenCode Go",
         "models": [{"id": "deepseek-v4.1-flash", "label": "deepseek-v4.1-flash", "reasoningLevels": ["low", "high"]},
                    {"id": "kimi-k2", "label": "kimi-k2"}]}
      ]
    }
    """.trimIndent()

    private fun crossOps(ms: String) = FakeOps().apply {
        answers["botStatus"] = boundStatus
        answers["modelSelection"] = ms
        answers["setModelSelection"] = ms
    }

    private fun ops(ms: String? = msJson()) = FakeOps().apply {
        answers["botStatus"] = boundStatus
        ms?.let { answers["modelSelection"] = it }
        answers["setModelSelection"] = msJson(plan = "account:zai-start-plan", model = "glm-start")
    }

    private fun commands(o: FakeOps): BotCommands = BotCommands(
        ops = o,
        hub = LiveHub(),
        daemonReset = { "fake ladder" },
        ownerUserId = { OWNER },
        clock = { 1_000_000L },
        history = FakeHistory(rows),
        defaultWorkspacePath = "",
    )

    private suspend fun BotCommands.reply(text: String): String? = handle(text, 1L)?.text

    /** The «A aplicar:» segment — exactly what the open draft would write. */
    private fun draftLine(text: String): String = text.substringAfter("A aplicar:", "")

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
    fun planWithoutAnActiveConversationNamesTheMissingContext() = runBlocking {
        val o = FakeOps().apply {
            answers["botStatus"] =
                """{"bot":{"id":"daemon-local","enabled":true},"context":null}"""
        }
        val reply = commands(o).reply("/plan")!!
        assertTrue("No hay conversación activa" in reply, reply)
        assertTrue("/tasks n" in reply, reply)
        assertTrue("fork" !in reply, "must not blame the deploy: $reply")
        assertTrue(
            o.queries.filterIsInstance<ChannelQuery.ModelSelection>().isEmpty(),
            "no modelSelection query without a context",
        )
    }

    @Test
    fun planTapOnlyMovesTheDraftAndWritesNothing() = runBlocking {
        val o = ops()
        val reply = commands(o).onCallback("pl:account:zai-start-plan", 2L, OWNER)!!
        // nothing was written: the draft is local until ✅ Aceptar
        assertTrue(o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().isEmpty())
        assertTrue("🟡" in reply.text, reply.text) // «A aplicar:»
        assertTrue("Nada cambia hasta tocar ✅ Aceptar" in reply.text, reply.text)
        assertTrue("Start Plan (free)" in draftLine(reply.text), reply.text)
        assertTrue("GLM Start" in draftLine(reply.text), reply.text)
        // the keyboard marks the draft plan 🟡 and offers the two verbs
        val markup = reply.markup.toString()
        assertTrue("🟡 Start Plan (free)" in markup)
        assertTrue("✅ Aceptar" in markup)
        assertTrue("✖️ Descartar" in markup)
        assertTrue("pac:" in markup)
        assertTrue("pcl:" in markup)
    }

    @Test
    fun theThreeStepsAssembleOneValidTripleAndAcceptWritesItOnce() = runBlocking {
        val o = ops()
        val bc = commands(o)
        bc.onCallback("pl:account:zai-start-plan", 3L, OWNER) // plan → its own first model
        bc.onCallback("pe:low", 4L, OWNER) // effort inside that model's vocabulary
        assertTrue(
            o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().isEmpty(),
            "still a draft — no write until accept",
        )
        val accepted = bc.onCallback("pac:", 5L, OWNER)!!
        assertTrue(accepted.text.startsWith("✅ aplicado"), accepted.text)
        val set = o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().single()
        assertEquals("conversation", set.scope)
        assertEquals("account:zai-start-plan", set.plan)
        assertEquals("glm-start", set.model)
        assertEquals("low", set.effort, "the WHOLE triple travels in the one write")
        assertEquals(app, set.workspacePath)
    }

    @Test
    fun switchingToOpenCodeGoNeverDragsTheZaiModel() = runBlocking {
        // THE OWNER'S REAL FAILURE (3/11 19:57): the app refused
        // «model opencode-go-chat/GLM-5.3-Flash is not selectable» because
        // the partial set carried the old plan's model into the new plan.
        val current = crossProviderJson("account:zai-start-plan", "GLM-5.3-Flash")
        val o = crossOps(current)
        val bc = commands(o)
        bc.onCallback("pl:opencode-go-chat", 8L, OWNER)
        bc.onCallback("pac:", 9L, OWNER)
        val set = o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().single()
        assertEquals("opencode-go-chat", set.plan)
        assertEquals("deepseek-v4.1-flash", set.model, "the new plan's OWN first model")
        assertEquals("high", set.effort, "the model's HIGHEST published tier (the fork's completion)")
    }

    @Test
    fun switchingBackToZaiFromOpenCodeGoIsSymmetric() = runBlocking {
        val current = crossProviderJson("opencode-go-chat", "deepseek-v4.1-flash")
        val o = crossOps(current)
        val bc = commands(o)
        bc.onCallback("pl:account:zai-start-plan", 10L, OWNER)
        bc.onCallback("pac:", 11L, OWNER)
        val set = o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().single()
        assertEquals("account:zai-start-plan", set.plan)
        assertEquals("GLM-5.3-Flash", set.model)
    }

    @Test
    fun aCrossPlanModelTapMovesTheDraftToItsOwnersPlan() = runBlocking {
        val current = crossProviderJson("account:zai-start-plan", "GLM-5.3-Flash")
        val o = crossOps(current)
        val bc = commands(o)
        val reply = bc.onCallback("pm:kimi-k2", 12L, OWNER)!!
        assertTrue("OpenCode Go" in draftLine(reply.text), "the model's OWNER, never the current plan: ${reply.text}")
        assertTrue("kimi-k2" in draftLine(reply.text), reply.text)
        assertTrue(o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().isEmpty())
    }

    @Test
    fun anEffortOutsideTheModelsVocabularyIsRefusedWithTheRealList() = runBlocking {
        // deepseek publishes only low/high — «max» (a GLM tier) must not travel
        val current = crossProviderJson("opencode-go-chat", "deepseek-v4.1-flash")
        val o = crossOps(current)
        val bc = commands(o)
        val reply = bc.onCallback("pe:max", 13L, OWNER)!!
        assertTrue("no existe para el modelo mostrado" in reply.text, reply.text)
        assertTrue("low · high" in reply.text, reply.text)
        assertTrue(o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().isEmpty())
    }

    @Test
    fun acceptWithoutADraftSaysThereIsNothingToApply() = runBlocking {
        val o = ops()
        val reply = commands(o).onCallback("pac:", 14L, OWNER)!!
        assertTrue("No hay ningún borrador abierto" in reply.text, reply.text)
        assertTrue(o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().isEmpty())
    }

    @Test
    fun discardDropsTheDraftAndKeepsTheSelection() = runBlocking {
        val o = ops()
        val bc = commands(o)
        bc.onCallback("pl:account:zai-start-plan", 15L, OWNER)
        val reply = bc.onCallback("pcl:", 16L, OWNER)!!
        assertTrue("Borrador descartado" in reply.text, reply.text)
        assertTrue(
            o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().isEmpty(),
            "discarding is not a write",
        )
        // the next ✅ has nothing to apply
        assertTrue(
            "No hay ningún borrador" in bc.onCallback("pac:", 17L, OWNER)!!.text,
        )
    }

    @Test
    fun aStalePlanTapIsSaidHonestlyAndNothingIsSent() = runBlocking {
        val o = ops()
        val reply = commands(o).onCallback("pl:gone-provider", 18L, OWNER)!!
        assertTrue("ya no está en el catálogo" in reply.text, reply.text)
        assertTrue(
            o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().isEmpty(),
            "an invented pair is exactly the bug being fixed",
        )
    }

    @Test
    fun theNewCommandNameAndTheOldOneBothOpenTheMenu() = runBlocking {
        val o = ops()
        val bc = commands(o)
        assertTrue("Effort: <b>high</b>" in bc.handle("/plan_model_effort", 19L)!!.text)
        assertTrue("Effort: <b>high</b>" in bc.handle("/plan", 20L)!!.text, "the old name keeps working")
        // the menu says the new name
        assertTrue("/plan_model_effort" in bc.handle("/help", 21L)!!.text)
    }

    @Test
    fun theDefaultTapCopiesTheWholeConversationSelection() = runBlocking {
        val o = ops()
        val reply = commands(o).onCallback("pd:", 5L, OWNER)!!
        assertTrue("default de la app actualizado" in reply.text, reply.text)
        val set = o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().single()
        assertEquals("default", set.scope, "pd: writes the APP's default, not the conversation's")
        assertEquals("account:zai-individual", set.plan)
        assertEquals("glm-5.3-flash", set.model)
        assertEquals("high", set.effort)
    }

    @Test
    fun aRefusedAcceptSaysSoWithItsCode() = runBlocking {
        val o = FakeOps().apply {
            available = true
            answers["botStatus"] = boundStatus
            answers["modelSelection"] = msJson()
            // setModelSelection deliberately MISSING -> unknown_query
        }
        val bc = commands(o)
        bc.onCallback("pe:low", 6L, OWNER) // a draft first
        val reply = bc.onCallback("pac:", 7L, OWNER)!!.text
        assertTrue("⚠️ No pude aplicar (unknown_query)" in reply, reply)
        assertTrue("El draft sigue ARMADO" in reply, "the retry promise is on every refusal: $reply")
    }

    @Test
    fun aTimeoutNamesTheChannelAndKeepsTheDraftRetryable() = runBlocking {
        val o = FakeOps().apply {
            answers["botStatus"] = boundStatus
            answers["modelSelection"] = msJson()
        }
        val bc = commands(o)
        bc.onCallback("pe:low", 6L, OWNER) // a draft first
        o.failCode = "timeout" // the host hiccuped mid-write (the 4/11 drill)
        val failed = bc.onCallback("pac:", 7L, OWNER)!!
        assertTrue("El canal no respondió a tiempo" in failed.text, failed.text)
        assertTrue("El draft sigue ARMADO" in failed.text, failed.text)
        assertTrue(failed.markup?.toString()?.contains("pac:") == true, "the retry keyboard survives: $failed")
        // THE PROMISE IS REAL: the same tap, channel back, applies the SAME draft
        o.failCode = null
        val ok = bc.onCallback("pac:", 8L, OWNER)!!.text
        assertTrue("✅ aplicado" in ok, ok)
        assertEquals("low", o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().last().effort)
    }

    @Test
    fun aDownChannelDegradesTheTaps() = runBlocking {
        val o = ops().apply { available = false }
        val reply = commands(o).onCallback("pe:low", 7L, OWNER)!!.text
        assertTrue("channel to the app is down" in reply, reply)
    }
}
