package dev.lattenero.bot.transport

import io.github.oshai.kotlinlogging.KotlinLogging
import kap.Kap
import kap.evalGraph
import kap.retry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/** send()'s answer: Python returns the message_id (truthy) or False — the cases stay distinct here. */
sealed interface SendResult {
    /** The message went out; messageId null means Telegram didn't include one (Python's bare True). */
    data class Sent(val messageId: Long?) : SendResult

    data object NotConfigured : SendResult

    /** The message did NOT go out (cooldown/breaker) — the outbox holds it (when aOutbox). */
    data object Parked : SendResult

    /**
     * Telegram REFUSED the payload itself (a final 400 — malformed HTML,
     * oversized…) and the outbox holds it when aOutbox. Distinct from
     * [Parked]: the drain counts THESE as poison attempts (an outage park
     * must never burn an item's retries — Telegram never said no to the
     * message, it said no to everything).
     */
    data object Rejected : SendResult
}

/**
 * The send path (utils/http.py send/outbox_flush), exact: cooldown
 * pre-check → breaker around the (0,1,3) retry → tg-last-ph on success;
 * every failure lands in the outbox for the poller to flush. Never
 * raises (a hook must never break the session); cancellation passes
 * through untouched.
 */
class Sender(
    private val api: TgApi,
    private val cooldown: Cooldown,
    private val outbox: Outbox,
    private val lastPh: LastPh,
    val breaker: TgBreaker = TgBreaker(),
) {
    /** breaker_state(): for the health line (healer) and tests. */
    val breakerState: String get() = breaker.state

    /**
     * Sends a message; returns the message_id if it went out. The id is
     * what allows EDITING that message later — a bare truthy left
     * button boxes stuck. loud=null → notifies only if loud_all is on.
     * markup → reply_markup (inline buttons), serialized as a JSON
     * STRING (Python's json.dumps(markup) inside the params).
     * aOutbox=false → no re-enqueue on failure (the flush's own items).
     */
    // never raises — Python's bare except; the breaker open IS the park answer
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException")
    suspend fun send(
        cfg: TgConfig,
        text: String,
        loud: Boolean? = null,
        markup: JsonObject? = null,
        aOutbox: Boolean = true,
    ): SendResult {
        if (!cfg.configured) return SendResult.NotConfigured
        if (cooldown.active()) {
            // Telegram named this window — parking beats burning attempts
            // inside it; the outbox flushes when it lifts
            logger.warn { "sendMessage: cooldown window — to the outbox" }
            if (aOutbox) outbox.put(text)
            return SendResult.Parked
        }
        // ONE composition path: the dry-run knob logs this exact object and
        // this call posts it — they cannot diverge by construction
        val params = SendRequest.compose(cfg, text, loud, markup)
        return try {
            val mid = breaker.call {
                Kap { messageIdOf(api.call("sendMessage", params)) }
                    .retry(
                        SendPolicy.schedule(),
                        onRetry = { n, exc, _ ->
                            if (retryAfterOf(exc) == null) { // the 429 wait is not an error
                                logger.warn { "sendMessage failed (attempt $n/${ATTEMPTS}): ${exc.message}" }
                            }
                        },
                    )
                    .evalGraph()
            }
            if (mid != null) lastPh.note(mid) // F84b: the ll floats under the newest
            SendResult.Sent(mid)
        } catch (e: CircuitBreakerOpen) {
            // Telegram/network is down and the breaker says so — the outbox
            // holds the message; getUpdates is the half-open probe
            logger.warn { "sendMessage: breaker OPEN — to the outbox" }
            park(aOutbox, text)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            retryAfterOf(e)?.let { cooldown.extend(it) }
            logger.warn { "sendMessage failed (final): ${e.message}" }
            park(aOutbox, text)
            // Rejected SOLO cuando Telegram entendió el request y rechazó el
            // PAYLOAD (un 4xx — el HTML crudo de /steer): un 429 (throttle,
            // el mensaje está bien), la red y los timeouts son PARKED — el
            // cap de veneno del drain jamás quema attempts de una outage.
            if (payloadRejection(e)) SendResult.Rejected else SendResult.Parked
        }
    }

    private suspend fun park(aOutbox: Boolean, text: String): SendResult {
        if (aOutbox) outbox.put(text)
        return SendResult.Parked
    }

    /**
     * outbox_flush: resends what piled up once the network came back.
     * The lock only covers the SWAP (fast) — sends go OUTSIDE, failed
     * ones go back to the FRONT of the queue, aOutbox=false avoids the
     * nested re-enqueue (the 15:47 self-deadlock lesson).
     *
     * The drain is PACED (one message per FLUSH_PAUSE_S): a back-to-back
     * flush of a parked backlog is a burst that re-trips Telegram's
     * flood limit the instant the window lifts — the ratchet that took
     * the penalty from seconds to hours on 29/10 (third offense; the
     * same burst Python's outbox_flush had). The first Parked answer
     * re-queues the REST untouched (they would all park into `remaining`
     * anyway — one cooldown read, no loop theater).
     */
    suspend fun outboxFlush(cfg: TgConfig, pause: suspend () -> Unit = { delay(FLUSH_PAUSE_S) }): Int {
        val taken = outbox.takeAll() ?: return 0
        var ok = 0
        val remaining = mutableListOf<dev.lattenero.bot.contracts.OutboxItem>()
        for ((index, item) in taken.withIndex()) {
            if (index > 0) pause() // the burst guard: sustained, never back-to-back
            when (send(cfg, item.text, loud = false, aOutbox = false)) {
                is SendResult.Sent -> ok++

                // THE POISON CAP (2/11): a message Telegram REJECTS forever
                // (the /steer <texto> 400 — a raw HTML tag in the copy) used
                // to cycle at the front of every drain pass, each pass
                // counting as breaker failures → the breaker never saw a
                // success → OPEN forever → the WHOLE bot went mute behind
                // one command's usage hint. Only a REAL rejection burns an
                // attempt — a breaker/cooldown park (an outage) re-queues
                // untouched. After MAX_ITEM_ATTEMPTS rejections the item is
                // dropped with a loud log line.
                SendResult.Rejected -> {
                    val bumped = item.copy(attempts = item.attempts + 1)
                    if (bumped.attempts >= MAX_ITEM_ATTEMPTS) {
                        logger.warn {
                            "outbox: DROPPED a poison item after ${bumped.attempts} rejections " +
                                "(Telegram refuses it every time): ${bumped.text.take(POISON_LOG_PREVIEW)}"
                        }
                    } else {
                        remaining.add(bumped)
                    }
                    if (cooldown.active()) {
                        // a 429 opened the window mid-drain — the rest would
                        // all park; re-queue them untouched and stop (a lone
                        // 4xx does NOT stop the drain: the rest still go out
                        // — Python's semantics)
                        remaining.addAll(taken.drop(index + 1))
                        break
                    }
                }

                // an outage park (cooldown/breaker): NOT the item's fault —
                // re-queue untouched, zero attempts burned
                SendResult.Parked, SendResult.NotConfigured -> remaining.add(item)
            }
        }
        outbox.putBack(remaining)
        return ok
    }

    /**
     * Telegram understood the request and rejected the PAYLOAD: any 4xx
     * except 429 (the throttle is not the message's fault). The drain's
     * poison cap counts only these.
     */
    private fun payloadRejection(e: Throwable): Boolean {
        val code = (e as? TgHttpException)?.code
        return code != null && code in REJECTION_MIN..REJECTION_MAX && code != THROTTLE
    }

    private companion object {
        const val ATTEMPTS = 3
        private const val REJECTION_MIN = 400
        private const val REJECTION_MAX = 499
        private const val THROTTLE = 429

        /**
         * The poison cap: one Telegram-rejected-forever message dies after
         * 5 failed flush passes (~5 cycles) instead of holding the whole
         * send path hostage (the breaker's half-open probe kept landing on
         * it). A transient failure survives dozens of passes; 5 straight
         * passes of a FINAL failure is a malformed payload, not an outage.
         */
        const val MAX_ITEM_ATTEMPTS = 5

        /** The dropped-poison log preview (code points, the render's own cap helper). */
        private const val POISON_LOG_PREVIEW = 120

        /** 1 msg/s is Telegram's per-chat sustained line — the flush rides it exactly. */
        private val FLUSH_PAUSE_S = 1.seconds
    }
}
