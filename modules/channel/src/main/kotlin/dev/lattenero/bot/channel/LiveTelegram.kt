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
}
