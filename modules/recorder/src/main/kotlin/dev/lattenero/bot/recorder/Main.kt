package dev.lattenero.bot.recorder

import java.io.File
import kotlin.system.exitProcess

/**
 * One-shot entry point:
 *
 * `recorder --state-dir DIR --out-dir DIR [--cap-bytes N] [--interval-seconds N]`
 *
 * One pass and exit 0 — the production cadence comes from the LaunchAgent's
 * StartInterval (a fresh JVM per pass is the same self-healing dumb-ring
 * pattern the bot already uses for its outer healer). `--interval-seconds`
 * greater than zero loops instead, for local development only.
 */
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
    do {
        val lines = try {
            recorder.runPass()
        } catch (e: Exception) {
            System.err.println("recorder pass failed: ${e.message}")
            exitProcess(1)
        }
        println("recorded $lines lines -> $outDir")
        if (intervalSeconds > 0) Thread.sleep(intervalSeconds * 1_000)
    } while (intervalSeconds > 0)
}

private fun Array<String>.getValue(index: Int, flag: String): String =
    getOrNull(index) ?: run {
        System.err.println("$flag needs a value")
        kotlin.system.exitProcess(2)
    }
