package dev.lattenero.bot.channel

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE SPINE'S CLASSIFIER (U5a, law 1): every frame of the fork's union
 * lands as the RIGHT RenderEvent, with EXACTLY the fields the fold reads —
 * the old imperative fold's own extraction, pinned as a pure table. The
 * mapKap head: KapStream.from(classified frames), the non-material frames
 * dropped by the classifier's own null.
 */
class RenderSpineTest {

    private var seq = 0L
    private var now = 100_000L

    private fun frameOf(
        type: String,
        vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>,
        taskId: String = "task-9",
    ): ServerFrame.StreamEvent {
        seq += 1
        return ServerFrame.StreamEvent(
            taskId = taskId,
            seq = seq,
            at = now,
            event = buildJsonObject {
                put("type", type)
                fields.forEach { (k, v) -> put(k, v) }
            },
        )
    }

    private fun str(s: String) = JsonPrimitive(s)

    @Test
    fun theLifecycleClassifiesStartedThoughtAnswerSeal() {
        val started = classify(frameOf("task_run_started"), nowMs = 5L)
        val s = assertIs<RenderEvent.Started>(started)
        assertEquals("task-9", s.taskId)
        assertEquals(100_000L, s.atMs)
        assertEquals(5L, s.nowMs)
        // v2 §1: the PAYLOAD's own start stamp rides the event (0 = absent)
        val stamped = classify(frameOf("task_run_started", "startedAt" to JsonPrimitive(1_234L)), nowMs = 5L)
        assertEquals(1_234L, assertIs<RenderEvent.Started>(stamped).startedAtMs)
        assertEquals(0L, s.startedAtMs, "absent payload stays 0 — the delivery clock keeps its fallback")

        val thought = classify(
            frameOf(
                "agent_thought_chunk",
                "content" to str("pensando"),
                "parentToolUseId" to JsonNull,
            ),
            nowMs = 6L,
        )
        val t = assertIs<RenderEvent.Thought>(thought)
        assertEquals("pensando", t.delta)
        assertNull(t.parentToolUseId, "JsonNull is the main lane — the reader's own law")

        val answer = classify(frameOf("agent_message_chunk", "content" to str("La respuesta")), nowMs = 7L)
        assertEquals("La respuesta", assertIs<RenderEvent.Answering>(answer).delta)

        val seal = classify(
            frameOf(
                "task_complete",
                "stopReason" to str("end_turn"),
                "usage" to buildJsonObject { put("totalTokens", 4242) },
            ),
            nowMs = 8L,
        )
        val done = assertIs<RenderEvent.Sealed>(seal)
        assertEquals("end_turn", done.stopReason)
        assertEquals(4242L, done.usageTokens)
        assertNull(done.error, "a clean complete carries no pill")
    }

    @Test
    fun theErrorSealCarriesTheTypedPill() {
        val seal = classify(
            frameOf(
                "task_error",
                "error" to str("usage cap exceeded"),
                "code" to str("fault.provider.quotaExhausted"),
                "attribution" to buildJsonObject {
                    put("source", str("provider"))
                    put("statusCode", 429)
                    put("retryable", false)
                },
            ),
            nowMs = 9L,
        )
        val done = assertIs<RenderEvent.Sealed>(seal)
        assertEquals("usage cap exceeded", done.stopReason, "the error IS the stop reason — the honest seal")
        val pill = done.error!!
        assertTrue(pill.isQuota)
        assertEquals("provider", pill.source)
        assertEquals(429, pill.statusCode)
    }

    @Test
    fun theToolLadderClassifiesOpenStreamAndClose() {
        val open = classify(
            frameOf(
                "tool_call",
                "toolId" to str("t1"),
                "toolName" to str("Bash"),
                "input" to buildJsonObject { put("command", str("npm test")) },
            ),
            nowMs = 10L,
        )
        val opened = assertIs<RenderEvent.ToolOpen>(open)
        assertTrue(opened.final, "a bare tool_call is the authority input")
        assertEquals("Bash", opened.tool.name)
        assertEquals("npm test", opened.tool.input?.str("command"))

        // the pending update streams the partial input: lines + raw chars
        val streamed = classify(
            frameOf(
                "tool_call_update",
                "toolId" to str("t1"),
                "status" to str("pending"),
                "input" to buildJsonObject { put("content", str("l1\nl2\nl3")) },
                "raw" to buildJsonObject { put("streamingRawInputLength", 4321) },
            ),
            nowMs = 11L,
        )
        val streaming = assertIs<RenderEvent.ToolOpen>(streamed)
        assertEquals(false, streaming.final)
        assertEquals(3, streaming.tool.streamedLines)
        assertEquals(4321, streaming.tool.rawChars)

        // v2 §3: in_progress is a REAL fold now — the fork's own «alive»
        // signal rides as ToolRunning, never the additive frame
        val progress = classify(
            frameOf(
                "tool_call_update",
                "toolId" to str("t1"),
                "toolName" to str("Bash"),
                "status" to str("in_progress"),
            ),
            nowMs = 12L,
        )
        val running = assertIs<RenderEvent.ToolRunning>(progress)
        assertEquals("t1", running.tool.toolId)
        assertEquals("Bash", running.tool.name, "the ToolCall rides — a never-opened lane keeps its real name")

        // the close: the verdict + the string content's own counts
        val close = classify(
            frameOf(
                "tool_call_update",
                "toolId" to str("t1"),
                "status" to str("completed"),
                "content" to str("passing 1\npassing 2\npassing 3"),
            ),
            nowMs = 13L,
        )
        val closed = assertIs<RenderEvent.ToolClose>(close)
        assertEquals("completed", closed.tool.status)
        assertEquals(3, closed.tool.outputLines)
        assertEquals("passing 1\npassing 2\npassing 3", closed.tool.output)
    }

    @Test
    fun aStructuredCloseInventsNoCount() {
        val close = classify(
            frameOf(
                "tool_call_update",
                "toolId" to str("tj"),
                "status" to str("completed"),
                "content" to buildJsonObject { put("ok", true) },
            ),
            nowMs = 14L,
        )
        val closed = assertIs<RenderEvent.ToolClose>(close)
        assertNull(closed.tool.outputLines)
        assertNull(closed.tool.output)
    }

    @Test
    fun theInteractionAndThePills() {
        val asked = classify(
            frameOf("permission_request", "requestId" to str("p1"), "title" to str("Run rm -rf")),
            nowMs = 15L,
        )
        val interaction = assertIs<RenderEvent.InteractionAsked>(asked)
        assertEquals("permission", interaction.kind)
        assertEquals("Run rm -rf", interaction.title)

        val resolved = classify(frameOf("permission_response", "requestId" to str("p1")), nowMs = 16L)
        assertIs<RenderEvent.InteractionResolved>(resolved)

        // session_info: the title patch (empty = nothing) + the apiRetry patch
        val session = classify(
            frameOf(
                "session_info_update",
                "title" to str("arreglá el login"),
                "apiRetry" to buildJsonObject {
                    put("attempt", 2); put("maxRetries", 5); put("reason", "rate_limited")
                },
            ),
            nowMs = 17L,
        )
        val info = assertIs<RenderEvent.SessionInfo>(session)
        assertEquals("arreglá el login", info.title)
        val patch = assertIs<ApiRetryText.ApiRetryPatch.Set>(info.retry)
        assertEquals("Rate limited", patch.pill.label)

        // network debug: the stalled pill, the countdown anchored at the event's own clock
        val debug = classify(
            frameOf(
                "task_network_debug_status",
                "statusType" to str("model_retry_scheduled"),
                "reason" to str("timeout"),
                "delayMs" to JsonPrimitive(5_000),
                "nextAttempt" to JsonPrimitive(3),
            ),
            nowMs = 18L,
        )
        val net = assertIs<RenderEvent.NetworkDebug>(debug)
        val stalled = assertIs<ApiRetryText.ApiRetryPatch.Set>(net.retry)
        // the countdown anchors at the EVENT's own clock (100_000), never the receive clock
        assertEquals(105_000L, stalled.pill.nextRetryAtMs)
    }

    @Test
    fun theAdditiveFramesAndTheForeignOnes() {
        // usage_update and the unknown union: freshness frames, type named for forensics
        val usage = classify(frameOf("usage_update"), nowMs = 19L)
        assertEquals("usage_update", assertIs<RenderEvent.Frame>(usage).type)
        val unknown = classify(frameOf("something_new_from_the_fork"), nowMs = 20L)
        assertEquals("something_new_from_the_fork", assertIs<RenderEvent.Frame>(unknown).type)

        // the keepalive is the bus's own proof of life — a Keepalive event, nothing else
        val keepalive = classify(ServerFrame.KeepAlive("task-9", seq = 42, at = 500L), nowMs = 21L)
        val beat = assertIs<RenderEvent.Keepalive>(keepalive)
        assertEquals(42L, beat.seq)

        // hello/pong/acks: not render material — the classifier's own null
        assertNull(classify(ServerFrame.Pong("p1", at = 1L), nowMs = 22L))
        assertNull(classify(ServerFrame.Bye("unspecified"), nowMs = 23L))
    }

    @Test
    fun mapKapIsTheStreamHeadFramesInEventsOutNothingForeignRides() = runTest {
        val frames = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 16)
        val seen = mutableListOf<RenderEvent>()
        val job = backgroundScope.launch {
            mapKap(frames, nowMs = { 7L }).asFlow().collect { seen += it }
        }
        testScheduler.runCurrent() // the collector is subscribed
        frames.emit(frameOf("task_run_started"))
        frames.emit(ServerFrame.Pong("p1", at = 1L)) // not render material — the classifier drops it
        frames.emit(frameOf("agent_thought_chunk", "content" to str("hola")))
        testScheduler.runCurrent()
        job.cancel()
        assertEquals(2, seen.size, "the pong left at the head — the spine carries render material only")
        assertIs<RenderEvent.Started>(seen[0])
        val thought = assertIs<RenderEvent.Thought>(seen[1])
        assertEquals("hola", thought.delta)
        assertEquals(7L, thought.nowMs, "the injected receive clock rides")
    }
}
