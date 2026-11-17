package dev.lattenero.bot.channel

/**
 * One palette command's usage counter — the row the ranking reads: WHICH
 * command and HOW MANY times the owner used it.
 */
data class CommandUse(val command: String, val uses: Long)

/**
 * The usage ledger: one counter per palette command (an absent command was
 * never used). Immutable value — [plusOne] returns the NEXT ledger, so the
 * count only ever moves forward and nothing is mutated in place.
 */
data class UsageLedger(private val counts: Map<String, Long>) {

    /** How many times [command] was used (0 = never). */
    fun of(command: String): Long = counts[command] ?: 0L

    /** The ledger after one more use of [command]. */
    fun plusOne(command: String): UsageLedger = UsageLedger(counts + (command to of(command) + 1))

    /** Every recorded counter, one [CommandUse] per command. */
    fun entries(): List<CommandUse> = counts.map { (command, uses) -> CommandUse(command, uses) }

    companion object {
        /** A fresh install's ledger: every command at zero. */
        val EMPTY: UsageLedger = UsageLedger(emptyMap())
    }
}

/**
 * The order Telegram's «/» menu currently holds — the command NAMES, first
 * to last. Comparing it with a fresh ranking is the refresher's whole
 * decision ("did the order really move?").
 */
@JvmInline
value class PaletteOrder(val names: List<String>)

/**
 * The palette's own command NAMES — the gate every usage tap goes through
 * (a name outside this set is free text or a non-palette verb and never
 * counts). Membership is the type's own API: `"tasks" in paletteNames`.
 */
@JvmInline
value class PaletteNames(private val names: Set<String>) {

    /** True when [command] is a row of the palette. */
    operator fun contains(command: String): Boolean = command in names

    companion object {
        /** The names of [menu], as one value. */
        fun of(menu: List<MenuCommand>): PaletteNames = PaletteNames(menu.mapTo(HashSet()) { it.name })
    }
}

/**
 * THE PALETTE'S RANKING (owner, 16/11): the commands he uses most sit FIRST
 * in Telegram's «/» menu. PURE — the same ledger always ranks the same way.
 *
 * Laws (each one a test):
 * - USAGE DESC: a command with more uses never ranks after one with fewer.
 * - TIES KEEP THE CANONICAL ORDER: an unused (or equally used) pair stays in
 *   the dispatcher's own order — the menu is stable, never random.
 * - PERMUTATION: the result is the SAME multiset as the input — nothing is
 *   invented, nothing disappears (the palette can never drift from /help).
 */
object CommandUsage {

    /** [menu] ordered by each row's usage in [ledger], ties by [menu]'s own order. */
    fun rank(menu: List<MenuCommand>, ledger: UsageLedger): List<MenuCommand> =
        menu.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<MenuCommand>> { ledger.of(it.value.name) }
                    .thenBy { it.index },
            )
            .map { it.value }

    /**
     * The palette row a typed command counts as — the dispatcher's own
     * aliases folded into their canonical MENU name ([palette] is the
     * dispatcher's canonical set). Null = not a palette command: free text,
     * an unknown verb, or `/retry` (dispatched but deliberately absent
     * from the «/» menu).
     */
    fun paletteNameOf(base: String, palette: PaletteNames): String? = when (base) {
        "task" -> "tasks"
        "alltasks" -> "all_tasks"
        "agents" -> "subagents"
        "plan_model_effort" -> "plan"
        "nuke" -> "nuclear"
        "start" -> "help"
        else -> base.takeIf { it in palette }
    }
}
