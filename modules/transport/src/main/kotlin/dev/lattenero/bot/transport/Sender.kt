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

    /** The message did NOT go out; the outbox holds it (when aOutbox). */
    data object Parked : SendResult
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
            if (send(cfg, item.text, loud = false, aOutbox = false) is SendResult.Sent) {
                ok++
            } else {
                remaining.add(item)
                if (cooldown.active()) {
                    // a 429 opened the window mid-drain — the rest would all
                    // park; re-queue them untouched and stop (a lone 4xx does
                    // NOT stop the drain: the poison item stays at the front,
                    // the rest still go out — Python's semantics)
                    remaining.addAll(taken.drop(index + 1))
                    break
                }
            }
        }
        outbox.putBack(remaining)
        return ok
    }

    private companion object {
        const val ATTEMPTS = 3

        /** 1 msg/s is Telegram's per-chat sustained line — the flush rides it exactly. */
        private val FLUSH_PAUSE_S = 1.seconds
    }
}
