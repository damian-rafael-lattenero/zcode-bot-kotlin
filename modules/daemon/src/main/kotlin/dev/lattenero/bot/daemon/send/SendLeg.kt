package dev.lattenero.bot.daemon.send

import dev.lattenero.bot.contracts.ShadowDecision
import dev.lattenero.bot.transport.SendRequest
import dev.lattenero.bot.transport.SendResult
import dev.lattenero.bot.transport.TgConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * What a decision's SEND leg does, as one injected effect (policy §1: the
 * decision stays pure, the effect lives at the edge). The shadow log is
 * NOT this leg's business — every decision lands in the shadow dir's
 * per-watcher JSONL whatever the mode, because the comparator keeps
 * diffing as the post-cutover audit (a DIVERGENT after the flip is
 * evidence, not a blocker). This leg is only the Telegram side.
 */
fun interface SendLeg {
    suspend fun deliver(decision: ShadowDecision, loud: Boolean)
}

/** The exact request dry-run logs and live posts — one value, so the log IS the wire. */
data class ComposedRequest(val method: String, val url: String, val body: String)

/**
 * Pure: the request the daemon would send for this decision. The body is
 * `params.toString()` — byte-for-byte what [dev.lattenero.bot.transport.TgApi]
 * posts as the request body; the url is the redacted twin (the token can
 * never reach a log).
 */
fun composeSend(cfg: TgConfig, text: String, loud: Boolean): ComposedRequest = ComposedRequest(
    method = "sendMessage",
    url = "${cfg.apiBase}/bot${cfg.redact(cfg.token)}/sendMessage",
    body = SendRequest.compose(cfg, text, loud).toString(),
)

/** The legs, one per knob position; the config source is injected (Python's per-call `load_config()` cadence). */
object SendLegs {

    /** SHADOW: nothing — Python speaks (ADR-023's contract, unchanged). */
    val NONE: SendLeg = SendLeg { _, _ -> }
    /**
     * DRY-RUN: compose and log, never post. The logged line is the full
     * forensic artifact — method, redacted url, the byte-exact body — so
     * a day of real traffic in daemon.jsonl proves the wire without
     * touching the chat or the shared files.
     */
    fun dryRun(remote: () -> File): SendLeg = SendLeg { d, loud ->
        val r = composeSend(TgConfig.load(remote()), d.text, loud)
        logger.info {
            "send DRY-RUN ${d.watcher}/${d.decision}: method=${r.method} url=${r.url} body=${r.body}"
        }
    }

    /**
     * LIVE: the daemon owns the send. The send function is injected
     * (Main wires Sender with the SHARED cooldown/outbox/tg-last-ph
     * files); every answer is one log line — the flip's paper trail.
     */
    fun live(
        send: suspend (TgConfig, String, Boolean) -> SendResult,
        remote: () -> File,
    ): SendLeg = SendLeg { d, loud ->
        when (val r = send(TgConfig.load(remote()), d.text, loud)) {
            is SendResult.Sent ->
                logger.info { "send LIVE ${d.watcher}/${d.decision}: message_id=${r.messageId}" }

            SendResult.NotConfigured ->
                logger.warn {
                    "send LIVE ${d.watcher}/${d.decision}: NOT CONFIGURED (enabled/token/chat_id) — nothing went out"
                }

            SendResult.Parked ->
                logger.warn {
                    "send LIVE ${d.watcher}/${d.decision}: parked (cooldown/breaker) — the shared outbox holds it"
                }
        }
    }
}

/**
 * The knob's wiring bundle, what the watchers receive: the leg, plus
 * whether the daemon OWNS the spectator feature. Ownership flips the
 * meaning of telegram-remote.json's `mac_spectator`: before the cutover
 * it gates BOTH sides (parity); at the cutover it is set false to
 * silence PYTHON's spawns, and the owner ignores it — turning it off
 * must not mute the phone (the owner's rule: a duplicate notice annoys,
 * a silent phone is an incident). Rollback restores both sides to the
 * knob's parity reading.
 */
data class DaemonSend(
    val leg: SendLeg = SendLegs.NONE,
    val ownsSpectator: Boolean = false,
) {
    companion object {
        /** SHADOW keeps today's parity wiring; DRY-RUN and LIVE own the feature. */
        fun of(mode: SendMode, leg: SendLeg): DaemonSend =
            DaemonSend(leg = leg, ownsSpectator = mode != SendMode.SHADOW)
    }
}
