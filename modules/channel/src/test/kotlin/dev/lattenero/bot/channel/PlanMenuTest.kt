package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The /plan menu's pure half: parse the channel's modelSelection shapes
 * (the fork's 3d07cace + the additive per-model reasoningLevels) and build
 * the tap-to-change keyboard.
 */
class PlanMenuTest {

    /** Legacy shape (a fork without the additive reasoningLevels) + default with effort. */
    private val resultJson = """
    {
      "scope": "conversation",
      "selection": {"providerId": "account:zai-individual", "modelId": "glm-5.3-flash", "reasoningLevel": "high"},
      "default": {"providerId": "account:zai-individual", "modelId": "glm-5.3-flash", "reasoningLevel": "max"},
      "plans": [
        {"providerId": "account:zai-individual", "label": "Z.ai (individual)",
         "models": [{"id": "glm-5.3-flash", "label": "GLM 5.3 Flash"}, {"id": "glm-5.3", "label": "GLM 5.3"}]},
        {"providerId": "account:zai-start-plan", "label": "Start Plan (free)",
         "models": [{"id": "glm-start", "label": "GLM Start"}]}
      ]
    }
    """.trimIndent()

    /**
     * GOLDEN: the fork's REAL answer as probed live on the daemon socket
     * (2026-10-30, scope conversation) with the additive reasoningLevels the
     * fixed fork emits — the same optionSpecs catalog the server validates
     * pe: taps against. Active effort «max» is NOT in the legacy trio.
     */
    private val goldenJson = """
    {
      "scope": "conversation",
      "selection": {"providerId": "account:zai-start-plan", "modelId": "GLM-5.3-Flash", "reasoningLevel": "max"},
      "default": null,
      "plans": [
        {"providerId": "account:zai-individual-coding-plan", "label": "Z.AI Individual Coding Plan",
         "models": [{"id": "GLM-5.3", "label": "GLM-5.3", "reasoningLevels": ["low", "high", "max"]},
                    {"id": "GLM-5.3-Flash", "label": "GLM-5.3-Flash", "reasoningLevels": ["low", "high", "max"]}]},
        {"providerId": "account:zai-start-plan", "label": "Start Plan",
         "models": [{"id": "GLM-5.3-Flash", "label": "GLM-5.3-Flash", "reasoningLevels": ["low", "high", "max"]}]}
      ]
    }
    """.trimIndent()

    @Test
    fun parseReadsSelectionDefaultAndPlans() {
        val v = PlanMenu.parse(resultJson)!!
        assertEquals("account:zai-individual", v.plan)
        assertEquals("glm-5.3-flash", v.model)
        assertEquals("high", v.effort)
        assertEquals(2, v.plans.size)
        assertEquals("Z.ai (individual)", v.planLabel(v.plan))
        assertEquals("GLM Start", v.modelLabel("glm-start"))
        assertNull(v.modelLabel("nope"))
    }

    @Test
    fun parseReadsGoldenLiveAnswerWithReasoningLevels() {
        val v = PlanMenu.parse(goldenJson)!!
        assertEquals("account:zai-start-plan", v.plan)
        assertEquals("GLM-5.3-Flash", v.model)
        assertEquals("max", v.effort)
        assertNull(v.defaultPlan)
        assertEquals(listOf("low", "high", "max"), v.plans.first().models.first().reasoningLevels)
        // the effort rows speak the CURRENT model's own vocabulary, not the legacy trio
        assertEquals(listOf("low", "high", "max"), v.effortLevels())
    }

    @Test
    fun legacyAnswerWithoutReasoningLevelsFallsBackToLegacyTrio() {
        val v = PlanMenu.parse(resultJson)!!
        assertEquals(listOf("high", "medium", "low"), v.effortLevels())
    }

    @Test
    fun statusLineSpeaksCurrentAndDefault() {
        val line = PlanMenu.parse(resultJson)!!.let { PlanMenu.statusLine(it, "unificador") }
        assertTrue("«unificador»" in line)
        assertTrue("Z.ai (individual)" in line)
        assertTrue("GLM 5.3 Flash" in line)
        assertTrue("Effort: <b>high</b>" in line)
        assertTrue("Default de la app" in line)
        // the default's effort rides along when the answer carries it
        assertTrue(" · max" in line)
    }

    @Test
    fun statusLineAlwaysSpeaksTheThreeFields() {
        // effort present: the real value; effort absent: a dash, the field NEVER vanishes
        val withEffort = PlanMenu.parse(goldenJson)!!.let { PlanMenu.statusLine(it, null) }
        assertTrue("Effort: <b>max</b>" in withEffort)
        val noEffort = PlanMenu.parse(
            """{"scope":"conversation","selection":{"providerId":"account:zai-start-plan","modelId":"GLM-5.3-Flash"},"default":null,"plans":[]}""",
        )!!.let { PlanMenu.statusLine(it, null) }
        assertTrue("Effort: <b>—</b>" in noEffort)
    }

    @Test
    fun keyboardHasPlanModelEffortAndDefaultRows() {
        val markup = PlanMenu.parse(resultJson)!!.let { PlanMenu.keyboard(it) }.toString()
        assertTrue("pl:account:zai-individual" in markup) // 37 bytes, under the 64 law
        assertTrue("pm:glm-5.3" in markup)
        assertTrue("pe:high" in markup)
        assertTrue("📌" in markup)
        // the current selections carry the 🎯 mark, the alternatives 🟢/▪️
        assertTrue("🎯 Z.ai (individual)" in markup)
        assertTrue("🟢 Start Plan (free)" in markup)
        assertTrue("🎯 GLM 5.3 Flash" in markup)
        assertTrue("▪️ GLM 5.3" in markup)
    }

    @Test
    fun keyboardMarksTheRealActiveEffortFromTheGoldenAnswer() {
        val markup = PlanMenu.parse(goldenJson)!!.let { PlanMenu.keyboard(it) }.toString()
        // the model's real vocabulary drives the rows — «max» exists and is active
        assertTrue("🎯 effort max" in markup)
        assertTrue("▪️ effort low" in markup)
        assertTrue("▪️ effort high" in markup)
        assertTrue("pe:max" in markup) // 6 bytes, under the 64 law
        assertTrue("🎯 GLM-5.3-Flash" in markup)
        assertTrue("🟢 Z.AI Individual Coding Plan" in markup)
        assertTrue("🎯 Start Plan" in markup)
        // default is null in the probed answer, but 📌 is the SET action —
        // present whenever a plan is selected, default or not
        assertTrue("📌 dejar como default de la app" in markup)
    }

    @Test
    fun planTargetKeepsTheCurrentModelOnlyWhenTheTargetExposesIt() {
        val v = PlanMenu.parse(goldenJson)!!
        // start-plan → individual: GLM-5.3-Flash EXISTS there, so it is kept
        assertEquals(
            PlanMenu.Target("account:zai-individual-coding-plan", "GLM-5.3-Flash"),
            v.planTarget("account:zai-individual-coding-plan"),
        )
        // a plan sharing no model falls to its OWN first row, never the stale id
        val cross = PlanMenu.parse(
            """{"scope":"conversation",
                "selection":{"providerId":"account:zai-start-plan","modelId":"GLM-5.3-Flash"},
                "default":null,
                "plans":[
                  {"providerId":"account:zai-start-plan","label":"Start Plan",
                   "models":[{"id":"GLM-5.3-Flash","reasoningLevels":["low","max"]}]},
                  {"providerId":"opencode-go-chat","label":"OpenCode Go",
                   "models":[{"id":"deepseek-v4.1-flash","reasoningLevels":["low","high"]},{"id":"kimi-k2"}]}]}""",
        )!!
        assertEquals(
            PlanMenu.Target("opencode-go-chat", "deepseek-v4.1-flash"),
            cross.planTarget("opencode-go-chat"),
        )
        assertNull(cross.planTarget("no-such-plan"), "an unknown plan is said, never guessed")
    }

    @Test
    fun modelTargetPrefersTheCurrentPlanThenTheRealOwner() {
        val v = PlanMenu.parse(goldenJson)!!
        // the current plan owns it → its own plan rides along
        assertEquals(
            PlanMenu.Target("account:zai-start-plan", "GLM-5.3-Flash"),
            v.modelTarget("GLM-5.3-Flash"),
        )
        // only the other plan exposes it → the OWNER is the pair's plan
        assertEquals(
            PlanMenu.Target("account:zai-individual-coding-plan", "GLM-5.3"),
            v.modelTarget("GLM-5.3"),
        )
        assertNull(v.modelTarget("gone-model"), "a stale keyboard is refused, not sent")
    }

    // ── the draft (owner, 3/11: choose the three, then ✅ Aceptar) ──

    private val mixedJson = """
    {
      "scope": "conversation",
      "selection": {"providerId": "account:zai-start-plan", "modelId": "GLM-5.3-Flash", "reasoningLevel": "max"},
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

    @Test
    fun theDraftStartsFromTheSelectionInForce() {
        val d = PlanMenu.parse(goldenJson)!!.startDraft(7L)!!
        assertEquals(PlanMenu.Draft("account:zai-start-plan", "GLM-5.3-Flash", "max", 7L), d)
    }

    @Test
    fun aPlanStepResolvesTheModelAndEffortTogether() {
        val v = PlanMenu.parse(mixedJson)!!
        val d = v.startDraft(7L)!!.let { v.draftWithPlan(it, "opencode-go-chat") }!!
        // the OLD plan's model does not exist there → the plan's own first
        assertEquals("deepseek-v4.1-flash", d.model)
        assertEquals("high", d.effort, "the model's highest published tier")
        assertTrue(v.draftValid(d))
        // a model that IS shared is kept
        val kept = v.draftWithPlan(d, "account:zai-start-plan")
        assertEquals("GLM-5.3-Flash", kept!!.model)
    }

    @Test
    fun aModelStepRidesItsOwnerAndRecompletesTheEffort() {
        val v = PlanMenu.parse(mixedJson)!!
        val d = v.startDraft(7L)!!.let { v.draftWithModel(it, "kimi-k2") }!!
        assertEquals("opencode-go-chat", d.plan)
        // kimi-k2 publishes no levels → the legacy trio's safe default
        assertEquals("high", d.effort)
        assertTrue(v.draftValid(d))
    }

    @Test
    fun anEffortStepIsRefusedOutsideTheModelsVocabulary() {
        val v = PlanMenu.parse(mixedJson)!!
        val d = v.startDraft(7L)!!.let { v.draftWithModel(it, "deepseek-v4.1-flash") }!!
        assertEquals(listOf("low", "high"), v.effortVocabulary(d.plan, d.model))
        assertEquals("low", v.draftWithEffort(d, "low")!!.effort)
        assertNull(v.draftWithEffort(d, "max"), "«max» is a GLM tier, not this model's")
    }

    @Test
    fun theDraftKeyboardMarksEveryStepAndOffersAccept() {
        val v = PlanMenu.parse(mixedJson)!!
        val d = v.startDraft(7L)!!.let { v.draftWithPlan(it, "opencode-go-chat") }!!
        val markup = PlanMenu.keyboard(v, d).toString()
        assertTrue("🟡 OpenCode Go" in markup)
        assertTrue("🟡 deepseek-v4.1-flash" in markup)
        assertTrue("🟡 effort high" in markup)
        assertTrue("✅ Aceptar" in markup)
        assertTrue("✖️ Descartar" in markup)
        // without a draft the two verbs are ABSENT — nothing to apply
        val plain = PlanMenu.keyboard(v).toString()
        assertTrue("Aceptar" !in plain)
        assertTrue("Descartar" !in plain)
    }
}
