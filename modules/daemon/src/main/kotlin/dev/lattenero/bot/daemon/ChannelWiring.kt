@file:Suppress("TooManyFunctions") // the wiring's breadth: the stack's every piece + the mirror's register leg

package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.Ack
import dev.lattenero.bot.channel.BotCommands
import dev.lattenero.bot.channel.BotReply
import dev.lattenero.bot.channel.BrainCommands
import dev.lattenero.bot.channel.ChannelClient
import dev.lattenero.bot.channel.DeployCommand
import dev.lattenero.bot.channel.ChannelOps
import dev.lattenero.bot.channel.ChannelQuery
import dev.lattenero.bot.channel.ChannelSpec
import dev.lattenero.bot.channel.ClientFrame
import dev.lattenero.bot.channel.ConfigOptionsUpdate
import dev.lattenero.bot.channel.GiftsMenu
import dev.lattenero.bot.channel.InboundAttachment
import dev.lattenero.bot.channel.InboundAnswer
import dev.lattenero.bot.channel.InboundReceipt
import dev.lattenero.bot.channel.InboundUpdate
import dev.lattenero.bot.channel.LastPrompt
import dev.lattenero.bot.channel.LiveHub
import dev.lattenero.bot.channel.LiveOrphanStore
import dev.lattenero.bot.channel.LivePublisher
import dev.lattenero.bot.channel.LiveTelegram
import dev.lattenero.bot.channel.MenuMirrorRefresher
import dev.lattenero.bot.channel.ModeMenu
import dev.lattenero.bot.channel.PlanMenu
import dev.lattenero.bot.channel.Poller
import dev.lattenero.bot.channel.OpencodeQuota
import dev.lattenero.bot.channel.QuotaDirect
import dev.lattenero.bot.channel.QueryAnswer
import dev.lattenero.bot.channel.SealFacts
import dev.lattenero.bot.channel.ServerFrame
import dev.lattenero.bot.channel.TasksHistory
import dev.lattenero.bot.channel.ToolLive
import dev.lattenero.bot.channel.UnixSocketTransport
import dev.lattenero.bot.channel.QueuedText
import dev.lattenero.bot.channel.QuestionBoard
import dev.lattenero.bot.channel.VerbositySetting
import dev.lattenero.bot.contracts.PyText
import dev.lattenero.bot.contracts.ShadowDecision
import dev.lattenero.bot.daemon.bus.BusProducer
import dev.lattenero.bot.daemon.bus.BusSink
import dev.lattenero.bot.daemon.bus.PromptTap
import dev.lattenero.bot.daemon.bus.activePromptContext
import dev.lattenero.bot.daemon.brain.BrainEndpoint
import dev.lattenero.bot.daemon.brain.MemoryInjector
import dev.lattenero.bot.daemon.brain.brainGateway
import dev.lattenero.bot.daemon.history.LIVE_VERBS
import dev.lattenero.bot.daemon.history.SEQUENCE_LINE_DIFFER
import dev.lattenero.bot.daemon.history.TasksHistoryIo
import dev.lattenero.bot.daemon.metrics.Metrics
import dev.lattenero.bot.daemon.metrics.NoopMetrics
import dev.lattenero.bot.daemon.metrics.TurnMetricsTap
import dev.lattenero.bot.daemon.otel.TurnSpanTracker
import dev.lattenero.bot.daemon.notifications.NotificationsWire
import dev.lattenero.bot.daemon.notifications.SubagentNotifs
import dev.lattenero.bot.daemon.nuclear.MacProcessEdge
import dev.lattenero.bot.daemon.nuclear.NuclearLadder
import dev.lattenero.bot.daemon.queue.InboundQueue
import dev.lattenero.bot.daemon.send.DaemonSend
import dev.lattenero.bot.daemon.send.LaneFiles
import dev.lattenero.bot.daemon.send.SendKnob
import dev.lattenero.bot.daemon.send.SendLanes
import dev.lattenero.bot.daemon.shadow.TimeIso
import dev.lattenero.bot.daemon.spectator.ToolBox
import dev.lattenero.bot.transport.Cooldown
import dev.lattenero.bot.transport.LastPh
import dev.lattenero.bot.transport.TgApi
import dev.lattenero.bot.transport.TgConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/** What [wireChannel] answers: the built pieces Main starts as Processes. */
internal class ChannelStack(
    val client: ChannelClient,
    val liveView: LivePublisher?,
    val poller: Poller?,
    val hub: LiveHub,
    /** U1b's producer — null with the `event_bus` knob off (zero I/O). */
    val bus: BusProducer? = null,
    /** 🔔 the subagent watcher — null with the `notifications` knob off (zero I/O). */
    val notifs: SubagentNotifs? = null,
)

/**
 * The seq-gap rule (§3.3): `seq` is monotonic per connection — a jump
 * means events were lost in a reconnect, and the stream NEVER replays.
 * The live view re-baselines (the lost CoT stays lost); the turn's final
 * answer is safe — it comes from the taskSnapshot at task_complete.
 */
internal suspend fun feedAndResync(hub: LiveHub, frame: ServerFrame.StreamEvent, nowMs: Long) {
    val before = hub.peek()
    val lastSeq = before?.takeIf { it.taskId == frame.taskId }?.lastSeq
    if (lastSeq != null && frame.seq > lastSeq + 1) {
        logger.warn {
            "channel seq GAP ${before.lastSeq} -> ${frame.seq} on ${frame.taskId}: events lost in the " +
                "reconnect (continuous, no replay) — the live view re-baselines"
        }
        hub.resync(frame.taskId)
    }
    hub.apply(frame, nowMs, SEQUENCE_LINE_DIFFER)
}



/**
 * /quota's DIRECT rung, wired — PROVIDER-AWARE (owner's 3/11: «estoy
 * usando la api de open code… me gustaría que me muestre los límites
 * disponibles de opencode go»). The active conversation's providerId
 * decides the meter: an OpenCode provider gets its OWN `/usage` windows
 * ([OpencodeQuota] — the app's provider_config.json carries the key),
 * anything else keeps the z.ai monitor endpoint (Python's _quota_api_key
 * path: config.providerConfigRules.providerRules[] → config.access, type
 * zhipu-coding-plan-api-key). Both files share the rules' shape, so the
 * legacy hook snapshot stays as the fallback reader. Every failure — no
 * file, no key, dead network, bad shape — is the rung's honest null and
 * the commands' ladder falls through; a THROWN one is caught there too.
 * The keys never log: they ride the fetch's header only.
 */
private fun quotaDirectRung(cfg: DaemonConfig): suspend (String?) -> String? = { providerId ->
    val opencodeJson = providerConfigText(cfg, OPENCODE_CONFIG_FILES)
    val opencode = providerId != null &&
        opencodeJson()?.let { OpencodeQuota.isOpenCodeProvider(it, providerId) } == true
    if (opencode) {
        OpencodeQuota.report(
            providerJson = opencodeJson,
            providerId = providerId,
            fetch = { url, token -> quotaBearerGet(url, token, headers = OPENCODE_HEADERS, service = "opencode") },
        )
    } else {
        QuotaDirect.report(
            providerJson = providerConfigText(cfg, ZAI_CONFIG_FILES),
            fetch = { url, token -> zaiQuotaGet(url, token) },
        )
    }
}

/** The provider config's reader: the first existing candidate file, its text (or null). */
private fun providerConfigText(cfg: DaemonConfig, names: List<String>): () -> String? = {
    names.asSequence()
        .mapNotNull { name ->
            runCatching { File(cfg.baseDir, name).takeIf { it.isFile }?.readText() }.getOrNull()
        }
        .firstOrNull()
}

/** OpenCode's live config first (3/11), the hook's legacy snapshot after. */
private val OPENCODE_CONFIG_FILES = listOf("v2/provider_config.json", "hooks/bot-provider.json")

/** z.ai's coding-plan key lives in the hook's snapshot; the live config is the fallback. */
private val ZAI_CONFIG_FILES = listOf("hooks/bot-provider.json", "v2/provider_config.json")

/**
 * OpenCode's edge sits behind Cloudflare (error 1010 without a
 * browser-ish signature — measured 3/11): the app's own UA/version
 * headers are the pass. Nothing secret rides here — the key goes in the
 * Authorization header, never in these values.
 */
private val OPENCODE_HEADERS = mapOf(
    "User-Agent" to "ZCode/3.12.1",
    "X-ZCode-App-Version" to "3.12.1",
)



/**
 * B3b's composition. The channel CLIENT always runs (degraded LOUD while
 * the fork's host is not deployed — the socket exists only when the fork
 * is). The LIVE VIEW runs only when the daemon owns the send (its birth
 * message and the command replies are SENDS — in shadow/dry-run Python
 * speaks and a second speaker is a duplicate). The POLLER runs only when
 * the `poll` knob is on (ADR-001's single reader — retire-python.nu is
 * the only thing that flips it). The final render rides the spectator's
 * own renderer, INJECTED so the channel module never depends on daemon.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // wiring breadth: the stack's every piece, one home
internal fun wireChannel(
    cfg: DaemonConfig,
    send: DaemonSend,
    lanes: SendLanes,
    pythonState: File,
    pollOn: Boolean,
    /** U1b: the `event_bus` knob, read once at boot — OFF means the producer is never built. */
    eventBusOn: Boolean = false,
    /** U3: the `memory_inject` knob — OFF means the send path never touches the brain. */
    memoryInjectOn: Boolean = false,
    /** E2: the `metrics` knob's facade — the no-op when OFF (zero cost, byte-identical daemon). */
    metrics: Metrics = NoopMetrics,
    /** 🔔 the owner's key-moment notifications — read once at boot in Main; OFF builds nothing. */
    notificationsOn: Boolean = false,
    /** E3: the span tracker — null with the `otel` knob off (zero cost, byte-identical daemon). */
    otel: TurnSpanTracker? = null,
): ChannelStack {
    val hub = LiveHub()
    val tokenFile = File(cfg.baseDir, ChannelSpec.TOKEN_FILENAME)
    val socketFile = File(cfg.baseDir, ChannelSpec.SOCKET_FILENAME)
    val remote = File(cfg.baseDir, "telegram-remote.json")

    // THE BOARD'S OWN STORE (the restart's memory, measured 4/11): one JSON
    // in the daemon's own state dir — after a restart the old cards keep
    // working and the fork's re-announcements dedupe into them. LastPrompt's
    // exact construction pattern.
    val questionStore = FileQuestionBoardStore(File(File(cfg.homeDir, "state"), QUESTION_BOARD_FILE))

    // THE QUESTIONS' throat (the answer round-trip): the commands lane's
    // OWN files — the chat the owner's taps already ride. A hanging
    // question is critical: the live-view law applies (the cooldown never
    // gates it, a 429 widens the window). Without a live api (SHADOW /
    // DRY-RUN) the board still REGISTERS — the stream's laws hold, only
    // the phone's message is missing. Declared BEFORE the client: the
    // stream callback below captures it.
    val board: QuestionBoard = lanes.api?.let { api ->
        val questionTelegram = LiveTelegram(
            api = api,
            cooldown = Cooldown(readFile = { File(pythonState, LaneFiles.COOLDOWN).takeIf { it.exists() } }),
        )
        QuestionBoard(
            sendQuestion = { text, markup -> questionTelegram.ask(TgConfig.load(remote), text, markup) ?: 0L },
            editQuestion = { mid, text, markup -> questionTelegram.edit(TgConfig.load(remote), mid, text, markup) },
            store = questionStore,
        )
    } ?: QuestionBoard(store = questionStore)

    // THE OPEN MENU MIRROR — declared before the client (its stream
    // callback captures the holder, the busTaps pattern): assigned right
    // after the history exists. Null (no live api) = the honest nothing —
    // the commands' own live re-read keeps working, nothing edits.
    var mirrorRefresh: MenuMirrorRefresher? = null

    // one send path for everything the channel says: the knob's leg (shadow
    // logs, dry-run composes+logs, LIVE posts with cooldown/outbox/last-ph);
    // the markup (the buttons) rides deliverMarkup — live posts it, the
    // shadow contract stays text. This is the COMMANDS lane (the main
    // throat): replies, buttons, announces — never render volume. The BORN
    // message's id comes back (null = nothing flew) — the OPEN MENU MIRROR
    // registers the /mode and /plan_model_effort menus so they can
    // self-refresh on the fork's config_option_update.
    val speak: suspend (String, Boolean, JsonObject?) -> Long? = { text, loud, markup ->
        val now = System.currentTimeMillis() / 1000.0
        send.leg.deliverMarkup(
            ShadowDecision(ts = now, tsIso = TimeIso.format(now), watcher = "channel", decision = "say", text = text),
            loud,
            markup,
        )
    }

    // the RENDER lane's speak: the live view's final answer/boxes ride the
    // firehose throat — render volume never touches the commands budget.
    // The message id comes back (the collapsed CoT blocks register it —
    // the tap's edit target); parked/null when the send didn't fly.
    val fireSpeak: suspend (String, Boolean, JsonObject?) -> Long? = { text, loud, markup ->
        val now = System.currentTimeMillis() / 1000.0
        send.firehose.deliverMarkup(
            ShadowDecision(
                ts = now,
                tsIso = TimeIso.format(now),
                watcher = "channel-live",
                decision = "say",
                text = text,
            ),
            loud,
            markup,
        )
    }

    // the client's stream callback feeds the hub directly; the resync after a
    // reconnect re-baselines the view (the lost events stay lost — no replay)
    // F17 live: a finished turn (task_complete) drains the dormant queue —
    // the texts that arrived mid-turn fire as inbounds now
    val queue = InboundQueue(File(File(cfg.homeDir, "state"), QUEUE_FILE))
    var drainQueue: suspend () -> Unit = {}
    // U1b: the producer is assigned right after the client exists (its
    // final-answer read is a channel query) — the callback reads the holder.
    var busTaps: BusProducer? = null
    // E2: the turn tap — built ONLY when the facade measures (the busTaps
    // pattern); it counts the same frames the producer reads, never double-
    // counting the sink (whose written/failures stay the one bus truth).
    val turnTaps = if (metrics.measures) TurnMetricsTap(metrics) else null
    val client = ChannelClient(
        transportFactory = { UnixSocketTransport { socketFile } },
        tokenReader = { tokenFile.takeIf { it.exists() }?.readText() },
        onStreamEvent = { frame ->
            feedAndResync(hub, frame, System.currentTimeMillis())
            // ADR-019: the turn's lifecycle rides the same frames the live
            // view folds — the tap never changes what the bot says or does
            busTaps?.onFrame(frame)
            turnTaps?.onFrame(frame)
            // E3: the span tree derives from the SAME real events — one
            // owner per tap point, the channel module never sees a span
            otel?.onFrame(frame)
            // THE ANSWER ROUND-TRIP: the stream carries the questions AND
            // their resolutions — the board messages the owner on the
            // former (dedupe inside), seals honestly on the latter (the
            // tap, the app's own answer, or the turn's own end).
            board.onStreamEvent(frame)
            when ((frame.event["type"] as? JsonPrimitive)?.content) {
                // LA LEY DEL SELLO (medida en vivo 31/10): task_complete NO mata
                // preguntas — un turno cancelado/moved-on deja la interacción
                // VIVA en el broker del CLI (la pregunta del dueño se respondió
                // por la app DESPUÉS del cancel), y el elicitation_response que
                // llega al resolverse sella solo. task_error SÍ: el broker murió
                // con el turno.
                "task_complete" -> {
                    drainQueue()
                    // the turn's seal is the mirror's second expiry: the open
                    // menu of THIS task dies with it (the TTL is the first)
                    mirrorRefresh?.seal(frame.taskId)
                }

                "task_error" ->
                    board.resolveTask(frame.taskId, "⌛ El turno terminó — la pregunta quedó sin responder.")

                "permission_response", "elicitation_response" ->
                    (frame.event["requestId"] as? JsonPrimitive)?.content?.let {
                        board.resolveByRequestId(it, "✅ Respondido desde la app.")
                    }

                // EL ESPEJO DE LOS MENUS (the fork's config_option_update,
                // additive — it does not exist on the wire until the fork
                // deploy ships it): a config write ANYWHERE (the Mac, another
                // tap) is the open /mode or /plan_model_effort menu's refresh
                // trigger. The payload is only the trigger — the fresh state
                // is re-READ live; a foreign shape is IGNORED (one log line,
                // the additive rule).
                ConfigOptionsUpdate.TYPE -> {
                    val options = ConfigOptionsUpdate.parse(frame.event)
                    if (options == null) {
                        logger.info {
                            "config_option_update ${frame.taskId.takeLast(SID_TAIL)}: foreign shape — ignored"
                        }
                    } else {
                        mirrorRefresh?.onConfigUpdate(frame.taskId, options)
                    }
                }
            }
        },
        onInboundResult = { result ->
            when {
                result.duplicate -> logger.info { "inbound ${result.id}: DUPLICATE (server dedupe) — not re-sent" }
                result.replies.isEmpty() -> logger.info { "inbound ${result.id}: accepted (no system replies)" }
                else -> result.replies.forEach { reply -> speak(reply, false, null) }
            }
        },
        onResubscribed = { taskId ->
            logger.info { "channel resync after reconnect: $taskId re-baselined (lost events stay lost)" }
            hub.resync(taskId)
        },
        // THE HEARTBEAT'S SEQ ACCOUNTING: the keepalive consumes the
        // connection's counter — without this every quiet stretch reads as
        // a lost-event gap and the resync wipes the accumulating CoT
        // (measured live 31/10: the blocks never reached 3000). The
        // future watchdog keys HERE: a missing heartbeat is a dead bus.
        onKeepalive = { frame -> hub.keepalive(frame.taskId, frame.seq) },
    )

    // U1b — the bus producer (ADR-019): OFF by default (the knob is read at
    // boot in Main; OFF means this block does not run, not even a file stat).
    // The final answer is the SAME snapshot read the live view speaks at the
    // seal: the daemon DOES see the assistant's text — nothing is invented.
    if (eventBusOn) {
        val sink = BusSink(
            File(File(cfg.baseDir, "agent-brain"), BUS_FILE),
            log = { message -> logger.warn { message } },
        )
        // E2: the sink's own counters ARE the bus truth — the gauges read
        // them at scrape time, no second count anywhere
        metrics.busGauges(written = { sink.written }, failures = { sink.failures })
        busTaps = BusProducer(
            sink = sink,
            finalAnswer = { taskId ->
                finalAnswerOf(
                    taskId,
                    query = { q -> client.query(q) },
                    workspaceOf = { sid -> workspacePathOf(cfg.baseDir, sid) },
                    log = { message -> logger.warn { message } },
                )
            },
            log = { message -> logger.warn { message } },
        )
        logger.info { "event_bus ON — appending to ${File(File(cfg.baseDir, "agent-brain"), BUS_FILE)}" }
    }
    // U2 — /brain + /memory: agent-brain's localhost HTTP (no auth). The
    // endpoint is `brain_url` in the same daemon.json (read once; absent =
    // agent-brain's own 8765). The commands render the reading list; the
    // transport lives here, never in the channel module.
    val brainEndpoint = BrainEndpoint.load(File(cfg.homeDir, SendKnob.FILE_NAME))
    if (brainEndpoint.warning != null) logger.warn { "brain endpoint: ${brainEndpoint.warning}" }
    val gateway = brainGateway(brainEndpoint, metrics = metrics)
    val brain = BrainCommands(gateway)
    logger.info { "brain commands wired: ${brainEndpoint.url}" }

    // U3 — memory_inject (ADR-020): the knob is read at boot in Main. OFF =
    // no injector and the send path is byte-for-byte the pre-U1b one.
    val injector = if (memoryInjectOn) {
        logger.info { "memory_inject ON — top-k ${MemoryInjector.TOP_K}, deadline ${MemoryInjector.DEFAULT_TIMEOUT}" }
        MemoryInjector(gateway.memorySearch, log = { message -> logger.info { message } }, metrics = metrics)
    } else {
        null
    }
    val promptTap = PromptTap(busTaps, { activePromptContext(client) }, injector)

    // 🔔 THE OWNER'S KEY-MOMENT NOTIFICATIONS — one guarded block, the
    // merge stays a one-line conflict at worst. The subagent watcher rides
    // the stack as its own Process; the seal tap rides the live view's OWN
    // seal (the facts the card already computed — nothing re-queried).
    // OFF = both null and the daemon is byte-for-byte the pre-feature one.
    val notifs = NotificationsWire.build(cfg, send, notificationsOn)

    drainQueue = {
        val pending = queue.drain()
        if (pending.isNotEmpty()) {
            delay(QUEUE_DRAIN_DELAY) // let the session's phase settle before the next prompt
            pending.forEachIndexed { i, p ->
                val token = promptTap.begin(p.text)
                val outbound = promptTap.prepare(p.text)
                val fired = client.sendInbound(outbound, "${p.messageId}-q$i", System.currentTimeMillis())
                if (fired == InboundReceipt.FIRED) promptTap.fired(token) else promptTap.dropped(token)
                logger.info {
                    "queue drained: ${p.messageId} (…) ${if (fired == InboundReceipt.FIRED) "fired" else "FAILED"}"
                }
            }
        }
    }

    // the history reader, built ONCE (the queue's pattern): both consumers
    // share it — the poller's commands AND the live view's title resolution
    val history: TasksHistory = TasksHistoryIo(cfg.baseDir)

    // THE MIRROR'S THROAT (the board's exact pattern): the commands lane's
    // api + the SHARED cooldown file — a throttled edit skips itself, never
    // retry-spams. The reads ride the channel client (the SAME queries the
    // commands use: the one-message taskSnapshot for the mode, the
    // modelSelection for the plan). The edit answers false when nothing
    // flew; the refresher folds that honestly (the baseline does not move).
    // The draft's source binds AFTER the commands exist (buildPoller below).
    if (lanes.api != null) {
        val mirrorTelegram = LiveTelegram(
            api = lanes.api,
            cooldown = Cooldown(readFile = { File(pythonState, LaneFiles.COOLDOWN).takeIf { it.exists() } }),
        )
        mirrorRefresh = MenuMirrorRefresher(
            readMode = { taskId, ws ->
                (client.query(ChannelQuery.TaskSnapshot(taskId, ws, messageLimit = 1)) as? QueryAnswer.Ok)
                    ?.let { ModeMenu.currentFrom(it.resultJson) }
            },
            readPlan = { ws ->
                (client.query(ChannelQuery.ModelSelection(ws)) as? QueryAnswer.Ok)
                    ?.let { PlanMenu.parse(it.resultJson) }
            },
            titleOf = { taskId ->
                runCatching {
                    history.ctxTasks(BotCommands.SCOPE_FOR_PICK).firstOrNull { r -> r.sid == taskId }?.title
                }.getOrNull()?.takeIf { it.isNotEmpty() }
            },
            edit = { _, messageId, text, markup ->
                // the mirror's chat IS the bot's own chat (cfg.chatId — the
                // recorded chatId is the registry's data; this throat has one)
                mirrorTelegram.edit(TgConfig.load(remote), messageId, text, markup)
            },
        )
    }

    // B6 — /deploy: the pure machine + THE DOOR's flow, built ONCE (the
    // arm outlives the updates that touch it). The announce rides the
    // commands lane's leg and its message id is the door's receipt: no
    // announce, no spawn. The spawn is the REAL edge — its OWN one-shot
    // launchd job (measured 2/11 22:02: nohup+ProcessBuilder DIED at the
    // script's own bootout of this daemon; a sibling job survives it).
    // 2026-11-04: a SECOND edge for the fork deploy — its own label, its
    // own log, its own plist; a BOTH deploy spawns fork FIRST because a
    // sibling job survives the daemon deploy's own bootout.
    val deployLog = File(File(cfg.homeDir, "logs"), DeploySpawn.LOG_FILE_NAME)
    val deployEdge = DeploySpawn(
        script = DeployScriptKnob.load(File(cfg.homeDir, SendKnob.FILE_NAME)),
        logFile = deployLog,
        plistFile = File(File(cfg.homeDir, "state"), DeploySpawn.JOB_PLIST_NAME),
    )
    val forkDeployLog = File(File(cfg.homeDir, "logs"), DeploySpawn.FORK_LOG_FILE_NAME)
    val forkDeployEdge = DeploySpawn(
        script = DeployScriptKnob.loadFork(File(cfg.homeDir, SendKnob.FILE_NAME)),
        logFile = forkDeployLog,
        plistFile = File(File(cfg.homeDir, "state"), DeploySpawn.FORK_JOB_PLIST_NAME),
        // THE MIRROR (4/11): the fork button runs the SAME script+args as the
        // shell's `zcode-deploy` — build-install --when-quit. The build speaks
        // its phases to Telegram; the swap waits for the owner's ⌘Q (the app
        // is never killed behind his back; the channel only drops at the
        // swap). The two-step confirm is the safety.
        args = listOf("--when-quit"),
        label = DeploySpawn.FORK_JOB_LABEL,
    )
    val deployFlow = DeployFlow(
        machine = DeployCommand(),
        say = { text, loud, markup ->
            val now = System.currentTimeMillis() / 1000.0
            send.leg.deliverMarkup(
                ShadowDecision(
                    ts = now,
                    tsIso = TimeIso.format(now),
                    watcher = "channel",
                    decision = "say",
                    text = text,
                ),
                loud,
                markup,
            )
        },
        spawn = { target ->
            // BOTH never arrives here: the FLOW splits it (fork first — the
            // survival order is the flow's law, pinned by its test). This
            // edge only maps ATOMIC targets to their own sibling job.
            when (target) {
                DeployCommand.Target.BOT -> {
                    deployEdge.spawn()
                    logger.warn {
                        "deploy(bot): ${DeploySpawn.JOB_LABEL} bootstrapped as its OWN job " +
                            "(survives this daemon's bootout) — log: $deployLog"
                    }
                }
                DeployCommand.Target.FORK -> {
                    forkDeployEdge.spawn()
                    logger.warn {
                        "deploy(fork): ${DeploySpawn.FORK_JOB_LABEL} bootstrapped as its OWN job " +
                            "(sibling of the daemon; this daemon SURVIVES it) — log: $forkDeployLog"
                    }
                }
                DeployCommand.Target.BOTH -> throw IllegalStateException(
                    "deploy(BOTH) must be split by the flow (fork first) — the wiring only spawns atomic targets",
                )
            }
        },
        ownerUserId = { TgConfig.load(remote).ownerUserId },
    )

    // the /verbosity level's store, built ONCE, SHARED: the commands WRITE
    // it, the publisher READS it per tick — one instance, one truth (the
    // file-backed store: the owner's choice survives the restarts)
    val verbosity: VerbositySetting = FileVerbositySetting(File(File(cfg.homeDir, "state"), VERBOSITY_FILE))

    // Python's F22: /retry's ammo — the LAST FIRED prompt, one JSON in the
    // daemon's OWN state dir (the queue's and the verbosity store's exact
    // pattern: ~/.zcode/bot-kotlin/state, one file, one law)
    val lastPrompt = LastPrompt(File(File(cfg.homeDir, "state"), LAST_PROMPT_FILE))


    val liveView = buildLiveView(
        send,
        lanes,
        hub,
        client,
        fireSpeak,
        File(File(cfg.homeDir, "state"), LIVE_ORPHAN_FILE),
        history,
        verbosity,
        workspaceOf = { sid -> workspacePathOf(cfg.baseDir, sid) },
        onSeal = notifs?.onSeal ?: NotificationsWire.noSeal,
    )
    val poller = buildPoller(
        cfg,
        lanes.api,
        pythonState,
        pollOn,
        hub,
        client,
        speak,
        remote,
        queue,
        history,
        verbosity,
        deployFlow = deployFlow,
        lastPrompt = lastPrompt,
        // THE BOARD'S OWN THROAT: the wired board SENT the cards — the taps
        // must consult THE SAME instance. This argument was lost in the
        // 10afea7/376e564 merge and every tap hit the default-EMPTY board:
        // 100% of question taps answered «ya no está pendiente» (measured
        // 4/11 all day — my AskUserQuestions and the plan approvals ride
        // these very cards)
        board = board,
        liveView = liveView,
        promptTap = promptTap,
        brain = brain,
        metrics = metrics,
        mirror = mirrorRefresh,
    )
    return ChannelStack(client, liveView, poller, hub, busTaps, notifs?.watcher)
}

private const val QUEUE_FILE = "inbound-queue.json"
private const val ATTACHMENTS_DIR = "attachments"
private const val LIVE_ORPHAN_FILE = "live-view-active.json"

/** ADR-019's append-only file (agent-brain's FileTailer owns the read side). */
private const val BUS_FILE = "bus-events.jsonl"

/** The /verbosity level's persisted line (FileVerbositySetting's file). */
private const val VERBOSITY_FILE = "verbosity.txt"

/** /retry's ammo (Python's F22) — the last FIRED prompt's own file (LastPrompt). */
private const val LAST_PROMPT_FILE = "last-prompt.json"

/** The question board's persisted registry (the restart's memory, FileQuestionBoardStore's file). */
private const val QUESTION_BOARD_FILE = "question-board.json"
private val QUEUE_DRAIN_DELAY = 1.seconds

/**
 * The never-silence view — LIVE-only (its birth message is a send), on
 * the RENDER lane. [orphanFile] is the boot-time ORPHAN SEAL's pointer
 * (the publisher's first tick edits a frozen pre-restart status message
 * once and dies — a restart never leaves a lying "thinking..." behind).
 * [history] is the REAL TITLE's source: the stream's
 * session_info_update title never arrives for some sessions and the
 * card wore the sid's tail — the resolution window is the SAME constant
 * the commands resolve picks against (no drift).
 */
private fun buildLiveView(
    send: DaemonSend,
    lanes: SendLanes,
    hub: LiveHub,
    client: ChannelClient,
    fireSpeak: suspend (String, Boolean, JsonObject?) -> Long?,
    orphanFile: File,
    history: TasksHistory,
    verbosity: VerbositySetting,
    workspaceOf: suspend (String) -> String?,
    /** 🔔 the seal tap — the notifications' hook on the card's own seal. */
    onSeal: suspend (SealFacts) -> Unit = NotificationsWire.noSeal,
): LivePublisher? {
    val lane = lanes.live
    if (lane == null || !send.ownsSpectator) return null
    val telegram = LiveTelegram(
        api = lane.api,
        cooldown = lane.cooldown,
        lastPh = lane.lastPh,
    )
    return LivePublisher(
        hub = hub,
        telegram = telegram,
        config = lane.config,
        channelAlive = { client.connected }, // 💓 vs 📡 in the status line: "channel healthy" vs "channel dead"
        resubscribe = { taskId ->
            // the stream watchdog's tooth: the fork's continuous bus dies
            // mid-run (measured 30/10 twice) — re-issue the subscription with
            // the bot conversation's CURRENT workspace
            val ws = when (val answer = client.query(ChannelQuery.BotStatus)) {
                is QueryAnswer.Ok ->
                    ((Json.parseToJsonElement(answer.resultJson) as? JsonObject)
                        ?.get("context") as? JsonObject)
                        ?.get("workspacePath")?.let { it as? JsonPrimitive }?.content
                else -> null
            }
            if (ws != null) client.subscribe(taskId, ws)
        },
        speak = { text, markup -> fireSpeak(text, false, markup) },
        // the transcript feed's per-tool CLOSE box: the spectator renderer
        // (the final transcript's own), status mark inside the head + the
        // VERBATIM output tail (the owner's «leer los commands de bash»)
        toolBox = { tool -> feedBoxOf(tool) },
        finalAnswer = { taskId ->
            finalAnswerOf(
                taskId,
                query = { q -> client.query(q) },
                workspaceOf = workspaceOf,
                log = { message -> logger.warn { message } },
            )
        },
        // the per-action verbs: the spectator's own classification, injected
        verbs = LIVE_VERBS,
        // the card's head: the history's honest name for the live task
        // (null → the fallback stays — nothing invented)
        resolveTitle = { taskId ->
            history.ctxTasks(BotCommands.SCOPE_FOR_PICK).firstOrNull { it.sid == taskId }?.title
        },
        orphanStore = LiveOrphanStore(orphanFile),
        // the transcript feed's level — the SAME store the commands write
        verbosity = verbosity,
        // THE PRIZE LINE (the owner, 3/11): one cached read of the coding
        // plan's gifts; the publisher renders «Premios» while a turn runs,
        // /quota keeps the detail and the taps. Channel down → null.
        gifts = {
            when (val answer = client.query(ChannelQuery.CodingPlanGifts())) {
                is QueryAnswer.Ok -> GiftsMenu.parse(answer.resultJson, System.currentTimeMillis())
                else -> null
            }
        },
        onSeal = onSeal,
    )
}

/** The single reader — knob-gated (retire-python.nu turns it on). */
@Suppress("LongMethod", "CyclomaticComplexMethod") // wiring breadth: the reader's every piece, one home
private fun buildPoller(
    cfg: DaemonConfig,
    api: TgApi?,
    pythonState: File,
    pollOn: Boolean,
    hub: LiveHub,
    client: ChannelClient,
    speak: suspend (String, Boolean, JsonObject?) -> Long?,
    remote: File,
    queue: InboundQueue,
    history: TasksHistory,
    // the /verbosity store (the commands WRITE it — the publisher reads it
    // per tick) and the questions' board — REQUIRED, no default: the lost
    // argument in the 10afea7/376e564 merge silently default-constructed an
    // EMPTY board and 100% of the question taps answered «ya no está
    // pendiente» (measured 4/11). A missing board must fail the COMPILE,
    // never the owner's tap.
    verbosity: VerbositySetting,
    board: QuestionBoard,
    // /retry's ammo (Python's F22) — the last FIRED prompt's store
    lastPrompt: LastPrompt,
    // B6 — /deploy's flow (the door): the intercepts below hand it the
    // /deploy texts and the dep: taps BEFORE the dispatcher ever sees them
    deployFlow: DeployFlow,
    // the collapsed CoT blocks' taps (the cb: buttons) — the live view IS
    // the registry (it spoke the messages and holds the ids + texts)
    liveView: LivePublisher? = null,
    // U1b: the free-text prompt seam (ADR-019) — a no-op with the bus off
    promptTap: PromptTap = PromptTap(null),
    // U2: /brain + /memory (the brain's HTTP door). Null = the honest unwired reply.
    brain: BrainCommands? = null,
    // E2: the metrics facade (the no-op when off) — the poller's cycles and
    // the owner's accepted updates, counted at the wiring
    metrics: Metrics = NoopMetrics,
    // THE OPEN MENU MIRROR (null when the live api is missing): the dispatch
    // registers the born /mode and /plan_model_effort menus into it
    mirror: MenuMirrorRefresher? = null,
): Poller? {
    if (!pollOn) return null
    val liveApi = api ?: TgApi({ TgConfig.load(remote) })
    // ☢️ the nuclear ladder — the daemon-side restart of EVERYTHING (the
    // owner's order overrides the old «never kill the app» rule; the
    // channel protocol stays clean — this speaks macOS directly)
    val ladder = NuclearLadder(
        edge = MacProcessEdge(),
        stopAll = {
            when (val answer = client.query(ChannelQuery.StopAll(null), timeout = 10.seconds)) {
                is QueryAnswer.Ok -> stoppedCountOf(answer.resultJson)
                else -> 0
            }
        },
        helloOk = { client.connected },
        announce = { text -> speak(text, true, null) },
    )
        val commands = BotCommands(
            ops = object : ChannelOps {
                override suspend fun query(query: ChannelQuery) = client.query(query)
                override suspend fun sendInbound(
                    text: String,
                    messageId: String,
                    receivedAt: Long,
                    attachments: List<InboundAttachment>,
                ): InboundReceipt {
                    val token = promptTap.begin(text)
                    val outbound = promptTap.prepare(text)
                    val receipt = client.sendInbound(outbound, messageId, receivedAt, attachments = attachments)
                    if (receipt == InboundReceipt.FIRED) promptTap.fired(token) else promptTap.dropped(token)
                    return receipt
                }

                // the ✍️ armed order: direct to the named session (the fork's
                // inbound taskId), AWAITED — the fork's rejection is the reply
                // (fire-and-forget said «entregada» to a sessionNotFound, 2/11)
                override suspend fun sendOrder(
                    text: String,
                    messageId: String,
                    receivedAt: Long,
                    taskId: String,
                ): InboundAnswer {
                    val token = promptTap.begin(text, knownSid = taskId)
                    val outbound = promptTap.prepare(text)
                    val answer = client.sendOrderAwait(outbound, messageId, receivedAt, taskId)
                    if (answer is InboundAnswer.Answered) promptTap.fired(token) else promptTap.dropped(token)
                    return answer
                }

                // STEER: the running turn's redirection (the fork's `steer` frame, awaited)
                override suspend fun steer(taskId: String, text: String) = client.steer(taskId, text)

                // LA RESPUESTA del dueño: the fork's respond frame, awaited —
                // the host's own truth comes back as the tap's answer
                override suspend fun respondQuestion(frame: ClientFrame.Respond) = client.respond(frame)

                override suspend fun sendInboundAwait(
                    text: String,
                    messageId: String,
                    receivedAt: Long,
                    bindTaskId: String?,
                ): InboundAnswer {
                    val token = promptTap.begin(text, knownSid = bindTaskId)
                    val outbound = promptTap.prepare(text)
                    val answer = client.sendInboundAwait(outbound, messageId, receivedAt, bindTaskId)
                    if (answer is InboundAnswer.Answered) promptTap.fired(token) else promptTap.dropped(token)
                    return answer
                }

                // EL BIND ATÓMICO (the fork's cap «bind», d8510c23): solo con
                // el cap anunciado — un servidor viejo ignoraría el campo y el
                // texto caería en la conversación equivocada
                override fun supportsBind(): Boolean = client.speaks("bind")

                override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long) =
                    queue.add(text, messageId, receivedAt)

                override suspend fun queueEntries(): List<QueuedText> =
                    queue.entries().map { QueuedText(it.text, it.messageId, it.receivedAt) }

                override suspend fun queueTake(messageId: String): QueuedText? =
                    queue.take(messageId)?.let { QueuedText(it.text, it.messageId, it.receivedAt) }

                override suspend fun subscribe(taskId: String, workspacePath: String) =
                    client.subscribe(taskId, workspacePath)
            },
        hub = hub,
        daemonReset = {
            // The cross-restart single-flight (the 08:42 loop's lesson: each
            // extra ☢️ tap ran a FULL ladder, and the ladder's own kickstart
            // resets any in-memory gate): the gate file's MTIME is the clock
            // — a start younger than the debounce is a double-trigger,
            // honestly refused; a crash leaves it stale and the next pass.
            val gate = File(pythonState, NUCLEAR_GATE)
            val ageMs = System.currentTimeMillis() - gate.lastModified()
            if (gate.exists() && ageMs in 0 until NUCLEAR_DEBOUNCE_MS) {
                "REFUSED: otro nuclear arrancó hace ${ageMs / 1000}s — esperá su resumen " +
                    "(single-flight; sobrevive al kickstart del propio nuclear)"
            } else {
                gate.writeText("started") // the write IS the mtime stamp
                hub.clear()
                client.reset() // subscriptions die with the socket — the reconnect replays nothing stale
                ladder.run().summary()
            }
        },
        // Python's ctx_tasks + model_selection — the same two sqlite files the spectator reads
        history = history,
        defaultWorkspacePath = File(File(cfg.baseDir, "workspace"), "default").absolutePath,
        verbosity = verbosity,
        board = board,
        quotaDirect = quotaDirectRung(cfg),
        blockTaps = liveView,
        // the collapsed outputs' pager (the same publisher owns both
        // registries: the tap edits the message it spoke)
        boxTaps = liveView,
        lastPrompt = lastPrompt,
        brain = brain,
        // THE OWNER'S ID — the SAME config read /deploy's door makes
        // (`TgConfig.load(remote).ownerUserId`): the destructive taps'
        // second lock reads the config at the tap, one truth, no copy.
        ownerUserId = { TgConfig.load(remote).ownerUserId },
    )
    // THE MIRROR'S DRAFT SOURCE (the late bind — the refresher was born
    // before the commands): the commands own the draft and its TTL law
    mirror?.bindDraft { commands.openDraft() }
    return Poller(
        api = liveApi,
        offsetFile = File(pythonState, "tg-offset"), // Python's own cursor — the torch passes at retirement
        // the bot explains its own silences: a long mute window ends with the
        // apology (loud — the owner must learn WHY it was quiet, from the bot)
        onReconnected = { window -> speak(window.render(), true, null) },
        // U9/F1: a cursor that was never live (fresh state / poisoned file)
        // means the fetched backlog was CONSUMED, not replayed — a silent
        // drop would be a lie by omission, so the owner hears it
        onCursorLost = { skipped -> speak(cursorLostText(skipped), true, null) },
        // E2: one completed pass of the reader — whatever it fetched
        onCycle = { metrics.pollerCycle() },
        dispatch = { cfg, update ->
            // the buttons' dispatch: a tap is answered (the toast) and runs
            // as the owner's typed words; a message is the words themselves
            when (update) {
                is InboundUpdate.Message -> {
                    metrics.pollerInbound()
                    // F23: 👀 the second the words are in, ✅ once handled —
                    // feedback on the owner's own message, zero new messages
                    Ack.react(liveApi, cfg, update.messageId, Ack.RECEIVED)
                    // FILES OUT + /diff (30/10): the two commands that need
                    // the wiring's own throats (the multipart upload; the
                    // snapshot query + the mini-diff render) — intercepted
                    // BEFORE the text dispatcher, which never sees them.
                    // B6: /deploy joins the pattern — the anti-suicide door
                    // lives HERE, never in the free-text router.
                    dispatchMessage(liveApi, cfg, client, history, commands, speak, deployFlow, mirror, update)
                    Ack.react(liveApi, cfg, update.messageId, Ack.HANDLED)
                }

                is InboundUpdate.Attachment -> {
                    metrics.pollerInbound()
                    dispatchAttachment(liveApi, cfg, pythonState, client, speak, prompts = promptTap, update = update)
                }

                is InboundUpdate.Callback -> {
                    metrics.pollerCallback()
                    answerCallback(liveApi, update.callbackId)
                    when {
                        // B6 — the deploy taps: intercepted BEFORE the
                        // callback fallback (the sst: law: a stray data must
                        // never ride to the agent as the owner's own words —
                        // «/deploy go» launching a build would be the graver bug)
                        update.data.startsWith("dep:") ->
                            deployFlow.onTap(update.data.removePrefix("dep:"), update.fromId)

                        else -> commands.onCallback(update.data, update.updateId, update.fromId)
                            ?.let { reply -> speakReply(mirror, cfg.chatId, reply, speak) }
                    }
                }
            }
        },
    )
}

/**
 * ATTACHMENTS IN (30/10): the owner's photo/document — downloaded with the
 * bot's own token into the daemon's state, then the inbound frame rides it
 * (localPath; the caption is the prompt). Honest per outcome: too big → the
 * 20 MB law named; the download failing → the error said; the channel down
 * → downloaded-but-NOT-delivered; the busy conversation → the server's own
 * refusal (the F17 queue is TEXT-only by design).
 */
@Suppress("TooGenericExceptionCaught") // the download leg: any failure speaks honestly, never crashes the poller
private suspend fun dispatchAttachment(
    api: TgApi,
    cfg: TgConfig,
    pythonState: File,
    client: ChannelClient,
    speak: suspend (String, Boolean, JsonObject?) -> Long?,
    prompts: PromptTap,
    update: InboundUpdate.Attachment,
) {
    Ack.react(api, cfg, update.messageId, Ack.RECEIVED)
    val refusal = attachmentRefusal(update.sizeBytes)
    if (refusal != null) {
        logger.info { "attachment ${update.updateId}: REFUSED — ${update.filename} ${update.sizeBytes ?: 0} bytes" }
        speak(refusal, false, null)
        Ack.react(api, cfg, update.messageId, Ack.HANDLED)
        return
    }
    try {
        val dir = File(pythonState, ATTACHMENTS_DIR).apply { mkdirs() }
        val target = File(dir, "${update.updateId}-${attachmentFilename(update.filename)}")
        val bytes = api.downloadFile(update.fileId, target)
        val att = InboundAttachment(
            id = "att-${update.updateId}",
            kind = update.kind,
            filename = update.filename,
            mimeType = update.mimeType,
            sizeBytes = bytes,
            localPath = target.absolutePath,
        )
        val token = prompts.begin(update.caption)
        val outbound = prompts.prepare(update.caption)
        val fired = client.sendInbound(
            outbound,
            "tg-${update.updateId}-att",
            System.currentTimeMillis(),
            attachments = listOf(att),
        )
        if (fired == InboundReceipt.FIRED) prompts.fired(token) else prompts.dropped(token)
        logger.info {
            "attachment ${update.updateId}: ${update.filename} ($bytes bytes) " +
                "${if (fired == InboundReceipt.FIRED) "delivered" else "NOT delivered (channel down)"}"
        }
        if (fired != InboundReceipt.FIRED) {
            speak(
                "📡 The channel to the app is down — «${update.filename}» was downloaded but " +
                    "NOT delivered; retry in a moment.",
                false,
                null,
            )
        }
    } catch (e: Exception) {
        logger.warn(e) { "attachment ${update.updateId} failed: ${e.message}" }
        speak("⚠️ No pude bajar «${update.filename}» (${e.message}) — mandalo de nuevo.", false, null)
    }
    Ack.react(api, cfg, update.messageId, Ack.HANDLED)
}


/** The nuclear gate's file (mtime = the last start) and its double-trigger window. */
private const val NUCLEAR_GATE = "nuclear-last"
private const val NUCLEAR_DEBOUNCE_MS = 180_000L

/**
 * THE MIRROR'S REGISTRATION LEG: speak the reply and, when it carries the
 * open-menu mark AND the send flew with an id, register THAT message —
 * the fork's config_option_update will edit it in place from now on. A
 * parked/failed send (null id, the shadow/dry-run legs) registers nothing:
 * there is no message to mirror, the honest nothing.
 */
private suspend fun speakReply(
    mirror: MenuMirrorRefresher?,
    chatId: JsonElement,
    reply: BotReply,
    speak: suspend (String, Boolean, JsonObject?) -> Long?,
) {
    val messageId = speak(reply.text, false, reply.markup)
    val mark = reply.mirror
    if (mark != null && messageId != null) {
        mirror?.noteMenu(mark.taskId, mark, messageId, chatId, reply.text)
    }
}

/**
 * The free-text dispatch: /deploy, /file and /diff take the wiring's own
 * throats (B6's anti-suicide door; the multipart upload; the snapshot
 * query + the mini-diff render) — everything else is the dispatcher's
 * (the commands, then the route). The deploy flow speaks through its OWN
 * say (the door's receipt is the announce's message id), so it returns
 * nothing here.
 */
@Suppress("LongParameterList") // the wiring's breadth: every throat the dispatch may need
private suspend fun dispatchMessage(
    api: TgApi,
    cfg: TgConfig,
    client: ChannelClient,
    history: TasksHistory,
    commands: BotCommands,
    speak: suspend (String, Boolean, JsonObject?) -> Long?,
    deployFlow: DeployFlow,
    mirror: MenuMirrorRefresher?,
    update: InboundUpdate.Message,
) {
    when {
        update.text == "/deploy" || update.text.startsWith("/deploy ") ->
            deployFlow.onText(update.text.removePrefix("/deploy"), update.fromId)

        update.text.startsWith("/file") ->
            speak(filesOut(api, cfg, update.text.removePrefix("/file")), false, null)

        update.text == "/diff" || update.text.startsWith("/diff ") ->
            speak(diffNow(client, history, update.text.removePrefix("/diff")), false, null)

        else -> commands.handle(update.text, update.updateId)
            ?.let { reply -> speakReply(mirror, cfg.chatId, reply, speak) }
    }
}

/**
 * FILES OUT (30/10): /file <ruta-absoluta> — the file flies as a Telegram
 * DOCUMENT (the transport's multipart sendDocument leg). The plan's honest
 * ladder: not absolute → said; missing → said; a directory → said; over
 * 20 MB → the cap named. The upload failing speaks its error. The bot's
 * own chat is the destination (the owner's phone).
 */
@Suppress("TooGenericExceptionCaught") // the upload leg: any failure speaks honestly, never crashes the poller
private suspend fun filesOut(api: TgApi, cfg: TgConfig, rest: String): String {
    val plan = FilesCommands.planFileSend(rest)
    val file = plan.file ?: return plan.refusal ?: "🤷 /file: nada que mandar"
    return try {
        api.upload(
            "sendDocument",
            mapOf("chat_id" to cfg.chatId.toString().trim('"')),
            "document",
            file,
        )
        logger.info { "file OUT: ${file.absolutePath} (${file.length()} bytes) → sendDocument" }
        "📎 <code>${file.name}</code> (${file.length() / BYTES_K}K) salió como documento."
    } catch (e: Exception) {
        logger.warn(e) { "file OUT failed: ${file.absolutePath}: ${e.message}" }
        "⚠️ No pude mandar «${file.name}»: ${e.message}"
    }
}

/**
 * /diff — the CURRENT turn's file changes, straight from the taskSnapshot's
 * own fileChanges (the fork's fileChangeTracker serialized form). Bare =
 * the last turn; `/diff all` = the whole session. The reply rides the
 * commands lane (text only): big files point at /file.
 */
@Suppress("ReturnCount") // the honesty ladder: down, no conversation, answered, failed — one exit each
private suspend fun diffNow(client: ChannelClient, history: TasksHistory, rest: String): String {
    val ctx = when (val answer = client.query(ChannelQuery.BotStatus)) {
        is QueryAnswer.Ok -> (runCatching {
            Json.parseToJsonElement(answer.resultJson) as? JsonObject
        }.getOrNull()?.get("context") as? JsonObject) ?: return DIFF_DOWN
        else -> return DIFF_DOWN
    }
    val taskId = (ctx.get("activeTaskId") as? JsonPrimitive)?.content
        ?: return "📭 No active conversation — /tasks n picks one; /diff reads the files it changed."
    val ws = (ctx.get("workspacePath") as? JsonPrimitive)?.content
    return when (val answer = client.query(ChannelQuery.TaskSnapshot(taskId, ws))) {
        is QueryAnswer.Ok -> {
            val all = rest.trim().equals("all", ignoreCase = true)
            val files = FilesCommands.changedFilesOf(answer.resultJson, all)
            val title = runCatching { history.ctxTasks(BotCommands.SCOPE_FOR_PICK) }.getOrDefault(emptyList())
                .firstOrNull { it.sid == taskId }?.title?.takeIf { it.isNotEmpty() }
                ?: taskId.takeLast(SID_TAIL)
            "📄 «$title»\n" + FilesCommands.renderDiff(files, all, DIFF_REPLY_CAP)
        }

        is QueryAnswer.Unavailable -> DIFF_DOWN

        is QueryAnswer.Failed -> "⚠️ /diff falló (${answer.code}): ${answer.message ?: "?"}"
    }
}

private const val DIFF_DOWN =
    "📡 The channel to the app is down — /diff needs the conversation's snapshot."

private const val DIFF_REPLY_CAP = 3500
private const val BYTES_K = 1024L
private const val SID_TAIL = 8

/** The tap's toast — best-effort by design (Python's bare except; the action itself already ran).
 *  [text] is the toast's honest words when the tap promises something (the ⏸️ park's ack). */
@Suppress("TooGenericExceptionCaught") // Python's bare except: a failed toast never fails the tap
private suspend fun answerCallback(api: TgApi, callbackId: String, text: String? = null) {
    try {
        api.call(
            "answerCallbackQuery",
            buildJsonObject {
                put("callback_query_id", callbackId)
                if (text != null) put("text", text)
            },
        )
    } catch (e: Exception) {
        logger.warn(e) { "answerCallbackQuery failed (the tap's action still ran): ${e.message}" }
    }
}

/** stopAll's answer → how many runs it stopped (the ladder's step-1 evidence). */
