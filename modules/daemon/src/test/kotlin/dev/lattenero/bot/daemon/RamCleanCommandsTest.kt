package dev.lattenero.bot.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * /ram_clean's pure half: the plan's mode table (bare = THE DRILL — the
 * owner's default — with --clean/clean as aliases, `report` = the explicit
 * read-only leg, anything else = the usage) and the second lock (the drill
 * is owner-gated, fail-closed). The render moved to the shared
 * [NuScriptRunner] — its battery lives there ([NuScriptRunnerTest]).
 */
class RamCleanCommandsTest {

    // ---- the plan ------------------------------------------------------------

    @Test
    fun bareRestPlansTheDrillAndCarriesTheFlag() {
        assertEquals(RamCleanCommands.Plan.Clean, RamCleanCommands.plan(""))
        assertEquals(RamCleanCommands.Plan.Clean, RamCleanCommands.plan("   "))
        assertEquals(listOf("--clean"), RamCleanCommands.plan("").argv)
    }

    @Test
    fun theCleanAliasesAndTheReportWordPlanTheirOwn() {
        assertEquals(RamCleanCommands.Plan.Clean, RamCleanCommands.plan("--clean"))
        assertEquals(RamCleanCommands.Plan.Clean, RamCleanCommands.plan("clean"))
        assertEquals(listOf("--clean"), RamCleanCommands.plan(" --clean ").argv)
        assertEquals(RamCleanCommands.Plan.Report, RamCleanCommands.plan("report"))
        assertEquals(emptyList(), RamCleanCommands.plan(" report ").argv)
    }

    @Test
    fun theTerminalOnlyFlagsRefuseWithTheUsage() {
        for (rest in listOf("--trend", "--leak-run-mb 5", "todo")) {
            val plan = RamCleanCommands.plan(rest)
            assertTrue(plan is RamCleanCommands.Plan.Refused, rest)
            assertTrue("/ram_clean" in plan.reason, plan.reason)
        }
    }

    // ---- the second lock -----------------------------------------------------

    @Test
    fun cleanRefusesStrangersAndFailCloses() {
        val gated = RamCleanCommands.gate(RamCleanCommands.Plan.Clean, fromId = 42L, owner = 7L)
        assertTrue(gated is RamCleanCommands.Plan.Refused, gated.toString())
        assertTrue("Solo el dueño" in gated.reason, gated.reason)
        // fail-closed: a null sender or an unwired owner never passes
        assertTrue(RamCleanCommands.gate(RamCleanCommands.Plan.Clean, null, 7L) is RamCleanCommands.Plan.Refused)
        assertTrue(RamCleanCommands.gate(RamCleanCommands.Plan.Clean, 7L, null) is RamCleanCommands.Plan.Refused)
    }

    @Test
    fun cleanPassesForTheOwnerAndTheReportNeedsNoLock() {
        assertEquals(RamCleanCommands.Plan.Clean, RamCleanCommands.gate(RamCleanCommands.Plan.Clean, 7L, 7L))
        assertEquals(
            RamCleanCommands.Plan.Report,
            RamCleanCommands.gate(RamCleanCommands.Plan.Report, fromId = 42L, owner = 7L),
        )
    }
}
