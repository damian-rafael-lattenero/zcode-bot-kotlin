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

    private fun seen(vararg pcts: Int?, rule: PlanQuota.Rule = PlanQuota.Rule.GATES): PlanQuota.Mark =
        PlanQuota.Mark.Seen(pcts.map { PlanQuota.Window("⏱", it) }, rule)

    private fun gates(vararg windows: Pair<String, Int?>): PlanQuota.Mark =
        PlanQuota.Mark.Seen(
            windows.map { (glyph, pct) -> PlanQuota.Window(glyph, pct) },
            PlanQuota.Rule.GATES,
        )

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
        assertTrue("⛔ no tokens" in verdict.notes.first(), verdict.notes.toString())
    }

    @Test
    fun oneAliveLaneKeepsTheStartPlanUsable() {
        // the Start Plan's buckets are LANES: one empty model pool, another alive — the plan works
        val quota = mapOf(
            "account:zai-start-plan" to seen(0, 45, rule = PlanQuota.Rule.LANES),
            "account:zai-individual-coding-plan" to seen(0),
            "opencode-go-chat" to seen(0),
        )
        assertEquals("account:zai-start-plan", PlanLadder.pick(catalog(), quota).pick!!.plan.providerId)
    }

    @Test
    fun aClosedGateSkipsThePlanEvenWithTokensInTheSiblingWindow() {
        // measured 17/11: the coding plan's 5h window held 98% while the WEEKLY was at zero —
        // the provider answered 1310 on every call, and the old all-windows rule kept picking it
        val quota = mapOf(
            "account:zai-start-plan" to seen(0, 0, rule = PlanQuota.Rule.LANES),
            "account:zai-individual-coding-plan" to gates("⏱" to 98, "📅" to 0),
            "opencode-go-chat" to gates("⏱" to 88, "📅" to 78, "🗓" to 35),
        )
        val verdict = PlanLadder.pick(catalog(), quota)
        assertEquals("opencode-go-chat", verdict.pick!!.plan.providerId, "the first plan with EVERY gate open")
        assertEquals("deepseek-v4.1-flash", verdict.pick.model.id)
        assertTrue(
            verdict.notes.any { "the 📅 window is exhausted" in it },
            verdict.notes.toString(),
        )
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
            verdict.notes.any { "«GLM-5.3-Flash» is not in the plan" in it },
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
            verdict.notes.any { "no effort levels" in it },
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
            verdict.notes.any { "opencode-go-chat is not in the app's catalog" in it },
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
            verdict.notes.any { "quota unknown — picked by the owner's order" in it },
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
