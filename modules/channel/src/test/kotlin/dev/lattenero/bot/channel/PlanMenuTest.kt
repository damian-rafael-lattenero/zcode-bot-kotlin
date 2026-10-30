package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The /plan menu's pure half: parse the channel's modelSelection shapes
 * (the fork's 3d07cace) and build the tap-to-change keyboard.
 */
class PlanMenuTest {

    private val resultJson = """
    {
      "scope": "conversation",
      "selection": {"providerId": "account:zai-individual", "modelId": "glm-5.3-flash", "reasoningLevel": "high"},
      "default": {"providerId": "account:zai-individual", "modelId": "glm-5.3-flash"},
      "plans": [
        {"providerId": "account:zai-individual", "label": "Z.ai (individual)",
         "models": [{"id": "glm-5.3-flash", "label": "GLM 5.3 Flash"}, {"id": "glm-5.3", "label": "GLM 5.3"}]},
        {"providerId": "account:zai-start-plan", "label": "Start Plan (free)",
         "models": [{"id": "glm-start", "label": "GLM Start"}]}
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
    fun statusLineSpeaksCurrentAndDefault() {
        val line = PlanMenu.parse(resultJson)!!.let { PlanMenu.statusLine(it, "unificador") }
        assertTrue("«unificador»" in line)
        assertTrue("Z.ai (individual)" in line)
        assertTrue("GLM 5.3 Flash" in line)
        assertTrue("Effort: <b>high</b>" in line)
        assertTrue("Default de la app" in line)
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
    fun anUnparseableAnswerIsNullNotACrash() {
        assertNull(PlanMenu.parse("{ broken"))
        assertNull(PlanMenu.parse("{}"))
    }
}
