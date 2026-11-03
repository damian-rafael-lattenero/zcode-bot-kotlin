package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * /quota's OpenCode rung (owner's order, 3/11: «estoy usando la api de
 * open code… me gustaría que me muestre los límites disponibles de
 * opencode go» — the bot was still answering the OLD z.ai coding plan's
 * pools because the ladder's direct leg was z.ai-hardcoded).
 *
 * The provider's OWN meter, discovered live on 3/11: ONE Bearer GET to
 * `<baseUrl>/usage` (measured against the real endpoint — 200 with the
 * provider key from the app's provider_config.json):
 * {"usage":{"rolling":{"status":"ok","percent":0,"resetsAt":"…Z"},
 *           "weekly":{…},"monthly":{…}}}
 * The percent IS the used share of that window — never inverted into
 * "remaining": the owner watches how much he burns, and a reset instant
 * rides every window.
 *
 * Measured boundaries, kept honest:
 *  • only the GO family exposes /usage (zen/v1/usage = 404 on 3/11) —
 *    [resolve] answers null for any other base URL, and the caller's
 *    ladder speaks the degrade instead of inventing numbers;
 *  • the JSON's own window names are the labels (rolling can only be
 *    READ, not inferred as "5h" — the endpoint never says the hours).
 * The pure core (parse + render + rule pick) lives here; the effects
 * (provider file read, HTTP GET, clock) ride injected parameters (§7).
 */
@Suppress("TooManyFunctions") // the pure core: parse + render + rule pick, one home (§7)
object OpencodeQuota {

    /** The endpoint's suffix on the provider's base URL (measured 3/11). */
    const val USAGE_SUFFIX = "/usage"

    /** The provider family's two base URLs (the app's own template ids). */
    const val GO_BASE_URL = "https://opencode.ai/zen/go/v1"

    /** The card's plan label, per family (the app calls them OpenCode Go / Zen). */
    const val GO_LABEL = "OpenCode Go"
    const val ZEN_LABEL = "OpenCode Zen"

    /** The status word that means «todo bien» — anything else is rendered raw. */
    private const val STATUS_OK = "ok"
    private const val ALERT_PCT = 75.0
    private const val FULL_PCT = 100.0

    /** One metered window: its own name, used percent, status and reset instant. */
    data class Window(val name: String, val percent: Double?, val status: String?, val resetMs: Long?)

    /** The endpoint's typed snapshot: rolling, weekly, monthly (JSON order kept after). */
    data class Usage(val windows: List<Window>)

    /** Where to ask and with which key — the effects' argument pair. */
    data class Target(val url: String, val key: String, val plan: String)

    /**
     * The endpoint's response → typed snapshot: root.usage.<window>{
     * status, percent, resetsAt }. A window without percent AND without
     * status is not a meter row (skipped); no usable window → null (an
     * honest «no data», never an empty shell). Malformed JSON → null too.
     * Windows sort rolling → weekly → monthly (the provider's own ladder),
     * unknown names keep the payload's order after them.
     */
    @Suppress("ReturnCount") // one guard per unusable shape (the parser's honest nulls)
    fun parse(json: String): Usage? {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject
            ?: return null
        val usage = root.obj("usage") ?: return null
        val windows = usage.mapNotNull { (name, value) ->
            (value as? JsonObject)?.let { window(name, it) }
        }
        if (windows.isEmpty()) return null
        val ordered = windows.sortedBy { rank(it.name) }
        return Usage(ordered)
    }

    /** The provider's own window ladder; unknown names go after, keeping the payload's order. */
    private fun rank(name: String): Int =
        WINDOW_ORDER.indexOf(name).let { i -> if (i < 0) WINDOW_ORDER.size else i }

    @Suppress("ReturnCount") // one guard per unusable window shape
    private fun window(name: String, o: JsonObject): Window? {
        val percent = o.num("percent")
        val status = o.str("status")
        if (percent == null && status == null) return null
        return Window(
            name = name,
            percent = percent,
            status = status,
            resetMs = o.str("resetsAt")?.let(::instantMs),
        )
    }

    /** ISO-8601 UTC instants (with or without millis) → epoch ms; garbage → null. */
    private fun instantMs(text: String): Long? =
        runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()

    /**
     * The card: the plan header, then one line per window —
     * `⏱ Rolling: 0% usado · resets 12:35`, `📅 Weekly`, `🗓 Monthly`,
     * ⚠️ from 75% and ⛔ at 100% (the z.ai card's own thresholds — one
     * vocabulary across /quota). A status other than «ok» is rendered
     * VERBATIM (the provider's word, never a bot-invented «limited»), and
     * a window that reports no percent shows only that word (no invented
     * 0%). A missing reset prints nothing — never a fake instant.
     */
    fun render(
        usage: Usage,
        plan: String = GO_LABEL,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val lines = mutableListOf("📊 <b>${PyText.esc(plan)}</b>")
        usage.windows.forEach { w ->
            val statusText = w.status?.takeIf { it != STATUS_OK }
            val body = when {
                w.percent != null -> "${fmtPct(w.percent)}% usado" +
                    (statusText?.let { " · ${PyText.esc(it)}" } ?: "")
                statusText != null -> PyText.esc(statusText)
                else -> "?"
            }
            val reset = w.resetMs?.let { " · resets ${fmtReset(it, nowMs, zone)}" } ?: ""
            val alert = when {
                (w.percent ?: 0.0) >= FULL_PCT -> " ⛔"
                (w.percent ?: 0.0) >= ALERT_PCT -> " ⚠️"
                else -> ""
            }
            lines.add("${label(w.name)}: $body$reset$alert")
        }
        return lines.joinToString("\n")
    }

    /**
     * The provider at hand is an OpenCode one? The wiring asks BEFORE
     * choosing the direct leg: a z.ai conversation must never be served
     * the OpenCode meter (and the other way round). Exact providerId
     * first; an id that NAMES opencode but has no exact rule (a renamed
     * template) falls back to the first OpenCode rule — never to another
     * family.
     */
    fun isOpenCodeProvider(providerJson: String, providerId: String?): Boolean =
        ruleFor(providerJson, providerId)?.let(::isOpenCodeRule) == true

    /**
     * The usage call for THIS provider: the rule's base URL + `/usage` and
     * its api key. Null when the provider is not OpenCode, when no key is
     * configured, or when the family has no meter (zen — 404 measured
     * 3/11): the ladder owns the honest degrade.
     */
    @Suppress("ReturnCount") // one guard per unusable rule shape (not opencode / no key / no meter)
    fun resolve(providerJson: String, providerId: String?): Target? {
        val rule = ruleFor(providerJson, providerId) ?: return null
        if (!isOpenCodeRule(rule)) return null
        val base = rule.obj("config")?.obj("api")?.str("baseUrl")?.trim()?.trimEnd('/')
        val url = when {
            base == null || base.isEmpty() -> GO_BASE_URL + USAGE_SUFFIX
            base.endsWith("/zen/go/v1") -> base + USAGE_SUFFIX
            base.contains("/zen/go/") -> base + USAGE_SUFFIX
            else -> return null // zen has no usage endpoint — never a guessed URL
        }
        val key = rule.obj("config")?.obj("access")?.str("apiKey")
            ?.takeIf { it.isNotEmpty() } ?: return null
        return Target(url = url, key = key, plan = GO_LABEL)
    }

    /**
     * The whole rung with the effects injected (§7): [providerJson] reads
     * the app's provider config (the wiring's edge), [fetch] is the Bearer
     * GET. Every failure — no file, no key, dead network, HTTP error, bad
     * shape — is an honest null (the ladder decides what to say); ONE
     * attempt, the retry is the user's next /quota.
     */
    // any failure IS the null verdict (the ladder owns the recovery)
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
    suspend fun report(
        providerJson: () -> String?,
        providerId: String?,
        fetch: suspend (url: String, token: String) -> String,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        val json = providerJson() ?: return null
        val target = resolve(json, providerId) ?: return null
        val raw = try {
            fetch(target.url, target.key)
        } catch (e: Exception) {
            return null // the ladder speaks the degrade — nothing raw flies to the user
        }
        val usage = parse(raw) ?: return null
        return render(usage, target.plan, nowMs)
    }

    // ── the provider config's rules (both files share this shape) ──

    @Suppress("ReturnCount") // one guard per unusable rule shape
    private fun ruleFor(providerJson: String, providerId: String?): JsonObject? {
        val pid = providerId?.takeIf { it.isNotEmpty() } ?: return null
        val rules = (runCatching { Json.parseToJsonElement(providerJson) }.getOrNull() as? JsonObject)
            ?.obj("config")?.obj("providerConfigRules")?.get("providerRules") as? JsonArray
            ?: return null
        val objects = rules.mapNotNull { it as? JsonObject }
        objects.firstOrNull { it.str("providerId") == pid }?.let { return it }
        if (!pid.contains("opencode", ignoreCase = true)) return null
        return objects.firstOrNull(::isOpenCodeRule)
    }

    private fun isOpenCodeRule(rule: JsonObject): Boolean {
        val id = rule.str("providerId").orEmpty()
        val base = rule.obj("config")?.obj("api")?.str("baseUrl").orEmpty()
        return id.contains("opencode", ignoreCase = true) || base.contains("opencode.ai")
    }

    // ── formatting (the z.ai card's own vocabulary, one law) ──

    private fun label(name: String): String = when (name) {
        "rolling" -> "⏱ Rolling"
        "weekly" -> "📅 Weekly"
        "monthly" -> "🗓 Monthly"
        else -> "🔒 ${name.replaceFirstChar { it.uppercase() }}"
    }

    /** JSON ints stay ints (44 → "44"), a fraction keeps its decimal (44.5). */
    private fun fmtPct(pct: Double): String =
        if (pct == pct.toLong().toDouble()) pct.toLong().toString() else pct.toString()

    /** HH:MM when the reset lands today, dd/MM HH:MM otherwise (local zone). */
    private fun fmtReset(epochMs: Long, nowMs: Long, zone: ZoneId): String {
        val dt = Instant.ofEpochMilli(epochMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return if (dt.toLocalDate() == today) dt.format(TIME_FMT) else dt.format(DATE_TIME_FMT)
    }

    private val WINDOW_ORDER = listOf("rolling", "weekly", "monthly")
    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm")

    /** A JSON NUMBER as Double (ints and fractions alike) — a quoted "44" is NOT one. */
    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
}
