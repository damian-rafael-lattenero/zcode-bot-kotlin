package dev.lattenero.bot.channel

import kotlinx.serialization.Serializable

/** The watcher's read-only snapshot: every plan's quota mark, the labels, and the selection in force. */
data class QuotaSnapshot(
    val marks: Map<String, PlanQuota.Mark>,
    val labels: Map<String, String>,
    val plan: String?,
    val model: String?,
)

/**
 * What the watcher ALREADY said — the hysteresis state, persisted as JSON so
 * a restart never re-sends yesterday's alerts.
 */
@Serializable
data class QuotaAlertState(
    /** Plans whose tight window was already announced. */
    val tight: List<String> = emptyList(),
    /** Plans that were inside an exhausted-in-force episode (and already announced). */
    val exhausted: List<String> = emptyList(),
    /** The provider-limit reason last announced (a new string is a new episode). */
    val limitReason: String? = null,
)

/**
 * THE QUOTA WATCHER'S PURE HALF (audit item 5): a snapshot + the hysteresis
 * state → the alerts to speak and the next state. Three honest rules:
 *
 *  1. TIGHT WINDOW — a declared window at or under the threshold announces
 *     once; it re-arms only when the window recovers past
 *     [REARM_MARGIN] points above it (no flapping around the line).
 *  2. EXHAUSTED IN FORCE — the plan CURRENTLY IN USE proven tokenless (a
 *     closed gate) is announced once per episode; a non-selected plan is
 *     left to /plan (the ladder's own business).
 *  3. PROVIDER LIMIT — the provider's own refusal (1310/429, recorded by
 *     the connection tracker) is announced once per DISTINCT reason string.
 */
object QuotaAlerts {

    /** A recovered window must climb this many points above the line to re-arm. */
    const val REARM_MARGIN = 10

    /** The alerts for this pass, plus the state the next pass starts from. */
    fun decide(
        snapshot: QuotaSnapshot,
        limitReason: String?,
        previous: QuotaAlertState,
        thresholdPct: Int,
    ): Pair<List<String>, QuotaAlertState> {
        val alerts = mutableListOf<String>()
        val tightNow = snapshot.marks.entries
            .filter { (_, mark) -> tightest(mark)?.let { it <= thresholdPct } == true }
            .map { (plan, _) -> plan }
        tightNow.filter { it !in previous.tight }.forEach { plan ->
            alerts += tightText(snapshot, plan, thresholdPct)
        }
        val stillArmed = previous.tight.filter { plan ->
            val pct = snapshot.marks[plan]?.let(::tightest)
            pct != null && pct <= thresholdPct + REARM_MARGIN
        }
        val nextTight = (tightNow + stillArmed).distinct().sorted()

        val exhaustedNow = snapshot.marks.entries
            .filter { (_, mark) -> PlanQuota.exhausted(mark) }
            .map { (plan, _) -> plan }
            .sorted()
        val inForce = snapshot.plan
        if (inForce != null && inForce in exhaustedNow && inForce !in previous.exhausted) {
            alerts += exhaustedText(snapshot, inForce)
        }

        val freshLimit = limitReason?.takeIf { it.isNotBlank() && it != previous.limitReason }
        freshLimit?.let { alerts += limitText(it) }

        val next = QuotaAlertState(
            tight = nextTight,
            exhausted = exhaustedNow,
            // keep the last announced reason: the tracker REPEATS it while the
            // episode holds (one alert per episode, never one per tick)
            limitReason = limitReason?.takeIf { it.isNotBlank() } ?: previous.limitReason,
        )
        return alerts to next
    }

    /** The smallest KNOWN remaining percent of a mark (null windows never count). */
    fun tightest(mark: PlanQuota.Mark?): Int? = when (mark) {
        is PlanQuota.Mark.Seen -> mark.windows.mapNotNull { it.remainingPct }.minOrNull()
        else -> null
    }

    private fun tightText(snapshot: QuotaSnapshot, plan: String, thresholdPct: Int): String {
        val label = snapshot.labels[plan] ?: plan
        val windows = (snapshot.marks[plan] as? PlanQuota.Mark.Seen)?.windows.orEmpty()
        val body = windows.joinToString(" · ") { w -> "${w.glyph} ${w.remainingPct ?: "?"}%" }
        val inUse = if (plan == snapshot.plan) " — <b>it is the plan in use</b>" else ""
        return "⚠️ Running low on <b>${dev.lattenero.bot.contracts.PyText.esc(label)}</b> ($body free) — " +
            "threshold ${thresholdPct}%$inUse"
    }

    private fun exhaustedText(snapshot: QuotaSnapshot, plan: String): String {
        val label = snapshot.labels[plan] ?: plan
        val note = (snapshot.marks[plan] as? PlanQuota.Mark.Seen)?.let { PlanQuota.blockedNote(it) }.orEmpty()
        return "⛔ The plan in use (<b>${dev.lattenero.bot.contracts.PyText.esc(label)}</b>) ran out of tokens — " +
            "$note · tap ⭐ in /plan to move to the first one with tokens"
    }

    private fun limitText(reason: String): String {
        val text = reason.take(LIMIT_REASON_CAP)
        return "🚫 The provider cut the service: ${dev.lattenero.bot.contracts.PyText.esc(text)}"
    }

    private const val LIMIT_REASON_CAP = 150
}
