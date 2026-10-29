package dev.lattenero.bot.comparator

import dev.lattenero.bot.contracts.ShadowDecision
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.system.exitProcess

/**
 * B1c — the parity comparator, one shot:
 *
 * `comparator [--shadow-dir DIR] [--logs-dir DIR] [--out DIR] [--week]`
 *
 * Reads the daemon's shadow decisions and the Python bot's own logs
 * (kb.log for zconn's sends, prompt-worker.log for the spectator's
 * turns), diffs them, writes `report-YYYY-MM-DD.json`, and prints the
 * human summary with the verdict. `--week` aggregates the reports already
 * in the out dir: the cutover gate is seven consecutive PARITY days.
 */
fun main(args: Array<String>) {
    val cfg = Config.parse(args.toList())
    val outDir = cfg.out
    outDir.mkdirs()
    if (cfg.week) {
        exitProcess(week(outDir))
    }

    val nowMs = System.currentTimeMillis()
    val shadow = shadowDecisions(cfg.shadowDir)
    val coverageStart = coverageStart(cfg, shadow)
    val zconnTruth = KbLog.parse(readLinesOrEmpty(File(cfg.logsDir, "kb.log")))
    val spectatorTruth = PwLog.parse(
        readLinesOrEmpty(File(cfg.logsDir, "prompt-worker.log")),
        year = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).year,
    )
    val z = Parity.zconn(zconnTruth, shadow, coverageStart)
    val s = Parity.spectator(spectatorTruth, Parity.shadowTurns(shadow), coverageStart)
    val report = ParityReport(
        generatedIso = Instant.ofEpochMilli(nowMs).toString(),
        coverageStart = coverageStart,
        zconn = z,
        spectator = s,
        verdict = Parity.verdict(z, s),
    )
    val json = Json { prettyPrint = true }
    File(outDir, "report-${Instant.ofEpochMilli(nowMs).toString().take(DAY)}.json")
        .writeText(json.encodeToString(ParityReport.serializer(), report))

    println("parity ${report.generatedIso}  (shadow coverage since epoch $coverageStart)")
    println(
        "zconn      python=${z.pythonEvents} (+${z.beforeCoverage} before coverage) shadow=${z.shadowEvents} " +
            "matched=${z.matched} pythonOnly=${z.pythonOnly} shadowOnly=${z.shadowOnly} " +
            "driftS(max/median)=${z.maxDriftS}/${z.medianDriftS}",
    )
    println(
        "spectator  python=${s.pythonTurns} (+${s.zeroStepTurnsSkipped} silent, skipped) " +
            "shadow=${s.shadowTurns} matched=${s.matched} stepMismatches=${s.stepMismatches} " +
            "pythonOnly=${s.pythonOnly} shadowOnly=${s.shadowOnly}",
    )
    println("verdict: ${report.verdict}")
}

private data class Config(
    val shadowDir: File = File(System.getProperty("user.home"), ".zcode/bot-kotlin/shadow"),
    val logsDir: File = File(System.getProperty("user.home"), ".zcode/hooks/logs"),
    val out: File = File(System.getProperty("user.home"), ".zcode/bot-kotlin/parity"),
    val week: Boolean = false,
) {
    companion object {
        fun parse(args: List<String>, cfg: Config = Config()): Config = when {
            args.isEmpty() -> cfg
            args[0] == "--shadow-dir" && args.size > 1 -> parse(args.drop(2), cfg.copy(shadowDir = File(args[1])))
            args[0] == "--logs-dir" && args.size > 1 -> parse(args.drop(2), cfg.copy(logsDir = File(args[1])))
            args[0] == "--out" && args.size > 1 -> parse(args.drop(2), cfg.copy(out = File(args[1])))
            args[0] == "--week" -> parse(args.drop(1), cfg.copy(week = true))
            else -> throw IllegalArgumentException(
                "unknown or incomplete flag: ${args.first()} " +
                    "(usage: comparator --shadow-dir DIR --logs-dir DIR --out DIR [--week])",
            )
        }
    }
}

/** The daemon's first word: the oldest shadow decision, or the daemon state's birth. */
private fun coverageStart(cfg: Config, shadow: List<ShadowDecision>): Double {
    shadow.minOfOrNull { it.ts }?.let { return it }
    return File(cfg.shadowDir.parentFile, "state/zconn.json").takeIf { it.exists() }
        ?.lastModified()?.div(MILLIS_PER_SECOND)
        ?: 0.0
}

private fun shadowDecisions(dir: File): List<ShadowDecision> =
    dir.listFiles { f -> f.name.endsWith(".jsonl") }
        .orEmpty()
        .flatMap { f ->
            readLinesOrEmpty(f).mapNotNull { line ->
                runCatching { Json.decodeFromString(ShadowDecision.serializer(), line) }.getOrNull()
            }
        }
        .sortedBy { it.ts }

private fun readLinesOrEmpty(f: File): List<String> =
    runCatching { f.readLines() }.getOrDefault(emptyList())

/** The gate: seven consecutive PARITY days with real evidence. Exit 0 = go. */
private fun week(outDir: File): Int {
    val reports = outDir.listFiles { f -> f.name.startsWith("report-") }
        .orEmpty()
        .sortedBy { it.name }
    if (reports.isEmpty()) {
        println("no reports yet — run the comparator daily (cron, or by hand after real traffic)")
        return 1
    }
    val days = reports.takeLast(WEEK_DAYS)
    days.forEach { f -> println("${f.name}: ${verdictOf(f)}") }
    val verdicts = days.map(::verdictOf)
    val green = days.size >= WEEK_DAYS && verdicts.all { it == "PARITY" }
    println(if (green) "WEEK GATE: GREEN — the cutover knob is earned" else "WEEK GATE: not yet (${days.size} days)")
    return if (green) 0 else 1
}

private fun verdictOf(f: File): String {
    val text = runCatching { f.readText() }.getOrNull() ?: return "UNREADABLE"
    return runCatching { Json.decodeFromString(ParityReport.serializer(), text).verdict }
        .getOrDefault("UNREADABLE")
}

private const val WEEK_DAYS = 7
private const val DAY = 10
private const val MILLIS_PER_SECOND = 1000.0
