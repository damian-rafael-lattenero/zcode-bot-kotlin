package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.BotCommands
import dev.lattenero.bot.channel.ChannelClient
import dev.lattenero.bot.channel.ChannelOps
import dev.lattenero.bot.channel.ChannelQuery
import dev.lattenero.bot.channel.ChannelSpec
import dev.lattenero.bot.channel.InboundReceipt
import dev.lattenero.bot.channel.InboundUpdate
import dev.lattenero.bot.channel.LiveHub
import dev.lattenero.bot.channel.LivePublisher
import dev.lattenero.bot.channel.LiveTelegram
import dev.lattenero.bot.channel.Poller
import dev.lattenero.bot.channel.QueryAnswer
import dev.lattenero.bot.channel.ServerFrame
import dev.lattenero.bot.channel.ToolLive
import dev.lattenero.bot.channel.UnixSocketTransport
import dev.lattenero.bot.contracts.ShadowDecision
import dev.lattenero.bot.daemon.history.LIVE_VERBS
import dev.lattenero.bot.daemon.history.SEQUENCE_LINE_DIFFER
import dev.lattenero.bot.daemon.history.TasksHistoryIo
import dev.lattenero.bot.daemon.nuclear.MacProcessEdge
import dev.lattenero.bot.daemon.nuclear.NuclearLadder
import dev.lattenero.bot.daemon.queue.InboundQueue
import dev.lattenero.bot.daemon.send.DaemonSend
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
 * B3b's composition. The channel CLIENT always runs (degraded LOUD while
 * the fork's host is not deployed — the socket exists only when the fork
 * is). The LIVE VIEW runs only when the daemon owns the send (its birth
 * message and the command replies are SENDS — in shadow/dry-run Python
 * speaks and a second speaker is a duplicate). The POLLER runs only when
 * the `poll` knob is on (ADR-001's single reader — retire-python.sh is
 * the only thing that flips it). The final render rides the spectator's
 * own renderer, INJECTED so the channel module never depends on daemon.
 */
@Suppress("LongMethod") // wiring breadth: the stack's every piece, one home
internal fun wireChannel(
    cfg: DaemonConfig,
    send: DaemonSend,
    lanes: SendLanes,
    pythonState: File,
    pollOn: Boolean,
): ChannelStack {
    val hub = LiveHub()
    val tokenFile = File(cfg.baseDir, ChannelSpec.TOKEN_FILENAME)
    val socketFile = File(cfg.baseDir, ChannelSpec.SOCKET_FILENAME)
    val remote = File(cfg.baseDir, "telegram-remote.json")

    // one send path for everything the channel says: the knob's leg (shadow
    // logs, dry-run composes+logs, LIVE posts with cooldown/outbox/last-ph);
    // the markup (the buttons) rides deliverMarkup — live posts it, the
    // shadow contract stays text. This is the COMMANDS lane (the main
    // throat): replies, buttons, announces — never render volume.
    val speak: suspend (String, Boolean, JsonObject?) -> Unit = { text, loud, markup ->
        val now = System.currentTimeMillis() / 1000.0
        send.leg.deliverMarkup(
            ShadowDecision(ts = now, tsIso = TimeIso.format(now), watcher = "channel", decision = "say", text = text),
            loud,
            markup,
        )
    }

    // the RENDER lane's speak: the live view's final answer/boxes ride the
    // firehose throat — render volume never touches the commands budget
    val fireSpeak: suspend (String, Boolean, JsonObject?) -> Unit = { text, loud, markup ->
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
    val client = ChannelClient(
        transportFactory = { UnixSocketTransport { socketFile } },
        tokenReader = { tokenFile.takeIf { it.exists() }?.readText() },
        onStreamEvent = { frame ->
            feedAndResync(hub, frame, System.currentTimeMillis())
            if ((frame.event["type"] as? JsonPrimitive)?.content == "task_complete") drainQueue()
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
    )
    drainQueue = {
        val pending = queue.drain()
        if (pending.isNotEmpty()) {
            delay(QUEUE_DRAIN_DELAY) // let the session's phase settle before the next prompt
            pending.forEachIndexed { i, p ->
                val fired = client.sendInbound(p.text, "${p.messageId}-q$i", System.currentTimeMillis())
                logger.info {
                    "queue drained: ${p.messageId} (…) ${if (fired == InboundReceipt.FIRED) "fired" else "FAILED"}"
                }
            }
        }
    }

    val liveView = buildLiveView(send, lanes, hub, client, fireSpeak)
    val poller = buildPoller(cfg, lanes.api, pythonState, pollOn, hub, client, speak, remote, queue)
    return ChannelStack(client, liveView, poller, hub)
}

private const val QUEUE_FILE = "inbound-queue.json"
private val QUEUE_DRAIN_DELAY = 1.seconds

/** The never-silence view — LIVE-only (its birth message is a send), on the RENDER lane. */
private fun buildLiveView(
    send: DaemonSend,
    lanes: SendLanes,
    hub: LiveHub,
    client: ChannelClient,
    fireSpeak: suspend (String, Boolean, JsonObject?) -> Unit,
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
        speak = { text -> fireSpeak(text, false, null) },
        finalBoxes = { tools -> finalBoxesOf(tools) },
        finalAnswer = { taskId ->
            when (val answer = client.query(ChannelQuery.TaskSnapshot(taskId))) {
                is QueryAnswer.Ok -> snapshotAnswerOf(answer.resultJson)
                else -> null
            }
        },
        // the per-action verbs: the spectator's own classification, injected
        verbs = LIVE_VERBS,
    )
}

/** The single reader — knob-gated (retire-python.sh turns it on). */
private fun buildPoller(
    cfg: DaemonConfig,
    api: TgApi?,
    pythonState: File,
    pollOn: Boolean,
    hub: LiveHub,
    client: ChannelClient,
    speak: suspend (String, Boolean, JsonObject?) -> Unit,
    remote: File,
    queue: InboundQueue,
): Poller? {
    if (!pollOn) return null
    val liveApi = api ?: TgApi({ TgConfig.load(remote) })
    val history = TasksHistoryIo(cfg.baseDir)
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
            override suspend fun sendInbound(text: String, messageId: String, receivedAt: Long) =
                client.sendInbound(text, messageId, receivedAt)

            override suspend fun sendInboundAwait(text: String, messageId: String, receivedAt: Long) =
                client.sendInboundAwait(text, messageId, receivedAt)

            override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long) =
                queue.add(text, messageId, receivedAt)

            override suspend fun subscribe(taskId: String, workspacePath: String) =
                client.subscribe(taskId, workspacePath)
        },
        hub = hub,
        daemonReset = {
            hub.clear()
            client.reset() // subscriptions die with the socket — the reconnect replays nothing stale
            ladder.run().summary()
        },
        // Python's ctx_tasks + model_selection — the same two sqlite files the spectator reads
        history = history,
        defaultWorkspacePath = File(File(cfg.baseDir, "workspace"), "default").absolutePath,
    )
    return Poller(
        api = liveApi,
        offsetFile = File(pythonState, "tg-offset"), // Python's own cursor — the torch passes at retirement
        // the bot explains its own silences: a long mute window ends with the
        // apology (loud — the owner must learn WHY it was quiet, from the bot)
        onReconnected = { window -> speak(window.render(), true, null) },
        dispatch = { _, update ->
            // the buttons' dispatch: a tap is answered (the toast) and runs
            // as the owner's typed words; a message is the words themselves
            when (update) {
                is InboundUpdate.Message ->
                    commands.handle(update.text, update.updateId)?.let { speak(it.text, false, it.markup) }

                is InboundUpdate.Callback -> {
                    answerCallback(liveApi, update.callbackId)
                    when {
                        update.data.startsWith("cot:") -> hub.expandCot(expand = true)
                        update.data.startsWith("cotr:") -> hub.expandCot(expand = false)
                        else ->
                            commands.onCallback(update.data, update.updateId)
                                ?.let { speak(it.text, false, it.markup) }
                    }
                }
            }
        },
    )
}

/** The tap's toast — best-effort by design (Python's bare except; the action itself already ran). */
@Suppress("TooGenericExceptionCaught") // Python's bare except: a failed toast never fails the tap
private suspend fun answerCallback(api: TgApi, callbackId: String) {
    try {
        api.call("answerCallbackQuery", buildJsonObject { put("callback_query_id", callbackId) })
    } catch (e: Exception) {
        logger.warn(e) { "answerCallbackQuery failed (the tap's action still ran): ${e.message}" }
    }
}

/** stopAll's answer → how many runs it stopped (the ladder's step-1 evidence). */
internal fun stoppedCountOf(resultJson: String): Int = runCatching {
    ((Json.parseToJsonElement(resultJson) as? JsonObject)?.get("stopped") as? JsonArray)?.size ?: 0
}.getOrDefault(0)

/** The final render's boxes: the spectator's renderer, one box per completed tool. */
internal fun finalBoxesOf(tools: List<ToolLive>): String =
    tools.filter { it.input != null }
        .takeLast(FINAL_BOXES)
        .joinToString("\n") { tool ->
            val mark = when (tool.status) {
                "failed" -> " ❌"
                "denied", "stopped" -> " ⏹️"
                else -> " ✅"
            }
            ToolBox.box(tool.name, tool.input ?: JsonObject(emptyMap())).replaceFirst("</b>", "</b>$mark")
        }

/** taskSnapshot → the last ASSISTANT message (the turn's answer, from the store). */
internal fun snapshotAnswerOf(resultJson: String): String? = runCatching {
    val root = Json.parseToJsonElement(resultJson) as? JsonObject ?: return null
    val messages = root["messages"] as? JsonArray ?: return null
    messages.asSequence()
        .mapNotNull { it as? JsonObject }
        .filter { (it["role"] as? JsonPrimitive)?.content == "assistant" }
        .lastOrNull()
        ?.let { (it["content"] as? JsonPrimitive)?.content }
        ?.takeIf { it.isNotBlank() }
}.getOrNull()

private const val FINAL_BOXES = 6
