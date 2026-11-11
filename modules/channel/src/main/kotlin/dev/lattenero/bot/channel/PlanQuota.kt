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
 * THE /plan ROWS' QUOTA (the owner's 11/11 order: «al lado del nombre del
 * plan te dice cuánta quota tenés… fundamental para saber si ese plan tiene
 * tokens o no y no elegirlo en vano»).
 *
 * One [Mark] per plan row, normalized across the three sources:
 *  - the fork's quota frame WITH the per-plan `providerId` override
 *    (`queries:quotaProvider`) — the entitlement's own shapes: Start Plan
 *    buckets (unit/usage) and z.ai coding-plan CREDIT_LIMIT windows;
 *  - OpenCode Go's direct /usage rung ([OpencodeQuota]) — the family the
 *    frame cannot answer (no planKind hint exists for it);
 *  - anything else is [Mark.Missing]: a tiny honest reason, never an
 *    invented number.
 *
 * A window's number is the REMAINING share — the owner decides with it
 * («tiene tokens o no»), so every source's used-percent is inverted ONCE,
 * here, and clamped to 0..100. Pure: parse + render only; the reads ride
 * the callers (the commands and the mirror render the same bytes).
 */
@Suppress("TooManyFunctions") // the three sources' parsers + the two renders — one home (§7)
object PlanQuota {

    /** One metered window of a plan: its glyph, the REMAINING percent, its reset. */
    data class Window(val glyph: String, val remainingPct: Int?, val resetMs: Long? = null)

    /** The plan's quota read: real windows, or the honest miss (the reason rides the line). */
    sealed interface Mark {
        data class Seen(val windows: List<Window>) : Mark
        data class Missing(val reason: String) : Mark
    }

    /** Any window with tokens left? Unknown counts as left (never refuse an unreadable window). */
    fun usable(mark: Mark?): Boolean = when (mark) {
        is Mark.Seen -> mark.windows.any { it.remainingPct == null || it.remainingPct > 0 }
        else -> true // unknown is not «empty»: the ladder's last resort, the honest ❔
    }

    /** The plan is PROVEN tokenless: every window read, every window at zero. */
    fun exhausted(mark: Mark?): Boolean = when (mark) {
        is Mark.Seen -> mark.windows.isNotEmpty() && mark.windows.all { (it.remainingPct ?: 1) <= 0 }
        else -> false
    }

    /**
     * The BUTTON's compact suffix: ` — ⏱ 68% · 📅 91% libre` — the owner's
     * literal ask («al lado del nombre»). ⛔ prefixes a PROVEN-empty plan,
     * ⚠️ the tightest window under 25%; a missing read shows just ❔ (the
     * reason lives in the card's line, buttons stay short).
     */
    fun compact(mark: Mark?): String = when (mark) {
        null -> " — ❔"
        is Mark.Missing -> " — ❔"
        is Mark.Seen -> compactSeen(mark.windows)
    }

    private fun compactSeen(windows: List<Window>): String {
        if (windows.isEmpty()) return " ❔"
        val parts = windows.joinToString(" · ") { w -> "${w.glyph} ${w.remainingPct?.toString() ?: "?"}%" }
        val flag = when {
            exhausted(Mark.Seen(windows)) -> "⛔ "
            (windows.minOfOrNull { it.remainingPct ?: 100 } ?: 100) < ALERT_REMAINING_PCT -> "⚠️ "
            else -> ""
        }
        return " — $flag$parts libre"
    }

    /**
     * The card's line for ONE plan: `<b>label</b> — ⏱ 68% libre · resets
     * 17:00` — the compact suffix's numbers plus each window's reset. A
     * missing read is the tiny error the owner ordered («qué pasó y
     * reintentar más tarde»), never silence and never a fake number.
     */
    fun line(
        label: String,
        mark: Mark?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = when (mark) {
        null -> missingLine(label, "quota no leída")
        is Mark.Missing -> missingLine(label, mark.reason)
        is Mark.Seen -> seenLine(label, mark.windows, nowMs, zone)
    }

    private fun missingLine(label: String, reason: String): String =
        "<b>${PyText.esc(label)}</b> — ❔ sin datos ($reason) — reintentá /plan más tarde"

    private fun seenLine(label: String, windows: List<Window>, nowMs: Long, zone: ZoneId): String {
        if (windows.isEmpty()) return missingLine(label, "el plan no publica ventanas")
        val body = windows.joinToString(" · ") { w ->
            "${w.glyph} ${w.remainingPct?.toString() ?: "?"}% libre" +
                (w.resetMs?.takeIf { it != 0L }?.let { " · resets ${fmtReset(it, nowMs, zone)}" } ?: "")
        }
        val flag = when {
            exhausted(Mark.Seen(windows)) -> "⛔ "
            (windows.minOfOrNull { it.remainingPct ?: 100 } ?: 100) < ALERT_REMAINING_PCT -> "⚠️ "
            else -> ""
        }
        return "<b>${PyText.esc(label)}</b> — $flag$body"
    }

    // ── the three sources ─────────────────────────────────────────────────

    /**
     * The fork's quota frame answer (the entitlement snapshot) — BOTH of
     * its shapes: the coding plan's CREDIT_LIMIT windows (`number` 5/1,
     * `percentage` = used) and the Start Plan's buckets (`unit` = total,
     * `usage` = used tokens, `period` names the window). An
     * `unavailableReason` rides as the Missing's reason, verbatim.
     */
    @Suppress("ReturnCount") // one guard per honest shape (the caller's ❔ is the reason's carrier)
    fun fromChannel(json: String): Mark? {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: return null
        (root.str("unavailableReason"))?.let { return Mark.Missing(it) }
        val limits = (root.obj("quota")?.get("limits") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val windows = limits.mapNotNull { lim ->
            when {
                lim.str("type") == "CREDIT_LIMIT" -> Window(
                    glyph = glyphByHours(lim.num("number")?.toInt()),
                    remainingPct = lim.num("percentage")
                        ?.let { (REMAINING_FULL - it.toInt()).coerceIn(0, REMAINING_FULL) },
                    resetMs = lim.num("nextResetTime")?.toLong(),
                )

                lim.num("unit") != null || lim.num("usage") != null -> {
                    val total = lim.num("unit")
                    val used = lim.num("usage") ?: 0.0
                    Window(
                        glyph = glyphByPeriod(lim.str("period") ?: lim.str("meter").orEmpty()),
                        remainingPct = total?.takeIf { it > 0 }
                            ?.let { (REMAINING_FULL - used / total * REMAINING_FULL).toInt() }
                            ?.coerceIn(0, REMAINING_FULL),
                        resetMs = lim.num("nextResetTime")?.toLong(),
                    )
                }

                else -> null // a foreign row — skipped, never invented
            }
        }
        if (windows.isEmpty()) return null
        return Mark.Seen(windows)
    }

    /** OpenCode Go's direct rung — the /usage windows' used-percents, inverted once here. */
    fun fromOpencode(usage: OpencodeQuota.Usage): Mark = Mark.Seen(
        usage.windows.map { w ->
            Window(
                glyph = glyphByWindowName(w.name),
                remainingPct = w.percent?.let { (REMAINING_FULL - it).toInt().coerceIn(0, REMAINING_FULL) },
                resetMs = w.resetMs,
            )
        },
    )

    /** z.ai's monitor endpoint (the direct rung's typed parse) — same inversion. */
    fun fromZai(snapshot: QuotaDirect.QuotaSnapshot): Mark = Mark.Seen(
        snapshot.windows.map { w ->
            Window(
                glyph = glyphByHours(w.hours),
                remainingPct = w.pct?.let { (REMAINING_FULL - it).toInt().coerceIn(0, REMAINING_FULL) },
                resetMs = w.resetMs,
            )
        },
    )

    // ── the glyphs: one vocabulary across every source ────────────────────

    private fun glyphByHours(hours: Int?): String = when (hours) {
        SHORT_HOURS -> GLYPH_SHORT
        WEEK_HOURS -> GLYPH_WEEK
        else -> GLYPH_OTHER
    }

    private fun glyphByPeriod(period: String): String = when {
        period.contains("week", ignoreCase = true) -> GLYPH_WEEK
        period.contains("5", ignoreCase = true) -> GLYPH_SHORT
        else -> GLYPH_OTHER
    }

    private fun glyphByWindowName(name: String): String = when (name) {
        "rolling" -> GLYPH_SHORT
        "weekly" -> GLYPH_WEEK
        "monthly" -> GLYPH_MONTH
        else -> GLYPH_OTHER
    }

    // ── formatting ────────────────────────────────────────────────────────

    /** HH:MM when the reset lands today, dd/MM HH:MM otherwise (the cards' own shape). */
    private fun fmtReset(epochMs: Long, nowMs: Long, zone: ZoneId): String {
        val dt = Instant.ofEpochMilli(epochMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return if (dt.toLocalDate() == today) dt.format(TIME_FMT) else dt.format(DATE_TIME_FMT)
    }

    private const val SHORT_HOURS = 5
    private const val WEEK_HOURS = 1
    private const val GLYPH_SHORT = "⏱"
    private const val GLYPH_WEEK = "📅"
    private const val GLYPH_MONTH = "🗓"
    private const val GLYPH_OTHER = "🔒"
    private const val REMAINING_FULL = 100
    private const val ALERT_REMAINING_PCT = 25
    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm")
}

/** A JSON NUMBER as Double (ints and fractions alike) — a quoted "44" is NOT one. */
internal fun JsonObject.num(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
