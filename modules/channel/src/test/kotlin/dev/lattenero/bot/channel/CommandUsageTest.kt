package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The ranking's laws: usage desc, ties canonical, permutation — and the
 * usage tap's gate (aliases fold into the canonical row; non-palette verbs
 * never count).
 */
class CommandUsageTest {

    private val menu = listOf(
        MenuCommand("tasks", "t"),
        MenuCommand("status", "s"),
        MenuCommand("last", "l"),
        MenuCommand("help", "h"),
    )
    private val palette = PaletteNames.of(menu)

    private fun ledger(vararg uses: Pair<String, Long>) = UsageLedger(uses.toMap())

    @Test
    fun zeroCountsKeepTheCanonicalOrder() {
        assertEquals(menu, CommandUsage.rank(menu, UsageLedger.EMPTY))
    }

    @Test
    fun usageDescWins() {
        val ranked = CommandUsage.rank(menu, ledger("help" to 5, "last" to 2))
        assertEquals(listOf("help", "last", "tasks", "status"), ranked.map { it.name })
    }

    @Test
    fun tiesKeepTheCanonicalOrder() {
        val ranked = CommandUsage.rank(menu, ledger("status" to 2, "help" to 2))
        assertEquals(listOf("status", "help", "tasks", "last"), ranked.map { it.name })
    }

    @Test
    fun theRankingIsAPermutation() {
        val ranked = CommandUsage.rank(menu, ledger("last" to 9))
        assertEquals(menu.toSet(), ranked.toSet())
        assertEquals(menu.size, ranked.size)
    }

    @Test
    fun aliasesFoldIntoTheirCanonicalRow() {
        assertEquals("tasks", CommandUsage.paletteNameOf("task", palette))
        assertEquals("all_tasks", CommandUsage.paletteNameOf("alltasks", palette))
        assertEquals("subagents", CommandUsage.paletteNameOf("agents", palette))
        assertEquals("plan", CommandUsage.paletteNameOf("plan_model_effort", palette))
        assertEquals("nuclear", CommandUsage.paletteNameOf("nuke", palette))
        assertEquals("help", CommandUsage.paletteNameOf("start", palette))
        assertEquals("status", CommandUsage.paletteNameOf("status", palette))
    }

    @Test
    fun nonPaletteVerbsCountNothing() {
        assertNull(CommandUsage.paletteNameOf("retry", palette))
        assertNull(CommandUsage.paletteNameOf("whatever", palette))
        assertNull(CommandUsage.paletteNameOf("", palette))
    }

    @Test
    fun theLedgerOnlyMovesForward() {
        val used = UsageLedger.EMPTY.plusOne("tasks").plusOne("tasks")
        assertEquals(2L, used.of("tasks"))
        assertEquals(0L, UsageLedger.EMPTY.of("tasks")) // the empty ledger is untouched
        assertEquals(listOf(CommandUse("tasks", 2L)), used.entries())
    }
}
