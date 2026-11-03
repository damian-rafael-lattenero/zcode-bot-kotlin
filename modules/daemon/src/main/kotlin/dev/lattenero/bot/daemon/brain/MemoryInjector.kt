package dev.lattenero.bot.daemon.brain

import dev.lattenero.bot.channel.BrainReply
import dev.lattenero.bot.channel.MemoryContext
import dev.lattenero.bot.contracts.PyText
import dev.lattenero.bot.daemon.bus.SwitchKnob
import dev.lattenero.bot.daemon.bus.SwitchReading
import dev.lattenero.bot.daemon.metrics.Metrics
import dev.lattenero.bot.daemon.metrics.NoopMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * ADR-020's knob: `memory_inject` in the same `<home>/daemon.json`, read
 * ONCE at boot. SHIPPED OFF — with the knob off the send path is
 * byte-for-byte the pre-U1b one (the injector is never built).
 */
object MemoryInjectKnob {

    const val KEY = "memory_inject"

    fun load(file: File): SwitchReading = SwitchKnob.read(file, KEY)
}

/**
 * memory_inject: agent-brain's memories ride IN FRONT of a free-text prompt
 * (Python's `brain_client.memory_context` + `workers.py`'s prepend). The
 * laws are the owner's:
 *
 * - NEVER blocks a send: a ~1.5 s deadline, and empty / down / timeout /
 *   HTTP error all send the RAW text — silence toward the chat, one line in
 *   daemon.jsonl for the operator;
 * - the block is bounded ([MemoryContext.BLOCK_CAP], 1500 code points) and
 *   prepended, never appended: the owner's words stay last, whole;
 * - the bus keeps the ORIGINAL prompt (the caller taps before preparing) —
 *   memory never feeds on its own output.
 *
 * E2's taps ride the same events the log lines already speak: one
 * `memory.injected` per block actually prepended, one
 * `memory.inject.failures` per timeout/down/HTTP-error, and the fetch's
 * real latency on the `memory.fetch` timer (timed around the call itself,
 * so the no-op facade costs no clock read).
 */
class MemoryInjector(
    private val search: suspend (query: String, k: Int) -> BrainReply,
    private val log: (String) -> Unit = {},
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val metrics: Metrics = NoopMetrics,
) {

    /** The text that goes to the conversation: the block + the owner's words, or the words alone. */
    suspend fun prepend(text: String): String {
        if (text.isBlank()) return text
        return when (val reply = fetch(text)) {
            null -> {
                log("memory_inject: timeout ${timeout.inWholeMilliseconds}ms — texto crudo")
                metrics.memoryInjectFailed()
                text
            }

            is BrainReply.Down -> {
                log("memory_inject: brain caído (${reply.detail ?: "?"}) — texto crudo")
                metrics.memoryInjectFailed()
                text
            }

            BrainReply.Timeout -> {
                log("memory_inject: brain sin respuesta — texto crudo")
                metrics.memoryInjectFailed()
                text
            }

            is BrainReply.Response -> when {
                reply.status !in HTTP_SUCCESS -> {
                    log("memory_inject: brain HTTP ${reply.status} — texto crudo")
                    metrics.memoryInjectFailed()
                    text
                }

                else -> {
                    val block = MemoryContext.block(reply.body)
                    if (block.isEmpty()) {
                        log("memory_inject: sin recuerdos — texto crudo")
                        text
                    } else {
                        log("memory_inject: ${PyText.cpLength(block)} chars inyectados (k=$TOP_K)")
                        metrics.memoryInjected()
                        MemoryContext.prepend(text, block)
                    }
                }
            }
        }
    }

    /**
     * The one fetch: the caller's deadline, and a thrown leg is folded into
     * the same 📡 classification (the send must never see it). The E2
     * timer wraps the whole bounded window — a deadline cut is a real
     * duration too.
     */
    @Suppress("TooGenericExceptionCaught") // the send path must never see a brain failure
    private suspend fun fetch(text: String): BrainReply? = try {
        metrics.memoryFetch { withTimeoutOrNull(timeout) { search(text, TOP_K) } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        BrainReply.Down("search threw: ${e::class.simpleName}")
    }

    companion object {
        /** ADR-020's top-k ("top-k 3" in the spec, Python's default). */
        const val TOP_K = 3

        /** The send waits this long for the brain at most (soft deadline, then raw). */
        val DEFAULT_TIMEOUT: Duration = 1_500.milliseconds

        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299
        private val HTTP_SUCCESS = HTTP_OK_MIN..HTTP_OK_MAX
    }
}
