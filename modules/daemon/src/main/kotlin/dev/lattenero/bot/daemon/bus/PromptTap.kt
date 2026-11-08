package dev.lattenero.bot.daemon.bus

import dev.lattenero.bot.channel.ChannelClient
import dev.lattenero.bot.channel.ChannelQuery
import dev.lattenero.bot.channel.ChannelQuery.BotStatus
import dev.lattenero.bot.channel.QueryAnswer
import dev.lattenero.bot.daemon.brain.MemoryInjector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The bot's current target: the active task and its workspace (both may be absent). */
data class PromptContext(val sid: String, val ws: String?)

/**
 * The free-text prompt seam of the daemon's wiring (U1b):
 *
 * - [begin] runs BEFORE the wire and registers the text with the bus (no
 *   event yet — a text that never reaches the channel is not a prompt). The
 *   sid is the caller's when it knows it (an atomic bind or an armed order);
 *   otherwise one short botStatus read resolves the active conversation. A
 *   command line ("/new", "/task …") is never a user prompt.
 * - [fired] publishes `user.prompt` once the channel CONFIRMED the delivery;
 *   [dropped] forgets the registration (dead channel, refusal, timeout).
 * - [prepare] is memory_inject (ADR-020): the text that ACTUALLY goes to the
 *   conversation, with agent-brain's bounded block prepended when the knob
 *   is on. The bus always keeps the ORIGINAL text.
 *
 * The knobs are the caller's: a null [bus] and a null [inject] make every
 * method a no-op (zero I/O, not a single clock read).
 */
class PromptTap(
    private val bus: BusProducer?,
    private val context: suspend () -> PromptContext? = { null },
    private val inject: MemoryInjector? = null,
) {

    /**
     * The text for the wire: memory_inject's block + the owner's words, or
     * the words alone. A COMMAND line ("/new", "/task …") is never injected —
     * the block in front of a slash command would break the fork's own parse.
     */
    suspend fun prepare(text: String): String =
        if (inject == null || text.isBlank() || text.startsWith("/")) text else inject.prepend(text)

    /** Registers the text before the send; null with the bus off or for a command line. */
    suspend fun begin(text: String, knownSid: String? = null): Long? {
        if (bus == null || text.isBlank() || text.startsWith("/")) return null
        val resolved = if (knownSid == null) context() else null
        return bus.beginPrompt(text, sidHint = knownSid ?: resolved?.sid, ws = resolved?.ws)
    }

    suspend fun fired(token: Long?) {
        token?.let { bus?.promptFired(it) }
    }

    suspend fun dropped(token: Long?) {
        token?.let { bus?.promptDropped(it) }
    }
}

/**
 * The active conversation's sid + workspace, read ONCE per prompt with a
 * short window (Python's `only_active_task` target). Best-effort: a dead
 * channel answers null and the touch's best-effort, never a thrown turn.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException") // a hint is a hint: any failure means "not at hand"
internal suspend fun activePromptContext(client: ChannelClient): PromptContext? =
    withTimeoutOrNull(PROMPT_CONTEXT_TIMEOUT_MS) {
        try {
            when (val answer = client.query(BotStatus)) {
                is QueryAnswer.Ok -> {
                    val ctx = (Json.parseToJsonElement(answer.resultJson) as? JsonObject)
                        ?.get("context") as? JsonObject
                    val sid = (ctx?.get("activeTaskId") as? JsonPrimitive)?.content
                    val ws = (ctx?.get("workspacePath") as? JsonPrimitive)?.content
                    sid?.takeIf { it.isNotBlank() }?.let { PromptContext(it, ws) }
                }

                else -> null
            }
        } catch (e: CancellationException) {
            // cancellation is not "not at hand": a timeout still resolves
            // to null via withTimeoutOrNull, a cancelled caller rethrows
            throw e
        } catch (e: Exception) {
            null
        }
    }

private const val PROMPT_CONTEXT_TIMEOUT_MS = 500L
