package dev.lattenero.bot.daemon

import dev.lattenero.bot.daemon.shadow.ShadowLog
import dev.lattenero.bot.daemon.zconn.WATCHER
import dev.lattenero.bot.daemon.zconn.ZconnShadow
import io.github.oshai.kotlinlogging.KotlinLogging
import kap.Kap
import kap.ProcessExhaustedException
import kap.ProcessStoppedException
import kap.RestartPolicy
import kap.Schedule
import kap.process
import kap.repeat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * The B1 shadow daemon: one long-lived JVM, one KAP `Process` per watcher.
 * Today: zconn. The cycle is `Kap { one pass }.repeat(spaced(interval))`
 * wrapped in `Process` — cadence is schedule algebra, bugs restart under
 * the convergence law, the heartbeat beats at its own pace (the kill-loop
 * lesson: a beat that only fires at cycle end reads a healthy-but-slow
 * watcher as dead), and launchd's KeepAlive is the dumb outer ring.
 *
 * Shadow mode: it never sends. Decisions land in `<home>/shadow/zconn.jsonl`
 * for the parity diff; the Python bot keeps speaking (ADR-023).
 */
fun main(args: Array<String>) {
    val cfg = DaemonConfig.parse(args.toList())
    val logger = KotlinLogging.logger("daemon")
    logger.info { "daemon starting: home=${cfg.homeDir} base=${cfg.baseDir} interval=${cfg.interval}" }
    val stateDir = File(cfg.homeDir, "state")
    val shadowLog = ShadowLog(File(cfg.homeDir, "shadow"))
    val watcher = ZconnShadow(cfg.baseDir, stateDir, shadowLog) { System.currentTimeMillis() / MILLIS_PER_SECOND }
    val hb = File(stateDir, HB_FILE)

    val zconn = Kap { withContext(MDCContext(mapOf(COMPONENT_KEY to WATCHER))) { watcher.pass() } }
        .repeat(Schedule.spaced(cfg.interval))
        .process(
            name = WATCHER,
            restart = RestartPolicy.exponential(base = 1.seconds),
            heartbeatEvery = DEFAULT_HEARTBEAT_EVERY,
            onRestart = { attempt, error, delay ->
                logger.warn(error) { "zconn cycle failed — restart $attempt in $delay (the convergence law)" }
            },
            onHeartbeat = { touch(hb) },
        )

    runBlocking {
        val handle = zconn.startIn(this)
        Runtime.getRuntime().addShutdownHook(Thread { handle.stop() })
        try {
            handle.await()
            logger.info { "zconn process finished normally" }
        } catch (e: ProcessStoppedException) {
            logger.info(e) { "zconn stopped — shutting down" }
        } catch (e: ProcessExhaustedException) {
            logger.error(e) { "zconn exhausted its restart policy — exiting 1 so launchd restarts the ring" }
            exitProcess(1)
        }
    }
}

/**
 * The mtime IS the clock — the hb-* file family, one beat regardless of
 * cycle progress. The beat never kills anything.
 */
@Suppress("SwallowedException")
private fun touch(file: File) {
    try {
        file.parentFile?.mkdirs()
        file.writeText(".")
    } catch (_: IOException) {
        // silent-by-design: a missed beat is the healer's signal, not ours
    }
}

private const val HB_FILE = "hb-zconn-shadow"
private const val COMPONENT_KEY = "component"
private val DEFAULT_HEARTBEAT_EVERY = 30.seconds
private const val MILLIS_PER_SECOND = 1000.0
