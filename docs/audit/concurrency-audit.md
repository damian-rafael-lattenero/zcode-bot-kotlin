# Concurrency/FP audit — U0 mechanical inventory (generated 2026-11-08)

Scope: `modules/*/src/main` (tests excluded, same scope as the CI sweep).
Every hit is a SUSPECT for the U1 semantic review, not a verdict.

- **forbidden**: 7
- **handrolled**: 49
- **impure**: 101
- **errors**: 181

## forbidden

| file | line | text |
|---|---|---|
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/metrics/MetricsEndpoint.kt` | 44 | `Thread(task, "metrics-endpoint").apply { isDaemon = true }` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/Main.kt` | 35 | `import kotlinx.coroutines.runBlocking` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/Main.kt` | 68 | `runBlocking { transportSelftest(cfg.baseDir, logger) }` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/Main.kt` | 203 | `runBlocking {` |
| `modules/recorder/src/main/kotlin/dev/lattenero/bot/recorder/Main.kt` | 7 | `import kotlinx.coroutines.runBlocking` |
| `modules/recorder/src/main/kotlin/dev/lattenero/bot/recorder/Main.kt` | 54 | `runBlocking {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Questions.kt` | 380 | `while (true) {` |

## handrolled

| file | line | text |
|---|---|---|
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/FileVerbositySetting.kt` | 22 | `@Volatile` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/FeedVerbosity.kt` | 50 | `@Volatile` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelTransport.kt` | 49 | `@Volatile` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/BotCommands.kt` | 245 | `private val nuclearGate = java.util.concurrent.atomic.AtomicBoolean(false)` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/BotCommands.kt` | 2098 | `@Volatile` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/BotCommands.kt` | 2115 | `@Volatile` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/BotCommands.kt` | 2133 | `@Volatile` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/BotCommands.kt` | 2142 | `private val captchaGatedPlans = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 17 | `import java.util.concurrent.atomic.AtomicLong` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 157 | `private val ids = AtomicLong(0)` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 159 | `@Volatile` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 168 | `@Volatile` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 300 | `val entries = synchronized(subscriptions) { subscriptions.toList() }` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 568 | `synchronized(subscriptions) { subscriptions[taskId] = workspacePath }` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 577 | `synchronized(subscriptions) { subscriptions.clear() }` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LivePublisher.kt` | 357 | `@Volatile` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Questions.kt` | 11 | `import java.util.concurrent.atomic.AtomicInteger` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Questions.kt` | 359 | `private val counter = AtomicInteger(0)` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Questions.kt` | 388 | `store?.save(synchronized(lock) { byShortId.toMap() })` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Questions.kt` | 405 | `val registered = synchronized(lock) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Questions.kt` | 430 | `synchronized(lock) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Questions.kt` | 439 | `fun byShortId(shortId: String): PendingQuestion? = synchronized(lock) { byShortId[shortId] }` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Questions.kt` | 455 | `val step = synchronized(lock) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Questions.kt` | 496 | `val question = synchronized(lock) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Questions.kt` | 507 | `val shortId = synchronized(lock) { shortIdByRequestId[requestId] } ?: return` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Questions.kt` | 513 | `val ids = synchronized(lock) { byShortId.filterValues { it.taskId == taskId }.keys.toList() }` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/MenuMirrorRefresher.kt` | 47 | `@Volatile` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/MenuMirrorRefresher.kt` | 51 | `@Volatile` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/notifications/SealGate.kt` | 3 | `import java.util.concurrent.ConcurrentHashMap` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/metrics/TurnMetricsTap.kt` | 50 | `synchronized(lock) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/metrics/TurnMetricsTap.kt` | 74 | `val startedAt: Long? = synchronized(lock) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/metrics/MetricsEndpoint.kt` | 7 | `import java.util.concurrent.Executors` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/metrics/MicrometerMetrics.kt` | 5 | `import java.util.concurrent.TimeUnit` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/ChannelWiring.kt` | 386 | `val yoloGuard = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/bus/BusSink.kt` | 31 | `@Volatile` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/bus/BusSink.kt` | 36 | `@Volatile` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/bus/BusSink.kt` | 44 | `synchronized(lock) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/otel/OtelSpanTaps.kt` | 8 | `import java.util.concurrent.TimeUnit` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/otel/OtelSpanTaps.kt` | 66 | `synchronized(lock) { turns[event.sid] = RootSpan(span, Context.root().with(span)) }` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/otel/OtelSpanTaps.kt` | 71 | `val root = synchronized(lock) { turns[event.sid] } ?: return` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/otel/OtelSpanTaps.kt` | 76 | `val root = synchronized(lock) { turns[event.sid] } ?: return // a child needs its root — never orphaned` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/otel/OtelSpanTaps.kt` | 83 | `synchronized(lock) { tools[event.toolCallId] = span }` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/otel/OtelSpanTaps.kt` | 87 | `val span = synchronized(lock) { tools.remove(event.toolCallId) } ?: return // never opened here: no lie` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/otel/OtelSpanTaps.kt` | 96 | `val root = synchronized(lock) { turns.remove(event.sid) } ?: return // never opened here: no lie` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/otel/TurnSpanTracker.kt` | 91 | `synchronized(lock) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/otel/TurnSpanTracker.kt` | 136 | `synchronized(lock) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/otel/TurnSpanTracker.kt` | 159 | `synchronized(lock) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/otel/TurnSpanTracker.kt` | 187 | `synchronized(lock) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/otel/TurnSpanTracker.kt` | 195 | `synchronized(lock) {` |

## impure

| file | line | text |
|---|---|---|
| `modules/recorder/src/main/kotlin/dev/lattenero/bot/recorder/Recorder.kt` | 6 | `import java.io.File` |
| `modules/recorder/src/main/kotlin/dev/lattenero/bot/recorder/Main.kt` | 8 | `import java.io.File` |
| `modules/comparator/src/main/kotlin/dev/lattenero/bot/comparator/Main.kt` | 5 | `import java.io.File` |
| `modules/comparator/src/main/kotlin/dev/lattenero/bot/comparator/Main.kt` | 29 | `val nowMs = System.currentTimeMillis()` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Cooldown.kt` | 3 | `import java.io.File` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Cooldown.kt` | 4 | `import java.io.IOException` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Cooldown.kt` | 15 | `private val now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgConfig.kt` | 9 | `import java.io.File` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgApi.kt` | 25 | `import java.io.File` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgApi.kt` | 26 | `import java.io.IOException` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgBreaker.kt` | 5 | `import java.io.IOException` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgOffset.kt` | 3 | `import java.io.File` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgOffset.kt` | 4 | `import java.io.FileOutputStream` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgOffset.kt` | 53 | `FileOutputStream(tmp).use { fos ->` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Outbox.kt` | 19 | `import java.io.File` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Outbox.kt` | 20 | `import java.io.FileOutputStream` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Outbox.kt` | 21 | `import java.io.IOException` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Outbox.kt` | 22 | `import java.io.RandomAccessFile` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Outbox.kt` | 56 | `private val now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Outbox.kt` | 183 | `FileOutputStream(tmp).use { fos ->` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/LastPh.kt` | 3 | `import java.io.File` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/LastPh.kt` | 4 | `import java.io.IOException` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LastPrompt.kt` | 11 | `import java.io.File` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LastPrompt.kt` | 12 | `import java.io.FileOutputStream` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LastPrompt.kt` | 13 | `import java.io.IOException` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LastPrompt.kt` | 43 | `FileOutputStream(tmp).use { stream ->` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/PollKnob.kt` | 6 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/FileVerbositySetting.kt` | 6 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/FileVerbositySetting.kt` | 7 | `import java.io.FileOutputStream` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/FileVerbositySetting.kt` | 8 | `import java.io.IOException` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/FileVerbositySetting.kt` | 41 | `FileOutputStream(tmp).use { stream ->` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/notifications/NotificationsKnob.kt` | 6 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/bus/BusSink.kt` | 3 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/bus/BusSink.kt` | 4 | `import java.io.FileOutputStream` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/bus/BusSink.kt` | 5 | `import java.io.RandomAccessFile` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/bus/BusSink.kt` | 48 | `FileOutputStream(file, true).use { out ->` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/notifications/NotificationsWire.kt` | 10 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/notifications/NotificationsWire.kt` | 66 | `val now = System.currentTimeMillis() / MILLIS_PER_SECOND` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/bus/EventBusKnob.kt` | 6 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/notifications/SubagentNotifs.kt` | 19 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/notifications/SubagentNotifs.kt` | 20 | `import java.io.FileOutputStream` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/notifications/SubagentNotifs.kt` | 21 | `import java.io.IOException` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/notifications/SubagentNotifs.kt` | 165 | `FileOutputStream(tmp).use {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/BotCommands.kt` | 3883 | `return if (dt.toLocalDate() == LocalDate.now(zone)) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/FilesCommands.kt` | 8 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/WiringFragments.kt` | 17 | `import java.io.File` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveOrphanStore.kt` | 9 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/send/Lanes.kt` | 10 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/shadow/ShadowLog.kt` | 4 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/shadow/ShadowLog.kt` | 5 | `import java.io.IOException` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/QuotaDirect.kt` | 183 | `return if (dt.toLocalDate() == LocalDate.now(zone)) dt.format(TIME_FMT)` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/send/SendKnob.kt` | 6 | `import java.io.File` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/OpencodeQuota.kt` | 117 | `nowMs: Long = System.currentTimeMillis(),` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/OpencodeQuota.kt` | 186 | `nowMs: Long = System.currentTimeMillis(),` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/send/SendLeg.kt` | 9 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/RichVoiceKnob.kt` | 6 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/ChannelWiring.kt` | 83 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/ChannelWiring.kt` | 254 | `val now = System.currentTimeMillis() / 1000.0` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/ChannelWiring.kt` | 267 | `val now = System.currentTimeMillis() / 1000.0` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/ChannelWiring.kt` | 303 | `feedAndResync(hub, frame, System.currentTimeMillis())` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/ChannelWiring.kt` | 457 | `val fired = client.sendInbound(outbound, "${p.messageId}-q$i", System.currentTimeMillis())` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/ChannelWiring.kt` | 553 | `val now = System.currentTimeMillis() / 1000.0` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/ChannelWiring.kt` | 615 | `val file = java.io.File(path)` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/ChannelWiring.kt` | 781 | `is QueryAnswer.Ok -> GiftsMenu.parse(answer.resultJson, System.currentTimeMillis())` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/ChannelWiring.kt` | 927 | `val ageMs = System.currentTimeMillis() - gate.lastModified()` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/ChannelWiring.kt` | 1058 | `System.currentTimeMillis(),` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelTransport.kt` | 6 | `import java.io.File` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelTransport.kt` | 7 | `import java.io.IOException` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/QuotaFetch.kt` | 12 | `import java.io.IOException` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/metrics/MicrometerMetrics.kt` | 92 | `val startedAt = System.nanoTime()` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/metrics/MicrometerMetrics.kt` | 96 | `timer.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Poller.kt` | 22 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/metrics/MetricsKnob.kt` | 7 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/Main.kt` | 38 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/Main.kt` | 39 | `import java.io.IOException` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/Main.kt` | 165 | `val clock = { System.currentTimeMillis() / MILLIS_PER_SECOND }` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/history/TasksHistoryIo.kt` | 12 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/DeploySpawn.kt` | 8 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/DeploySpawn.kt` | 229 | `logFile.appendText("\n==> deploy job $label armed at ${java.time.Instant.now()}\n")` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/spectator/SpectatorIo.kt` | 9 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/DaemonConfig.kt` | 3 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/clilogs/CliLogs.kt` | 6 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/clilogs/CliLogs.kt` | 7 | `import java.io.IOException` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/clilogs/CliLogs.kt` | 8 | `import java.io.RandomAccessFile` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/otel/OtelKnob.kt` | 7 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/spectator/SpectatorWatch.kt` | 20 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/queue/InboundQueue.kt` | 11 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/queue/InboundQueue.kt` | 90 | `} catch (_: java.io.IOException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/spectator/RolloutReader.kt` | 5 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/spectator/RolloutReader.kt` | 6 | `import java.io.IOException` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/spectator/RolloutReader.kt` | 7 | `import java.io.RandomAccessFile` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/zconn/ZconnShadow.kt` | 17 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/zconn/ZconnShadow.kt` | 18 | `import java.io.FileOutputStream` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/zconn/ZconnShadow.kt` | 19 | `import java.io.IOException` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/zconn/ZconnShadow.kt` | 241 | `FileOutputStream(tmp).use {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/brain/MemoryInjector.kt` | 12 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/FileQuestionBoardStore.kt` | 14 | `import java.io.File` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/FileQuestionBoardStore.kt` | 15 | `import java.io.FileOutputStream` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/FileQuestionBoardStore.kt` | 16 | `import java.io.IOException` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/FileQuestionBoardStore.kt` | 59 | `FileOutputStream(tmp).use { stream ->` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/brain/BrainFetch.kt` | 27 | `import java.io.File` |

## errors

| file | line | text |
|---|---|---|
| `modules/contract-types/src/main/kotlin/dev/lattenero/bot/contracts/BusEvent.kt` | 50 | `} catch (e: SerializationException) {` |
| `modules/contract-types/src/main/kotlin/dev/lattenero/bot/contracts/BusEvent.kt` | 52 | `} catch (e: IllegalArgumentException) {` |
| `modules/contract-types/src/main/kotlin/dev/lattenero/bot/contracts/BusEvent.kt` | 60 | `} catch (e: DateTimeParseException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/FileVerbositySetting.kt` | 29 | `} catch (_: IOException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/FileVerbositySetting.kt` | 45 | `if (!tmp.renameTo(file)) throw IOException("atomic rename failed: $file")` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/FileVerbositySetting.kt` | 46 | `} catch (e: IOException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/notifications/NotificationsKnob.kt` | 49 | `} catch (e: Exception) {` |
| `modules/comparator/src/main/kotlin/dev/lattenero/bot/comparator/Main.kt` | 92 | `else -> throw IllegalArgumentException(` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/Main.kt` | 272 | `launch { firehoseProbe(lanes.live!!, logger) }` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/Main.kt` | 304 | `} catch (e: ProcessStoppedException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/Main.kt` | 306 | `} catch (e: ProcessExhaustedException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/Main.kt` | 385 | `} catch (_: IOException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/Main.kt` | 417 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/notifications/NotificationsWire.kt` | 83 | `} catch (e: CancellationException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/notifications/NotificationsWire.kt` | 84 | `throw e` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/notifications/NotificationsWire.kt` | 85 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/notifications/SubagentNotifs.kt` | 157 | `} catch (e: IOException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/notifications/SubagentNotifs.kt` | 169 | `if (!tmp.renameTo(target)) throw IOException("atomic rename failed: $target")` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LastPrompt.kt` | 52 | `} catch (_: IOException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LastPrompt.kt` | 68 | `} catch (_: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/DeploySpawn.kt` | 87 | `throw IllegalStateException(busyMessage())` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/DeploySpawn.kt` | 164 | `throw IllegalStateException(busyMessage())` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/DeploySpawn.kt` | 226 | `throw IllegalStateException("launchctl bootstrap $label failed: ${out.text.trim()}")` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/DeploySpawn.kt` | 291 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/DeploySpawn.kt` | 304 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/DeploySpawn.kt` | 346 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/send/SendKnob.kt` | 76 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/RichVoiceKnob.kt` | 43 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/QuotaDirect.kt` | 150 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/QuotaFetch.kt` | 49 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/QuotaFetch.kt` | 52 | `throw IOException("$service quota fetch failed: ${e::class.simpleName}", e)` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/QuotaFetch.kt` | 54 | `if (!resp.status.isSuccess()) throw IOException("$service quota HTTP ${resp.status.value}")` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Ack.kt` | 50 | `} catch (e: CancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Ack.kt` | 51 | `throw e` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Ack.kt` | 52 | `} catch (_: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/spectator/RolloutReader.kt` | 44 | `} catch (_: IOException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/otel/OtelSpanTaps.kt` | 134 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LivePublisher.kt` | 830 | `} catch (e: CancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LivePublisher.kt` | 831 | `throw e` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LivePublisher.kt` | 832 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/QuestionBoardStore.kt` | 28 | `/** The FULL snapshot write — the impl owns atomicity and its own failure (degrade, never throw). */` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 77 | `} catch (e: CancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 78 | `throw e` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 79 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 96 | `} catch (e: CancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 97 | `throw e` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 98 | `} catch (e: TgHttpException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 102 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 121 | `} catch (e: CancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 122 | `throw e` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 123 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 141 | `} catch (e: CancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 142 | `throw e` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 143 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 156 | `} catch (e: CancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 157 | `throw e` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 158 | `} catch (e: TgHttpException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 162 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 192 | `} catch (e: CancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 193 | `throw e` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 194 | `} catch (e: TgHttpException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTelegram.kt` | 198 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/otel/OtelKnob.kt` | 68 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 230 | `} catch (e: TimeoutCancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 235 | `} catch (e: CancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 236 | `throw e` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 237 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 255 | `if (!transport.connect()) throw ChannelDown("no socket — the fork's host is not deployed")` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 257 | `if (token.isEmpty()) throw ChannelDown("token file missing/empty (${ChannelSpec.TOKEN_FILENAME})")` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 278 | `val line = live.transport.readLine() ?: throw ChannelDown("connection closed before hello_ok")` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 284 | `throw ChannelDown("handshake rejected: ${frame.code} ${frame.message ?: ""}")` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 382 | `} catch (e: TimeoutCancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 384 | `} catch (e: CancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 385 | `throw e` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 386 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 413 | `} catch (e: TimeoutCancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 415 | `} catch (e: CancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 416 | `throw e` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 417 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 446 | `} catch (e: TimeoutCancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 448 | `} catch (e: CancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 449 | `throw e` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 450 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 469 | `} catch (e: CancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 470 | `throw e` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 471 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 511 | `} catch (e: TimeoutCancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 513 | `} catch (e: CancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 514 | `throw e` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 515 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 557 | `} catch (e: TimeoutCancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 559 | `} catch (e: CancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 560 | `throw e` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelClient.kt` | 561 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Routing.kt` | 114 | `val selected = facts.selectedSid!!` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Routing.kt` | 132 | `val active = facts.activeTaskId!!` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Routing.kt` | 142 | `val active = facts.activeTaskId!!` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Poller.kt` | 171 | `} catch (e: CancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Poller.kt` | 172 | `throw e` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Poller.kt` | 173 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Poller.kt` | 205 | `} catch (e: CancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Poller.kt` | 206 | `throw e` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Poller.kt` | 207 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Poller.kt` | 226 | `} catch (e: CancellationException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Poller.kt` | 227 | `throw e` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/Poller.kt` | 228 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveOrphanStore.kt` | 34 | `} catch (_: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveOrphanStore.kt` | 49 | `} catch (_: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveOrphanStore.kt` | 56 | `} catch (_: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/BotMenu.kt` | 31 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/OpencodeQuota.kt` | 192 | `} catch (e: Exception) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelTransport.kt` | 71 | `val ch = channel ?: throw IOException("channel not connected")` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelTransport.kt` | 79 | `val ch = channel ?: throw IOException("channel not connected")` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelTransport.kt` | 91 | `throw IOException("channel line over the ${ChannelSpec.MAX_LINE_BYTES}B budget")` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelTransport.kt` | 96 | `throw IOException("channel line over the ${ChannelSpec.MAX_LINE_BYTES}B budget")` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelTransport.kt` | 101 | `} catch (e: AsynchronousCloseException) {` |
| `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelTransport.kt` | 102 | `throw IOException("channel closed while reading (ping shutdown or stop)", e)` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/brain/BrainFetch.kt` | 60 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/brain/BrainFetch.kt` | 130 | `} catch (e: CancellationException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/brain/BrainFetch.kt` | 131 | `throw e` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/brain/BrainFetch.kt` | 132 | `} catch (e: HttpRequestTimeoutException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/brain/BrainFetch.kt` | 134 | `} catch (e: ConnectTimeoutException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/brain/BrainFetch.kt` | 136 | `} catch (e: SocketTimeoutException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/brain/BrainFetch.kt` | 138 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/PollKnob.kt` | 46 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/brain/MemoryInjector.kt` | 107 | `} catch (e: CancellationException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/brain/MemoryInjector.kt` | 108 | `throw e` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/brain/MemoryInjector.kt` | 109 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/metrics/MetricsKnob.kt` | 61 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/ChannelWiring.kt` | 585 | `DeployCommand.Target.BOTH -> throw IllegalStateException(` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/ChannelWiring.kt` | 1074 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/ChannelWiring.kt` | 1161 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/ChannelWiring.kt` | 1219 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/nuclear/NuclearLadder.kt` | 91 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/nuclear/NuclearLadder.kt` | 196 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/bus/EventBusKnob.kt` | 42 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/bus/BusProducer.kt` | 349 | `} catch (e: CancellationException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/bus/BusProducer.kt` | 350 | `throw e` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/bus/BusProducer.kt` | 351 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/bus/PromptTap.kt` | 83 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/shadow/ShadowLog.kt` | 39 | `} catch (_: IOException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/DaemonConfig.kt` | 28 | `else -> throw IllegalArgumentException(` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/clilogs/CliLogs.kt` | 103 | `} catch (_: IOException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/FileQuestionBoardStore.kt` | 47 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/FileQuestionBoardStore.kt` | 68 | `} catch (e: IOException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/DeployFlow.kt` | 31 | `* (missing script, unwritable log) — a throw means THIS daemon stays up` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/DeployFlow.kt` | 151 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/zconn/ZconnShadow.kt` | 233 | `} catch (e: IOException) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/zconn/ZconnShadow.kt` | 245 | `if (!tmp.renameTo(target)) throw IOException("atomic rename failed: $target")` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/bus/BusSink.kt` | 54 | `} catch (e: Exception) {` |
| `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/bus/BusSink.kt` | 69 | `} catch (_: Exception) {` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Cooldown.kt` | 51 | `} catch (_: IOException) {` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgApi.kt` | 56 | `} catch (e: Exception) { // Python lets urllib's errors fly raw; ours must ALL pass the redactor` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgApi.kt` | 59 | `throw IOException(cfg.redact(e.message ?: e::class.simpleName.orEmpty()), e)` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgApi.kt` | 62 | `if (!resp.status.isSuccess()) throw TgHttpException(resp.status.value, body)` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgApi.kt` | 80 | `?: throw IOException(cfg.redact("getFile answered no file_path for the download"))` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgApi.kt` | 84 | `} catch (e: Exception) {` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgApi.kt` | 85 | `throw IOException(cfg.redact(e.message ?: e::class.simpleName.orEmpty()), e)` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgApi.kt` | 87 | `if (!resp.status.isSuccess()) throw TgHttpException(resp.status.value, resp.bodyAsText())` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgApi.kt` | 128 | `} catch (e: Exception) {` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgApi.kt` | 129 | `throw IOException(cfg.redact(e.message ?: e::class.simpleName.orEmpty()), e)` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgApi.kt` | 132 | `if (!resp.status.isSuccess()) throw TgHttpException(resp.status.value, body)` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgApi.kt` | 161 | `} catch (_: Exception) {` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgApi.kt` | 181 | `} catch (_: Exception) {` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgOffset.kt` | 41 | `} catch (_: Exception) {` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/LastPh.kt` | 19 | `} catch (_: IOException) {` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/LastPh.kt` | 29 | `} catch (_: IOException) {` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Sender.kt` | 100 | `if (e is CancellationException) throw e` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgConfig.kt` | 63 | `} catch (_: Exception) {` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgBreaker.kt` | 93 | `if (!breaker.tryAcquire()) throw CircuitBreakerOpen(name, remainingWindowS())` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgBreaker.kt` | 96 | `} catch (e: CancellationException) {` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgBreaker.kt` | 99 | `throw e` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/TgBreaker.kt` | 102 | `throw e` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Outbox.kt` | 60 | `// concurrent channel would throw OverlappingFileLockException, not` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Outbox.kt` | 78 | `} catch (e: IOException) {` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Outbox.kt` | 98 | `} catch (e: IOException) {` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Outbox.kt` | 111 | `} catch (e: IOException) {` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Outbox.kt` | 143 | `val lock = raf.channel.tryLock() ?: throw FlockBusy(lockPath)` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Outbox.kt` | 154 | `throw IOException(` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Outbox.kt` | 175 | `} catch (_: Exception) {` |
| `modules/transport/src/main/kotlin/dev/lattenero/bot/transport/Selftest.kt` | 31 | `} catch (e: Exception) {` |

