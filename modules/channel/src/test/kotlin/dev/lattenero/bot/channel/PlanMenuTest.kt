package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plan picker's pure half (the 11/11 split): parse the channel's
 * modelSelection shapes (the fork's 3d07cace + the additive per-model
 * reasoningLevels) and build the THREE focused cards — /plan (each plan
 * with its quota beside the name), /model and /effort.
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
        assertEquals(
            listOf("low", "high", "max"),
            v.plans.first().models.first().reasoningLevels,
        )
        // the effort card speaks the CURRENT model's own vocabulary, not the legacy trio
        assertEquals(listOf("low", "high", "max"), v.levels(v.plan, v.model))
    }

    @Test
    fun legacyAnswerWithoutReasoningLevelsFallsBackToLegacyTrio() {
        val v = PlanMenu.parse(resultJson)!!
        assertEquals(listOf("high", "medium", "low"), v.effortVocabulary(v.plan, v.model))
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

    // ── the three cards (the 11/11 split) ─────────────────────────────────

    private val quotaSeen = mapOf(
        "account:zai-start-plan" to PlanQuota.Mark.Seen(
            listOf(
                PlanQuota.Window("⏱", 32, 1_700_000_000_000L),
                PlanQuota.Window("📅", 78),
            ),
        ),
        "account:zai-individual-coding-plan" to PlanQuota.Mark.Missing("el canal no respondió"),
    )

    @Test
    fun thePlanCardSpeaksQuotaLinesAndButtonsWithQuotaBesideTheName() {
        val v = PlanMenu.parse(goldenJson)!!
        val card = PlanMenu.planCard(v, "arreglá el login", quotaSeen, nowMs = 1_000L)
        // the text: one line per plan, missing read included
        assertTrue("Quota por plan" in card.text)
        assertTrue("<b>Start Plan</b> — ⏱ 32% libre · resets" in card.text, card.text)
        assertTrue("<b>Z.AI Individual Coding Plan</b> — ❔ sin datos (el canal no respondió)" in card.text, card.text)
        assertTrue("Modelo: /model · Effort: /effort" in card.text)
        // the buttons: the quota BESIDE the plan name (the owner's literal order)
        val markup = card.markup.toString()
        assertTrue("🎯 Start Plan — ⏱ 32% · 📅 78% libre" in markup, markup)
        assertTrue("🟢 Z.AI Individual Coding Plan — ❔" in markup, markup)
        assertTrue("pl:account:zai-start-plan" in markup)
        assertTrue("⭐ Default — el primer plan con tokens" in markup)
        assertTrue("pdef:" in markup)
        assertTrue("📌 dejar como default de la app" in markup)
    }

    @Test
    fun theModelCardListsThePlanInForceModels() {
        val v = PlanMenu.parse(goldenJson)!!
        val card = PlanMenu.modelCard(v, "arreglá el login")!!
        assertTrue("Modelos de <b>Start Plan</b>" in card.text)
        assertTrue("Plan: /plan · Effort: /effort" in card.text)
        val markup = card.markup.toString()
        assertTrue("🎯 GLM-5.3-Flash" in markup)
        assertTrue("pm:GLM-5.3-Flash" in markup)
        assertTrue("pl:" !in markup, "the model card lists NO plans")
        assertTrue("pdef:" !in markup, "the ladder row lives in /plan only")
    }

    @Test
    fun theEffortCardListsTheShownModelTiers() {
        val v = PlanMenu.parse(goldenJson)!!
        val card = PlanMenu.effortCard(v, "arreglá el login")!!
        assertTrue("Effort de <b>GLM-5.3-Flash</b>" in card.text)
        assertTrue("Plan: /plan · Modelo: /model" in card.text)
        val markup = card.markup.toString()
        assertTrue("🎯 effort max" in markup)
        assertTrue("▪️ effort low" in markup)
        assertTrue("pe:max" in markup)
        assertTrue("pm:" !in markup, "the effort card lists NO models")
    }

    @Test
    fun theEffortCardIsRefusedForAModelWithoutTiers() {
        // kimi-k2 publishes no reasoningLevels → the /effort menu says so instead of inventing rows
        val noTiers = PlanMenu.parse(
            """
            {"scope":"conversation",
             "selection":{"providerId":"opencode-go-chat","modelId":"kimi-k2","reasoningLevel":"high"},
             "default":null,
             "plans":[{"providerId":"opencode-go-chat","label":"OpenCode Go",
                       "models":[{"id":"kimi-k2","label":"kimi-k2"}]}]}
            """.trimIndent(),
        )!!
        assertNull(PlanMenu.effortCard(noTiers, null), "no tiers → no invented rows")
    }

    @Test
    fun theModelCardWithoutAnyPlanInForceIsRefused() {
        val orphan = PlanMenu.parse(
            """{"scope":"conversation","selection":null,"default":null,
                "plans":[{"providerId":"account:zai-start-plan","label":"Start Plan",
                          "models":[{"id":"GLM-5.3-Flash","label":"GLM-5.3-Flash"}]}]}""",
        )!!
        assertNull(PlanMenu.modelCard(orphan, null))
        assertNull(PlanMenu.effortCard(orphan, null))
    }

    @Test
    fun theModelCardFallsBackToTheDefaultPlanWhenNoSelectionRides() {
        val byDefault = PlanMenu.parse(
            """{"scope":"conversation","selection":null,
                "default":{"providerId":"account:zai-individual","modelId":"glm-5.3-flash"},
                "plans":[{"providerId":"account:zai-individual","label":"Z.ai",
                          "models":[{"id":"glm-5.3-flash","label":"GLM 5.3 Flash"}]}]}""",
        )!!
        val card = PlanMenu.modelCard(byDefault, null)!!
        assertTrue("Modelos de <b>Z.ai</b>" in card.text)
    }
}
