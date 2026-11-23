package dev.lattenero.bot.daemon

import dev.lattenero.bot.contracts.PyText

/**
 * /ram_clean (22/11): the fleet's own `ram-clean` nushell command, run BY
 * the daemon on the Mac it lives in — the phone asks, the Mac answers. The
 * pure half lives here (the plan, the owner gate, the render); the process
 * edge is the wiring's throat (the FilesCommands shape: /file and /diff
 * split the same way). Zero invented content: the script's own words ride
 * escaped inside one `<pre>`, and when they do not fit, the head AND the
 * tail survive with the cut said — never a silent truncation.
 */
internal object RamCleanCommands {

    /**
     * The plan — the script's whole phone surface as one mode table: bare =
     * the read-only report (leaks, swap, top citizens), `--clean` = the
     * drill (idle islands die, receipts speak). Everything else refuses
     * with the usage; the script's OTHER flags (`--trend`, the leak knobs)
     * stay terminal-only on purpose — a phone types words, not ladders.
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
        "" -> Plan.Report
        "--clean", "clean" -> Plan.Clean
        else -> Plan.Refused(USAGE)
    }

    /**
     * THE SECOND LOCK (U9's defense-in-depth, the /deploy law): `--clean`
     * kills processes, so its plan re-checks the sender against the owner
     * before the edge ever spawns. Fail-closed — a null sender or an
     * unwired owner refuses; the report needs no lock (read-only, and the
     * poller's own normalize already gates every update by owner).
     */
    internal fun gate(plan: Plan, fromId: Long?, owner: Long?): Plan = when {
        plan !is Plan.Clean -> plan
        fromId == null || owner == null || fromId != owner -> Plan.Refused(NOT_OWNER)
        else -> plan
    }

    /**
     * The phone reply: one header line (mode · seconds, or the exit code
     * when the script itself failed), the script's words in one `<pre>`,
     * and — only when they do not fit — the cut said with the full log's
     * path. Overlong output keeps the HEAD (the leak suspects, the swap
     * line) and the TAIL (the --clean receipts, the after-Δ): the middle
     * tables are what the log is for.
     */
    internal fun render(clean: Boolean, exit: Int, tookMs: Long, out: String, logPath: String): String {
        val mode = if (clean) "clean" else "report"
        val head = if (exit == 0) {
            "🧹 <b>ram-clean</b> ($mode) · ${tookMs / 1000}s\n"
        } else {
            "⚠️ ram-clean ($mode) salió con código $exit — el log dice el resto:\n"
        }
        val body = PyText.esc(out.trimEnd())
        if (PyText.cpLength(body) <= BODY_CAP) return head + "<pre>" + body + "</pre>"
        val headPart = PyText.take(body, BODY_CAP * HEAD_SHARE / 100)
        val tailPart = takeTail(body, BODY_CAP * (100 - HEAD_SHARE) / 100)
        return head + "<pre>" + headPart + "\n…✂️…\n" + tailPart + "</pre>" +
            "\n✂️ no entró entero — log completo: <code>" + PyText.esc(logPath) + "</code> (/file lo trae)"
    }

    /** The LAST [n] code points — the tail half of the capped render. */
    private fun takeTail(s: String, n: Int): String {
        val total = PyText.cpLength(s)
        if (n >= total) return s
        return s.substring(s.offsetByCodePoints(0, total - n))
    }

    /** The pinned interpreter and script — launchd's PATH is narrow (the VoiceTranscribe law). */
    internal const val NU = "/opt/homebrew/bin/nu"
    internal const val SCRIPT = "/Users/lattenero/Scripts/ram-clean.nu"

    /** The last run's full words — one file per run (the ledger keeps the history). */
    internal const val LOG_FILE_NAME = "ram-clean.log"

    /** The edge's hard ceiling: the dispatch loop is SERIAL — a stuck script never starves the feed. */
    internal const val BUDGET_SECONDS = 90L

    /** The `<pre>` body's HTML budget (DIFF_REPLY_CAP's law: the reply has a roof). */
    private const val BODY_CAP = 3300

    /** How the capped budget splits: the head earns more (leaks + swap), the tail keeps the receipts. */
    private const val HEAD_SHARE = 60

    internal const val USAGE =
        "🧹 /ram_clean — el reporte de RAM/swap de la Mac (solo lectura).\n" +
            "🧹 /ram_clean --clean — además mata las islas idle y purga caches (con receipts)."

    internal const val NOT_OWNER = "🚫 Solo el dueño corre el --clean."
}
