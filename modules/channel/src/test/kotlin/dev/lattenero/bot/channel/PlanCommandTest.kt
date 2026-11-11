package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bot's configured owner — the taps the suite sends come from him. */
private const val OWNER = 7L

/**
 * THE PLAN PICKER'S REPLY CONTRACT (the 11/11 split): /plan shows every
 * plan WITH its remaining quota; /model lists the plan in force's models;
 * /effort the plan+model's tiers. EVERY tap writes its ONE field and
 * re-renders from the SET'S OWN answer — the server's word is the truth
 * (the fork's resolveModelPair keeps pairs valid; no local draft exists).
 * The ⭐ tap runs the DEFAULT LADDER; pac:/pcl: answer the old-menu note.
 */
class PlanCommandTest {

    /**
     * The fake ops: canned answers by query kind (+ per-plan quota rows),
     * the query recorder, and the capability gate.
     */
    private class FakeOps(private val quotaCap: Boolean = true) : ChannelOps {
        var available = true

        /** When set, ONLY the setModelSelection write fails with THIS code (the timeout drill's hook). */
        var failSetModelWith: String? = null
        val queries = mutableListOf<ChannelQuery>()
        val answers = mutableMapOf<String, String>()

        /** The quota frame's answers, KEYED BY PLAN (the per-plan override's own queries). */
        val quotaAnswers = mutableMapOf<String, String>()

        override fun speaksQuotaProvider(): Boolean = quotaCap

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            queries.add(query)
            if (!available) return QueryAnswer.Unavailable
            if (query is ChannelQuery.SetModelSelection) {
                failSetModelWith?.let { return QueryAnswer.Failed(it, "simulated") }
            }
            if (query is ChannelQuery.Quota) {
                return query.providerId?.let { pid -> quotaAnswers[pid]?.let { QueryAnswer.Ok(it) } }
                    ?: QueryAnswer.Failed("unknown_query", "no canned quota")
            }
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

    /** The owner's REAL catalog (the three plans, the fork's own ids + labels). */
    private fun catalogJson(plan: String = "account:zai-start-plan", model: String = "GLM-5.3-Flash") = """
    {
      "scope": "conversation",
      "selection": {"providerId": "$plan", "modelId": "$model", "reasoningLevel": "max"},
      "default": null,
      "plans": [
        {"providerId": "account:zai-start-plan", "label": "Start Plan",
         "models": [{"id": "GLM-5.3-Flash", "label": "GLM-5.3-Flash", "reasoningLevels": ["low", "high", "max"]}]},
        {"providerId": "account:zai-individual-coding-plan", "label": "Z.ai",
         "models": [{"id": "GLM-5.3-Flash", "label": "GLM-5.3-Flash", "reasoningLevels": ["low", "high", "max"]}]},
        {"providerId": "opencode-go-chat", "label": "OpenCode Go (chat)",
         "models": [{"id": "deepseek-v4.1-flash", "label": "deepseek-v4.1-flash", "reasoningLevels": ["low", "high"]},
                    {"id": "kimi-k2", "label": "kimi-k2"}]}
      ]
    }
    """.trimIndent()

    private val boundStatus = """
        {"bot":{"id":"daemon-local","enabled":true},
         "context":{"workspacePath":"$app","activeTaskId":"task-77","mode":"task"}}
    """.trimIndent()

    /** The z.ai plan's quota frame answer (the entitlement's CREDIT_LIMIT shape, 5h + weekly). */
    private fun creditLimitJson(pct5h: Int, pctWeek: Int) = """
    {"quota":{"limits":[
        {"type":"CREDIT_LIMIT","number":5,"currentValue":31,"usage":120,"percentage":$pct5h,"nextResetTime":1767225600000},
        {"type":"CREDIT_LIMIT","number":1,"currentValue":10,"usage":100,"percentage":$pctWeek,"nextResetTime":1767250000000}
    ]}}
    """.trimIndent()

    /** The Start Plan's quota frame answer (the entitlement's buckets shape): `usage` is the USED share. */
    private fun startPlanJson(remaining: Int) = """
    {"quota":{"limits":[{"unit":100,"usage":${100 - remaining},"period":"5 hours","nextResetTime":1767225600000}]}}
    """.trimIndent()

    private fun commands(o: FakeOps, directMark: PlanQuota.Mark? = null): BotCommands = BotCommands(
        ops = o,
        hub = LiveHub(),
        daemonReset = { "fake ladder" },
        ownerUserId = { OWNER },
        clock = { 1_000_000L },
        history = FakeHistory(rows),
        defaultWorkspacePath = "",
        directQuotaMark = { _ -> directMark },
    )

    private suspend fun BotCommands.reply(text: String): String? = handle(text, 1L)?.text

    private fun quotaOps(
        startPlan: String = startPlanJson(32),
        zai: String = creditLimitJson(pct5h = 26, pctWeek = 10),
    ): FakeOps = FakeOps().apply {
        answers["botStatus"] = boundStatus
        answers["modelSelection"] = catalogJson()
        answers["setModelSelection"] = catalogJson()
        quotaAnswers["account:zai-start-plan"] = startPlan
        quotaAnswers["account:zai-individual-coding-plan"] = zai
        quotaAnswers["opencode-go-chat"] = creditLimitJson(pct5h = 0, pctWeek = 13)
    }

    // ── /plan: the card ───────────────────────────────────────────────────

    @Test
    fun planRendersEveryPlanWithItsQuotaBesideTheName() = runBlocking {
        val o = quotaOps()
        val reply = commands(o).handle("/plan", 1L)!!
        assertTrue("«arreglá el login»" in reply.text, "the title comes from the history")
        assertTrue("Quota por plan" in reply.text)
        // the Start Plan row: 32% remaining of the 5h bucket
        assertTrue("<b>Start Plan</b> — ⏱ 32% libre · resets" in reply.text, reply.text)
        // the z.ai row: 100-26 = 74% of the 5h window, 100-10 = 90% weekly
        assertTrue("⏱ 74% libre" in reply.text, reply.text)
        assertTrue("📅 90% libre" in reply.text, reply.text)
        val markup = reply.markup.toString()
        // THE OWNER'S ORDER: the quota BESIDE the plan name
        assertTrue("🎯 Start Plan — ⏱ 32% libre" in markup, markup)
        assertTrue("🟢 Z.ai — ⏱ 74% · 📅 90% libre" in markup, markup)
        assertTrue("pl:account:zai-start-plan" in markup)
        assertTrue("⭐ Default — el primer plan con tokens" in markup)
        assertTrue("pdef:" in markup)
        assertTrue("pd:" in markup)
        // the read rode the context's workspace
        val read = o.queries.filterIsInstance<ChannelQuery.ModelSelection>().single()
        assertEquals(app, read.workspacePath)
    }

    @Test
    fun planRowsShowMissingReadsWithTheTinyError() = runBlocking {
        // the opencode row has NO direct mark wired and NO frame answer → the honest ❔
        val o = quotaOps().apply { quotaAnswers.remove("opencode-go-chat") }
        val reply = commands(o).handle("/plan", 1L)!!
        assertTrue("<b>OpenCode Go (chat)</b> — ❔ sin datos" in reply.text, reply.text)
        assertTrue("reintentá /plan más tarde" in reply.text, reply.text)
        val markup = reply.markup.toString()
        assertTrue("🟢 OpenCode Go (chat) — ❔" in markup, markup)
    }

    @Test
    fun opencodeRowsReadTheirOwnDirectMeter() = runBlocking {
        val direct = PlanQuota.Mark.Seen(
            listOf(PlanQuota.Window("⏱", 100), PlanQuota.Window("📅", 87), PlanQuota.Window("🗓", 92)),
        )
        val o = quotaOps()
        val reply = commands(o, directMark = direct).handle("/plan", 1L)!!
        val markup = reply.markup.toString()
        assertTrue("🟢 OpenCode Go (chat) — ⏱ 100% · 📅 87% · 🗓 92% libre" in markup, markup)
        // the frame was NEVER asked for the opencode row
        assertTrue(
            o.queries.filterIsInstance<ChannelQuery.Quota>().none { it.providerId == "opencode-go-chat" },
            "the opencode row rides the direct rung",
        )
    }

    @Test
    fun planWithoutAForkDeploySpeaksTheHonestDegrade() = runBlocking {
        val o = quotaOps().apply { answers.remove("modelSelection") } // unknown_query, the old server's word
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
        assertTrue(
            o.queries.filterIsInstance<ChannelQuery.ModelSelection>().isEmpty(),
            "no modelSelection query without a context",
        )
    }

    @Test
    fun theOldMegaMenuNameStillOpensThePlanCard() = runBlocking {
        val o = quotaOps()
        val bc = commands(o)
        assertTrue("Quota por plan" in bc.handle("/plan_model_effort", 19L)!!.text)
        assertTrue("Quota por plan" in bc.handle("/plan", 20L)!!.text, "the old name keeps working")
        // the MENU speaks the three new names
        val help = bc.handle("/help", 21L)!!.text
        assertTrue("/plan —" in help)
        assertTrue("/model —" in help)
        assertTrue("/effort —" in help)
    }

    @Test
    fun aCaplessForkNeverShowsAPlanQuotaRowFromTheFrame() = runBlocking {
        // WITHOUT the cap the frame read is forbidden — every z.ai row says the deploy note
        val o = FakeOps(quotaCap = false).apply {
            answers["botStatus"] = boundStatus
            answers["modelSelection"] = catalogJson()
            quotaAnswers["account:zai-start-plan"] = startPlanJson(32)
        }
        val reply = commands(o).handle("/plan", 1L)!!
        assertTrue("falta deploy del fork" in reply.text, reply.text)
        assertTrue(
            o.queries.filterIsInstance<ChannelQuery.Quota>().isEmpty(),
            "the capless host is never asked — its answer would be the ACTIVE plan's",
        )
    }

    // ── /model and /effort ────────────────────────────────────────────────

    @Test
    fun modelListsThePlanInForceModels() = runBlocking {
        val o = quotaOps()
        val reply = commands(o).handle("/model", 2L)!!
        assertTrue("Modelos de <b>Start Plan</b>" in reply.text, reply.text)
        val markup = reply.markup.toString()
        assertTrue("🎯 GLM-5.3-Flash" in markup)
        assertTrue("pm:GLM-5.3-Flash" in markup)
        assertTrue("pl:" !in markup)
        assertEquals(MenuMirror.Kind.MODEL, reply.mirror?.kind)
    }

    @Test
    fun effortListsTheShownModelTiers() = runBlocking {
        val o = quotaOps()
        val reply = commands(o).handle("/effort", 3L)!!
        assertTrue("Effort de <b>GLM-5.3-Flash</b>" in reply.text, reply.text)
        val markup = reply.markup.toString()
        assertTrue("🎯 effort max" in markup)
        assertTrue("pe:max" in markup)
        assertEquals(MenuMirror.Kind.EFFORT, reply.mirror?.kind)
    }

    // ── the taps: every one WRITES its field, no draft ────────────────────

    @Test
    fun aPlanTapWritesOnlyThePlanAndRerendersTheCard() = runBlocking {
        val o = quotaOps()
        val bc = commands(o)
        val reply = bc.onCallback("pl:opencode-go-chat", 2L, OWNER)!!
        val set = o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().single()
        assertEquals("conversation", set.scope)
        assertEquals("opencode-go-chat", set.plan)
        assertNull(set.model, "plan only — the fork resolves the model")
        assertNull(set.effort, "plan only — the fork completes the effort")
        assertEquals(app, set.workspacePath)
        assertTrue("✅ Plan aplicado" in reply.text, reply.text)
        assertTrue("Quota por plan" in reply.text, "the fresh card rides the write's answer")
        assertEquals(MenuMirror.Kind.PLAN, reply.mirror?.kind)
    }

    @Test
    fun aModelTapWritesOnlyTheModelAndRerendersTheModelMenu() = runBlocking {
        val o = quotaOps()
        val bc = commands(o)
        val reply = bc.onCallback("pm:kimi-k2", 3L, OWNER)!!
        val set = o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().single()
        assertEquals("kimi-k2", set.model)
        assertNull(set.plan, "model only — the fork places it under its owner plan")
        assertTrue("✅ Modelo aplicado" in reply.text, reply.text)
        assertTrue("Modelos de" in reply.text, "the model menu re-renders")
        assertEquals(MenuMirror.Kind.MODEL, reply.mirror?.kind)
    }

    @Test
    fun anEffortTapWritesOnlyTheEffortAndRerendersTheEffortMenu() = runBlocking {
        val o = quotaOps()
        val bc = commands(o)
        val reply = bc.onCallback("pe:high", 4L, OWNER)!!
        val set = o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().single()
        assertEquals("high", set.effort)
        assertNull(set.plan, "effort only")
        assertNull(set.model, "effort only")
        assertTrue("✅ Effort aplicado" in reply.text, reply.text)
        assertTrue("Effort de" in reply.text, "the effort menu re-renders")
        assertEquals(MenuMirror.Kind.EFFORT, reply.mirror?.kind)
    }

    @Test
    fun theDefaultTapCopiesTheWholeSelectionInForce() = runBlocking {
        val o = quotaOps()
        val reply = commands(o).onCallback("pd:", 5L, OWNER)!!
        assertTrue("default de la app actualizado" in reply.text, reply.text)
        val set = o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().single()
        assertEquals("default", set.scope, "pd: writes the APP's default, not the conversation's")
        assertEquals("account:zai-start-plan", set.plan)
        assertEquals("GLM-5.3-Flash", set.model)
        assertEquals("max", set.effort)
    }

    @Test
    fun aRefusedWriteSpeaksTheCodeAndTheIntactPromise() = runBlocking {
        val o = quotaOps().apply {
            failSetModelWith = "internal"
            answers["setModelSelection"] = catalogJson() // must NOT be used — the write FAILED
        }
        val reply = commands(o).onCallback("pl:opencode-go-chat", 6L, OWNER)!!
        assertTrue("⚠️ No pude aplicar (internal)" in reply.text, reply.text)
        assertTrue("la selección vigente quedó intacta" in reply.text, reply.text)
    }

    @Test
    fun aTimeoutNamesTheChannelAndTheRetry() = runBlocking {
        val o = quotaOps().apply { failSetModelWith = "timeout" }
        val reply = commands(o).onCallback("pl:opencode-go-chat", 7L, OWNER)!!
        assertTrue("El canal no respondió a tiempo" in reply.text, reply.text)
        assertTrue("reintentá el tap" in reply.text, reply.text)
    }

    @Test
    fun aDownChannelDegradesTheTaps() = runBlocking {
        val o = quotaOps().apply { available = false }
        val reply = commands(o).onCallback("pe:low", 7L, OWNER)!!.text
        assertTrue("channel to the app is down" in reply, reply)
    }

    // ── the ⭐ DEFAULT LADDER (the owner's 11/11 order) ───────────────────

    @Test
    fun theLadderAppliesTheFirstPlanWithTokens() = runBlocking {
        // the conversation rides OpenCode Go: the ladder's pick (rung 1, 32% left) DIFFERS → the write flies
        val o = quotaOps().apply { answers["modelSelection"] = catalogJson(plan = "opencode-go-chat", model = "kimi-k2") }
        val reply = commands(o).onCallback("pdef:", 8L, OWNER)!!
        val set = o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().single()
        // the owner's rung 1: Start Plan · GLM-5.3-Flash · max
        assertEquals("account:zai-start-plan", set.plan)
        assertEquals("GLM-5.3-Flash", set.model)
        assertEquals("max", set.effort)
        assertTrue("⭐ Default aplicado: Start Plan · GLM-5.3-Flash · max" in reply.text, reply.text)
    }

    @Test
    fun theLadderSkipsAProvenEmptyPlanAndLandsOnTheNext() = runBlocking {
        // rung 1 exhausted (every window at 0) → rung 2 (Z.ai) carries tokens
        val o = quotaOps(startPlan = startPlanJson(0))
        val reply = commands(o).onCallback("pdef:", 9L, OWNER)!!
        val set = o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().single()
        assertEquals("account:zai-individual-coding-plan", set.plan)
        assertEquals("GLM-5.3-Flash", set.model)
        assertEquals("max", set.effort)
        assertTrue("⭐ Default aplicado: Z.ai · GLM-5.3-Flash · max" in reply.text, reply.text)
        assertTrue("⛔ sin tokens" in reply.text, "the skip is said: ${reply.text}")
    }

    @Test
    fun theLadderFallsBackToThePlansOwnModelAndTier() = runBlocking {
        // rung 1's GLM-5.3-Flash is gone from the catalog → its OWN first model, its top tier
        val o = quotaOps().apply {
            answers["modelSelection"] = catalogJson(plan = "account:zai-start-plan", model = "glm-old")
                .replace(
                    """"models": [{"id": "GLM-5.3-Flash", "label": "GLM-5.3-Flash", "reasoningLevels": ["low", "high", "max"]}]""",
                    """"models": [{"id": "glm-next", "label": "GLM Next", "reasoningLevels": ["low", "high"]}]""",
                )
        }
        val reply = commands(o).onCallback("pdef:", 10L, OWNER)!!
        val set = o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().single()
        assertEquals("account:zai-start-plan", set.plan, "the plan still has tokens")
        assertEquals("glm-next", set.model, "the plan's OWN first model")
        assertEquals("high", set.effort, "the model's HIGHEST published tier")
        assertTrue("«GLM-5.3-Flash» no está en el plan — usé GLM Next" in reply.text, reply.text)
    }

    @Test
    fun anUnknownQuotaIsTheLaddersLastResort() = runBlocking {
        // rung 1 + 2 unreadable, rung 3 PROVEN empty → the unknown rung 1 wins (the bot must work);
        // the conversation rides OpenCode Go so the last resort's pick DIFFERS from the selection in force
        val o = quotaOps().apply {
            answers["modelSelection"] = catalogJson(plan = "opencode-go-chat", model = "kimi-k2")
            quotaAnswers["opencode-go-chat"] = creditLimitJson(pct5h = 0, pctWeek = 0)
            quotaAnswers["account:zai-start-plan"] = """{"quota":{"limits":[]}}"""
            quotaAnswers["account:zai-individual-coding-plan"] = """{"quota":{"limits":[]}}"""
        }
        val reply = commands(o).onCallback("pdef:", 11L, OWNER)!!
        val set = o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().single()
        assertEquals("account:zai-start-plan", set.plan)
        assertTrue("quota desconocida — elegido por el orden del dueño" in reply.text, reply.text)
    }

    @Test
    fun everyPlanProvenEmptySpeaksTheExhaustedError() = runBlocking {
        val o = quotaOps(
            startPlan = startPlanJson(0),
            zai = creditLimitJson(pct5h = 100, pctWeek = 100),
        )
        val directEmpty = PlanQuota.Mark.Seen(
            listOf(PlanQuota.Window("⏱", 0), PlanQuota.Window("📅", 0)),
        )
        val reply = commands(o, directMark = directEmpty).onCallback("pdef:", 12L, OWNER)!!
        assertTrue("Quota limit reached for all" in reply.text, reply.text)
        assertTrue(
            o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().isEmpty(),
            "nothing is applied when nothing has tokens",
        )
        // the fresh card rides underneath — the ⛔ marks on screen
        assertTrue("Quota por plan" in reply.text, reply.text)
    }

    @Test
    fun theLadderWritesNothingWhenItsPickIsAlreadyInForce() = runBlocking {
        // the conversation rides OpenCode Go: the FIRST pdef applies rung 1, the SECOND finds it in force
        // (the mock's modelSelection flips to the applied catalog — the fork's own truth after the write)
        val o = quotaOps().apply { answers["modelSelection"] = catalogJson(plan = "opencode-go-chat", model = "kimi-k2") }
        val bc = commands(o)
        val first = bc.onCallback("pdef:", 13L, OWNER)!!
        o.answers["modelSelection"] = catalogJson() // the write LANDED: start-plan is in force now
        val writes = o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().size
        val second = bc.onCallback("pdef:", 14L, OWNER)!!
        assertEquals(writes, o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().size, "no second write")
        assertTrue("⭐ Default ya vigente" in second.text, second.text)
        assertTrue("⭐ Default aplicado" in first.text, first.text)
    }

    // ── the retired mega-menu's verbs ─────────────────────────────────────

    @Test
    fun theOldMenusVerbsSpeakTheOldMenuNote() = runBlocking {
        val o = quotaOps()
        val bc = commands(o)
        val accept = bc.onCallback("pac:", 15L, OWNER)!!
        val discard = bc.onCallback("pcl:", 16L, OWNER)!!
        assertTrue("versión anterior" in accept.text, accept.text)
        assertTrue("versión anterior" in discard.text, discard.text)
        assertTrue(
            o.queries.filterIsInstance<ChannelQuery.SetModelSelection>().isEmpty(),
            "a retired verb is never a write",
        )
    }

    // ── the boot ladder (the wiring's own seam) ───────────────────────────

    @Test
    fun theBootLadderAppliesAndSpeaksOnlyWhenItChangesSomething() = runBlocking {
        val o = quotaOps().apply { answers["modelSelection"] = catalogJson(plan = "opencode-go-chat", model = "kimi-k2") }
        val bc = commands(o)
        val outcome = bc.applyDefaultLadder()
        assertTrue(outcome.speak, "a real change is spoken")
        assertTrue("⭐ Default aplicado" in outcome.text, outcome.text)
        // a second run: the applied selection is now IN FORCE (the mock reflects the write) → silent
        o.answers["modelSelection"] = catalogJson()
        val second = bc.applyDefaultLadder()
        assertTrue(!second.speak, "no change → no announcement")
    }
}
