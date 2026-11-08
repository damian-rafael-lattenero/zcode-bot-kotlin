package dev.lattenero.bot.daemon.bus

import dev.lattenero.bot.channel.ServerFrame
import dev.lattenero.bot.contracts.BusWire
import dev.lattenero.bot.contracts.PyText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.math.round

/**
 * U1b's PRODUCER (ADR-019): the daemon publishes the turn's six bus events
 * to agent-brain's append-only JSONL — `user.prompt`, `turn.started`,
 * `tool.call`, `tool.result`, `turn.completed`, `turn.failed` — without
 * changing one bit of the bot's behavior.
 *
 * Where the taps land (all in the daemon's wiring, never in the channel):
 * - [beginPrompt]/[promptFired] — the free-text fire path: the text is
 *   REGISTERED before the wire (so a fast `task_run_started` frame finds
 *   its prompt) and the `user.prompt` line is emitted only after the
 *   channel CONFIRMED the delivery ([promptDropped] on a dead channel — a
 *   parked or refused text is not a prompt);
 * - [onFrame] — every stream frame the channel delivers: `task_run_started`
 *   births the turn (`turn.started`), `tool_call`/`tool_call_update` carry
 *   the tool lifecycle, `task_complete`/`task_error` seal it;
 * - `turn.completed` carries the assistant's FINAL TEXT, fetched from the
 *   task snapshot (the same source the live view speaks at the seal) — the
 *   memory worker (P3) extracts from `payload.reply`, so an empty reply is
 *   said empty, never invented.
 *
 * Laws (each one a test):
 * - the bus NEVER breaks a turn: every public entry point swallows its own
 *   failure into one log line (Python's `tap()` invariant);
 * - one turn id for every event of one turn, minted at the send (Python's
 *   `bus.turn_id(t0)`: ms epoch, as a string) and shared by every tap;
 * - ids are unique per event and the consumer deduplicates by id;
 * - knob OFF ⇒ zero I/O: the wiring does not even construct this object.
 */
// one method per tap shape + the pure payload builders — the class IS the map
@Suppress("TooManyFunctions")
class BusProducer(
    private val sink: BusSink,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = BusWire::newId,
    /** taskSnapshot → the last assistant message (the live view's own source). */
    private val finalAnswer: suspend (String) -> String? = { null },
    private val log: (String) -> Unit = {},
) : AutoCloseable {

    private data class PendingPrompt(
        val token: Long,
        val turnId: String,
        val text: String,
        val acceptedAtMs: Long,
        val sidHint: String?,
        val ws: String?,
    )

    private class TurnState(
        val turnId: String,
        val taskId: String,
        val startedAtMs: Long,
        val prompt: String?,
        val promptChars: Int?,
        val ws: String?,
        /** The fork's own turn id when the frame carried it — the resubscribe-burst dedupe. */
        val forkTurn: String? = null,
    ) {
        val calls = HashSet<String>()
        val results = HashSet<String>()
        var cotChars = 0
    }

    /** A turn this producer already sealed — the burst's replay of its frames must publish nothing. */
    private data class SealMark(val startedAtMs: Long, val forkTurn: String?, val turnId: String)

    private val mutex = Mutex()
    private val pending = ArrayDeque<PendingPrompt>()
    private val turns = HashMap<String, TurnState>()
    private val lastSeal = LinkedHashMap<String, SealMark>()
    private var nextToken = 0L
    private var lastTurnIdMs = 0L

    /**
     * Registers the prompt BEFORE the wire and answers its token. No event
     * yet: a text that never reaches the channel is not a prompt.
     */
    suspend fun beginPrompt(text: String, sidHint: String? = null, ws: String? = null): Long? =
        guard("begin-prompt") {
            if (text.isBlank()) return@guard null
            mutex.withLock {
                val now = clockMs()
                val token = ++nextToken
                pending.addLast(PendingPrompt(token, mintTurnId(now), text, now, sidHint, ws))
                while (pending.size > PENDING_CAP) pending.removeFirst()
                token
            }
        }

    /** The channel confirmed the delivery: `user.prompt` is emitted with the registered turn id. */
    suspend fun promptFired(token: Long) = guard("prompt-fired") {
        val entry = mutex.withLock { pending.firstOrNull { it.token == token } } ?: return@guard
        emit(
            kind = KIND_USER_PROMPT,
            sid = entry.sidHint ?: "",
            turn = entry.turnId,
            payload = buildJsonObject {
                put("prompt", BusWire.clip(entry.text))
                put("prompt_chars", PyText.cpLength(entry.text))
                entry.ws?.let { put("cwd", it) }
            },
        )
    }

    /** The delivery did not land (dead channel, refusal, timeout): the registration dies with it. */
    suspend fun promptDropped(token: Long) = guard("prompt-dropped") {
        mutex.withLock { pending.removeAll { it.token == token } }
    }

    /** One stream frame — the six kinds' whole lifecycle. */
    suspend fun onFrame(frame: ServerFrame.StreamEvent) = guard("frame") {
        when (str(frame.event, "type")) {
            RUN_STARTED -> onRunStarted(frame)
            TOOL_CALL -> onToolCall(frame)
            TOOL_UPDATE -> onToolUpdate(frame)
            THOUGHT -> onThought(frame)
            COMPLETE -> onCompleted(frame)
            ERROR -> onFailed(frame)
            else -> Unit // the rest of the union is not bus material (additive rule)
        }
    }

    private suspend fun onRunStarted(frame: ServerFrame.StreamEvent) {
        val now = clockMs()
        val eventStart = long(frame.event, "startedAt")?.takeIf { it > 0 }
        val forkTurn = str(frame.event, "turnId")
        // a resubscribe burst RE-EMITS the live turn's frames: the same fork
        // turn (or the same startedAt) is the same turn, never a second one —
        // and a burst replaying an ALREADY SEALED turn publishes nothing
        val existing = mutex.withLock { turns[frame.taskId] }
        if (existing != null && sameTurn(existing, eventStart, forkTurn)) return
        if (existing == null) {
            val mark = mutex.withLock { lastSeal[frame.taskId] }
            if (mark != null && sameSeal(mark, eventStart, forkTurn)) return
        }
        val (turn, pendingBefore) = mutex.withLock {
            val match = takePending(frame.taskId, now)
            val turnId = match?.turnId ?: mintTurnId(now)
            val state = TurnState(
                turnId = turnId,
                taskId = frame.taskId,
                startedAtMs = eventStart ?: frame.at.takeIf { it > 0 } ?: now,
                prompt = match?.text,
                promptChars = match?.text?.let(PyText::cpLength),
                ws = match?.ws,
                forkTurn = forkTurn,
            )
            turns[frame.taskId] = state
            state to match
        }
        emit(
            kind = KIND_TURN_STARTED,
            sid = frame.taskId,
            turn = turn.turnId,
            payload = buildJsonObject {
                // title/model are NOT at hand in the stream at birth — never invented.
                pendingBefore?.ws?.let { put("ws", it) }
                turn.promptChars?.let { put("prompt_chars", it) }
                turn.prompt?.let { put("prompt", BusWire.clip(it)) }
            },
        )
    }

    private fun sameTurn(existing: TurnState, eventStart: Long?, forkTurn: String?): Boolean =
        (forkTurn != null && forkTurn == existing.forkTurn) ||
            (eventStart != null && eventStart == existing.startedAtMs)

    private fun sameSeal(mark: SealMark, eventStart: Long?, forkTurn: String?): Boolean =
        (forkTurn != null && forkTurn == mark.forkTurn) ||
            (eventStart != null && eventStart == mark.startedAtMs)

    private suspend fun onToolCall(frame: ServerFrame.StreamEvent) {
        val event = frame.event
        val toolCallId = str(event, "toolCallId") ?: ""
        val state = turnFor(frame)
        val duplicate = mutex.withLock {
            if (toolCallId.isEmpty()) return@withLock false
            !state.calls.add(toolCallId)
        }
        if (duplicate) return
        emit(
            kind = KIND_TOOL_CALL,
            sid = frame.taskId,
            turn = state.turnId,
            payload = buildJsonObject {
                put("toolCallId", toolCallId)
                put("tool", str(event, "toolName") ?: "")
                put("input", inputJson(event))
            },
        )
    }

    private suspend fun onToolUpdate(frame: ServerFrame.StreamEvent) {
        val event = frame.event
        val status = str(event, "status") ?: return
        if (status !in TOOL_CLOSED) return
        val toolCallId = str(event, "toolCallId") ?: ""
        val state = turnFor(frame)
        val duplicate = mutex.withLock {
            if (toolCallId.isEmpty()) return@withLock false
            !state.results.add(toolCallId)
        }
        if (!duplicate) {
            val content = (event["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            emit(
                kind = KIND_TOOL_RESULT,
                sid = frame.taskId,
                turn = state.turnId,
                payload = buildJsonObject {
                    put("toolCallId", toolCallId)
                    put("success", status == TOOL_COMPLETED)
                    put("chars", PyText.cpLength(content))
                },
            )
        }
    }

    private suspend fun onThought(frame: ServerFrame.StreamEvent) {
        // subagent lanes are their own streams — cot_chars is the main agent's thinking
        val parent = str(frame.event, "parentToolUseId")?.takeIf { it != "null" && it.isNotEmpty() }
        if (parent != null) return
        val delta = (frame.event["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        mutex.withLock {
            turns[frame.taskId]?.let { it.cotChars += PyText.cpLength(delta) }
        }
    }

    private suspend fun onCompleted(frame: ServerFrame.StreamEvent) {
        val state = seal(frame) ?: return
        val duration = seconds(state.startedAtMs, sealAt(frame))
        // the snapshot lives on the SAME socket the frame arrived on: query it
        // OFF the read loop (the live view does the same from its own tick)
        scope.launch {
            val reply = withTimeoutOrNull(REPLY_TIMEOUT_MS) { finalAnswer(frame.taskId) }
            if (reply.isNullOrBlank()) {
                log("bus: turn.completed ${frame.taskId} without reply text (snapshot unavailable) — empty reply said")
            }
            emit(
                kind = KIND_TURN_COMPLETED,
                sid = frame.taskId,
                turn = state.turnId,
                payload = buildJsonObject {
                    put("duration_s", duration)
                    put("reply_chars", PyText.cpLength(reply ?: ""))
                    put("reply", BusWire.clip(reply ?: ""))
                    put("cot_chars", state.cotChars)
                },
            )
        }
    }

    private suspend fun onFailed(frame: ServerFrame.StreamEvent) {
        val state = seal(frame) ?: return
        emit(
            kind = KIND_TURN_FAILED,
            sid = frame.taskId,
            turn = state.turnId,
            payload = buildJsonObject {
                put("error", BusWire.clip(str(frame.event, "error") ?: "task error"))
                put("duration_s", seconds(state.startedAtMs, sealAt(frame)))
            },
        )
    }

    /**
     * Takes the turn out of the map exactly once. A terminal frame with NO
     * live turn is a burst replay of a turn this producer already sealed —
     * silence (a second `turn.completed` would make agent-brain extract the
     * same memories twice); only a turn first WITNESSED at its own end (a
     * subscription that attached mid-flight) seals without a start.
     */
    private suspend fun seal(frame: ServerFrame.StreamEvent): TurnState? = mutex.withLock {
        val live = turns.remove(frame.taskId)
        if (live == null && lastSeal.containsKey(frame.taskId)) return@withLock null
        val state = live ?: lazyTurnNoLock(frame)
        lastSeal[frame.taskId] = SealMark(state.startedAtMs, state.forkTurn, state.turnId)
        while (lastSeal.size > SEAL_CAP) lastSeal.remove(lastSeal.keys.first())
        state
    }

    private suspend fun turnFor(frame: ServerFrame.StreamEvent): TurnState = mutex.withLock {
        turns[frame.taskId] ?: lazyTurnNoLock(frame)
    }

    /** A turn first WITNESSED mid-flight (a subscription attached during it): the honest partial record. */
    private fun lazyTurnNoLock(frame: ServerFrame.StreamEvent): TurnState {
        val now = clockMs()
        return TurnState(
            turnId = mintTurnId(now),
            taskId = frame.taskId,
            startedAtMs = frame.at.takeIf { it > 0 } ?: now,
            prompt = null,
            promptChars = null,
            ws = null,
        ).also { turns[frame.taskId] = it }
    }

    /** The pending prompt for [taskId]: its own sid first (a bind/order target), else the oldest. */
    private fun takePending(taskId: String, nowMs: Long): PendingPrompt? {
        pending.removeAll { nowMs - it.acceptedAtMs > PENDING_TTL_MS }
        val own = pending.firstOrNull { it.sidHint == taskId } ?: pending.firstOrNull()
        if (own != null) pending.remove(own)
        return own
    }

    private fun mintTurnId(nowMs: Long): String {
        val value = maxOf(nowMs, lastTurnIdMs + 1)
        lastTurnIdMs = value
        return value.toString()
    }

    /** The close moment: the host's reception stamp when present, else the injected clock. */
    private fun sealAt(frame: ServerFrame.StreamEvent): Long =
        frame.at.takeIf { it > 0 } ?: clockMs()

    private suspend fun emit(kind: String, sid: String, turn: String, payload: JsonObject) {
        val now = clockMs()
        val event = BusWire.event(kind, sid, turn, payload, Instant.ofEpochMilli(now), newId())
        sink.append(BusWire.line(event))
    }

    /** The tap's guard: no producer failure may ever reach a turn (cancellation still propagates). */
    @Suppress("TooGenericExceptionCaught") // the bus never breaks a turn — Python's tap() invariant
    private suspend fun <T> guard(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("bus tap '$what' failed (${e::class.simpleName}: ${e.message}) — the turn is unaffected")
        null
    }

    /** The stop: in-flight reply fetches die with the daemon. */
    override fun close() {
        scope.cancel()
    }

    private companion object {
        const val KIND_USER_PROMPT = "user.prompt"
        const val KIND_TURN_STARTED = "turn.started"
        const val KIND_TOOL_CALL = "tool.call"
        const val KIND_TOOL_RESULT = "tool.result"
        const val KIND_TURN_COMPLETED = "turn.completed"
        const val KIND_TURN_FAILED = "turn.failed"

        const val RUN_STARTED = "task_run_started"
        const val TOOL_CALL = "tool_call"
        const val TOOL_UPDATE = "tool_call_update"
        const val THOUGHT = "agent_thought_chunk"
        const val COMPLETE = "task_complete"
        const val ERROR = "task_error"

        const val TOOL_COMPLETED = "completed"
        val TOOL_CLOSED = setOf("completed", "failed", "denied", "stopped")

        /** A prompt older than this never matches a turn start (a text that went nowhere). */
        const val PENDING_TTL_MS = 10 * 60_000L
        const val PENDING_CAP = 32

        /** The sealed-turn memory (taskId → mark) — pruned FIFO, never unbounded. */
        const val SEAL_CAP = 512

        /** One snapshot read, bounded: a hung host never holds a mutex or a slot. */
        const val REPLY_TIMEOUT_MS = 5_000L
    }
}

/** Python round(x, 1) — the fixture's `duration_s` shape. */
private fun seconds(fromMs: Long, toMs: Long): Double =
    round(((toMs - fromMs).coerceAtLeast(0)) / MILLIS_PER_TENTH) / TENTHS_PER_SECOND

private const val MILLIS_PER_TENTH = 100.0
private const val TENTHS_PER_SECOND = 10.0

/** The tool call's input as the contract's JSON STRING (Python's json.dumps of the parsed input). */
private fun inputJson(event: JsonObject): String {
    val input = event["input"] as? JsonObject ?: return ""
    return Json.encodeToString(JsonObject.serializer(), input)
}

private fun str(event: JsonObject, key: String): String? = (event[key] as? JsonPrimitive)?.content

private fun long(event: JsonObject, key: String): Long? =
    (event[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
