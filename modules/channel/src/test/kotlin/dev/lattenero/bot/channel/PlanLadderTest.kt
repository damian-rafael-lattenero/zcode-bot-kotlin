package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE DEFAULT LADDER (the owner's 11/11 order, verbatim):
 *   1) Start Plan — GLM-5.3-Flash — Max
 *   2) Z.ai — GLM-5.3-Flash — Max
 *   3) OpenCode Go — deepseek-v4.1-flash — Max
 * First plan with tokens wins; a proven-empty plan is skipped; an
 * unknown-quota plan is the last resort; nothing usable = the exhausted
 * verdict. The rungs resolve against the LIVE catalog — never invented.
 */
class PlanLadderTest {

    /** The owner's real catalog (the three plans, the fork's own ids). */
    private fun catalog(
        startModels: List<PlanMenu.Model> = listOf(
            PlanMenu.Model("GLM-5.3-Flash", "GLM-5.3-Flash", listOf("low", "high", "max")),
        ),
        zaiModels: List<PlanMenu.Model> = listOf(
            PlanMenu.Model("GLM-5.3-Flash", "GLM-5.3-Flash", listOf("low", "high", "max")),
        ),
        goModels: List<PlanMenu.Model> = listOf(
            PlanMenu.Model("deepseek-v4.1-flash", "deepseek-v4.1-flash", listOf("low", "high")),
            PlanMenu.Model("kimi-k2", "kimi-k2"),
        ),
    ) = PlanMenu.Views(
        scope = "conversation",
        plan = "account:zai-start-plan",
        model = "GLM-5.3-Flash",
        effort = "max",
        defaultPlan = null,
        defaultModel = null,
        defaultEffort = null,
        plans = listOf(
            PlanMenu.Plan("account:zai-start-plan", "Start Plan", startModels),
            PlanMenu.Plan("account:zai-individual-coding-plan", "Z.ai", zaiModels),
            PlanMenu.Plan("opencode-go-chat", "OpenCode Go (chat)", goModels),
        ),
    )

    private fun seen(vararg pcts: Int?): PlanQuota.Mark =
        PlanQuota.Mark.Seen(pcts.map { PlanQuota.Window("⏱", it) })

    @Test
    fun theFirstPlanWithTokensWinsInHisExactOrder() {
        val quota = mapOf(
            "account:zai-start-plan" to seen(32, 78),
            "account:zai-individual-coding-plan" to seen(90),
            "opencode-go-chat" to seen(100),
        )
        val verdict = PlanLadder.pick(catalog(), quota)
        val pick = verdict.pick!!
        assertEquals("account:zai-start-plan", pick.plan.providerId)
        assertEquals("GLM-5.3-Flash", pick.model.id)
        assertEquals("max", pick.effort, "the owner's named tier, published by the model")
        assertTrue(verdict.notes.isEmpty(), "a clean rung needs no notes: ${verdict.notes}")
    }

    @Test
    fun aProvenEmptyPlanIsSkippedToTheNext() {
        val quota = mapOf(
            "account:zai-start-plan" to seen(0, 0),
            "account:zai-individual-coding-plan" to seen(90),
            "opencode-go-chat" to seen(0),
        )
        val verdict = PlanLadder.pick(catalog(), quota)
        assertEquals("account:zai-individual-coding-plan", verdict.pick!!.plan.providerId)
        assertTrue("⛔ sin tokens" in verdict.notes.first(), verdict.notes.toString())
    }

    @Test
    fun oneWindowWithTokensKeepsThePlanUsable() {
        // the 5h pool dry but the weekly alive — the plan still works
        val quota = mapOf(
            "account:zai-start-plan" to seen(0, 45),
            "account:zai-individual-coding-plan" to seen(0),
            "opencode-go-chat" to seen(0),
        )
        assertEquals("account:zai-start-plan", PlanLadder.pick(catalog(), quota).pick!!.plan.providerId)
    }

    @Test
    fun aMissingModelFallsToThePlansOwnFirstModelWithANote() {
        val quota = mapOf(
            "account:zai-start-plan" to seen(32),
            "account:zai-individual-coding-plan" to seen(90),
            "opencode-go-chat" to seen(100),
        )
        val verdict = PlanLadder.pick(
            catalog(startModels = listOf(PlanMenu.Model("glm-next", "GLM Next", listOf("low", "high")))),
            quota,
        )
        assertEquals("glm-next", verdict.pick!!.model.id)
        assertEquals("high", verdict.pick.effort, "the model's HIGHEST published tier")
        assertTrue(
            verdict.notes.any { "«GLM-5.3-Flash» no está en el plan" in it },
            verdict.notes.toString(),
        )
    }

    @Test
    fun aModelWithoutTiersRidesWithoutEffort() {
        val quota = mapOf(
            "account:zai-start-plan" to seen(32),
        )
        val verdict = PlanLadder.pick(
            catalog(startModels = listOf(PlanMenu.Model("glm-raw", "GLM Raw"))),
            quota,
        )
        assertNull(verdict.pick!!.effort, "no invented tier — the fork selects by identity")
        assertTrue(
            verdict.notes.any { "sin niveles de effort" in it },
            verdict.notes.toString(),
        )
    }

    @Test
    fun aPlanMissingFromTheCatalogIsSkippedWithANote() {
        val quota = mapOf(
            "account:zai-start-plan" to seen(0),
            "account:zai-individual-coding-plan" to seen(90),
        )
        val verdict = PlanLadder.pick(
            catalog(
                startModels = listOf(PlanMenu.Model("GLM-5.3-Flash", "GLM-5.3-Flash", listOf("max"))),
            ).copy(plans = catalog().plans.dropLast(1)), // OpenCode Go vanished
            quota,
        )
        assertEquals("account:zai-individual-coding-plan", verdict.pick!!.plan.providerId)
        assertTrue(
            verdict.notes.any { "opencode-go-chat no está en el catálogo" in it },
            verdict.notes.toString(),
        )
    }

    @Test
    fun anUnknownQuotaIsTheLastResort() {
        val quota = mapOf(
            "account:zai-start-plan" to PlanQuota.Mark.Missing("timeout"),
            "account:zai-individual-coding-plan" to PlanQuota.Mark.Missing("timeout"),
            "opencode-go-chat" to seen(0),
        )
        val verdict = PlanLadder.pick(catalog(), quota)
        assertEquals("account:zai-start-plan", verdict.pick!!.plan.providerId, "his first rung, quota unknown")
        assertTrue(
            verdict.notes.any { "quota desconocida — elegido por el orden del dueño" in it },
            verdict.notes.toString(),
        )
    }

    @Test
    fun everyPlanProvenEmptyAnswersTheExhaustedVerdict() {
        val quota = mapOf(
            "account:zai-start-plan" to seen(0),
            "account:zai-individual-coding-plan" to seen(0),
            "opencode-go-chat" to seen(0),
        )
        val verdict = PlanLadder.pick(catalog(), quota)
        assertNull(verdict.pick)
        assertEquals(3, verdict.notes.size, "each rung's tiny reason rides: ${verdict.notes}")
    }

    @Test
    fun theLadderOrderIsTheOwnersVerbatim() {
        assertEquals(
            listOf(
                "account:zai-start-plan" to "GLM-5.3-Flash",
                "account:zai-individual-coding-plan" to "GLM-5.3-Flash",
                "opencode-go-chat" to "deepseek-v4.1-flash",
            ),
            PlanLadder.DEFAULT.map { it.providerId to it.modelId },
        )
        assertTrue(PlanLadder.DEFAULT.all { it.effort == "max" }, "Max for every rung")
    }
}
