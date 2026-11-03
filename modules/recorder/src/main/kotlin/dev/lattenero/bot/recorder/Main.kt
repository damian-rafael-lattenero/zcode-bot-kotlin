package dev.lattenero.bot.recorder

import kap.Kap
import kap.Schedule
import kap.evalGraph
import kap.repeat
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * One-shot entry point:
 *
 * `recorder --state-dir DIR --out-dir DIR [--cap-bytes N] [--interval-seconds N]`
 *
 * One pass and exit 0 — the production cadence comes from the LaunchAgent's
 * StartInterval (a fresh JVM per pass is the same self-healing dumb-ring
 * pattern the bot already uses for its outer healer). `--interval-seconds`
 * greater than zero loops instead, for local development only: the pass
 * rides `Kap.repeat(Schedule.spaced(...))` — pass, wait, pass — kap's
 * cadence operator, not the hand-rolled sleep loop it replaces (the CI
 * sweep's classified exemption retired with it, kap-first sweep 2, 3/11).
 * The
 * interval semantics are the old loop's exact: every pass is followed by
 * the wait, and the first failing pass ends the JVM with exit 1 either way.
 */
// The catch is Python's bare except's spirit: the entry degrades with a
// stderr line and a non-zero exit; no exception class is worth
// distinguishing here (launchd logs it).
@Suppress("TooGenericExceptionCaught")
fun main(args: Array<String>) {
    var stateDir = File(System.getProperty("user.home"), ".zcode/hooks/state")
    var outDir = File(System.getProperty("user.home"), ".zcode/bot-recordings")
    var capBytes = Recorder.DEFAULT_CAP_BYTES
    var intervalSeconds = 0L

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--state-dir" -> stateDir = File(args.getValue(++i, "--state-dir"))
            "--out-dir" -> outDir = File(args.getValue(++i, "--out-dir"))
            "--cap-bytes" -> capBytes = args.getValue(++i, "--cap-bytes").toLong()
            "--interval-seconds" -> intervalSeconds = args.getValue(++i, "--interval-seconds").toLong()
            else -> {
                System.err.println("unknown flag: ${args[i]}")
                exitProcess(2)
            }
        }
        i++
    }

    val recorder = Recorder(stateDir, outDir, capBytes)
    runBlocking {
        val pass = Kap {
            val lines = recorder.runPass()
            println("recorded $lines lines -> $outDir")
        }
        val loop =
            if (intervalSeconds > 0) pass.repeat(Schedule.spaced(intervalSeconds.seconds)) else pass
        loop.evalGraph()
        // a failed pass propagates out of the loop: stderr + exit 1, launchd's log
    }
}

private fun Array<String>.getValue(index: Int, flag: String): String =
    getOrNull(index) ?: run {
        System.err.println("$flag needs a value")
        kotlin.system.exitProcess(2)
    }
