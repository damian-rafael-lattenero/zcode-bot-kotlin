package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.BotCommands
import dev.lattenero.bot.channel.ChannelClient
import dev.lattenero.bot.channel.ChannelOps
import dev.lattenero.bot.channel.ChannelQuery
import dev.lattenero.bot.channel.ChannelSpec
import dev.lattenero.bot.channel.LiveHub
import dev.lattenero.bot.channel.LivePublisher
import dev.lattenero.bot.channel.LiveTelegram
import dev.lattenero.bot.channel.Poller
import dev.lattenero.bot.channel.QueryAnswer
import dev.lattenero.bot.channel.ServerFrame
import dev.lattenero.bot.channel.ToolLive
import dev.lattenero.bot.channel.UnixSocketTransport
import dev.lattenero.bot.contracts.ShadowDecision
import dev.lattenero.bot.daemon.send.DaemonSend
import dev.lattenero.bot.daemon.shadow.TimeIso
import dev.lattenero.bot.daemon.spectator.ToolBox
import dev.lattenero.bot.transport.Cooldown
import dev.lattenero.bot.transport.LastPh
import dev.lattenero.bot.transport.TgApi
import dev.lattenero.bot.transport.TgConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

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
    hub.apply(frame, nowMs)
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
internal fun wireChannel(
    cfg: DaemonConfig,
    send: DaemonSend,
    api: TgApi?,
    pythonState: File,
    pollOn: Boolean,
): ChannelStack {
    val hub = LiveHub()
    val tokenFile = File(cfg.baseDir, ChannelSpec.TOKEN_FILENAME)
    val socketFile = File(cfg.baseDir, ChannelSpec.SOCKET_FILENAME)
    val remote = File(cfg.baseDir, "telegram-remote.json")

    // one send path for everything the channel says: the knob's leg (shadow
    // logs, dry-run composes+logs, LIVE posts with cooldown/outbox/last-ph)
    val speak: suspend (String, Boolean) -> Unit = { text, loud ->
        val now = System.currentTimeMillis() / 1000.0
        send.leg.deliver(
            ShadowDecision(ts = now, tsIso = TimeIso.format(now), watcher = "channel", decision = "say", text = text),
            loud,
        )
    }

    // the client's stream callback feeds the hub directly; the resync after a
    // reconnect re-baselines the view (the lost events stay lost — no replay)
    val client = ChannelClient(
        transportFactory = { UnixSocketTransport { socketFile } },
        tokenReader = { tokenFile.takeIf { it.exists() }?.readText() },
        onStreamEvent = { frame -> feedAndResync(hub, frame, System.currentTimeMillis()) },
        onInboundResult = { result ->
            when {
                result.duplicate -> logger.info { "inbound ${result.id}: DUPLICATE (server dedupe) — not re-sent" }
                result.replies.isEmpty() -> logger.info { "inbound ${result.id}: accepted (no system replies)" }
                else -> result.replies.forEach { reply -> speak(reply, false) }
            }
        },
        onResubscribed = { taskId ->
            logger.info { "channel resync after reconnect: $taskId re-baselined (lost events stay lost)" }
            hub.resync(taskId)
        },
    )

    val liveView = buildLiveView(send, api, pythonState, hub, client, speak, remote)
    val poller = buildPoller(api, pythonState, pollOn, hub, client, speak, remote)
    return ChannelStack(client, liveView, poller, hub)
}

/** The never-silence view — LIVE-only (its birth message is a send). */
private fun buildLiveView(
    send: DaemonSend,
    api: TgApi?,
    pythonState: File,
    hub: LiveHub,
    client: ChannelClient,
    speak: suspend (String, Boolean) -> Unit,
    remote: File,
): LivePublisher? {
    if (!send.ownsSpectator || api == null) return null
    val telegram = LiveTelegram(
        api = api,
        cooldown = Cooldown(
            readFile = { File(pythonState, "tg-cooldown") },
            writeFile = { File(pythonState, "tg-cooldown") },
        ),
        lastPh = LastPh({ File(pythonState, "tg-last-ph") }),
    )
    return LivePublisher(
        hub = hub,
        telegram = telegram,
        config = { TgConfig.load(remote) },
        channelAlive = { client.connected }, // 💓 vs 📡 in the status line: "channel healthy" vs "channel dead"
        speak = { text -> speak(text, false) },
        finalBoxes = { tools -> finalBoxesOf(tools) },
        finalAnswer = { taskId ->
            when (val answer = client.query(ChannelQuery.TaskSnapshot(taskId))) {
                is QueryAnswer.Ok -> snapshotAnswerOf(answer.resultJson)
                else -> null
            }
        },
    )
}

/** The single reader — knob-gated (retire-python.sh turns it on). */
private fun buildPoller(
    api: TgApi?,
    pythonState: File,
    pollOn: Boolean,
    hub: LiveHub,
    client: ChannelClient,
    speak: suspend (String, Boolean) -> Unit,
    remote: File,
): Poller? {
    if (!pollOn) return null
    val liveApi = api ?: TgApi({ TgConfig.load(remote) })
    val commands = BotCommands(
        ops = object : ChannelOps {
            override suspend fun query(query: ChannelQuery) = client.query(query)
            override suspend fun sendInbound(text: String, messageId: String, receivedAt: Long) =
                client.sendInbound(text, messageId, receivedAt)

            override suspend fun subscribe(taskId: String, workspacePath: String) =
                client.subscribe(taskId, workspacePath)
        },
        hub = hub,
        daemonReset = {
            hub.clear()
            client.reset()
        },
    )
    return Poller(
        api = liveApi,
        offsetFile = File(pythonState, "tg-offset"), // Python's own cursor — the torch passes at retirement
        dispatch = { _, update ->
            val reply = commands.handle(update.text, update.updateId)
            if (reply != null) speak(reply, false)
        },
    )
}

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
