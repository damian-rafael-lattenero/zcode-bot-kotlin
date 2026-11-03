package dev.lattenero.bot.daemon.otel

import dev.lattenero.bot.channel.ServerFrame
import io.opentelemetry.api.common.AttributeKey
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The OFF law and the span tree's shape, through the REAL stack: OFF
 * builds nothing (the exporter factory itself is never called); ON keeps
 * the whole production shape — the provider, the batch processor, the
 * guarded edge — with only the exporter swapped for the in-memory one.
 * The scripted turn's tree is exactly what a collector would receive.
 */
class OtelStackTest {

    private val log = mutableListOf<String>()

    private fun started(taskId: String, at: Long, forkTurn: String? = null): ServerFrame.StreamEvent =
        ServerFrame.StreamEvent(taskId, 1, at, buildJsonObject {
            put("type", "task_run_started")
            put("startedAt", at)
            forkTurn?.let { put("turnId", it) }
        })

    private fun toolCall(taskId: String, id: String, tool: String, at: Long): ServerFrame.StreamEvent =
        ServerFrame.StreamEvent(taskId, 2, at, buildJsonObject {
            put("type", "tool_call")
            put("toolCallId", id)
            put("toolName", tool)
        })

    private fun toolClose(
        taskId: String,
        id: String,
        status: String,
        at: Long,
        content: String? = null,
    ): ServerFrame.StreamEvent = ServerFrame.StreamEvent(taskId, 3, at, buildJsonObject {
        put("type", "tool_call_update")
        put("toolCallId", id)
        put("status", status)
        content?.let { put("content", it) }
    })

    private fun completed(taskId: String, at: Long): ServerFrame.StreamEvent =
        ServerFrame.StreamEvent(taskId, 9, at, buildJsonObject { put("type", "task_complete") })

    private fun failed(taskId: String, at: Long, error: String): ServerFrame.StreamEvent =
        ServerFrame.StreamEvent(taskId, 9, at, buildJsonObject {
            put("type", "task_error")
            put("error", error)
        })

    // ── the OFF law ───────────────────────────────────────────────────────

    @Test
    fun offMeansNothing() {
        var exporterBuilt = false
        val stack = OtelStack.fromKnob(
            OtelKnobReading(on = false),
            log = { log.add(it) },
            exporter = {
                exporterBuilt = true
                RecordingSpanExporter()
            },
        )
        assertNull(stack.tracker) // the wiring's tap point is a null check — nothing else
        stack.close() // a no-op: no provider exists to flush, no thread to stop
        assertTrue(!exporterBuilt) // the OFF stack never even names an exporter
        assertTrue(log.isEmpty())
    }

    // ── the ON shape: the scripted turn's tree ────────────────────────────

    @Test
    fun aScriptedTurnExportsTheTreeTheCollectorWouldSee() {
        val recorder = RecordingSpanExporter()
        val stack = OtelStack.fromKnob(
            OtelKnobReading(on = true, endpoint = OtelKnob.DEFAULT_ENDPOINT),
            log = { log.add(it) },
            exporter = { recorder },
        )
        val tracker = assertNotNull(stack.tracker)
        // ONE scripted turn: birth (fork's own stamp) → two tools → seal
        tracker.onFrame(started("conv-1", at = 1_000, forkTurn = "t7"))
        tracker.onFrame(toolCall("conv-1", "t1", "Bash", at = 1_100))
        tracker.onFrame(toolClose("conv-1", "t1", "completed", at = 1_500, content = "ok out"))
        tracker.onFrame(toolCall("conv-1", "t2", "Write", at = 1_600))
        tracker.onFrame(toolClose("conv-1", "t2", "completed", at = 1_800, content = "w"))
        tracker.onFrame(completed("conv-1", at = 2_000))
        stack.close() // the shutdown hook's shape: the batch flushes and dies

        assertEquals(3, recorder.spans.size, "one root + two children, exactly")
        val turn = recorder.spans.single { it.name == "bot.turn" }
        val tools = recorder.spans.filter { it.name == "bot.tool" }
        assertEquals(2, tools.size)
        assertEquals(INVALID_PARENT, turn.parentSpanId)
        assertEquals("conv-1", turn.attributes.get(AttributeKey.stringKey("bot.sid")))
        assertEquals("t7", turn.attributes.get(AttributeKey.stringKey("bot.turn.fork_turn")))
        assertEquals("completed", turn.attributes.get(AttributeKey.stringKey("bot.turn.outcome")))
        assertEquals(1_000L, turn.startEpochNanos / NANOS_PER_MS) // the fork's own birth stamp
        assertEquals(2_000L, turn.endEpochNanos / NANOS_PER_MS)
        assertEquals("zcode-bot-daemon", turn.resource.getAttribute(AttributeKey.stringKey("service.name")))
        // the tree: both children parent to the root, same trace, honest attrs
        tools.forEach { tool ->
            assertEquals(turn.spanId, tool.parentSpanId)
            assertEquals(turn.traceId, tool.traceId)
            assertEquals("conv-1", tool.attributes.get(AttributeKey.stringKey("bot.sid")))
        }
        assertEquals(
            listOf("Bash", "Write"),
            tools.map { it.attributes.get(AttributeKey.stringKey("bot.tool.name")) },
        )
        assertEquals(6L, tools[0].attributes.get(AttributeKey.longKey("bot.tool.chars")))
        assertTrue(log.isEmpty())
    }

    @Test
    fun aMidFlightTurnStillBuildsAWholeTreeWithoutAnInventedStart() {
        val recorder = RecordingSpanExporter()
        val stack = OtelStack.fromKnob(
            OtelKnobReading(on = true),
            log = { log.add(it) },
            exporter = { recorder },
        )
        val tracker = assertNotNull(stack.tracker)
        // the daemon attached mid-flight: the first witness is the tool
        tracker.onFrame(toolCall("conv-1", "t1", "Bash", at = 5_000))
        tracker.onFrame(toolClose("conv-1", "t1", "completed", at = 6_000, content = "out"))
        tracker.onFrame(completed("conv-1", at = 7_000))
        stack.close()

        val turn = recorder.spans.single { it.name == "bot.turn" }
        val tool = recorder.spans.single { it.name == "bot.tool" }
        assertEquals(true, turn.attributes.get(AttributeKey.booleanKey("bot.turn.midflight")))
        assertEquals(5_000L, turn.startEpochNanos / NANOS_PER_MS) // the witnessing moment
        assertNull(turn.attributes.get(AttributeKey.stringKey("bot.turn.fork_turn")))
        assertEquals(turn.spanId, tool.parentSpanId)
        assertEquals("completed", turn.attributes.get(AttributeKey.stringKey("bot.turn.outcome")))
    }

    @Test
    fun aFailedTurnMarksTheRootError() {
        val recorder = RecordingSpanExporter()
        val stack = OtelStack.fromKnob(
            OtelKnobReading(on = true),
            log = { log.add(it) },
            exporter = { recorder },
        )
        val tracker = assertNotNull(stack.tracker)
        tracker.onFrame(started("conv-1", at = 1_000))
        tracker.onFrame(failed("conv-1", at = 2_000, error = "model exploded"))
        stack.close()

        val turn = recorder.spans.single { it.name == "bot.turn" }
        assertEquals("failed", turn.attributes.get(AttributeKey.stringKey("bot.turn.outcome")))
        assertEquals("model exploded", turn.attributes.get(AttributeKey.stringKey("bot.turn.error")))
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, turn.status.statusCode)
    }

    // ── the live-io invariant ─────────────────────────────────────────────

    @Test
    fun aTapFailureIsOneLogLineAndNeverBreaksTheTurn() {
        val throwing = SpanTaps { throw IllegalStateException("the SDK had a bad day") }
        val guarded = GuardedSpanTaps(throwing) { log.add(it) }
        guarded.on(SpanTapEvent.TurnStart("conv-1", 1_000, null, midFlight = false))
        guarded.on(SpanTapEvent.TurnEnd("conv-1", "completed", null, 2_000))
        assertEquals(2, log.size, "one line per failed tap — the turn is unaffected")
        assertTrue(log[0].contains("TurnStart"))
        assertTrue(log[0].contains("the turn is unaffected"))
    }

    @Test
    fun aDeadCollectorNeverBreaksTheTurn() {
        // the REAL exporter against a refused port: the whole turn runs,
        // the close flushes, the failure is the batch worker's log line —
        // nothing reaches the turn (telemetry exports, never blocks)
        val stack = OtelStack.fromKnob(
            OtelKnobReading(on = true, endpoint = "http://127.0.0.1:1/v1/traces"),
            log = { log.add(it) },
        )
        val tracker = assertNotNull(stack.tracker)
        tracker.onFrame(started("conv-1", at = 1_000, forkTurn = "t7"))
        tracker.onFrame(toolCall("conv-1", "t1", "Bash", at = 1_100))
        tracker.onFrame(toolClose("conv-1", "t1", "completed", at = 1_500, content = "ok"))
        tracker.onFrame(completed("conv-1", at = 2_000))
        stack.close() // the flush fails against the dead collector — and stops there
        assertTrue(log.isEmpty()) // the turn's path never saw a thing
    }

    companion object {
        private const val NANOS_PER_MS = 1_000_000L
        private const val INVALID_PARENT = "0000000000000000"
    }
}
