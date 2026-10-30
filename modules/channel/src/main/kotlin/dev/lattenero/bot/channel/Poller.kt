package dev.lattenero.bot.channel

import dev.lattenero.bot.transport.CircuitBreakerOpen
import dev.lattenero.bot.transport.TgApi
import dev.lattenero.bot.transport.TgConfig
import dev.lattenero.bot.transport.TgHttpException
import dev.lattenero.bot.transport.TgOffset
import dev.lattenero.bot.transport.TgBreaker
import io.github.oshai.kotlinlogging.KotlinLogging
import kap.Kap
import kap.Process
import kap.RestartPolicy
import kap.Schedule
import kap.process
import kap.repeat
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/** One Telegram update normalized into the dispatcher's shape (or skipped). */
sealed interface InboundUpdate {
    val updateId: Long
    val fromId: Long?

    /** The owner's words. */
    data class Message(override val updateId: Long, override val fromId: Long?, val text: String) : InboundUpdate

    /**
     * A button tap (the markup hot-fix): `data` is the callback_data
     * (Python's R5 vocabulary), `callbackId` answers the tap's toast.
     */
    data class Callback(
        override val updateId: Long,
        override val fromId: Long?,
        val callbackId: String,
        val data: String,
    ) : InboundUpdate
}

/** One poll cycle's outcome — the offset to confirm POST-dispatch (F2d's at-least-once). */
data class PollOutcome(val offset: Long, val messages: Int, val conflict: Boolean = false)

/**
 * B3b's poller — getUpdates as a Kap Process, SHIPPED OFF (`poll` knob in
 * daemon.json, read at boot like the send knob). The sequencing rule is
 * ADR-001's single reader: TWO pollers on one token fight Telegram's 409,
 * so Python keeps the phone's reads until the retirement moment — then
 * `retire-python.sh` flips this knob on and unloads Python's agents, and
 * THIS process becomes the one reader.
 *
 * The cycle is Python's `AsyncPoller` shape in values: a SHORT long-poll
 * (3 s — hooks used to share the token; 3 s keeps the worst-case latency
 * bounded), the fail-closed owner filter, serial dispatch, and the
 * offset confirmed only AFTER the batch was dispatched (the 14/10 fix:
 * a crash mid-batch replays the batch, it never loses it). getUpdates
 * rides the shared breaker — when Telegram is down it fails fast and IS
 * the half-open probe; a 409 is POLICY, not an error: it means somebody
 * else still polls (the retirement's out-of-order case), the cycle logs
 * it LOUD and moves on.
 */
class Poller(
    private val api: TgApi,
    private val offsetFile: File,
    private val dispatch: suspend (TgConfig, InboundUpdate) -> Unit,
    private val breaker: TgBreaker = TgBreaker(),
    private val longPoll: Duration = 3.seconds,
    private val limit: Int = 100,
    /** The bot explains its own silences: a long getUpdates failure window ends with the apology. */
    private val onReconnected: suspend (MuteWindow) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mute = MuteTracker(clock)

    /** One cycle: fetch → filter → dispatch → confirm the offset. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount") // poison must not crash-loop (F35b)
    suspend fun cycle(cfg: TgConfig): PollOutcome {
        if (!cfg.configured) return PollOutcome(TgOffset.current(offsetFile), 0)
        val offset = TgOffset.current(offsetFile)
        val (next, messages) = try {
            fetch(cfg, offset)
        } catch (e: CircuitBreakerOpen) {
            // policy, not error: the breaker is OPEN — an empty cycle; the
            // probe reopens on its own timeout. The phone is still deaf
            // while it lasts: the mute window EXTENDS, it does not close.
            mute.onFetchFailed()
            logger.warn { "getUpdates: breaker OPEN (${e.retryAfter}s) — empty cycle, the probe reopens it" }
            return PollOutcome(offset, 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mute.onFetchFailed()
            logger.warn { "getUpdates failed: ${e.message}" }
            return PollOutcome(offset, 0, conflict = (e as? TgHttpException)?.code == CONFLICT)
        }
        mute.onFetchOk()?.let { window ->
            logger.warn { "poller RECOVERED after a ${window.toMs - window.fromMs}ms mute window — telling the owner" }
            onReconnected(window)
        }
        for (message in messages) {
            try {
                dispatch(cfg, message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "dispatch crashed (moving on): update_id=${message.updateId}" }
            }
        }
        TgOffset.save(offsetFile, next) // POST-dispatch: at-least-once, never at-most-once
        return PollOutcome(next, messages.size)
    }

    /** fetch_updates: breaker around the call, offset advance over every update, the owner gate. */
    @Suppress("ReturnCount") // one guard per degrade: breaker, empty result, unreadable body
    private suspend fun fetch(cfg: TgConfig, offset: Long): Pair<Long, List<InboundUpdate>> {
        val params = buildJsonObject {
            put("offset", offset)
            put("timeout", longPoll.inWholeSeconds)
            put("limit", limit)
        }
        val result = breaker.call {
            api.call("getUpdates", params)
        }
        val updates = (result as? JsonObject)?.get("result") as? kotlinx.serialization.json.JsonArray
            ?: return offset to emptyList()
        var next = offset
        val messages = mutableListOf<InboundUpdate>()
        @Suppress("LoopWithTooManyJumpStatements") // the update ladder: one guard per skip
        for (u in updates) {
            val obj = u as? JsonObject ?: continue
            val updateId = obj["update_id"]?.let { (it as? JsonPrimitive)?.longOrNull } ?: continue
            next = maxOf(next, updateId + 1)
            normalize(cfg, obj, updateId)?.let(messages::add)
        }
        return next to messages
    }

    /**
     * normalize_update + from_owner in one pass: the update's message OR
     * callback_query, stamped with its update_id; the owner filter is
     * FAIL-CLOSED — with no owner_user_id in the config NOBODY gets
     * through (never the first message that arrives), and it gates the
     * BUTTON TAPS too (a button is a message).
     */
    @Suppress("ReturnCount") // normalize_update guard chain, one per line (Python shape)
    private fun normalize(cfg: TgConfig, update: JsonObject, updateId: Long): InboundUpdate? {
        val owner = cfg.ownerUserId
        if (owner == null) {
            logger.warn { "from_owner: config without owner_user_id — update discarded (fail-closed)" }
            return null
        }
        val from = { obj: JsonObject ->
            (obj["from"] as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.longOrNull }
        }
        val message = update["message"] as? JsonObject ?: update["edited_message"] as? JsonObject
        if (message != null) {
            val text = (message["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val fromId = from(message)
            if (fromId != owner) return null
            return InboundUpdate.Message(updateId = updateId, fromId = fromId, text = text)
        }
        val callback = update["callback_query"] as? JsonObject ?: return null
        val callbackId = (callback["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val data = (callback["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val fromId = from(callback)
        if (fromId != owner) return null
        return InboundUpdate.Callback(updateId = updateId, fromId = fromId, callbackId = callbackId, data = data)
    }

    /** The poller Process: one cycle per iteration, the long-poll inside the body is the pacing. */
    fun asProcess(
        config: () -> TgConfig,
        heartbeatEvery: Duration = 30.seconds,
        onHeartbeat: () -> Unit = {},
    ): Process<PollOutcome> =
        Kap { cycle(config()) }
            .repeat(Schedule.spaced(1.seconds))
            .process(
                name = "poller",
                restart = RestartPolicy.exponential(base = 1.seconds, max = 15.seconds),
                heartbeatEvery = heartbeatEvery,
                onHeartbeat = onHeartbeat,
                onRestart = { attempt, error, delay ->
                    logger.warn(error) { "poller cycle failed — restart $attempt in $delay" }
                },
            )

    private companion object {
        const val CONFLICT = 409
    }
}
