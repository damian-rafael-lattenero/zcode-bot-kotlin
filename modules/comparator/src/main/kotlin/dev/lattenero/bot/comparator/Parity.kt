package dev.lattenero.bot.comparator

import dev.lattenero.bot.contracts.ShadowDecision
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** One shadow spectator turn, regrouped from its decisions (the header is the boundary). */
data class ShadowTurn(val sid: String, val startTs: Double, val endTs: Double, val steps: Int)

@Serializable
data class ParityReport(
    val generatedIso: String,
    val coverageStart: Double,
    val zconn: ZconnParity,
    val spectator: SpectatorParity,
    /** PARITY (every overlapping event agrees) · DIVERGENT · INSUFFICIENT (nothing overlapping yet). */
    val verdict: String,
)

@Serializable
data class ZconnParity(
    val pythonEvents: Int,
    val shadowEvents: Int,
    val matched: Int,
    val beforeCoverage: Int,
    val pythonOnly: Int,
    val shadowOnly: Int,
    val maxDriftS: Double?,
    val medianDriftS: Double?,
)

@Serializable
data class SpectatorParity(
    val pythonTurns: Int,
    val shadowTurns: Int,
    val zeroStepTurnsSkipped: Int,
    val matched: Int,
    val stepMismatches: Int,
    val pythonOnly: Int,
    val shadowOnly: Int,
)

/**
 * The pure matchers. zconn: the two transition streams must be the same
 * kind sequence with every pair within [ZCONN_TOLERANCE_S] — each side
 * polls the same log at its own cadence, so the drift is poll latency,
 * not divergence. spectator: every python turn with steps > 0 must have a
 * shadow turn starting within [TURN_TOLERANCE_S] with the SAME step count;
 * steps=0 turns sent nothing on either side and are skipped (the shadow
 * records no decisions for a silent turn, by design — both sides quiet).
 *
 * COVERAGE NEVER SEPARATES TWINS (divergence #2, 29/10 12:47): the
 * shadow's first word of a window IS the coverage boundary, and the
 * python twin of that very decision can land a hair BEFORE it — pwlog
 * truncates to seconds (12:47:18.0 vs the header's 12:47:18.468), kb.log
 * stamps the send while the shadow stamps its poll. Events in the
 * boundary zone (`[coverageStart - tolerance, coverageStart)`) get a
 * rescue match against the shadow the in-coverage pass left unmatched;
 * what stays unmatched there is history, exactly as before. In-coverage
 * events always match FIRST, so a boundary event can never steal the
 * twin of one.
 */
object Parity {

    const val ZCONN_TOLERANCE_S = 60.0
    const val TURN_TOLERANCE_S = 90.0
    private val STEP_SUFFIX = Regex(""" · <b>(\d+)</b>$""")

    fun zconn(python: List<ZconnTruth>, shadow: List<ShadowDecision>, coverageStart: Double): ZconnParity {
        val total = shadow.count { it.watcher == "zconn" }
        val pending = shadow.filter { it.watcher == "zconn" }.sortedBy { it.ts }.toMutableList()
        val sorted = python.sortedBy { it.ts }
        val inCoverage = sorted.filter { it.ts >= coverageStart }
        val boundary = sorted.filter { it.ts >= coverageStart - ZCONN_TOLERANCE_S && it.ts < coverageStart }
        val drifts = mutableListOf<Double>()
        var matched = 0
        var pythonOnly = 0
        for (event in inCoverage) {
            val idx = pending.indexOfFirst { it.decision == event.kind && abs(it.ts - event.ts) <= ZCONN_TOLERANCE_S }
            if (idx >= 0) {
                drifts.add(abs(pending.removeAt(idx).ts - event.ts))
                matched++
            } else {
                pythonOnly++
            }
        }
        var rescued = 0
        for (event in boundary) {
            val idx = pending.indexOfFirst { it.decision == event.kind && abs(it.ts - event.ts) <= ZCONN_TOLERANCE_S }
            if (idx >= 0) {
                drifts.add(abs(pending.removeAt(idx).ts - event.ts))
                rescued++
            }
        }
        return ZconnParity(
            pythonEvents = inCoverage.size + rescued,
            shadowEvents = total,
            matched = matched + rescued,
            beforeCoverage = sorted.size - inCoverage.size - rescued,
            pythonOnly = pythonOnly,
            shadowOnly = pending.size,
            maxDriftS = drifts.maxOrNull(),
            medianDriftS = drifts.sorted().getOrNull(drifts.size / 2),
        )
    }

    /** Regroups the spectator's decisions into turns: a header message opens a turn. */
    fun shadowTurns(decisions: List<ShadowDecision>): List<ShadowTurn> {
        val out = mutableListOf<ShadowTurn>()
        var sid = ""
        var start = 0.0
        var end = 0.0
        var steps = 0
        var open = false
        for (d in decisions.filter { it.watcher == "spectator" }.sortedBy { it.ts }) {
            if (d.text.endsWith(HEADER_SUFFIX)) {
                if (open) out.add(ShadowTurn(sid, start, end, steps))
                sid = d.sid.orEmpty()
                start = d.ts
                end = d.ts
                steps = 0
                open = true
            } else if (open) {
                end = d.ts
                STEP_SUFFIX.find(d.text)?.groupValues?.get(1)?.toIntOrNull()?.let { n -> steps = maxOf(steps, n) }
            }
        }
        if (open) out.add(ShadowTurn(sid, start, end, steps))
        return out
    }

    fun spectator(
        python: List<SpectatorTurnTruth>,
        shadow: List<ShadowTurn>,
        coverageStart: Double,
    ): SpectatorParity {
        val spoken = python.filter { (it.steps ?: 0) > 0 }
        val inCoverage = spoken.filter { it.startTs >= coverageStart }
        val boundary = spoken.filter {
            it.startTs >= coverageStart - TURN_TOLERANCE_S && it.startTs < coverageStart
        }
        val skipped = python.count { (it.steps ?: 0) == 0 && it.startTs >= coverageStart }
        val pending = shadow.toMutableList()
        var matched = 0
        var mismatches = 0
        var pythonOnly = 0
        for (turn in inCoverage) {
            val idx = pending.indexOfFirst { abs(it.startTs - turn.startTs) <= TURN_TOLERANCE_S }
            if (idx < 0) {
                pythonOnly++
            } else {
                val other = pending.removeAt(idx)
                if (other.steps == turn.steps) matched++ else mismatches++
            }
        }
        var rescued = 0
        for (turn in boundary) {
            val idx = pending.indexOfFirst { abs(it.startTs - turn.startTs) <= TURN_TOLERANCE_S }
            if (idx >= 0) {
                val other = pending.removeAt(idx)
                if (other.steps == turn.steps) rescued++ else mismatches++
            }
        }
        return SpectatorParity(
            pythonTurns = inCoverage.size + rescued,
            shadowTurns = shadow.size,
            zeroStepTurnsSkipped = skipped,
            matched = matched + rescued,
            stepMismatches = mismatches,
            pythonOnly = pythonOnly,
            shadowOnly = pending.size,
        )
    }

    fun verdict(z: ZconnParity, s: SpectatorParity): String = when {
        z.pythonOnly > 0 || z.shadowOnly > 0 || s.stepMismatches > 0 || s.pythonOnly > 0 || s.shadowOnly > 0 ->
            "DIVERGENT"

        z.matched + s.matched == 0 -> "INSUFFICIENT"
        else -> "PARITY"
    }

    private const val HEADER_SUFFIX = "— Mac turn live (spectator)"
}
