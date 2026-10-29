package dev.lattenero.bot.daemon

import dev.lattenero.bot.daemon.shadow.ShadowLog
import dev.lattenero.bot.daemon.spectator.SpectatorOrchestrator
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * The B1 shadow daemon: one long-lived JVM, one KAP `Process` per watcher.
 * B1a: zconn. B1b: the spectator — an orchestrator Process scans the turn
 * markers every 2 s (what the on_user_prompt hook does for Python) and
 * starts one per-turn `Process` per markerless-worker session; each turn's
 * body completes when the marker dies, so the handle list drains itself.
 * Cadence is schedule algebra, bugs restart under the convergence law, the
 * heartbeat beats at its own pace, and launchd's KeepAlive is the dumb
 * outer ring.
 *
 * Shadow mode: it never sends. Decisions land in the shadow dir under
 * `<home>/shadow` for the parity diff; the Python bot keeps speaking
 * (ADR-023/024).
 */
// The composition root: wiring only, no logic to extract.
@Suppress("LongMethod")
fun main(args: Array<String>) {
    val cfg = DaemonConfig.parse(args.toList())
    val logger = KotlinLogging.logger("daemon")
    logger.info { "daemon starting: home=${cfg.homeDir} base=${cfg.baseDir} interval=${cfg.interval}" }
    val stateDir = File(cfg.homeDir, "state")
    val shadowLog = ShadowLog(File(cfg.homeDir, "shadow"))
    val pythonState = File(File(cfg.baseDir, "hooks"), "state")
    val clock = { System.currentTimeMillis() / MILLIS_PER_SECOND }
    val zconnWatch = ZconnShadow(cfg.baseDir, stateDir, shadowLog, clock)
    val hbZconn = File(stateDir, HB_ZCONN)
    val hbSpectator = File(stateDir, HB_SPECTATOR)

    val zconn = Kap { withContext(MDCContext(mapOf(COMPONENT_KEY to WATCHER))) { zconnWatch.pass() } }
        .repeat(Schedule.spaced(cfg.interval))
        .process(
            name = WATCHER,
            restart = RestartPolicy.exponential(base = 1.seconds),
            heartbeatEvery = DEFAULT_HEARTBEAT_EVERY,
            onRestart = { attempt, error, delay ->
                logger.warn(error) { "zconn cycle failed — restart $attempt in $delay (the convergence law)" }
            },
            onHeartbeat = { touch(hbZconn) },
        )

    runBlocking {
        val orchestrator = SpectatorOrchestrator(cfg.baseDir, pythonState, shadowLog, clock, this)
        val spectator = Kap { orchestrator.scan() }
            .repeat(Schedule.spaced(SPECTATOR_SCAN))
            .process(
                name = "spectator-orchestrator",
                restart = RestartPolicy.exponential(base = 1.seconds),
                heartbeatEvery = DEFAULT_HEARTBEAT_EVERY,
                onRestart = { attempt, error, delay ->
                    logger.warn(error) { "spectator scan failed — restart $attempt in $delay" }
                },
                onHeartbeat = { touch(hbSpectator) },
            )

        val zconnHandle = zconn.startIn(this)
        val spectatorHandle = spectator.startIn(this)
        val root = coroutineContext[Job]
        Runtime.getRuntime().addShutdownHook(
            Thread {
                zconnHandle.stop()
                spectatorHandle.stop()
                root?.cancel() // the per-turn spectators must not hang the exit
            },
        )
        try {
            coroutineScope {
                listOf(zconnHandle to WATCHER, spectatorHandle to "spectator-orchestrator").forEach { (h, name) ->
                    launch {
                        try {
                            h.await()
                        } catch (e: ProcessStoppedException) {
                            logger.info(e) { "$name stopped — shutting down" }
                        } catch (e: ProcessExhaustedException) {
                            logger.error(e) { "$name exhausted — exiting 1 so launchd restarts the ring" }
                            exitProcess(1)
                        }
                    }
                }
            }
            logger.info { "watchers finished — shutting down" }
        } finally {
            root?.cancel()
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

private const val HB_ZCONN = "hb-zconn-shadow"
private const val HB_SPECTATOR = "hb-spectator"
private const val COMPONENT_KEY = "component"
private val DEFAULT_HEARTBEAT_EVERY = 30.seconds
private val SPECTATOR_SCAN = 2.seconds
private const val MILLIS_PER_SECOND = 1000.0
