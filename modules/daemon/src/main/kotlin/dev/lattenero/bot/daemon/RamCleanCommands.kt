package dev.lattenero.bot.daemon

/**
 * /ram_clean (22/11): the fleet's own `ram-clean` nushell command, run BY
 * the daemon on the Mac it lives in — the phone asks, the Mac answers. The
 * pure half lives here (the plan, the owner gate, the command's own
 * words); the shared subprocess machinery is [NuScriptRunner]'s (the
 * FilesCommands shape: pure half + wiring throat, split per command). The
 * owner's ruling (22/11): bare IS the drill — clean comes first on the
 * phone.
 */
internal object RamCleanCommands {

    /**
     * The plan — the script's whole phone surface as one mode table: bare =
     * THE DRILL (idle islands die, receipts speak), `--clean`/`clean` ride
     * as aliases, `report` = the explicit read-only leg (leaks, swap, top
     * citizens). Everything else refuses with the usage; the script's OTHER
     * flags (`--trend`, the leak knobs) stay terminal-only on purpose — a
     * phone types words, not ladders.
     */
    internal sealed interface Plan {
        /** The script's argv for this plan (the edge runs it verbatim). */
        val argv: List<String>

        data object Report : Plan {
            override val argv: List<String> = emptyList()
        }

        data object Clean : Plan {
            override val argv: List<String> = listOf("--clean")
        }

        data class Refused(val reason: String) : Plan {
            override val argv: List<String> = emptyList()
        }
    }

    internal fun plan(rest: String): Plan = when (rest.trim()) {
        "", "--clean", "clean" -> Plan.Clean
        "report" -> Plan.Report
        else -> Plan.Refused(USAGE)
    }

    /**
     * THE SECOND LOCK (U9's defense-in-depth, the /deploy law): the drill
     * kills processes — and it IS the bare command now — so its plan
     * re-checks the sender against the owner before the edge ever spawns.
     * Fail-closed — a null sender or an unwired owner refuses; the report
     * needs no lock (read-only, and the poller's own normalize already
     * gates every update by owner).
     */
    internal fun gate(plan: Plan, fromId: Long?, owner: Long?): Plan = when {
        plan !is Plan.Clean -> plan
        fromId == null || owner == null || fromId != owner -> Plan.Refused(NOT_OWNER)
        else -> plan
    }

    /** The pinned script path — the interpreter and budget live in [NuScriptRunner]. */
    internal const val SCRIPT = "/Users/lattenero/Scripts/ram-clean.nu"

    /** The last run's full words — one file per run (the ledger keeps the history). */
    internal const val LOG_FILE_NAME = "ram-clean.log"

    internal const val USAGE =
        "🧹 /ram_clean — limpia la Mac AHORA: mata islas idle, purga caches, receipts en la respuesta.\n" +
            "🧹 /ram_clean report — solo lectura: leak suspects, swap, top citizens."

    internal const val NOT_OWNER = "🚫 Solo el dueño corre la limpieza."

    /** The drill's timeout words — the shared edge names the budget. */
    internal fun timeout(budgetSeconds: Long): String =
        "⏱ ram-clean no terminó en $budgetSeconds s — lo maté; " +
            "algo lo trabó (¿purge lento?): probá de nuevo en un momento."

    /** The drill's spawn-failure words. */
    internal const val ERRORED = "⚠️ ram-clean no pudo correr — el daemon sigue vivo: "
}
