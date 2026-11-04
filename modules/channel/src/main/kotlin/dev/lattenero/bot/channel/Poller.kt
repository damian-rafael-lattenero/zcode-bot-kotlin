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

    /**
     * The owner's words. `messageId` is Telegram's own message id — the
     * F23 reactions (👀/✅) answer THE message, not a new one; 0 when a
     * shape ever arrives without it (the react guard then skips).
     */
    data class Message(
        override val updateId: Long,
        override val fromId: Long?,
        val text: String,
        val messageId: Long = 0,
    ) : InboundUpdate

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

    /**
     * ATTACHMENTS IN (30/10): the owner's photo or document, headed for
     * the agent — the wiring downloads it (the bot's own token, the Bot
     * API's getFile) into the daemon's state and rides the inbound frame's
     * attachments (localPath; the caption is the prompt's text). The SIZE
     * is Telegram's word (absent = unknown — the gate treats unknown as
     * passable; the honest refusals happen at the wiring).
     */
    data class Attachment(
        override val updateId: Long,
        override val fromId: Long?,
        val messageId: Long = 0,
        /** The wire's kind: «image» (photos) or «file» (documents). */
        val kind: String,
        val fileId: String,
        val filename: String,
        val mimeType: String,
        val sizeBytes: Long?,
        /** The photo/document caption — the prompt's text (empty when absent). */
        val caption: String,
    ) : InboundUpdate
}

/**
 * One poll cycle's outcome — the offset to confirm POST-dispatch (F2d's
 * at-least-once). [messages] counts what was DISPATCHED; [cursorLost]
 * (U9/F1) says this pass began with a cursor that was never live, so the
 * batch it fetched was consumed WITHOUT dispatching (see [Poller]).
 */
data class PollOutcome(
    val offset: Long,
    val messages: Int,
    val conflict: Boolean = false,
    val cursorLost: Boolean = false,
)

/**
 * B3b's poller — getUpdates as a Kap Process, SHIPPED OFF (`poll` knob in
 * daemon.json, read at boot like the send knob). The sequencing rule is
 * ADR-001's single reader: TWO pollers on one token fight Telegram's 409,
 * so Python keeps the phone's reads until the retirement moment — then
 * `retire-python.nu` flips this knob on and unloads Python's agents, and
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
    /**
     * The wiring's E2 tap: one call per completed pass with its outcome
     * (a plain lambda — this module stays stdlib-pure).
     */
    private val onCycle: (PollOutcome) -> Unit = {},
    /**
     * U9/F1: the cursor was never live (absent = first run, corrupt = a
     * poisoned file) and a fetched batch was consumed WITHOUT dispatching.
     * The count is what was dropped; the OWNER hears it once (the honest
     * law: a silent drop is a lie by omission).
     */
    private val onCursorLost: suspend (Int) -> Unit = {},
) {
    private val mute = MuteTracker(clock)

    /**
     * The in-memory cursor FLOOR: the highest update_id this process has
     * already consumed. It outranks the file so a disk that could not take
     * the last save can never make THIS process re-dispatch a batch it
     * already ran (U9/F1's other half — a failing write is not a license
     * to replay /nuclear).
     */
    private var floor: Long = 0L

    /** One cycle: fetch → filter → dispatch → confirm the offset. */
    suspend fun cycle(cfg: TgConfig): PollOutcome {
        val outcome = runCycle(cfg)
        onCycle(outcome)
        return outcome
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount") // poison must not crash-loop (F35b)
    private suspend fun runCycle(cfg: TgConfig): PollOutcome {
        if (!cfg.configured) return PollOutcome(maxOf(floor, TgOffset.current(offsetFile)), 0)
        val cursor = TgOffset.read(offsetFile)
        val offset = maxOf(cursor.value, floor)
        // U9/F1: a cursor that was never LIVE reads as 0, and 0 makes
        // Telegram replay up to 24 h of updates — including every old
        // `/nuclear go` / `/deploy go`. The FIRST pass of a lost cursor
        // therefore CONSUMES the backlog without dispatching it (the
        // owner is told); later passes ride the in-memory floor and flow.
        val cursorLost = cursor.state != TgOffset.CursorState.LIVE && floor == 0L
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
        if (cursorLost) {
            logger.warn {
                "getUpdates cursor LOST (${cursor.state}) — ${messages.size} fetched update(s) consumed WITHOUT " +
                    "dispatching: a replay would re-run old commands (U9/F1); the cursor moves to $next"
            }
            saveCursor(next) // POST-consumption: the backlog dies HERE, never in the dispatcher
            runCatching { onCursorLost(messages.size) }
                .onFailure { logger.warn(it) { "cursor-lost notice failed (the drop stands)" } }
            return PollOutcome(next, 0, cursorLost = true)
        }
        for (message in messages) {
            // THE REPLAY FIX (4/11): an update that can KILL this daemon
            // mid-dispatch commits its cursor THROUGH ITSELF FIRST. If the
            // dispatch dies with the process (the nuclear ladder's own
            // self-kickstart, the deploy script's bootout), the restart
            // must not RE-DELIVER the update: every in-process single-flight
            // gate (nuclearGate, the deploy's consumed arm) dies with the
            // process and cannot protect across it (measured: nuclear fired
            // twice, deploys chained «muchas veces»). Everything else keeps
            // the at-least-once post-commit law untouched.
            if (isSuicidal(message)) saveCursor(message.updateId + 1)
            try {
                dispatch(cfg, message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "dispatch crashed (moving on): update_id=${message.updateId}" }
            }
        }
        saveCursor(next) // POST-dispatch: at-least-once, never at-most-once
        return PollOutcome(next, messages.size)
    }

    /**
     * Confirms the cursor: the FLOOR first (in-process truth), then the
     * file. A failing save is logged and swallowed — the floor already
     * keeps this process from re-dispatching, so a read-only disk costs
     * durability across restarts, never a replay today.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // the floor is the guarantee; the disk is the nicety
    private fun saveCursor(next: Long) {
        floor = maxOf(floor, next)
        try {
            TgOffset.save(offsetFile, next)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) {
                "tg-offset SAVE FAILED at $next — the in-memory floor keeps this process from re-dispatching"
            }
        }
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
            val fromId = from(message)
            if (fromId != owner) return null
            val mid = (message["message_id"] as? JsonPrimitive)?.longOrNull ?: 0L
            val text = (message["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (text != null) {
                return InboundUpdate.Message(updateId = updateId, fromId = fromId, text = text, messageId = mid)
            }
            // ATTACHMENTS IN (30/10): the owner's photo or document — an
            // attachment update, downloaded and delivered by the wiring
            normalizeAttachment(message, updateId, fromId, mid)?.let { return it }
            return null
        }
        val callback = update["callback_query"] as? JsonObject ?: return null
        val callbackId = (callback["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val data = (callback["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val fromId = from(callback)
        if (fromId != owner) return null
        return InboundUpdate.Callback(updateId = updateId, fromId = fromId, callbackId = callbackId, data = data)
    }

    /**
     * The photo/document ladder (ATTACHMENTS IN): a well-formed photo (an
     * ARRAY of sizes — the largest is the one worth downloading) or a
     * document ({file_name, mime_type, file_size?, file_id}). A malformed
     * shape (Telegram's `"photo":"x"` degenerate or an unnamed document)
     * is skipped — the cursor already advanced, the honest silence is the
     * log's. Photos carry no name: the wire's filename is the update's own.
     */
    @Suppress("ReturnCount") // the attachment ladder: one guard per photo/document shape — Python's normalize shape
    private fun normalizeAttachment(
        message: JsonObject,
        updateId: Long,
        fromId: Long?,
        mid: Long,
    ): InboundUpdate.Attachment? {
        val caption = (message["caption"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        (message["photo"] as? kotlinx.serialization.json.JsonArray)?.let { sizes ->
            val largest = largestPhoto(sizes) ?: return null
            val fileId = largest.str("file_id") ?: return null
            return InboundUpdate.Attachment(
                updateId = updateId,
                fromId = fromId,
                messageId = mid,
                kind = "image",
                fileId = fileId,
                filename = "photo-$updateId.jpg",
                mimeType = "image/jpeg",
                sizeBytes = largest.long("file_size"),
                caption = caption,
            )
        }
        val document = message["document"] as? JsonObject ?: return null
        val fileId = document.str("file_id") ?: return null
        return InboundUpdate.Attachment(
            updateId = updateId,
            fromId = fromId,
            messageId = mid,
            kind = "file",
            fileId = fileId,
            filename = document.str("file_name")?.takeIf { it.isNotBlank() }
                ?: "document-$updateId",
            mimeType = document.str("mime_type") ?: "application/octet-stream",
            sizeBytes = document.long("file_size"),
            caption = caption,
        )
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

    internal companion object {
        const val CONFLICT = 409

        /**
         * The photo's LARGEST size row (the one the Bot API serves whole) — null
         * when none carries a file_id. Pixels first, bytes as the tiebreak.
         */
        internal fun largestPhoto(sizes: kotlinx.serialization.json.JsonArray): JsonObject? =
            sizes.mapNotNull { it as? JsonObject }
                .filter { it.str("file_id") != null }
                .maxWithOrNull(
                    compareBy(
                        { size -> (size.long("width") ?: 0L) * (size.long("height") ?: 0L) },
                        { size -> size.long("file_size") ?: 0L },
                    ),
                )
    }
}

/** The photo's LARGEST size row — PIXELS first (area), bytes only break the tie. */
internal fun largestPhoto(sizes: kotlinx.serialization.json.JsonArray): JsonObject? =
    sizes.mapNotNull { it as? JsonObject }
        .filter { it.str("file_id") != null }
        .maxWithOrNull(
            compareBy(
                { (it.long("width") ?: 0L) * (it.long("height") ?: 0L) },
                { it.long("file_size") ?: 0L },
            ),
        )

/**
 * THE SUICIDAL UPDATES (the offset law's OWN vocabulary, 4/11): the
 * updates whose dispatch can KILL this daemon — the nuclear ladder's
 * self-kickstart, /stop's hard half, the bot/both deploys' bootout of
 * THIS process. Deliberately SEPARATE from [isDestructiveTap]'s
 * DESTRUCTIVE vocabularies (that one is the owner gate, a different
 * concern — never merged): the `intr:/odp:/sst:/gfy` interrupts do NOT
 * kill this process, so they stay post-commit; `deploy` pre-commits
 * WHOLE (bare /deploy replaying the card twice is harmless, but a simple
 * law beats a clever one).
 */
private val SUICIDAL_COMMANDS = setOf("nuclear", "nuke", "stop", "deploy")
private val SUICIDAL_TAP_PREFIXES = listOf("nuc:", "stop:", "dep:")

/** True when dispatching [update] may kill this process → the cursor commits BEFORE it. */
internal fun isSuicidal(update: InboundUpdate): Boolean = when (update) {
    is InboundUpdate.Message -> {
        val cmd = BotCommands.parseCommand(update.text)?.first
        cmd != null && cmd in SUICIDAL_COMMANDS
    }

    // the tap prefixes PLUS the existing destructive verdict — a callback
    // whose data SPELLS the words (the fallback's own shapes) pre-commits too
    is InboundUpdate.Callback ->
        SUICIDAL_TAP_PREFIXES.any(update.data::startsWith) || isDestructiveTap(update.data)

    is InboundUpdate.Attachment -> false
}
