package dev.lattenero.bot.channel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * /quota's DIRECT rung (incident 30/10's fix): the coding plan's REAL
 * pools straight from z.ai's monitor endpoint — the same live source the
 * retired Python bot used (reporting.py quota_live), with no fork's usage
 * provider in the middle. The pure core (parse + key path + render) lives
 * here; the effects (the provider file's read, the HTTP GET) ride
 * injected function parameters (§7 FP-first), and the composition
 * [report] answers null on EVERY failure — the caller's ladder falls
 * through to the channel path, the user never sees an exception.
 *
 * Python's leveldb-cache scraping fallback is retired on purpose: the
 * app's snapshot dies with its compaction (it happened on 11/10), the
 * live endpoint does not. Parity is the maximum, not the goal.
 */
object QuotaDirect {

    /** The panel's live endpoint (Python's quota_live URL, Bearer auth). */
    const val ENDPOINT = "https://api.z.ai/api/monitor/usage/quota/limit"

    /** One usage window: `hours` 5 = the 5h pool, 1 = the weekly one. */
    data class QuotaWindow(
        val hours: Int?,
        val used: Double,
        val limit: Double,
        val pct: Double?,
        val resetMs: Long?,
    )

    /** The endpoint's typed snapshot: the windows, 5h first, weekly after. */
    data class QuotaSnapshot(
        val level: String?,
        val windows: List<QuotaWindow>,
    )

    /**
     * The endpoint's response → typed snapshot (Python's quota_live
     * parsing): {"data":{"level":...,"limits":[
     * {"type":"CREDIT_LIMIT","number":5,"currentValue":31,"usage":120,
     *  "percentage":26,"nextResetTime":1767225600000}, ...]}}.
     * A row skips when `used` is not a number or `limit` is falsy
     * (0/absent — Python's guard); no usable rows → null (an honest "no
     * data", never an empty shell). Malformed JSON → null too.
     */
    @Suppress("ReturnCount") // one guard per unusable shape — each its own honest null
    fun parseQuotaResponse(json: String): QuotaSnapshot? {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject
            ?: return null
        val data = root.obj("data") ?: return null
        val limits = (data["limits"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val windows = limits.mapNotNull { lim ->
            val used = lim.num("currentValue") ?: return@mapNotNull null
            val limit = lim.num("usage")
            if (limit == null || limit == 0.0) return@mapNotNull null // Python's `not limit`
            QuotaWindow(
                hours = lim.int("number"),
                used = used,
                limit = limit,
                pct = lim.num("percentage"),
                resetMs = lim.long("nextResetTime"),
            )
        }.sortedByDescending { it.hours ?: 0 } // 5h first, week after (Python's sort)
        if (windows.isEmpty()) return null
        return QuotaSnapshot(level = data.str("level"), windows = windows)
    }

    /**
     * The coding-plan key's path inside bot-provider.json (Python's
     * _quota_api_key): config.providerConfigRules.providerRules[] →
     * config.access, type `zhipu-coding-plan-api-key`. A missing key is
     * an honest null (never an exception to the user). The VALUE itself
     * is never logged, tested or committed — it only rides the injected
     * fetch's argument at the edge.
     */
    @Suppress("ReturnCount") // one guard per unusable shape (Python's silent probe)
    fun providerKey(providerJson: String): String? {
        val root = runCatching { Json.parseToJsonElement(providerJson) }.getOrNull()
            as? JsonObject ?: return null
        val rules = root.obj("config")
            ?.obj("providerConfigRules")
            ?.get("providerRules") as? JsonArray
            ?: return null
        for (element in rules) {
            val access = (element as? JsonObject)?.obj("config")?.obj("access") ?: continue
            if (access.str("type") == "zhipu-coding-plan-api-key") {
                access.str("apiKey")?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    /**
     * Python's quota_report windows branch, byte-exact: 📊 Coding plan,
     * then one line per window — ⏱ 5 hours / 📅 Weekly (🔒 Limit for any
     * other `number`), `used/limit credits`, the percentage, ` · resets
     * HH:MM` (dd/MM HH:MM when the reset is not today) and ⚠️ from 75%.
     * Python's trailing «Per-session detail: /usage» is left out on
     * purpose: this bot has no /usage — pointing at a dead command would
     * be a lie, and honesty beats parity. The clock rides in ([nowMs],
     * OpencodeQuota.render's pattern): the bytes are a function of the
     * inputs, the wall clock never leaks into the render.
     */
    fun renderQuota(
        snapshot: QuotaSnapshot,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val lines = mutableListOf("📊 <b>Coding plan</b>")
        snapshot.windows.forEach { w ->
            val name = when (w.hours) {
                5 -> "⏱ 5 hours"
                1 -> "📅 Weekly"
                else -> "🔒 Limit"
            }
            val pctText = w.pct?.let { " (${fmtPct(it)}%)" } ?: ""
            val alert = if ((w.pct ?: 0.0) >= ALERT_PCT) " ⚠️" else ""
            val reset = w.resetMs?.takeIf { it != 0L } // Python: falsy reset_ms → no text
                ?.let { " · resets ${fmtReset(it, nowMs, zone)}" } ?: ""
            lines.add("$name: ${fmtNum(w.used)}/${fmtNum(w.limit)} credits$pctText$reset$alert")
        }
        return lines.joinToString("\n")
    }

    /**
     * The whole direct rung with the effects injected (§7): [providerJson]
     * reads bot-provider.json's text (the wiring's edge), [fetch] is the
     * Bearer GET (`suspend (url, token) -> String`, throws on transport
     * or HTTP failure). Every failure — no file, no key, dead network,
     * bad shape — is an honest null: the ladder falls through to the
     * channel path. ONE attempt, no retry: the channel fallback IS the
     * recovery (Python's 3 attempts bought a flaky network nothing the
     * ladder can't).
     */
    // any failure IS the null verdict (Python's silent-by-design probe)
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
    suspend fun report(
        providerJson: () -> String?,
        fetch: suspend (url: String, token: String) -> String,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        val key = providerJson()?.let { providerKey(it) } ?: return null
        val raw = try {
            fetch(ENDPOINT, key)
        } catch (e: Exception) {
            return null // the fallback ladder owns the recovery — nothing flies to the user
        }
        return parseQuotaResponse(raw)?.let { renderQuota(it, nowMs) }
    }

    // ── Formatting helpers — Python's reset/percent shapes + the owner's number law (2/11) ──

    /** The owner's number law (2/11, repeated three times): a comma every
     *  3 digits, the dot for decimals, the unit NEVER moves — Python's
     *  fmt_num K-conversion died here (it turned 300,000K tokens into
     *  «300K»: a thousand times fewer). The separator IS the whole change. */
    private fun fmtNum(n: Double?): String = when {
        n == null -> "?"
        else -> group(BigDecimal(n.toString()).stripTrailingZeros().toPlainString())
    }

    /** Miles con coma (el dueño 2/11: «300,000.4» — ínfimo pero ayuda a leer). */
    private fun group(digits: String): String {
        val dot = digits.indexOf('.')
        val head = if (dot >= 0) digits.substring(0, dot) else digits
        val tail = if (dot >= 0) digits.substring(dot) else ""
        return head.reversed().chunked(GROUP_SIZE).joinToString(",").reversed() + tail
    }

    /** Python renders the raw percentage: JSON ints stay ints (26 → "26"). */
    private fun fmtPct(pct: Double): String =
        if (pct == pct.toLong().toDouble()) pct.toLong().toString() else pct.toString()

    /** Python's datetime_dt: HH:MM when the reset lands on the INJECTED
     *  today's local date, dd/MM HH:MM otherwise — the same shape
     *  OpencodeQuota.fmtReset speaks; the wall clock never leaks in. */
    private fun fmtReset(epochMs: Long, nowMs: Long, zone: ZoneId): String {
        val dt = Instant.ofEpochMilli(epochMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return if (dt.toLocalDate() == today) dt.format(TIME_FMT)
        else dt.format(DATE_TIME_FMT)
    }

    private const val ALERT_PCT = 75.0
    private const val GROUP_SIZE = 3
    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm")

    /** A JSON NUMBER as Double (ints and fractions alike) — a quoted "31" is
     *  NOT a number (Python's isinstance guard). */
    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
}
