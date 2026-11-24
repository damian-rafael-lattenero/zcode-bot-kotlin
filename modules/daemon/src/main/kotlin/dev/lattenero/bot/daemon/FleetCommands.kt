package dev.lattenero.bot.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * /fleet_<verbo> (24/11): EVERY nushell command of the Mac, one phone door.
 *
 * The hub owns the table — `fleet list --json` in ~/Scripts/scripts/fleet.nu —
 * and the daemon READS it at call time, so a new script is a new Telegram
 * command the day it lands (no Kotlin edit, no restart). The name mapping is
 * mechanical: `/fleet_ram_clean` -> verb `ram-clean`.
 *
 * The five deploy verbs never ride the runner: they go to the deploy flow
 * (launchd one-shot), because a ten-minute gradle build must not die at the
 * shared subprocess budget — and the deploy flow already owns the in-flight
 * lock, the owner gate and the one-tap card.
 *
 * The pure half lives here (mapping, discovery parse, gate, words); the
 * subprocess machinery is [NuScriptRunner]'s, the wiring throat is
 * `fleetNow`.
 */
@Suppress("TooManyFunctions") // one family's whole surface: mapping, routing, discovery, gate, words
internal object FleetCommands {

    /** The Telegram prefix — every command of this family starts with it. */
    const val PREFIX = "/fleet_"

    /** The hub's door (the interpreter and budget live in [NuScriptRunner]). */
    const val SCRIPT = "/Users/lattenero/Scripts/scripts/fleet.nu"

    /** One row of `fleet list --json` — what the phone needs to offer a verb. */
    internal data class Verb(
        val name: String,
        val kind: String,
        val script: String,
        val arguments: String,
        val description: String,
    )

    /** One phone line, parsed once: the verb (null = the list) and its argv. */
    internal data class Call(val verb: String?, val argv: List<String>)

    /**
     * BOTH forms answer the same verb — the owner's `/fleet ram-clean --clean`
     * and the Telegram-menu tap `/fleet_ram_clean --clean`:
     *   /fleet <verbo> [args...]      the space form (what the owner types)
     *   /fleet_<verbo> [args...]      the tap form (what the menu registers)
     * A bare `/fleet` or `/fleet list` answers the list (verb = null / "list").
     */
    internal fun callOf(text: String): Call {
        val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val head = words.firstOrNull() ?: ""
        val tail = words.drop(1)
        val verb = when {
            head == "/fleet" -> tail.firstOrNull()?.replace('_', '-')
            head.startsWith(PREFIX) -> head.removePrefix(PREFIX).replace('_', '-')
            else -> null
        }
        val argv = if (head == "/fleet") tail.drop(1) else tail
        return Call(verb = verb?.takeIf { it.isNotEmpty() }, argv = argv)
    }

    /** Is this text ours at all? `/fleet` alone (the menu's tap) counts too. */
    internal fun isFleet(text: String): Boolean {
        val word = text.substringBefore(' ')
        return word == "/fleet" || word.startsWith(PREFIX)
    }

    /**
     * The deploy legs ride [DeployFlow] (launchd one-shot): the alias verbs and
     * the discovered `zcode-*-deploy` twins both answer the same leg.
     */
    internal fun deployTarget(text: String): String? = when (callOf(text).verb) {
        "deploy-all", "zcode-deploy-all" -> "all"
        "deploy-brain", "zcode-brain-deploy" -> "brain"
        "deploy-bot", "zcode-bot-kotlin-deploy" -> "bot"
        "deploy-fork", "zcode-fork-deploy" -> "fork"
        "deploy-whatsapp", "zcode-whatsapp-kotlin-deploy" -> "whatsapp"
        else -> null
    }

    /** The discovery argv — `fleet list --json`. */
    internal fun discoveryArgv(): List<String> = listOf("list", "--json")

    /** The log home per verb — the last run is the whole truth. */
    internal fun logFileFor(verb: String): String = "fleet-$verb.log"

    /** The discovery run has its own log — it never overwrites a verb's last run. */
    internal const val DISCOVERY_LOG = "fleet-discovery.log"

    /** A fleet answer in the two dialects: HTML for sendMessage, markdown for sendRichMessage. */
    internal data class Reply(val plain: String, val rich: String?)

    /**
     * The rich-markdown body of a verb's run. Bot API 10.1 renders TABLES
     * natively from GFM markdown, so a clean nu table becomes a real table on
     * the phone; anything the parser refuses (wrapped cells) rides a fenced
     * code block — the monospace truth, never invented columns.
     */
    internal fun richOf(row: Verb, argv: List<String>, exit: Int, tookMs: Long, out: String): String {
        val mode = argv.joinToString(" ").ifEmpty { "-" }
        val body = NuTable.toRichMarkdown(out.trimEnd()) ?: ("```\n" + out.trimEnd() + "\n```")
        val verdict = if (exit == 0) "" else " · exit $exit"
        return "**🧰 fleet ${row.name}** ($mode) · ${tookMs / MS_PER_SECOND}s$verdict\n\n$body"
    }

    private const val MS_PER_SECOND = 1000L

    /**
     * Discovery parse: `fleet list --json` -> rows. Tolerant by design — a
     * malformed payload answers empty and the caller says it could not read
     * the table, never half a menu that would refuse valid verbs.
     */
    internal fun parse(json: String): List<Verb> = runCatching {
        Json.parseToJsonElement(json).jsonArray.mapNotNull { element ->
            val row = element.jsonObject
            Verb(
                name = row.string("name") ?: return@mapNotNull null,
                kind = row.string("kind") ?: "script",
                script = row.string("script") ?: return@mapNotNull null,
                arguments = row.string("arguments") ?: "-",
                description = row.string("description") ?: "-",
            )
        }
    }.getOrDefault(emptyList())

    /**
     * THE SECOND LOCK (the /deploy and /ram_clean law): every verb runs code
     * on the Mac, several of them destructive — the sender is re-checked
     * against the owner before the edge spawns. Fail-closed.
     */
    internal fun gate(fromId: Long?, owner: Long?): String? =
        if (fromId == null || owner == null || fromId != owner) NOT_OWNER else null

    internal const val NOT_OWNER = "🚫 Solo el dueño corre comandos de la Mac."

    internal const val DISCOVERY_FAILED =
        "⚠️ No pude leer la lista de comandos (`fleet list --json` falló) — el daemon sigue vivo."

    internal const val USAGE =
        "🧰 /fleet_<verbo> — TODO comando nu de la Mac.\n" +
            "🧰 /fleet_list — la lista completa (verbo, argumentos, para qué sirve)."

    /**
     * The phone sends with parse_mode=HTML, so every `<`, `>` and `&` in DATA
     * is a tag to Telegram: `[repo] <path>` made the whole list a poison item
     * — rejected five times and dropped (measured 24/11, the owner's first
     * `/fleet_list`: the bot answered nothing at all).
     */
    internal fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** The phone's table: one line per verb — the description clipped, the args named. */
    internal fun listText(verbs: List<Verb>): String {
        if (verbs.isEmpty()) return DISCOVERY_FAILED
        val lines = verbs.map { v ->
            val desc = if (v.description.length <= DESCRIPTION_MAX) {
                v.description
            } else {
                v.description.take(DESCRIPTION_MAX).substringBeforeLast(' ') + "…"
            }
            val takes = if (v.arguments == "-") "" else " ${v.arguments}"
            "• /fleet_${v.name.replace('-', '_')}${escapeHtml(takes)} — ${escapeHtml(desc)}"
        }
        return (listOf("🧰 <b>${verbs.size} comandos nu</b> — /fleet_&lt;verbo&gt; [args]:", "") + lines)
            .joinToString("\n")
    }

    internal fun unknown(verb: String, verbs: List<Verb>): String {
        val near = verbs.map { it.name }.filter { it.startsWith(verb.take(4)) }
        val hint = if (near.isEmpty()) "" else "\n¿querías: ${near.joinToString(" · ") { it.replace('-', '_') }}?"
        return "🤷 No existe el verbo «${escapeHtml(verb)}» — /fleet_list tiene los ${verbs.size}.$hint"
    }

    /** The timeout words — the shared edge names the budget it burned. */
    internal fun timeout(verb: String, budgetSeconds: Long): String =
        "⏱ /fleet_${verb.replace('-', '_')} no terminó en $budgetSeconds s — lo maté; " +
            "algo lo trabó (¿red? ¿lock?): probá de nuevo en un momento."

    /**
     * Does this verb NEED an argument the phone did not send? The signature's
     * required positionals are the ones rendered quoted (`"repo" <path>`);
     * optional ones ride in brackets and a rest rides with `...`. Running a
     * verb with a missing required argument earns a raw parse error the owner
     * reads as silence — the usage line is the honest answer instead.
     */
    internal fun missingRequired(arguments: String, argv: List<String>): Boolean {
        if (argv.isNotEmpty()) return false
        return arguments.split(" · ").any { part ->
            part.startsWith("\"") && !part.startsWith("[") && !part.startsWith("...")
        }
    }

    /** The one-verb usage: what it takes and what it does. */
    internal fun usageOf(v: Verb): String {
        val takes = if (v.arguments == "-") "sin argumentos" else escapeHtml(v.arguments)
        return "🧰 <b>/fleet_${v.name.replace('-', '_')}</b> — $takes\n${escapeHtml(v.description)}\n" +
            "Tip: /fleet ${v.name} <args> también funciona."
    }

    internal const val ERRORED = "⚠️ El comando no pudo correr — el daemon sigue vivo: "

    /** 4096 chars is Telegram's ceiling; 26 verbs × ~120 chars fit with air. */
    private const val DESCRIPTION_MAX = 110
    private val WHITESPACE = Regex("\\s+")

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
}
