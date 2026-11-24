package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import dev.lattenero.bot.transport.CH_REPLY
import dev.lattenero.bot.transport.Cooldown
import dev.lattenero.bot.transport.LastPh
import dev.lattenero.bot.transport.RichRequest
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
 * The render throttle's verdict for one edit — a CRITICAL edit (the question
 * surface) is NEVER gated: the pending question outranks the feed's
 * back-pressure, the same reasoning [LiveTelegram.ask] already carries. Pure,
 * so the law is pinned without a socket.
 */
internal fun editThrottled(cooldownActive: Boolean, critical: Boolean): Boolean = !critical && cooldownActive

/**
 * The live view's Telegram side — Python's `_live_send`/`_live_edit`
 * (F4d): the live view is OPTIONAL, a failure never breaks anything.
 * On a 429 the cooldown window WIDENS (the shared file — the same chat,
 * never the same process) and while it lasts the edits SKIP THEMSELVES:
 * streaming degrades, it never hammers.
 */
// The Telegram edge's whole surface (birth/edit/question/rich/delete) — the
// count is the port's shape, not a grab bag.
@Suppress("TooManyFunctions")
class LiveTelegram(
    private val api: TgApi,
    private val cooldown: Cooldown,
    private val lastPh: LastPh? = null,
) {

    /**
     * The card's birth — rich first (Bot API 10.1), the classic
     * `sendMessage` on any rejection. [markup] is the card's own keyboard.
     */
    suspend fun birth(cfg: TgConfig, text: String, markup: JsonObject? = null): Long? {
        if (cooldown.active()) return null // throttled: the box is born dead — never critical
        return richSend(cfg, text, loud = false, markup = markup) ?: plainBirth(cfg, text, markup)
    }

    @Suppress("TooGenericExceptionCaught") // the live view never raises (Python's bare except)
    private suspend fun plainBirth(cfg: TgConfig, text: String, markup: JsonObject?): Long? = try {
        val wire = SendRequest.compose(cfg, text, loud = false, markup = markup)
        val mid = messageIdOf(api.call("sendMessage", wire))
        mid?.let { lastPh?.note(it) } // a live box is a message too (F84b)
        mid
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn { "live send failed: ${e.message}" }
        null
    }

    /**
     * The QUESTION's send — rich first, the classic `sendMessage` with its
     * buttons on rejection. A hanging question is CRITICAL: the cooldown
     * does not gate it, but a 429 still widens the window. loud=true: the
     * phone must buzz — a question the owner never sees is a hung turn.
     */
    suspend fun ask(cfg: TgConfig, text: String, markup: JsonObject): Long? =
        richSend(cfg, text, loud = true, markup = markup) ?: plainAsk(cfg, text, markup)

    @Suppress("TooGenericExceptionCaught") // the live view never raises (Python's bare except)
    private suspend fun plainAsk(cfg: TgConfig, text: String, markup: JsonObject): Long? = try {
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

    /**
     * THE VIOLET SEND (the owner, 5/11): the LLM's voice as a Bot API 10.x
     * rich message — [RichVoice] builds the blocks, this is the leg.
     * BEST-EFFORT with the honest degradation: any rejection (a Bot API
     * that never heard of 10.x, a client too old, a malformed block)
     * falls back ONCE to the classic HTML blockquote send — the same
     * quote-look on every client, the channel never dies with the API
     * version. The cooldown gates it like every send.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount") // the live view never raises; one exit per leg
    suspend fun sendRich(cfg: TgConfig, blocks: List<JsonObject>, markup: JsonObject? = null): Long? {
        if (cooldown.active()) return null
        return try {
            messageIdOf(api.call("sendRichMessage", RichVoice.compose(cfg, blocks, markup)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.info { "rich send fell back to blockquote: ${e.message}" }
            fallbackQuote(cfg, blocks, markup)
        }
    }

    /** The degradation: the same voice as an HTML blockquote (every client's violet). */
    @Suppress("TooGenericExceptionCaught") // the live view never raises (Python's bare except)
    private suspend fun fallbackQuote(cfg: TgConfig, blocks: List<JsonObject>, markup: JsonObject?): Long? = try {
        val text = blocks.joinToString("\n\n") { block ->
            val body = block["text"]?.let { it as? JsonPrimitive }?.content ?: ""
            when (block["type"]?.let { it as? JsonPrimitive }?.content) {
                "pre" -> "<pre>${PyText.esc(body)}</pre>"
                "paragraph" -> PyText.esc(body) // normal text, no container
                "expandable_blockquote" -> "<blockquote expandable>${PyText.esc(body)}</blockquote>"
                else -> "<blockquote>${PyText.esc(body)}</blockquote>"
            }
        }
        messageIdOf(api.call("sendMessage", SendRequest.compose(cfg, text, loud = false, markup = markup)))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn { "violet fallback failed: ${e.message}" }
        null
    }

    /**
     * THE RICH HTML LEG (24/11): the bot's existing HTML — tables, `<pre>`,
     * bold, lines — as a Bot API 10.1 rich body. `null` answers a rejection
     * (or a spent cooldown) and the CALLER keeps its own HTML fallback: a
     * rich body sent through `sendMessage` would show its tags raw, so this
     * leg never launders one dialect into the other.
     */
    suspend fun sendRichHtml(
        cfg: TgConfig,
        html: String,
        loud: Boolean = false,
        markup: JsonObject? = null,
    ): Long? {
        if (cooldown.active()) return null
        return richSend(cfg, html, loud, markup)
    }

    /** The raw rich send (no cooldown gate — the CRITICAL callers get their own): rejection → null. */
    @Suppress("TooGenericExceptionCaught") // best-effort by contract: the caller owns the fallback
    private suspend fun richSend(cfg: TgConfig, html: String, loud: Boolean, markup: JsonObject?): Long? = try {
        val mid = messageIdOf(api.call("sendRichMessage", RichRequest.composeSend(cfg, html, loud, markup)))
        mid?.let { lastPh?.note(it) }
        mid
    } catch (e: CancellationException) {
        throw e
    } catch (e: TgHttpException) {
        retryAfterOf(e)?.let { cooldown.extend(it) }
        logger.warn { "rich html send failed: ${e.message}" }
        null
    } catch (e: Exception) {
        logger.warn { "rich html send failed: ${e.message}" }
        null
    }

    /**
     * The rich twin of [edit]: same throttle (a critical edit never waits),
     * same 429 widening, `rich_message` instead of `text`. `false` = the
     * caller may fall back to the plain edit.
     */
    @Suppress("ReturnCount") // the id guard, the throttle guard, the leg — three honest exits
    suspend fun editRichHtml(
        cfg: TgConfig,
        messageId: Long,
        html: String,
        markup: JsonObject? = null,
        critical: Boolean = false,
    ): Boolean {
        if (messageId <= 0) return false
        if (editThrottled(cooldown.active(), critical)) {
            logger.info { "rich edit skipped (cooldown window) mid=$messageId" }
            return false
        }
        return editRichLeg(cfg, messageId, html, markup)
    }

    @Suppress("TooGenericExceptionCaught") // the live view never raises (Python's bare except)
    private suspend fun editRichLeg(cfg: TgConfig, messageId: Long, html: String, markup: JsonObject?): Boolean = try {
        api.call("editMessageText", RichRequest.composeEdit(cfg, messageId, html, markup))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: TgHttpException) {
        retryAfterOf(e)?.let { cooldown.extend(it) }
        logger.warn { "rich edit failed: ${e.message}" }
        false
    } catch (e: Exception) {
        logger.warn { "rich edit failed: ${e.message}" }
        false
    }

    /**
     * THE CLASSIC EDIT (the scroll law, 24/11): a message BORN classic (the
     * tool boxes, the file viewer) is edited classic too — a rich `pre`
     * wraps long lines on the client, the classic `<pre>` scrolls, and a
     * mid-life dialect switch would wrap the page the owner just opened.
     */
    @Suppress("ReturnCount") // the id guard and the throttle guard — two honest exits
    suspend fun editPlain(
        cfg: TgConfig,
        messageId: Long,
        text: String,
        markup: JsonObject? = null,
        critical: Boolean = false,
    ): Boolean {
        if (messageId <= 0) return false
        if (editThrottled(cooldown.active(), critical)) {
            logger.info { "plain edit skipped (cooldown window) mid=$messageId" }
            return false
        }
        return editLeg(cfg, messageId, text, markup)
    }

    /** Best-effort editMessageText; false when skipped or failed. The markup rides when present. */
    @Suppress("ReturnCount") // the skip and the leg are two honest exits
    suspend fun edit(cfg: TgConfig, messageId: Long, text: String, markup: JsonObject? = null): Boolean {
        if (messageId <= 0) return false
        if (editThrottled(cooldown.active(), critical = false)) {
            // the skip is NEVER silent: a dropped render is a finding, not a whisper
            logger.info { "live edit skipped (cooldown window) mid=$messageId" }
            return false
        }
        return editRichThenPlain(cfg, messageId, text, markup)
    }

    /**
     * THE QUESTION SURFACE's edit — the same call WITHOUT the render
     * throttle, and with a receipt on EVERY outcome. A pending question is
     * CRITICAL (the [ask] law): the advance or the seal that redraws its
     * card must never be dropped by the feed's back-pressure, or the owner
     * keeps answering a question that already advanced (measured 24/11:
     * the 4th question of a form never reached the phone). Low volume, so
     * the receipt is affordable — and it is the only way to tell a lost
     * edit from a lost network.
     */
    suspend fun editCritical(cfg: TgConfig, messageId: Long, text: String, markup: JsonObject? = null): Boolean {
        if (messageId <= 0) {
            logger.warn { "question card edit skipped: no message id" }
            return false
        }
        val ok = editRichThenPlain(cfg, messageId, text, markup)
        logger.info { "question card edit mid=$messageId → ${if (ok) "ok" else "failed"}" }
        return ok
    }

    /**
     * The rich edit first (the phone gets tables and line structure), the
     * classic HTML edit on a REJECTION. A 429 already widened the shared
     * window, so the plain retry would only eat a second refusal — it is
     * skipped in that case.
     */
    private suspend fun editRichThenPlain(cfg: TgConfig, messageId: Long, text: String, markup: JsonObject?): Boolean =
        editRichLeg(cfg, messageId, text, markup) || (!cooldown.active() && editLeg(cfg, messageId, text, markup))

    /** The one leg: the call and its honest failures. A 429 widens the shared window. */
    @Suppress("TooGenericExceptionCaught") // the live view never raises (Python's bare except)
    private suspend fun editLeg(cfg: TgConfig, messageId: Long, text: String, markup: JsonObject?): Boolean = try {
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
