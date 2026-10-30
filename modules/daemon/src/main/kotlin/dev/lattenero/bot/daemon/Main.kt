package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.BotCommands
import dev.lattenero.bot.daemon.send.DaemonSend
import dev.lattenero.bot.daemon.send.LiveLane
import dev.lattenero.bot.daemon.send.SendKnob
import dev.lattenero.bot.daemon.send.SendLanes
import dev.lattenero.bot.daemon.send.buildLanes
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
@Suppress("LongMethod", "CyclomaticComplexMethod") // wiring breadth, not logic depth
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

    // B3b's poll knob: SHIPPED OFF — Python owns getUpdates until the
    // retirement (ADR-001's single reader; retire-python.sh flips this).
    val pollKnob = PollKnob.load(knobFile)
    if (pollKnob.warning != null) logger.warn { "poll knob $knobFile: ${pollKnob.warning}" }
    logger.info {
        val who = if (pollKnob.on) "this daemon OWNS getUpdates" else "Python keeps the phone's reads"
        "poll knob: on=${pollKnob.on} ($who)"
    }

    val stateDir = File(cfg.homeDir, "state")
    val shadowLog = ShadowLog(File(cfg.homeDir, "shadow"))
    val pythonState = File(File(cfg.baseDir, "hooks"), "state")
    val remote = { File(cfg.baseDir, "telegram-remote.json") }
    // BOTH throats, one chat: the commands lane and (when the config says
    // so) the firehose lane — the spectator/live-view renders ride their
    // OWN token+files, so a render flood can never mute a command
    val lanes = buildLanes(knob.mode, remote, pythonState)
    val send = DaemonSend.of(knob.mode, lanes.leg, lanes.firehose)
    val (leg, api) = lanes.leg to lanes.api
    if (knob.mode == SendMode.LIVE) {
        logger.info {
            "send LIVE: two-throat wiring — commands on the shared tg-cooldown/outbox.json, " +
                "renders on ${if (lanes.live?.leg === lanes.leg) "the SAME throat (no firehose_token)" else "the firehose throat (own files)"}"
        }
    }

    // B3b: the channel stack (client always; live view when the daemon owns
    // the send; poller only at the retirement knob)
    val stack = wireChannel(cfg, send, lanes, pythonState, pollKnob.on)

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

        // B3b: the channel client (degraded LOUD without the fork's host),
        // its keepalive, the live view (never-silence; LIVE sends only),
        // and — only when retire-python.sh flipped the knob — the poller
        val hbChannel = File(stateDir, HB_CHANNEL)
        val channelHandle = stack.client.asProcess(onHeartbeat = { touch(hbChannel) }).startIn(this)
        val pingHandle = stack.client.pingProcess().startIn(this)
        val liveHandle = stack.liveView?.asProcess(onHeartbeat = { touch(File(stateDir, HB_LIVE_VIEW)) })?.startIn(this)
        val pollerHandle = stack.poller
            ?.asProcess(
                config = { dev.lattenero.bot.transport.TgConfig.load(File(cfg.baseDir, "telegram-remote.json")) },
                onHeartbeat = { touch(File(stateDir, HB_POLLER)) },
            )
            ?.startIn(this)
        logger.info {
            val view = if (liveHandle != null) "on" else "off"
            val poll = if (pollerHandle != null) "ON" else "off"
            "channel wired: client=on socket=${File(cfg.baseDir, "daemon-local.sock").path} " +
                "liveView=$view poller=$poll"
        }

        // Python's register_bot_menu: with the poller ON the daemon owns the
        // phone, so the «/» menu must be OUR eight — never Python's leftovers
        if (pollerHandle != null) {
            launch { registerTelegramMenu(cfg, api, logger) }
        }

        // the outbox drain (Python's poller used to do this; retired with it):
        // BOTH lanes' piles flush on their own pace — 1 msg/s, stopped at a
        // window — so a parked message is a DELAY, never a loss
        val outboxDrain = Kap { lanes.flush() }
            .repeat(Schedule.spaced(OUTBOX_DRAIN_EVERY))
            .process(
                name = "outbox-drain",
                restart = RestartPolicy.exponential(base = 1.seconds),
                onRestart = { attempt, error, delay ->
                    logger.warn(error) { "outbox drain failed — restart $attempt in $delay" }
                },
            )
            .startIn(this)

        // the firehose lane's boot probe: a fresh bot that was never /started
        // answers 403 on every send — diagnosable from the FIRST minutes
        if (knob.mode == SendMode.LIVE && lanes.live?.leg === lanes.firehose && lanes.firehose !== lanes.leg) {
            launch { firehoseProbe(lanes.live!!, logger) }
        }

        val root = coroutineContext[Job]
        Runtime.getRuntime().addShutdownHook(
            Thread {
                zconnHandle.stop()
                spectatorHandle.stop()
                outboxDrain.stop()
                pollerHandle?.stop()
                liveHandle?.stop()
                pingHandle.stop()
                channelHandle.stop()
                stack.client.close() // unblocks the session's read so the process can end
                api?.close() // the live leg's client — no sends outlive the daemon
                root?.cancel() // the per-turn spectators must not hang the exit
            },
        )
        try {
            coroutineScope {
                listOf(
                    zconnHandle to WATCHER,
                    spectatorHandle to "spectator-orchestrator",
                    channelHandle to "channel",
                ).forEach { (h, name) ->
                    launch {
                        try {
                            h.await()
                        } catch (e: ProcessStoppedException) {
                            logger.info(e) { "$name stopped — shutting down" }
                        } catch (e: ProcessExhaustedException) {
                            when (name) {
                                // the channel is DEGRADED-BY-DESIGN when the fork's
                                // host is down: exhaustion is a loud log line, never
                                // the death of the daemon that still owns the SENDS
                                "channel" -> logger.error(e) {
                                    "channel exhausted — degraded (sends/watchers keep flowing)"
                                }

                                else -> {
                                    logger.error(e) { "$name exhausted — exiting 1 so launchd restarts the ring" }
                                    exitProcess(1)
                                }
                            }
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
 * Python's register_bot_menu: with the poller ON the daemon owns the
 * phone, so Telegram's «/» menu must be OUR eight — never Python's
 * leftovers. Best-effort: a Telegram hiccup logs a line and moves on.
 */
private suspend fun registerTelegramMenu(
    cfg: DaemonConfig,
    api: dev.lattenero.bot.transport.TgApi?,
    logger: io.github.oshai.kotlinlogging.KLogger,
) {
    val tgCfg = dev.lattenero.bot.transport.TgConfig.load(File(cfg.baseDir, "telegram-remote.json"))
    val menuApi = api ?: dev.lattenero.bot.transport.TgApi({ tgCfg })
    val ok = dev.lattenero.bot.channel.BotMenu.register(menuApi, tgCfg)
    logger.info {
        "telegram menu ${if (ok) "registered" else "FAILED to register"}: the eight " +
            "(/tasks /all_tasks /status /stop /nuclear /quota /subagents /help) — " +
            "${BotCommands.MENU.size} commands"
    }
    if (api == null) menuApi.close()
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

/**
 * The firehose lane's boot probe: a bot the owner never /started answers
 * 403 to EVERY send — the LOUD warn makes the cause readable in the first
 * minutes instead of a pile of parked renders.
 */
@Suppress("TooGenericExceptionCaught", "ReturnCount") // a probe never raises: the lane fails loud, the daemon lives
private suspend fun firehoseProbe(
    lane: dev.lattenero.bot.daemon.send.LiveLane,
    logger: io.github.oshai.kotlinlogging.KLogger,
) {
    val cfg = lane.config()
    if (!cfg.configured) return
    try {
        lane.api.call(
            "getChat",
            kotlinx.serialization.json.buildJsonObject { put("chat_id", cfg.chatId) },
        )
        logger.info { "firehose lane probe OK — the firehose bot talks to the chat" }
    } catch (e: dev.lattenero.bot.transport.TgHttpException) {
        if (e.code == NOT_STARTED_403) {
            logger.warn {
                "firehose lane rejected (403) — did the owner /start the firehose bot? " +
                    "Every render send will park in outbox-firehose.json until then"
            }
        } else {
            logger.warn { "firehose lane probe failed: ${e.message}" }
        }
    } catch (e: Exception) {
        logger.warn { "firehose lane probe failed: ${e.message}" }
    }
}

private const val HB_ZCONN = "hb-zconn-shadow"

/** Telegram's 403: the bot was never /started by the chat's user. */
private const val NOT_STARTED_403 = 403
private const val HB_SPECTATOR = "hb-spectator"
private const val HB_CHANNEL = "hb-channel"
private const val HB_LIVE_VIEW = "hb-live-view"
private const val HB_POLLER = "hb-poller"
private const val COMPONENT_KEY = "component"
private val DEFAULT_HEARTBEAT_EVERY = 30.seconds
private val SPECTATOR_SCAN = 2.seconds
private val OUTBOX_DRAIN_EVERY = 15.seconds
private const val MILLIS_PER_SECOND = 1000.0
