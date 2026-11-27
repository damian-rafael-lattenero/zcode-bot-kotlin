package dev.lattenero.bot.daemon

/**
 * /fleet_healthcheck (22/11): the fleet-healthcheck skill's battery, run BY
 * the daemon — the phone asks, the whole ecosystem answers: colima, the pg
 * containers, the ports, the brain's /health, ollama, RAG end-to-end, the
 * bot family (27/11: the Telegram daemon, the WhatsApp daemon and its
 * sidecar, and the three helpers — one leg each, judged by the bot's OWN
 * beat, never launchd's opinion of "loaded"), stack-up, the launchd census,
 * lora. The pure half lives here (plan, gate, the command's words); the
 * machinery is [NuScriptRunner]'s — including the bot repairs, which ride
 * the script's census and reach the phone with no daemon change.
 * The owner's ruling: bare IS `--fix` — the battery runs, the safe
 * launchd-owned recoveries apply, and the battery re-runs as the receipt.
 * `check` = the explicit read-only word.
 */
internal object FleetHealthcheckCommands {

    /**
     * The plan — one mode table: bare/`--fix`/`fix` = THE FIX (the
     * battery + safe recoveries + the re-run receipt), `check` = the
     * read-only battery. Everything else refuses with the usage; the
     * script's other flags stay terminal-only.
     */
    internal sealed interface Plan {
        /** The script's argv for this plan (the edge runs it verbatim). */
        val argv: List<String>

        data object Check : Plan {
            override val argv: List<String> = emptyList()
        }

        data object Fix : Plan {
            override val argv: List<String> = listOf("--fix")
        }

        data class Refused(val reason: String) : Plan {
            override val argv: List<String> = emptyList()
        }
    }

    internal fun plan(rest: String): Plan = when (rest.trim()) {
        "", "--fix", "fix" -> Plan.Fix
        "check" -> Plan.Check
        else -> Plan.Refused(USAGE)
    }

    /**
     * THE SECOND LOCK (U9's defense-in-depth, the /deploy law): the fix
     * kickstarts services — and it IS the bare command now — so its plan
     * re-checks the sender against the owner before the edge ever spawns.
     * Fail-closed — a null sender or an unwired owner refuses; the check
     * needs no lock (read-only, and the poller's own normalize already
     * gates every update by owner).
     */
    internal fun gate(plan: Plan, fromId: Long?, owner: Long?): Plan = when {
        plan !is Plan.Fix -> plan
        fromId == null || owner == null || fromId != owner -> Plan.Refused(NOT_OWNER)
        else -> plan
    }

    /** The pinned script path — the interpreter and budget live in [NuScriptRunner]. */
    internal const val SCRIPT = "/Users/lattenero/.agents/skills/fleet-healthcheck/scripts/healthcheck.nu"

    /** The last run's full words — one file per run (the script's own history log is ITS log). */
    internal const val LOG_FILE_NAME = "fleet-healthcheck.log"

    internal const val USAGE =
        "🩺 /fleet_healthcheck — the ecosystem's full battery and its safe fixes (launchd-owned).\n" +
            "🩺 /fleet_healthcheck check — read only: the battery without touching anything."

    internal const val NOT_OWNER = "🚫 Only the owner runs the fix."

    /** The fix's timeout words — the shared edge names the budget. */
    internal fun timeout(budgetSeconds: Long): String =
        "⏱ the battery did not finish in $budgetSeconds s — I killed it; retry in a moment."

    /** The fix's spawn-failure words. */
    internal const val ERRORED = "⚠️ fleet-healthcheck could not run — the daemon is still alive: "
}
