package dev.lattenero.bot.daemon

import dev.lattenero.bot.daemon.send.DaemonSend
import dev.lattenero.bot.daemon.send.SendKnob
import dev.lattenero.bot.daemon.send.SendLeg
import dev.lattenero.bot.daemon.send.SendLegs
import dev.lattenero.bot.daemon.send.SendMode
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
 * (ADR-023/024). B2's cutover knob (`<home>/daemon.json`, read at boot)
 * adds DRY-RUN (compose the exact request and log it, redacted, without
 * posting) and LIVE (the daemon owns the Telegram send through the
 * transport, sharing Python's cooldown/outbox/tg-last-ph; Python keeps
 * polling getUpdates — this daemon NEVER polls, ADR-001's single
 * reader). Rollback is the knob back to shadow.
 */
// The composition root: wiring only, no logic to extract.
@Suppress("LongMethod")
fun main(args: Array<String>) {
    val cfg = DaemonConfig.parse(args.toList())
    val logger = KotlinLogging.logger("daemon")
    logger.info { "daemon starting: home=${cfg.homeDir} base=${cfg.baseDir} interval=${cfg.interval}" }
    runBlocking { transportSelftest(cfg.baseDir, logger) }

    // B2's cutover knob: read ONCE at boot (a mode change is a restart).
    val knobFile = File(cfg.homeDir, SendKnob.FILE_NAME)
    val knob = SendKnob.load(knobFile)
    if (knob.warning != null) logger.warn { "send knob $knobFile: ${knob.warning}" }
    logger.info { "send knob: mode=${knob.mode} (file=$knobFile)" }

    val stateDir = File(cfg.homeDir, "state")
    val shadowLog = ShadowLog(File(cfg.homeDir, "shadow"))
    val pythonState = File(File(cfg.baseDir, "hooks"), "state")
    val remote = { File(cfg.baseDir, "telegram-remote.json") }
    val (leg, api) = buildLeg(knob.mode, remote, pythonState, logger)
    val send = DaemonSend.of(knob.mode, leg)
    if (knob.mode == SendMode.LIVE) {
        logger.info {
            "send LIVE: tg-cooldown/outbox.json/tg-last-ph are SHARED with Python at ${pythonState.absolutePath} " +
                "— the strangler's handover files (Python still polls getUpdates; this daemon NEVER does)"
        }
    }

    val clock = { System.currentTimeMillis() / MILLIS_PER_SECOND }
    val zconnWatch = ZconnShadow(cfg.baseDir, stateDir, shadowLog, clock, pythonState, send.leg)
    val hbZconn = File(stateDir, HB_ZCONN)
    val hbSpectator = File(stateDir, HB_SPECTATOR)

    val zconn = Kap {
        withContext(MDCContext(mapOf(COMPONENT_KEY to WATCHER))) {
            zconnWatch.speak(zconnWatch.pass())
        }
    }
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
        val orchestrator = SpectatorOrchestrator(cfg.baseDir, pythonState, shadowLog, clock, this, send)
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
                api?.close() // the live leg's client — no sends outlive the daemon
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
 * B2's knob positions as wiring: SHADOW is today's no-op leg; DRY-RUN
 * composes-and-logs (no client at all — nothing can post by accident);
 * LIVE builds the full Sender with the SHARED contract files — Python's
 * own tg-cooldown (read AND write: the window is addressed to the chat,
 * never to a process), the flock-compatible outbox (a parked message is
 * flushed by whichever bot cycles next — Python's poller still does),
 * and tg-last-ph (the ll floats under the newest message, either bot's).
 */
private fun buildLeg(
    mode: SendMode,
    remote: () -> File,
    pythonState: File,
    logger: io.github.oshai.kotlinlogging.KLogger,
): Pair<SendLeg, dev.lattenero.bot.transport.TgApi?> = when (mode) {
    SendMode.SHADOW -> SendLegs.NONE to null

    SendMode.DRY_RUN -> SendLegs.dryRun(remote) to null

    SendMode.LIVE -> {
        val api = dev.lattenero.bot.transport.TgApi({ dev.lattenero.bot.transport.TgConfig.load(remote()) })
        val sender = dev.lattenero.bot.transport.Sender(
            api,
            dev.lattenero.bot.transport.Cooldown(
                readFile = { File(pythonState, "tg-cooldown") },
                writeFile = { File(pythonState, "tg-cooldown") },
            ),
            dev.lattenero.bot.transport.Outbox(File(pythonState, "outbox.json")),
            dev.lattenero.bot.transport.LastPh({ File(pythonState, "tg-last-ph") }),
        )
        logger.info { "send LIVE: Sender wired (cooldown/outbox/tg-last-ph shared, breaker + (0,1,3) retry)" }
        SendLegs.live({ cfg, text, loud -> sender.send(cfg, text, loud) }, remote) to api
    }
}

/**
 * B2a's boot proof: one read-only getMe through the REAL transport —
 * token, Ktor client, API path — with zero side effects. The daemon
 * never FAILS to start on a Telegram hiccup (the watchers are readers,
 * not senders); the line in daemon.jsonl is the artifact. The token
 * never reaches a log (TgApi redacts every message).
 */
private suspend fun transportSelftest(baseDir: File, logger: io.github.oshai.kotlinlogging.KLogger) {
    val cfg = dev.lattenero.bot.transport.TgConfig.load(File(baseDir, "telegram-remote.json"))
    if (!cfg.configured) {
        logger.warn { "transport selftest skipped: not configured (enabled/token/chat_id)" }
        return
    }
    dev.lattenero.bot.transport.TgApi({ cfg }).use { api ->
        val r = dev.lattenero.bot.transport.Selftest(api).run()
        if (r.ok) {
            logger.info { "transport selftest OK: bot=@${r.username} id=${r.botId}" }
        } else {
            logger.warn { "transport selftest FAILED: ${r.error}" }
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
