package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.Ack
import dev.lattenero.bot.channel.BotCommands
import dev.lattenero.bot.channel.ChannelClient
import dev.lattenero.bot.channel.ChannelOps
import dev.lattenero.bot.channel.ChannelQuery
import dev.lattenero.bot.channel.ChannelSpec
import dev.lattenero.bot.channel.InboundAttachment
import dev.lattenero.bot.channel.InboundReceipt
import dev.lattenero.bot.channel.InboundUpdate
import dev.lattenero.bot.channel.LiveHub
import dev.lattenero.bot.channel.LiveOrphanStore
import dev.lattenero.bot.channel.LivePublisher
import dev.lattenero.bot.channel.LiveTelegram
import dev.lattenero.bot.channel.Poller
import dev.lattenero.bot.channel.QueryAnswer
import dev.lattenero.bot.channel.ServerFrame
import dev.lattenero.bot.channel.TasksHistory
import dev.lattenero.bot.channel.ToolLive
import dev.lattenero.bot.channel.UnixSocketTransport
import dev.lattenero.bot.channel.QueuedText
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

    // the history reader, built ONCE (the queue's pattern): both consumers
    // share it — the poller's commands AND the live view's title resolution
    val history: TasksHistory = TasksHistoryIo(cfg.baseDir)

    val liveView = buildLiveView(
        send,
        lanes,
        hub,
        client,
        fireSpeak,
        File(File(cfg.homeDir, "state"), LIVE_ORPHAN_FILE),
        history,
    )
    val poller = buildPoller(cfg, lanes.api, pythonState, pollOn, hub, client, speak, remote, queue, history, liveView)
    return ChannelStack(client, liveView, poller, hub)
}

private const val QUEUE_FILE = "inbound-queue.json"
private const val LIVE_ORPHAN_FILE = "live-view-active.json"
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
    fireSpeak: suspend (String, Boolean, JsonObject?) -> Unit,
    orphanFile: File,
    history: TasksHistory,
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
        // the card's head: the history's honest name for the live task
        // (null → the fallback stays — nothing invented)
        resolveTitle = { taskId ->
            history.ctxTasks(BotCommands.SCOPE_FOR_PICK).firstOrNull { it.sid == taskId }?.title
        },
        orphanStore = LiveOrphanStore(orphanFile),
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
    history: TasksHistory,
    liveView: LivePublisher?,
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
                ) = client.sendInbound(text, messageId, receivedAt, attachments)

                // the ✍️ armed order: direct to the named session (the fork's inbound taskId)
                override suspend fun sendOrder(
                    text: String,
                    messageId: String,
                    receivedAt: Long,
                    taskId: String,
                ) = client.sendInbound(text, messageId, receivedAt, taskId)

                // STEER: the running turn's redirection (the fork's `steer` frame, awaited)
                override suspend fun steer(taskId: String, text: String) = client.steer(taskId, text)

                override suspend fun sendInboundAwait(text: String, messageId: String, receivedAt: Long) =
                    client.sendInboundAwait(text, messageId, receivedAt)

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
    )
    return Poller(
        api = liveApi,
        offsetFile = File(pythonState, "tg-offset"), // Python's own cursor — the torch passes at retirement
        // the bot explains its own silences: a long mute window ends with the
        // apology (loud — the owner must learn WHY it was quiet, from the bot)
        onReconnected = { window -> speak(window.render(), true, null) },
        dispatch = { cfg, update ->
            // the buttons' dispatch: a tap is answered (the toast) and runs
            // as the owner's typed words; a message is the words themselves
            when (update) {
                is InboundUpdate.Message -> {
                    // F23: 👀 the second the words are in, ✅ once handled —
                    // feedback on the owner's own message, zero new messages
                    Ack.react(liveApi, cfg, update.messageId, Ack.RECEIVED)
                    // FILES OUT + /diff (30/10): the two commands that need
                    // the wiring's own throats (the multipart upload; the
                    // snapshot query + the mini-diff render) — intercepted
                    // BEFORE the text dispatcher, which never sees them
                    dispatchMessage(liveApi, cfg, client, history, commands, speak, update)
                    Ack.react(liveApi, cfg, update.messageId, Ack.HANDLED)
                }

                is InboundUpdate.Attachment ->
                    dispatchAttachment(liveApi, cfg, pythonState, client, speak, update)

                is InboundUpdate.Callback -> {
                    answerCallback(liveApi, update.callbackId)
                    commands.onCallback(update.data, update.updateId)
                        ?.let { speak(it.text, false, it.markup) }
                    // THE BLOCK TAP'S EDIT IS IMMEDIATE (the owner taps «📖
                    // CoT N» and reads it right there — the ~3s cadence
                    // continues after): the tick re-renders the card with
                    // the toggled block; the publisher's tick mutex
                    // serializes it with the scheduled clock
                    if (update.data.startsWith("cb:")) liveView?.tick()
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
    speak: suspend (String, Boolean, JsonObject?) -> Unit,
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
        val fired = client.sendInbound(
            update.caption,
            "tg-${update.updateId}-att",
            System.currentTimeMillis(),
            attachments = listOf(att),
        )
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

/**
 * ATTACHMENTS IN's gate: the Bot API's own download law — the bot's token
 * cannot fetch a file bigger than 20 MB (Telegram answers with «file is
 * too big»). The refusal is SPOKEN, never silent. Absent size (Telegram
 * omits file_size on some photos) passes: the honest failure then happens
 * at the download itself, where the truth is.
 */
internal fun attachmentRefusal(sizeBytes: Long?): String? =
    if (sizeBytes != null && sizeBytes > MAX_ATTACHMENT_BYTES) {
        "⚠️ «file is too big» — Telegram no deja bajar más de ${MAX_ATTACHMENT_BYTES / MB} MB " +
            "con el token del bot (${sizeBytes / MB} MB los tuyos). Pasalo por la Mac de otro modo."
    } else {
        null
    }

private val FILENAME_SAFE = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('.', '_', '-')

/** The wire's filename reduced to a safe on-disk name (no paths, no separators). */
internal fun attachmentFilename(filename: String): String =
    filename.map { c -> if (c in FILENAME_SAFE) c else '_' }
        .joinToString("")
        .take(FILENAME_CAP)
        .ifEmpty { "attachment" }

private const val ATTACHMENTS_DIR = "attachments"
private const val MB = 1024L * 1024L
private const val MAX_ATTACHMENT_BYTES = 20L * MB
private const val FILENAME_CAP = 120

/**
 * The free-text dispatch: /file and /diff take the wiring's own throats
 * (the multipart upload; the snapshot query + the mini-diff render) —
 * everything else is the dispatcher's (the commands, then the route).
 */
private suspend fun dispatchMessage(
    api: TgApi,
    cfg: TgConfig,
    client: ChannelClient,
    history: TasksHistory,
    commands: BotCommands,
    speak: suspend (String, Boolean, JsonObject?) -> Unit,
    update: InboundUpdate.Message,
) {
    when {
        update.text.startsWith("/file") ->
            speak(filesOut(api, cfg, update.text.removePrefix("/file")), false, null)

        update.text == "/diff" || update.text.startsWith("/diff ") ->
            speak(diffNow(client, history, update.text.removePrefix("/diff")), false, null)

        else -> commands.handle(update.text, update.updateId)?.let { speak(it.text, false, it.markup) }
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
internal fun snapshotAnswerOf(resultJson: String): String? = BotCommands.snapshotLastAnswer(resultJson)

private const val FINAL_BOXES = 6

/** The nuclear gate's file (mtime = the last start) and its double-trigger window. */
private const val NUCLEAR_GATE = "nuclear-last"
private const val NUCLEAR_DEBOUNCE_MS = 180_000L
