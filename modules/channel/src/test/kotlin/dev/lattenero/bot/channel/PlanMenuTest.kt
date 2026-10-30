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
}
