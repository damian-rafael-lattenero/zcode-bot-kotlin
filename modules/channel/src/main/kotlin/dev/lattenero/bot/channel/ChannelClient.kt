package dev.lattenero.bot.channel

import io.github.oshai.kotlinlogging.KotlinLogging
import kap.Kap
import kap.Process
import kap.RestartPolicy
import kap.Schedule
import kap.evalGraph
import kap.process
import kap.repeat
import kap.retry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * The transport-level failure family — the ONLY errors the reconnect
 * backoff retries forever. The channel exists only while the fork's host
 * is deployed: "no socket" is a STATE, not a bug. The client degrades
 * LOUD (the periodic warning) and never crashes the daemon. Anything
 * else reaching the Process's restart policy is a bug and dies under the
 * convergence law like any watcher's.
 */
class ChannelDown(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The answer of a query attempt — the dispatcher turns each case into its reply. */
sealed interface QueryAnswer {
    data class Ok(val resultJson: String) : QueryAnswer

    data object Unavailable : QueryAnswer

    data class Failed(val code: String, val message: String?) : QueryAnswer
}

/** The receipt of an inbound send (fire-and-forget: the turn's truth is the stream). */
enum class InboundReceipt { FIRED, UNAVAILABLE }

/**
 * The awaited steer's answer — honest by construction: [Delivered] only
 * when the host acked the frame; [Failed] carries the host's own word
 * (bad_request, unknown_request of an old fork, internal); [Unavailable]
 * is the channel being down. A steer NEVER rides as free text.
 */
sealed interface SteerAnswer {
    data object Delivered : SteerAnswer

    data object Unavailable : SteerAnswer

    data class Failed(val code: String, val message: String?) : SteerAnswer
}

/**
 * The awaited respond's answer — the question tap's truth: [Resolved] ONLY
 * when the host's respond_result said ok (the v4 command acked — the fork's
 * own honest gate); [Failed] carries the host's error verbatim (an unknown
 * interactionId, an already-resolved question, a stale turn — the fork
 * never lies ok) and the tap must EDIT the question message with it;
 * [Unavailable] is the channel being down (the question stays armed — the
 * owner can retry).
 */
sealed interface RespondAnswer {
    data object Resolved : RespondAnswer

    data object Unavailable : RespondAnswer

    data class Failed(val code: String, val message: String?) : RespondAnswer
}

/**
 * The AWAITED inbound's answer — the bind machinery's truth: the
 * botsService's own replies (taskRunning, taskMissing, the status card)
 * come back on the inbound_result, correlated by id (§3.2: it may arrive
 * "much later" for PROMPTS; for COMMANDS it is the answer).
 */
sealed interface InboundAnswer {
    data class Answered(
        val duplicate: Boolean,
        /** The system replies verbatim — the router classifies on them. */
        val replies: List<String>,
    ) : InboundAnswer

    data object Unavailable : InboundAnswer

    data class TimedOut(val waitedMs: Long) : InboundAnswer
}

/** One live connection: serialized writes + the pending-request table, correlated by id. */
private class Session(val transport: ChannelTransport) {
    val write = Mutex()
    val pending = mutableMapOf<String, CompletableDeferred<ServerFrame>>()

    suspend fun request(frame: ClientFrame, id: String, timeout: Duration): ServerFrame {
        val deferred = CompletableDeferred<ServerFrame>()
        pending[id] = deferred
        try {
            write.withLock { transport.writeLine(encodeClient(frame)) }
            return withTimeout(timeout) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    suspend fun fire(frame: ClientFrame) {
        write.withLock { transport.writeLine(encodeClient(frame)) }
    }

    fun kill() = transport.shutdown()
}

/**
 * B3b's channel client: the daemon-local socket as Kap policy.
 *
 * - reconnect IS the policy: `retry(exponential and whileInput{ChannelDown})`
 *   backs off 1s→30s on every transport-level failure forever (the fork
 *   not being deployed is the steady state until Damian restarts the
 *   app), and `repeat(spaced(1s))` re-opens after a clean end (bye). A
 *   missing socket logs the DEGRADED warning on every attempt — LOUD,
 *   never a crash;
 * - the handshake is the first line (token from the 0600 file, re-read
 *   EVERY attempt: the host rotates it at boot); hello_ok gates the rest;
 * - subscriptions are REPLAYED on every (re)connect — the stream is
 *   continuous WITHOUT replay, so the caller learns via [onResubscribed]
 *   and resyncs with a `taskSnapshot` query (lost events stay lost: the
 *   red line of the live mode);
 * - inbound is at-least-once: the daemon passes Telegram's update_id as
 *   messageId and the HOST dedupes (TTL 2 min); the late `inbound_result`
 *   is a callback, never a block;
 * - `ping`→`pong {at}` every ~30 s from a second Process distinguishes
 *   "channel healthy but the model is quiet" from "channel dead": a pong
 *   that does not arrive within 10 s kills the socket, which ends the
 *   session and reconnects.
 */
@Suppress("TooManyFunctions") // session, pump, keepalive, one API per frame kind
class ChannelClient(
    private val transportFactory: () -> ChannelTransport,
    private val tokenReader: () -> String?,
    private val onStreamEvent: suspend (ServerFrame.StreamEvent) -> Unit,
    private val onInboundResult: suspend (ServerFrame.InboundResult) -> Unit = {},
    private val onResubscribed: suspend (taskId: String) -> Unit = {},
    /** The stream heartbeat (f94af960): the seq accounting + the future
     * watchdog's tooth (a missing heartbeat is a dead bus, not a quiet
     * model — the silence law keyed on REAL proof of life). */
    private val onKeepalive: suspend (ServerFrame.KeepAlive) -> Unit = {},
) : AutoCloseable {

    private val ids = AtomicLong(0)

    @Volatile
    private var session: Session? = null

    /** True while a hello_ok'd connection exists — the 💓/📡 of the status line. */
    val connected: Boolean get() = session != null

    /** taskId → workspacePath — the subscriptions that survive reconnects. */
    private val subscriptions = linkedMapOf<String, String>()

    /** The client Process: reconnect as Kap policy, bugs under the convergence law. */
    fun asProcess(
        heartbeatEvery: Duration = 30.seconds,
        onHeartbeat: () -> Unit = {},
    ): Process<Unit> =
        Kap { session() }
            .retry(
                Schedule.exponential<Throwable>(base = 1.seconds, factor = 2.0, max = 30.seconds)
                    .and(Schedule.whileInput { it is ChannelDown }),
                onRetry = { attempt, error, delay ->
                    logger.warn {
                        "channel DEGRADED: ${error.message} — reconnect attempt $attempt in $delay " +
                            "(the channel exists only while the fork's host is deployed; sends keep flowing)"
                    }
                },
            )
            .repeat(Schedule.spaced(1.seconds))
            .process(
                name = "channel",
                restart = RestartPolicy.exponential(base = 1.seconds, max = 15.seconds, maxRestarts = 12),
                heartbeatEvery = heartbeatEvery,
                onHeartbeat = onHeartbeat,
                onRestart = { attempt, error, delay ->
                    logger.warn(error) { "channel session BUG — restart $attempt in $delay (convergence law)" }
                },
            )

    /** The keepalive Process: ping → pong {at}; a missing pong kills the socket. */
    fun pingProcess(every: Duration = 30.seconds): Process<Unit> =
        Kap { pingOnce() }
            .repeat(Schedule.spaced(every))
            .process(
                name = "channel-ping",
                restart = RestartPolicy.exponential(base = 1.seconds, max = 15.seconds),
                onRestart = { attempt, error, delay ->
                    logger.warn(error) { "channel ping failed — restart $attempt in $delay" }
                },
            )

    @Suppress("SwallowedException", "TooGenericExceptionCaught") // the ping is a probe: any miss just kills the socket
    private suspend fun pingOnce() {
        val live = session ?: return // no session: the main process owns the retry
        val id = "p${ids.incrementAndGet()}"
        try {
            val pong = live.request(ClientFrame.Ping(id), id, PONG_TIMEOUT)
            if (pong !is ServerFrame.Pong) {
                logger.warn { "channel ping answered ${pong::class.simpleName} — treating the socket as dead" }
                live.kill()
            }
        } catch (e: TimeoutCancellationException) {
            // withTimeout's own clock ran out — NOT coroutine cancellation: kill the
            // socket; the blocked read unblocks and the session reconnects on its policy
            logger.warn { "channel pong MISSING in $PONG_TIMEOUT — shutting the socket (reconnect follows)" }
            live.kill()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the session died between the peek and the request — the main process is already on it
            logger.debug { "channel ping skipped: ${e::class.simpleName} ${e.message}" }
        }
    }

    /**
     * One connection lifetime: connect → hello → replay subscriptions →
     * serve. The line pump is THIS coroutine throughout — the handshake
     * itself reads inline (the hello_ok that answers us is just the first
     * routed frame), and every request fired from outside (queries,
     * pings, inbound) has its answer routed back by [dispatch] while the
     * pump keeps reading. One reader, no deadlock by construction.
     */
    internal suspend fun session() {
        val transport = transportFactory()
        val live = Session(transport)
        try {
            if (!transport.connect()) throw ChannelDown("no socket — the fork's host is not deployed")
            val token = tokenReader()?.trim().orEmpty()
            if (token.isEmpty()) throw ChannelDown("token file missing/empty (${ChannelSpec.TOKEN_FILENAME})")
            session = live
            live.fire(ClientFrame.Hello(token))
            val hello = awaitHello(live)
            logger.info {
                "channel UP: hello_ok v${hello.protocolVersion} bot=${hello.botId} enabled=${hello.botEnabled} " +
                    "server pid=${hello.serverPid} app=${hello.appVersion ?: "?"} caps=${hello.capabilities.size}"
            }
            replaySubscriptions(live)
            serve(live)
        } finally {
            session = null
            live.pending.values.forEach { it.completeExceptionally(ChannelDown("connection ended")) }
            live.pending.clear()
            transport.shutdown()
        }
    }

    /** Reads lines until the hello_ok, ROUTING everything else that arrived early. */
    private tailrec suspend fun awaitHello(live: Session): ServerFrame.HelloOk {
        val line = live.transport.readLine() ?: throw ChannelDown("connection closed before hello_ok")
        val frame = decodeServerLine(line) ?: return awaitHello(live)
        return when (frame) {
            is ServerFrame.HelloOk -> frame

            is ServerFrame.ChannelError ->
                throw ChannelDown("handshake rejected: ${frame.code} ${frame.message ?: ""}")

            else -> {
                dispatch(live, frame)
                awaitHello(live)
            }
        }
    }

    /**
     * Subscriptions are FIRE-AND-REMEMBER: the map survives reconnects,
     * every (re)connect replays the frames, and the stream events
     * themselves prove the subscription took (at-least-once — a lost ack
     * is harmless, re-subscribing replaces, §3.3).
     */
    private suspend fun replaySubscriptions(live: Session) {
        val entries = synchronized(subscriptions) { subscriptions.toList() }
        for ((taskId, workspacePath) in entries) {
            runCatching { live.fire(ClientFrame.Subscribe("s${ids.incrementAndGet()}", taskId, workspacePath)) }
                .onSuccess {
                    logger.info { "channel resubscribed taskId=$taskId (frame sent — the stream proves it)" }
                    onResubscribed(taskId)
                }
                .onFailure { logger.warn { "channel resubscribe taskId=$taskId failed: ${it.message}" } }
        }
    }

    /**
     * The read loop as schedule algebra: one frame per Kap iteration,
     * `repeat(doWhile { it })` — EOF ends the repetition and with it the
     * session (clean end → the outer `repeat(spaced(1s))` re-opens).
     */
    private suspend fun serve(live: Session) {
        var ignored = 0
        Kap {
            val line = live.transport.readLine() ?: return@Kap false
            val frame = decodeServerLine(line)
            if (frame == null) {
                ignored++
                if (ignored % IGNORED_LINE_EVERY == 1) {
                    logger.warn { "channel ignored $ignored unknown/unparseable lines (the additive rule)" }
                }
            } else {
                dispatch(live, frame)
            }
            true
        }.repeat(Schedule.doWhile<Boolean> { it }).evalGraph()
    }

    /** Routes one server frame: stream events out, answers to their waiters. */
    private suspend fun dispatch(live: Session, frame: ServerFrame) {
        when (frame) {
            is ServerFrame.StreamEvent -> onStreamEvent(frame)

            is ServerFrame.KeepAlive -> onKeepalive(frame)

            // an AWAITED inbound_result belongs to its waiter (the bind reads
            // the replies itself and speaks its own summary); unawaited ones
            // keep the old fire-and-forget callback
            is ServerFrame.InboundResult ->
                if (live.pending.remove(frame.id)?.complete(frame) != true) onInboundResult(frame)

            is ServerFrame.ChannelError -> {
                logger.warn { "channel error id=${frame.id} code=${frame.code}: ${frame.message ?: ""}" }
                frame.id?.let { id -> live.pending[id]?.complete(frame) }
            }

            is ServerFrame.Bye -> logger.info { "channel bye: ${frame.reason} — the session ends, reconnect follows" }

            else -> requestIdOf(frame)?.let { id -> live.pending[id]?.complete(frame) }
        }
    }

    /** The correlation id of the request-answer frames (the union has no common member). */
    private fun requestIdOf(frame: ServerFrame): String? = when (frame) {
        // the hello's request id is fixed (§3.1: an idempotent re-hello re-answers)
        is ServerFrame.HelloOk -> HELLO_ID
        is ServerFrame.QueryResult -> frame.id
        is ServerFrame.Pong -> frame.id
        is ServerFrame.Subscribed -> frame.id
        is ServerFrame.Unsubscribed -> frame.id
        is ServerFrame.RespondResult -> frame.id
        else -> null
    }

    /** A service query — the eight commands' data source. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // best-effort for its caller, never for the daemon
    suspend fun query(query: ChannelQuery, timeout: Duration = ACK_TIMEOUT): QueryAnswer {
        val live = session ?: return QueryAnswer.Unavailable
        val id = "q${ids.incrementAndGet()}"
        return try {
            when (val answer = live.request(ClientFrame.Query(id, query), id, timeout)) {
                is ServerFrame.QueryResult -> QueryAnswer.Ok(answer.result)
                is ServerFrame.ChannelError -> QueryAnswer.Failed(answer.code, answer.message)
                else -> QueryAnswer.Failed("internal", "query answered ${answer::class.simpleName}")
            }
        } catch (e: ChannelDown) {
            QueryAnswer.Unavailable
        } catch (e: TimeoutCancellationException) {
            QueryAnswer.Failed("timeout", "no answer in $timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            QueryAnswer.Failed("internal", e.message)
        }
    }

    /**
     * STEER: the running turn's redirection — the `steer` frame, AWAITED
     * (the ack IS the truth: the host answers inbound_result once the
     * delivery is in, an error when the fork predates the frame or the
     * task is gone). The ack says NOTHING about the turn's fate — an idle
     * conversation admits the guide as a fresh turn (the CLI's own
     * admission); the caller renders that honestly from its own facts.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // best-effort for its caller, never for the daemon
    suspend fun steer(taskId: String, text: String, timeout: Duration = ACK_TIMEOUT): SteerAnswer {
        val live = session ?: return SteerAnswer.Unavailable
        val id = "t${ids.incrementAndGet()}"
        return try {
            when (val frame = live.request(ClientFrame.Steer(id, taskId, text), id, timeout)) {
                is ServerFrame.InboundResult -> SteerAnswer.Delivered

                is ServerFrame.ChannelError -> SteerAnswer.Failed(frame.code, frame.message)

                else -> SteerAnswer.Failed("internal", "steer answered ${frame::class.simpleName}")
            }
        } catch (e: ChannelDown) {
            SteerAnswer.Unavailable
        } catch (e: TimeoutCancellationException) {
            SteerAnswer.Failed("timeout", "no answer in $timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SteerAnswer.Failed("internal", e.message)
        }
    }

    /**
     * EL RESPOND del dueño (el loop cerrado, el fork's respond frame —
     * 6993d7e8): AWAITED — respond_result ok:true SOLO tras el ACK del v4
     * command; el fallo (interactionId desconocido, interacción ya
     * resuelta, turno sellado) llega COMO ChannelError con el mensaje del
     * CLI verbatim — el tap edita el mensaje de la pregunta con esa
     * verdad. Un fork viejo dice unknown_request: Failed honesta.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // best-effort for its caller, never for the daemon
    suspend fun respond(frame: ClientFrame.Respond, timeout: Duration = ACK_TIMEOUT): RespondAnswer {
        val live = session ?: return RespondAnswer.Unavailable
        val id = "p${ids.incrementAndGet()}"
        return try {
            when (val answer = live.request(frame.copy(id = id), id, timeout)) {
                is ServerFrame.RespondResult ->
                    if (answer.ok) RespondAnswer.Resolved
                    else RespondAnswer.Failed("internal", "respond_result ok=false")

                is ServerFrame.ChannelError -> RespondAnswer.Failed(answer.code, answer.message)

                else -> RespondAnswer.Failed("internal", "respond answered ${answer::class.simpleName}")
            }
        } catch (e: ChannelDown) {
            RespondAnswer.Unavailable
        } catch (e: TimeoutCancellationException) {
            RespondAnswer.Failed("timeout", "no answer in $timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RespondAnswer.Failed("internal", e.message)
        }
    }

    /** Free text → the REAL conversation (the composer's own pipeline). */
    @Suppress("TooGenericExceptionCaught") // fire-and-forget: any miss answers UNAVAILABLE
    suspend fun sendInbound(
        text: String,
        messageId: String,
        receivedAt: Long,
        taskId: String? = null,
        attachments: List<InboundAttachment> = emptyList(),
    ): InboundReceipt {
        val live = session ?: return InboundReceipt.UNAVAILABLE
        val id = "r${ids.incrementAndGet()}"
        return try {
            live.fire(ClientFrame.Inbound(id, text, messageId, receivedAt, taskId, attachments))
            InboundReceipt.FIRED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "channel inbound send failed: ${e.message}" }
            InboundReceipt.UNAVAILABLE
        }
    }

    /**
     * The bind's send: fire the inbound and AWAIT its result — the
     * botsService answers commands with system replies (taskRunning,
     * taskMissing, the status card) and the router's every decision must
     * read them, not guess at them.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // best-effort for its caller, never for the daemon
    suspend fun sendInboundAwait(
        text: String,
        messageId: String,
        receivedAt: Long,
        timeout: Duration = INBOUND_ANSWER_TIMEOUT,
    ): InboundAnswer {
        val live = session ?: return InboundAnswer.Unavailable
        val id = "r${ids.incrementAndGet()}"
        return try {
            when (val frame = live.request(ClientFrame.Inbound(id, text, messageId, receivedAt), id, timeout)) {
                is ServerFrame.InboundResult ->
                    InboundAnswer.Answered(duplicate = frame.duplicate, replies = frame.replies)

                is ServerFrame.ChannelError ->
                    InboundAnswer.Answered(duplicate = false, replies = listOf("${frame.code}: ${frame.message ?: ""}"))

                else -> InboundAnswer.Answered(duplicate = false, emptyList())
            }
        } catch (e: ChannelDown) {
            InboundAnswer.Unavailable
        } catch (e: TimeoutCancellationException) {
            InboundAnswer.TimedOut(timeout.inWholeMilliseconds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            InboundAnswer.Unavailable
        }
    }

    /** Remembers a subscription (replayed on every reconnect) and fires it now if a session is live. */
    suspend fun subscribe(taskId: String, workspacePath: String) {
        synchronized(subscriptions) { subscriptions[taskId] = workspacePath }
        val live = session ?: return
        runCatching { live.fire(ClientFrame.Subscribe("s${ids.incrementAndGet()}", taskId, workspacePath)) }
            .onSuccess { logger.info { "channel subscribed taskId=$taskId (frame sent)" } }
            .onFailure { logger.warn { "channel subscribe taskId=$taskId failed: ${it.message}" } }
    }

    /** /nuclear's daemon half: forget everything so the next connect starts clean. */
    fun reset() {
        synchronized(subscriptions) { subscriptions.clear() }
        session?.kill()
    }

    override fun close() {
        session?.kill()
    }

    private companion object {
        const val HELLO_ID = "hello"
        const val IGNORED_LINE_EVERY = 100
        val HANDSHAKE_TIMEOUT = 10.seconds
        val ACK_TIMEOUT = 15.seconds
        val PONG_TIMEOUT = 10.seconds

        /** The bind command's answer budget: commands answer fast (prompts may not). */
        val INBOUND_ANSWER_TIMEOUT = 8.seconds
    }
}
