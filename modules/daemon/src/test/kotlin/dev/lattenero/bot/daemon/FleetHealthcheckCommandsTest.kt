package dev.lattenero.bot.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * /fleet_healthcheck's pure half: the plan's mode table (bare/--fix/fix =
 * THE FIX — the owner's default — `check` = the read-only battery,
 * anything else = the usage), the second lock (the fix is owner-gated,
 * fail-closed), and one render smoke through the shared runner (the full
 * render battery lives in [NuScriptRunnerTest]).
 */
class FleetHealthcheckCommandsTest {

    // ---- the plan ------------------------------------------------------------

    @Test
    fun bareRestPlansTheFixAndCarriesTheFlag() {
        assertEquals(FleetHealthcheckCommands.Plan.Fix, FleetHealthcheckCommands.plan(""))
        assertEquals(FleetHealthcheckCommands.Plan.Fix, FleetHealthcheckCommands.plan("   "))
        assertEquals(listOf("--fix"), FleetHealthcheckCommands.plan("").argv)
    }

    @Test
    fun theFixAliasesAndTheCheckWordPlanTheirOwn() {
        assertEquals(FleetHealthcheckCommands.Plan.Fix, FleetHealthcheckCommands.plan("--fix"))
        assertEquals(FleetHealthcheckCommands.Plan.Fix, FleetHealthcheckCommands.plan("fix"))
        assertEquals(FleetHealthcheckCommands.Plan.Check, FleetHealthcheckCommands.plan("check"))
        assertEquals(emptyList(), FleetHealthcheckCommands.plan(" check ").argv)
    }

    @Test
    fun anythingElseRefusesWithTheUsage() {
        for (rest in listOf("--trend", "reparar", "todo")) {
            val plan = FleetHealthcheckCommands.plan(rest)
            assertTrue(plan is FleetHealthcheckCommands.Plan.Refused, rest)
            assertTrue("/fleet_healthcheck" in plan.reason, plan.reason)
        }
    }

    // ---- the second lock -----------------------------------------------------

    @Test
    fun fixRefusesStrangersAndFailCloses() {
        val gated = FleetHealthcheckCommands.gate(FleetHealthcheckCommands.Plan.Fix, fromId = 42L, owner = 7L)
        assertTrue(gated is FleetHealthcheckCommands.Plan.Refused, gated.toString())
        assertTrue("Only the owner" in gated.reason, gated.reason)
        // fail-closed: a null sender or an unwired owner never passes
        assertTrue(FleetHealthcheckCommands.gate(FleetHealthcheckCommands.Plan.Fix, null, 7L) is FleetHealthcheckCommands.Plan.Refused)
        assertTrue(FleetHealthcheckCommands.gate(FleetHealthcheckCommands.Plan.Fix, 7L, null) is FleetHealthcheckCommands.Plan.Refused)
    }

    @Test
    fun fixPassesForTheOwnerAndTheCheckNeedsNoLock() {
        assertEquals(FleetHealthcheckCommands.Plan.Fix, FleetHealthcheckCommands.gate(FleetHealthcheckCommands.Plan.Fix, 7L, 7L))
        assertEquals(
            FleetHealthcheckCommands.Plan.Check,
            FleetHealthcheckCommands.gate(FleetHealthcheckCommands.Plan.Check, fromId = 42L, owner = 7L),
        )
    }

    // ---- the render smoke (through the shared runner) ------------------------

    @Test
    fun theFixHeaderSpeaksTheMode() {
        val reply = NuScriptRunner.render(
            emoji = "🩺",
            label = "fleet-healthcheck",
            mode = "fix",
            exit = 0,
            tookMs = 3_402,
            out = "boot: ok · colima: ok",
            logPath = "/logs/fleet-healthcheck.log",
        )
        assertTrue(reply.startsWith("🩺 <b>fleet-healthcheck</b> (fix) · 3s"), reply)
        assertFalse("✂️" in reply, reply) // it fit — no cut line
    }
}
