package dev.lattenero.bot.daemon

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The B1 shadow daemon's whole configuration, in one value: the CLI-log
 * base dir, the Kotlin side's data home, the cycle cadence. Defaults are
 * the production paths; the LaunchAgent only pins `--home`.
 */
data class DaemonConfig(
    val baseDir: File = File(System.getProperty("user.home"), ".zcode"),
    val homeDir: File = File(System.getProperty("user.home"), ".zcode/bot-kotlin"),
    val interval: Duration = DEFAULT_INTERVAL,
) {
    companion object {
        val DEFAULT_INTERVAL = 5.seconds

        /** Tail-recursive fold over the flags — the arg parser is data in, config out. */
        fun parse(args: List<String>, cfg: DaemonConfig = DaemonConfig()): DaemonConfig = when {
            args.isEmpty() -> cfg
            args[0] == "--base" && args.size > 1 -> parse(args.drop(2), cfg.copy(baseDir = File(args[1])))
            args[0] == "--home" && args.size > 1 -> parse(args.drop(2), cfg.copy(homeDir = File(args[1])))
            args[0] == "--interval-seconds" && args.size > 1 ->
                parse(args.drop(2), cfg.copy(interval = args[1].toDouble().seconds))

            else -> throw IllegalArgumentException(
                "unknown or incomplete flag: ${args.first()} (usage: --base DIR --home DIR --interval-seconds N)",
            )
        }
    }
}
