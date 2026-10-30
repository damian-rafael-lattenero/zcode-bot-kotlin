package dev.lattenero.bot.daemon.send

import dev.lattenero.bot.transport.Cooldown
import dev.lattenero.bot.transport.LastPh
import dev.lattenero.bot.transport.Outbox
import dev.lattenero.bot.transport.Sender
import dev.lattenero.bot.transport.TgApi
import dev.lattenero.bot.transport.TgConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * THE FIREHOSE LANE (the flood post-mortem's structural fix, 29/10):
 * Telegram's rate budget and its flood-penalty MEMORY are per BOT — one
 * shared token let the spectator's render volume mute /stop for 11h.
 * Two throats, one chat:
 *
 * - the COMMANDS lane (zconn notices, /tasks, buttons, confirms,
 *   status, the nuclear announces) — the main token, the SHARED contract
 *   files (tg-cooldown / outbox.json / tg-last-ph), Python's own;
 * - the FIREHOSE lane (the spectator's turns, the live view's renders,
 *   the cajitas) — the firehose token, its OWN files, so a render flood
 *   parks in its own outbox and can never mute a command.
 *
 * Without `firehose_token` ([TgConfig.firehoseSplit] false) the lane is
 * the MAIN lane itself — one leg, one set of files, the pre-lane
 * behavior byte for byte.
 */

/** What the LIVE VIEW rides: the lane's client, files and speak leg. */
internal class LiveLane(
    val api: TgApi,
    val config: () -> TgConfig,
    val cooldown: Cooldown,
    val lastPh: LastPh,
    val leg: SendLeg,
)

/** Both lanes, wired — plus the outbox drain that keeps every park honest. */
internal class SendLanes(
    /** The commands lane: zconn, the eight commands' replies, the announces. */
    val leg: SendLeg,
    /** The render lane: the spectator's sends and the live view's speaks. */
    val firehose: SendLeg,
    /** The commands lane's client (the poller, selftests, callback toasts). */
    val api: TgApi?,
    /** The live view's lane (null when the live view does not run). */
    val live: LiveLane?,
    /** Drains BOTH outboxes — the paced flush (1 msg/s, stops at a window). */
    val flush: suspend () -> Unit,
)

/** The lanes' file names — the contract the coordinator's split named. */
internal object LaneFiles {
    const val COOLDOWN = "tg-cooldown"
    const val OUTBOX = "outbox.json"
    const val LAST_PH = "tg-last-ph"
    const val FIRE_COOLDOWN = "tg-cooldown-firehose"
    const val FIRE_OUTBOX = "outbox-firehose.json"
    const val FIRE_LAST_PH = "tg-last-ph-firehose"
}

/**
 * Wires the lanes. The split is read ONCE at boot (the knob doctrine) and
 * LOUD — a mode change is a restart, like the send knob. In SHADOW and
 * DRY-RUN the firehose lane is the knob's own leg (dry-run logs the
 * lane's REAL url — the redacted firehose token — without posting).
 */
internal fun buildLanes(
    mode: SendMode,
    remote: () -> File,
    pythonState: File,
): SendLanes = when (mode) {
    SendMode.SHADOW -> SendLanes(
        leg = SendLegs.NONE,
        firehose = SendLegs.NONE,
        api = null,
        live = null,
        flush = {},
    )

    SendMode.DRY_RUN -> SendLanes(
        leg = SendLegs.dryRun(remote),
        firehose = SendLegs.dryRun(remote) { it.firehose },
        api = null,
        live = null,
        flush = {},
    )

    SendMode.LIVE -> liveLanes(remote, pythonState)
}

private fun liveLanes(remote: () -> File, pythonState: File): SendLanes {
    val mainApi = TgApi({ TgConfig.load(remote()) })
    val mainCooldown = cooldownOf(pythonState, LaneFiles.COOLDOWN)
    val mainSender = Sender(
        mainApi,
        mainCooldown,
        Outbox(File(pythonState, LaneFiles.OUTBOX)),
        LastPh({ File(pythonState, LaneFiles.LAST_PH) }),
    )
    val mainLeg = SendLegs.live(
        { cfg, text, loud, markup -> mainSender.send(cfg, text, loud, markup) },
        remote,
    )
    val flushMain: suspend () -> Unit = { mainSender.outboxFlush(TgConfig.load(remote())) }
    val split = TgConfig.load(remote()).firehoseSplit
    logger.info {
        if (split) {
            "firehose lane: SPLIT — render traffic posts through the firehose bot " +
                "(own ${LaneFiles.FIRE_COOLDOWN}/${LaneFiles.FIRE_OUTBOX}); commands keep the main throat"
        } else {
            "firehose lane: OFF (no firehose_token) — one throat, the shared files, exactly as before"
        }
    }
    if (!split) {
        // identity: no lane, no second throat — the pre-split behavior byte for byte
        val mainLive = LiveLane(
            api = mainApi,
            config = { TgConfig.load(remote()) },
            cooldown = mainCooldown,
            lastPh = LastPh({ File(pythonState, LaneFiles.LAST_PH) }),
            leg = mainLeg,
        )
        return SendLanes(mainLeg, mainLeg, mainApi, mainLive, flushMain)
    }
    return splitLanes(remote, pythonState, mainApi, mainLeg, flushMain)
}

/** The split: the firehose throat — own api, own files, own leg; the live view rides it. */
private fun splitLanes(
    remote: () -> File,
    pythonState: File,
    mainApi: TgApi,
    mainLeg: SendLeg,
    flushMain: suspend () -> Unit,
): SendLanes {
    val fireApi = TgApi({ TgConfig.load(remote()).firehose })
    val fireCooldown = cooldownOf(pythonState, LaneFiles.FIRE_COOLDOWN)
    val fireSender = Sender(
        fireApi,
        fireCooldown,
        Outbox(File(pythonState, LaneFiles.FIRE_OUTBOX)),
        LastPh({ File(pythonState, LaneFiles.FIRE_LAST_PH) }),
    )
    val firehoseLeg = SendLegs.live(
        { cfg, text, loud, markup -> fireSender.send(cfg.firehose, text, loud, markup) },
        remote,
    )
    val fireLane = LiveLane(
        api = fireApi,
        config = { TgConfig.load(remote()).firehose },
        cooldown = fireCooldown,
        lastPh = LastPh({ File(pythonState, LaneFiles.FIRE_LAST_PH) }),
        leg = firehoseLeg,
    )
    return SendLanes(
        leg = mainLeg,
        firehose = firehoseLeg,
        api = mainApi,
        live = fireLane,
        flush = {
            flushMain()
            fireSender.outboxFlush(TgConfig.load(remote()).firehose)
        },
    )
}

private fun cooldownOf(pythonState: File, name: String): Cooldown = Cooldown(
    readFile = { File(pythonState, name) },
    writeFile = { File(pythonState, name) },
)
