package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import dev.lattenero.bot.transport.CH_REPLY
import dev.lattenero.bot.transport.Cooldown
import dev.lattenero.bot.transport.LastPh
import dev.lattenero.bot.transport.SendRequest
import dev.lattenero.bot.transport.TgApi
import dev.lattenero.bot.transport.TgConfig
import dev.lattenero.bot.transport.TgHttpException
import dev.lattenero.bot.transport.htmlTruncate
import dev.lattenero.bot.transport.messageIdOf
import dev.lattenero.bot.transport.retryAfterOf
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val logger = KotlinLogging.logger {}

/**
 * Pure: the editMessageText request (utils/http.py _live_edit's params
 * block) — one composition, like SendRequest, so the edit's wire is
 * pinnable by a test without a network. The markup (the cajita's
 * buttons) replaces the keyboard wholesale, Python's edit semantics.
 */
object EditRequest {
    fun compose(cfg: TgConfig, messageId: Long, text: String, markup: JsonObject? = null): JsonObject =
        buildJsonObject {
            put("chat_id", cfg.chatId)
            put("message_id", messageId)
            put("text", htmlTruncate(text, CH_REPLY))
            put("parse_mode", "HTML")
            if (markup != null) put("reply_markup", JsonPrimitive(PyText.jsonDumps(markup)))
        }
}

/**
 * Pure: the deleteMessage request — the TRANSCRIPT FEED's card swap: the
 * status card is redrawn as a new last message and the previous one dies.
 * One composition, pinnable like [EditRequest].
 */
object DeleteRequest {
    fun compose(cfg: TgConfig, messageId: Long): JsonObject = buildJsonObject {
        put("chat_id", cfg.chatId)
        put("message_id", messageId)
    }
}

/**
 * The live view's Telegram side — Python's `_live_send`/`_live_edit`
 * (F4d): the live view is OPTIONAL, a failure never breaks anything.
 * On a 429 the cooldown window WIDENS (the shared file — the same chat,
 * never the same process) and while it lasts the edits SKIP THEMSELVES:
 * streaming degrades, it never hammers.
 */
class LiveTelegram(
    private val api: TgApi,
    private val cooldown: Cooldown,
    private val lastPh: LastPh? = null,
) {

    /** sendMessage that answers the message_id (the placeholder's birth). */
    @Suppress("TooGenericExceptionCaught") // the live view never raises (Python's bare except)
    suspend fun birth(cfg: TgConfig, text: String): Long? {
        if (cooldown.active()) return null // throttled: the box is born dead — never critical
        return try {
            val mid = messageIdOf(api.call("sendMessage", SendRequest.compose(cfg, text, loud = false)))
            mid?.let { lastPh?.note(it) } // a live box is a message too (F84b)
            mid
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "live send failed: ${e.message}" }
            null
        }
    }

    /**
     * The QUESTION's send — the dedicated message with its buttons (the
     * answer round-trip's birth). A hanging question is CRITICAL: the
     * cooldown does not gate it (the card's throttle is the feed's
     * back-pressure, not the owner's answer — the turn blocks until the
     * owner speaks), but a 429 still widens the window. loud=true: the
     * phone must buzz — a question the owner never sees is a hung turn.
     */
    @Suppress("TooGenericExceptionCaught") // the live view never raises (Python's bare except)
    suspend fun ask(cfg: TgConfig, text: String, markup: JsonObject): Long? = try {
        messageIdOf(api.call("sendMessage", SendRequest.compose(cfg, text, loud = true, markup = markup)))
    } catch (e: CancellationException) {
        throw e
    } catch (e: TgHttpException) {
        retryAfterOf(e)?.let { cooldown.extend(it) }
        logger.warn { "question send failed: ${e.message}" }
        null
    } catch (e: Exception) {
        logger.warn { "question send failed: ${e.message}" }
        null
    }

    /** Best-effort editMessageText; false when skipped or failed. The markup rides when present. */
    @Suppress("TooGenericExceptionCaught", "ReturnCount") // the live view never raises (Python's bare except)
    suspend fun edit(cfg: TgConfig, messageId: Long, text: String, markup: JsonObject? = null): Boolean {
        if (messageId <= 0) return false
        if (cooldown.active()) return false
        return try {
            api.call("editMessageText", EditRequest.compose(cfg, messageId, text, markup))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: TgHttpException) {
            retryAfterOf(e)?.let { cooldown.extend(it) }
            logger.warn { "live edit failed: ${e.message}" }
            false
        } catch (e: Exception) {
            logger.warn { "live edit failed: ${e.message}" }
            false
        }
    }

    /**
     * The shared cooldown window's state — the TRANSCRIPT FEED's
     * back-pressure: while a 429 window lasts the feed's swap skips
     * ITSELF whole (the seen pointer holds, the next tick re-speaks the
     * same diff when the window lifts) — a parked outbox and a re-born
     * card must never race for the chat's order.
     */
    fun throttled(): Boolean = cooldown.active()

    /**
     * Best-effort deleteMessage — the card swap's other half. The swap's
     * order is delete → feed → re-birth (the owner's rule: the thinking
     * box is ALWAYS the last message — a card born first would be buried
     * by the feed), so a lost delete leaves a stale corpse ABOVE the
     * re-born card until the next swap eats it — noisy, never lying. A
     * delete is not a message send: the cooldown does not gate it, but a
     * 429 still widens the window.
     */
    @Suppress("TooGenericExceptionCaught") // the live view never raises (Python's bare except)
    suspend fun delete(cfg: TgConfig, messageId: Long): Boolean {
        if (messageId <= 0) return false
        return try {
            api.call("deleteMessage", DeleteRequest.compose(cfg, messageId))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: TgHttpException) {
            retryAfterOf(e)?.let { cooldown.extend(it) }
            logger.warn { "live delete failed: ${e.message}" }
            false
        } catch (e: Exception) {
            logger.warn { "live delete failed: ${e.message}" }
            false
        }
    }
}
