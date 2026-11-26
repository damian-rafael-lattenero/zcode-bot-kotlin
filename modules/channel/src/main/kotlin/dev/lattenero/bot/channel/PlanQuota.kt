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
 * THE /plan ROWS' QUOTA (the owner's 11/11 order: «beside the plan's name it tells
 * you how much quota you have… fundamental to know whether that plan has
 * tokens or not and not to pick it in vain» [translated]).
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
 * («does it have tokens or not» [translated]), so every source's used-percent is inverted ONCE,
 * here, and clamped to 0..100. Pure: parse + render only; the reads ride
 * the callers (the commands and the mirror render the same bytes).
 */
@Suppress("TooManyFunctions") // the three sources' parsers + the two renders — one home (§7)
object PlanQuota {

    /** One metered window of a plan: its glyph, the REMAINING percent, its reset. */
    data class Window(val glyph: String, val remainingPct: Int?, val resetMs: Long? = null)

    /**
     * How a plan's windows decide «it has tokens» (measured 17/11 against a
     * coding plan the bot kept choosing with its weekly window at zero):
     *  - [GATES]: every window must be open — ONE window at zero blocks
     *    EVERY call (the provider answers 1310/429 while that gate is dry,
     *    however much the sibling window holds). The coding plan's 5h/
     *    weekly pools and OpenCode's rolling/weekly/monthly are gates.
     *  - [LANES]: the windows are independent pools — the plan still works
     *    while ONE lane has tokens (Start Plan's per-model buckets).
     * GATES is the default: skipping a usable plan is recoverable, picking
     * a blocked one burns the turn on a 1310.
     */
    enum class Rule { GATES, LANES }

    /** The plan's quota read: real windows (+ how they decide), or the honest miss. */
    sealed interface Mark {
        data class Seen(val windows: List<Window>, val rule: Rule = Rule.GATES) : Mark
        data class Missing(val reason: String) : Mark
    }

    /** Any window with tokens left? Unknown counts as left (never refuse an unreadable window). */
    fun usable(mark: Mark?): Boolean = when (mark) {
        is Mark.Seen -> when (mark.rule) {
            // one closed gate blocks every call: only a plan with NO closed gate is usable
            Rule.GATES -> mark.windows.isNotEmpty() && !exhausted(mark)
            Rule.LANES -> mark.windows.any { it.remainingPct == null || it.remainingPct > 0 }
        }
        else -> true // unknown is not «empty»: the ladder's last resort, the honest ❔
    }

    /**
     * The plan is PROVEN tokenless: a GATE closed (it blocks every call on
     * its own) or EVERY lane empty (independent pools, all dry).
     */
    fun exhausted(mark: Mark?): Boolean = when (mark) {
        is Mark.Seen -> mark.windows.isNotEmpty() && when (mark.rule) {
            Rule.GATES -> mark.windows.any { (it.remainingPct ?: 1) <= 0 }
            Rule.LANES -> mark.windows.all { (it.remainingPct ?: 1) <= 0 }
        }
        else -> false
    }

    /**
     * The exhausted mark's evidence for the ladder's note: the closed gate
     * (one window is enough to block), or «every window» for independent
     * lanes — the owner reads WHY the plan was skipped, never a vague ⛔.
     */
    fun blockedNote(mark: Mark?): String = when (mark) {
        is Mark.Seen -> {
            val closed = mark.windows.filter { (it.remainingPct ?: 1) <= 0 }
            when {
                mark.rule == Rule.LANES || closed.isEmpty() -> "every window exhausted"
                closed.size == 1 -> "the ${closed.single().glyph} window is exhausted"
                else -> "the ${closed.joinToString(" ") { it.glyph }} windows are exhausted"
            }
        }

        else -> "every window exhausted"
    }

    /**
     * The BUTTON's compact suffix: ` — ⏱ 68% · 📅 91% free` — the owner's
     * literal ask («beside the name» [translated]). ⛔ prefixes a PROVEN-empty plan,
     * ⚠️ the tightest window under 25%; a missing read shows just ❔ (the
     * reason lives in the card's line, buttons stay short).
     */
    fun compact(mark: Mark?): String = when (mark) {
        null -> " — ❔"
        is Mark.Missing -> " — ❔"
        is Mark.Seen -> compactSeen(mark)
    }

    private fun compactSeen(mark: Mark.Seen): String {
        val windows = mark.windows
        if (windows.isEmpty()) return " ❔"
        val parts = windows.joinToString(" · ") { w -> "${w.glyph} ${w.remainingPct?.toString() ?: "?"}%" }
        val flag = when {
            exhausted(mark) -> "⛔ "
            (windows.minOfOrNull { it.remainingPct ?: 100 } ?: 100) < ALERT_REMAINING_PCT -> "⚠️ "
            else -> ""
        }
        return " — $flag$parts free"
    }

    /**
     * The card's line for ONE plan: `<b>label</b> — ⏱ 68% free · resets
     * 17:00` — the compact suffix's numbers plus each window's reset. A
     * missing read is the tiny error the owner ordered («what happened and
     * retry later» [translated]), never silence and never a fake number.
     */
    fun line(
        label: String,
        mark: Mark?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = when (mark) {
        null -> missingLine(label, "quota not read")
        is Mark.Missing -> missingLine(label, mark.reason)
        is Mark.Seen -> seenLine(label, mark, nowMs, zone)
    }

    private fun missingLine(label: String, reason: String): String =
        "<b>${PyText.esc(label)}</b> — ❔ no data ($reason) — retry /plan later"

    private fun seenLine(label: String, mark: Mark.Seen, nowMs: Long, zone: ZoneId): String {
        val windows = mark.windows
        if (windows.isEmpty()) return missingLine(label, "the plan publishes no windows")
        val body = windows.joinToString(" · ") { w ->
            "${w.glyph} ${w.remainingPct?.toString() ?: "?"}% free" +
                (w.resetMs?.takeIf { it != 0L }?.let { " · resets ${fmtReset(it, nowMs, zone)}" } ?: "")
        }
        val flag = when {
            exhausted(mark) -> "⛔ "
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
        // CREDIT_LIMIT rows are the coding plan's GATES; unit/usage rows are the Start Plan's LANES
        val rule = if (limits.any { it.str("type") == "CREDIT_LIMIT" }) Rule.GATES else Rule.LANES
        return Mark.Seen(windows, rule)
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
        Rule.GATES, // rolling/weekly/monthly are the provider's gates — one dry blocks the call
    )

    /** z.ai's monitor endpoint (the direct rung's typed parse) — same inversion, same gates. */
    fun fromZai(snapshot: QuotaDirect.QuotaSnapshot): Mark = Mark.Seen(
        snapshot.windows.map { w ->
            Window(
                glyph = glyphByHours(w.hours),
                remainingPct = w.pct?.let { (REMAINING_FULL - it).toInt().coerceIn(0, REMAINING_FULL) },
                resetMs = w.resetMs,
            )
        },
        Rule.GATES,
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
