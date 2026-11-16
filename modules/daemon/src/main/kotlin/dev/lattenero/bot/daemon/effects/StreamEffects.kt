package dev.lattenero.bot.daemon.effects

import dev.lattenero.bot.channel.ConfigOptionsUpdate
import dev.lattenero.bot.channel.ServerFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kap.Kap
import kap.Process
import kap.RestartPolicy
import kap.process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * THE PUMP'S EFFECTS AS DATA (M2): the stream callback folds the frame (pure,
 * fast) and EMITS what the frame asks for. The worker — a supervised
 * Process — runs each effect with its own budget and failure isolation, so
 * the channel's ONE reader never waits on Telegram, a channel query, or a
 * one-second drain again.
 *
 * The queue is bounded: an effect that cannot enter is DROPPED and counted
 * (a full queue means the worker is degraded — the pump must keep reading).
 */
sealed interface StreamEffect {

    /** A turn started for [taskId]: write the default mode when the conversation never chose one. */
    data class EnforceMode(val taskId: String) : StreamEffect

    /** The fork announced a config change: refresh the open /mode or /plan menu. */
    data class RefreshMenu(val taskId: String, val options: List<ConfigOptionsUpdate.Option>) : StreamEffect

    /** A question/permission the owner must answer: the board sends (or dedupes) its card. */
    data class RegisterQuestion(val frame: ServerFrame.StreamEvent, val title: String?) : StreamEffect

    /** The turn died: its still-pending question seals honestly. */
    data class ResolveTask(val taskId: String, val note: String) : StreamEffect

    /** An answer landed elsewhere (the app, a tap): seal the card by request id. */
    data class ResolveRequest(val requestId: String, val note: String) : StreamEffect

    /** The turn completed: the task's open menu mirror expires. */
    data class SealMirror(val taskId: String) : StreamEffect

    /** The turn completed: the F17 parked texts fire (their own paced drain). */
    data object DrainQueue : StreamEffect

    /** A dispatch lane named its child: subscribe the child's own session topic. */
    data class SubscribeChild(val parentTaskId: String, val agentId: String) : StreamEffect

    /** A dispatch lane closed: the child's session topic stops being followed (M3). */
    data class UnsubscribeChild(val agentId: String) : StreamEffect

    /**
     * A reconnect re-baselined [taskId]: the stream never replays, so the
     * already-pending interactions are re-read ONCE from the snapshot and
     * handed to the same board (dedupe by requestId makes it harmless).
     */
    data class RecoverAfterReconnect(val taskId: String) : StreamEffect
}

/** The bounded mailbox between the pump and its worker. */
class StreamEffects(private val capacity: Int = CAPACITY) {

    private val queue = Channel<StreamEffect>(capacity)

    /** Effects lost to a full queue — a degraded worker made visible, never silent. */
    @Volatile
    var dropped: Long = 0L
        private set

    /** The pump's side: never suspends; a full queue drops and counts. */
    fun emit(effect: StreamEffect) {
        if (!queue.trySend(effect).isSuccess) {
            dropped += 1
            logger.warn {
                "effect ${effect::class.simpleName} DROPPED (queue full, $dropped total) — the pump keeps reading"
            }
        }
    }

    /** The worker's loop: every effect in order, budgeted, failures isolated. */
    @Suppress("TooGenericExceptionCaught") // isolation IS the law: one effect's bug never kills the worker
    suspend fun drain(runEffect: suspend (StreamEffect) -> Unit) {
        for (effect in queue) {
            try {
                val done = withTimeoutOrNull(EFFECT_BUDGET) { runEffect(effect) }
                if (done == null) {
                    logger.warn { "effect ${effect::class.simpleName} exceeded $EFFECT_BUDGET — abandoned" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "effect ${effect::class.simpleName} FAILED — the pump is unaffected" }
            }
        }
    }

    /** The worker as a supervised Process (bugs restart, cancellation stops it). */
    fun asProcess(runEffect: suspend (StreamEffect) -> Unit, onHeartbeat: () -> Unit = {}): Process<Unit> =
        Kap { drain(runEffect) }
            .process(
                name = PROCESS_NAME,
                restart = RestartPolicy.exponential(base = 1.seconds),
                heartbeatEvery = HEARTBEAT_EVERY,
                onHeartbeat = onHeartbeat,
                onRestart = { attempt, error, delay ->
                    logger.warn(error) { "stream effects worker failed — restart $attempt in $delay" }
                },
            )

    private companion object {
        const val CAPACITY = 256
        const val PROCESS_NAME = "stream-effects"
        val EFFECT_BUDGET = 45.seconds // Telegram's own request timeout (35 s) plus margin
        val HEARTBEAT_EVERY = 30.seconds
    }
}
