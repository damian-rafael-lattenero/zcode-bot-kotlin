package dev.lattenero.bot.daemon.effects

import dev.lattenero.bot.channel.ConfigOptionsUpdate
import dev.lattenero.bot.channel.ServerFrame

/**
 * THE PUMP'S EFFECTS AS DATA (M2): the stream callback folds the frame (pure,
 * fast) and OFFERS what the frame asks for. The worker runs each effect with
 * its own budget and failure isolation, so the channel's ONE reader never
 * waits on Telegram, a channel query, or a one-second drain again.
 *
 * The mailbox itself is kap 5.4.0's `KapProcess` since the dogfooding (the
 * bot's hand-rolled `StreamEffects` is gone); this sealed interface is the
 * bot's own effect vocabulary and stays here — kap supplies the transport,
 * the project supplies the commands.
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
