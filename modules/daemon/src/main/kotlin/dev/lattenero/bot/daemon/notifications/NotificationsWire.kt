package dev.lattenero.bot.daemon.notifications

import dev.lattenero.bot.channel.SealFacts
import dev.lattenero.bot.contracts.ShadowDecision
import dev.lattenero.bot.daemon.DaemonConfig
import dev.lattenero.bot.daemon.send.DaemonSend
import dev.lattenero.bot.daemon.shadow.TimeIso
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * 🔔 THE KEY-MOMENT NOTIFICATIONS' ASSEMBLY (the owner's explicit
 * order): one build, one guard — the wiring's whole surface is the null
 * check. OFF (the knob read once at boot in Main) answers null and the
 * daemon is byte-for-byte the pre-feature one: no watcher Process, no
 * seal tap, zero I/O.
 *
 * ON answers the feature's two taps:
 * - [NotifTaps.watcher] — the subagent watcher (its own Process in the
 *   channel stack), the store's '' → value transitions;
 * - [NotifTaps.onSeal] — the seal tap, riding the SAME seal the live
 *   view speaks (the publisher hands the facts over — nothing is
 *   re-queried, the turn is not re-read): the loud line the owner's
 *   bound conversation earned, folded into silence when the card just
 *   showed it (the 60 s gate) or when the turn produced nothing.
 *
 * The guard law (the bus's own): a tap never breaks what it taps — the
 * seal callback swallows its own failure into one log line.
 */
object NotificationsWire {

    /** The no-op tap — the OFF wiring's shape. */
    val noSeal: suspend (SealFacts) -> Unit = {}

    /** The feature's taps, one value. */
    class NotifTaps(
        val watcher: SubagentNotifs,
        val onSeal: suspend (SealFacts) -> Unit,
        /** M4: the global task notifier — any task's end, subscribed or not. */
        val global: GlobalNoticesIo,
    )

    fun build(
        cfg: DaemonConfig,
        send: DaemonSend,
        on: Boolean,
        /** The gate's clock, injected like every clock in the house. */
        clockMs: () -> Long = System::currentTimeMillis,
    ): NotifTaps? {
        if (!on) return null
        val watcher = SubagentNotifs(cfg.baseDir, File(cfg.homeDir, "state"), send.leg)
        // ONE voice per transition: the seal tap and the global notifier share
        // this gate (24/11: both spoke the same turn end, 5 s and 61 s apart)
        val voice = TaskVoiceGate(clockMs = clockMs)
        val global = GlobalNoticesIo(
            cfg.baseDir,
            File(cfg.homeDir, "state"),
            send.leg,
            clockMs = clockMs,
            voice = voice,
        )
        val gate = SealGate(clockMs = clockMs)
        return NotifTaps(
            watcher = watcher,
            onSeal = { facts ->
                guard {
                    val text = NotifText.turnSealed(facts)
                    if (text != null && claimVoice(facts, gate, voice)) {
                        deliverSeal(send, text)
                    }
                }
            },
            global = global,
        )
    }

    /**
     * THE GATES' CHAIN, in order: the 60 s probe is READ-ONLY, the
     * cross-source gate records ONLY when it speaks, and the seal window is
     * spent LAST — a folded seal costs nothing (24/11: a ghost re-seal used
     * to burn the window and silence the next real turn).
     */
    private fun claimVoice(facts: SealFacts, gate: SealGate, voice: TaskVoiceGate): Boolean {
        val terminal = if (facts.failed) TaskSignal.Terminal.FAILED else TaskSignal.Terminal.FINISHED
        return !gate.heard(facts.taskId) &&
            voice.trySpeak(facts.taskId, terminal, facts.startedAtMs) &&
            gate.tryAnnounce(facts.taskId)
    }

    /** MOMENT 2's send: the COMMANDS lane (the shared throat), loud, one decision. */
    private suspend fun deliverSeal(send: DaemonSend, text: String) {
        val now = System.currentTimeMillis() / MILLIS_PER_SECOND
        send.leg.deliver(
            ShadowDecision(
                ts = now,
                tsIso = TimeIso.format(now),
                watcher = NotifText.WATCHER,
                decision = SEAL_DECISION,
                text = text,
            ),
            loud = true,
        )
    }

    /** The tap's guard: no notification failure may ever reach the seal (cancellation passes). */
    @Suppress("TooGenericExceptionCaught") // the bus's tap() invariant, verbatim
    private suspend fun guard(block: suspend () -> Unit) = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(e) {
            "seal notification tap failed (${e::class.simpleName}: ${e.message}) — the seal is unaffected"
        }
    }

    private const val SEAL_DECISION = "turn_sealed"
    private const val MILLIS_PER_SECOND = 1000.0
}
