package dev.lattenero.bot.daemon.otel

import dev.lattenero.bot.channel.ServerFrame
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The OTel edge's attribute honesty: the span carries ONLY what the real
 * frames carried, the timestamps are the events' own stamps, the child
 * parents to its turn's span on the same trace. The real SDK runs here
 * (SimpleSpanProcessor: the export is synchronous at each span's end) —
 * no mock framework, the in-memory exporter is the collector.
 */
class OtelSpanTapsTest {

    private val recorder = RecordingSpanExporter()
    private val provider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(recorder))
        .build()
    private val taps = OtelSpanTaps(provider.get("dev.lattenero.bot.daemon"))

    @AfterTest
    fun down() {
        provider.close()
    }

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

    private fun failed(taskId: String, at: Long, error: String?): ServerFrame.StreamEvent =
        ServerFrame.StreamEvent(taskId, 9, at, buildJsonObject {
            put("type", "task_error")
            error?.let { put("error", it) }
        })

    private fun attr(span: io.opentelemetry.sdk.trace.data.SpanData, key: String): Any? =
        span.attributes.get(AttributeKey.stringKey(key))

    private fun boolAttr(span: io.opentelemetry.sdk.trace.data.SpanData, key: String): Boolean? =
        span.attributes.get(AttributeKey.booleanKey(key))

    private fun longAttr(span: io.opentelemetry.sdk.trace.data.SpanData, key: String): Long? =
        span.attributes.get(AttributeKey.longKey(key))

    @Test
    fun aFullTurnBuildsTheRootWithItsOwnTimestampsAndTheSealOutcome() {
        taps.on(SpanTapEvent.TurnStart("conv-1", startedAtMs = 1_000, forkTurn = "t7", midFlight = false))
        taps.on(SpanTapEvent.TurnEnd("conv-1", outcome = "completed", error = null, endAtMs = 3_500))

        val turn = recorder.spans.single { it.name == "bot.turn" }
        assertEquals("conv-1", attr(turn, "bot.sid"))
        assertEquals("t7", attr(turn, "bot.turn.fork_turn"))
        assertNull(attr(turn, "bot.turn.midflight")) // honest absence: the birth was witnessed
        assertNull(attr(turn, "bot.turn.error"))
        assertEquals("completed", attr(turn, "bot.turn.outcome"))
        assertEquals(1_000L, turn.startEpochNanos / NANOS_PER_MS) // the frame's own stamp
        assertEquals(3_500L, turn.endEpochNanos / NANOS_PER_MS) // the seal's own stamp
        assertEquals(StatusCode.UNSET, turn.status.statusCode) // a completed turn is not an error
        assertEquals(INVALID_PARENT, turn.parentSpanId) // the root has no parent
    }

    @Test
    fun theTitleRidesOnlyWhenTheStreamSaidSo() {
        taps.on(SpanTapEvent.TurnStart("conv-1", startedAtMs = 1_000, forkTurn = null, midFlight = false))
        taps.on(SpanTapEvent.TurnTitled("conv-1", "the real title"))
        taps.on(SpanTapEvent.TurnEnd("conv-1", outcome = "completed", error = null, endAtMs = 2_000))

        val turn = recorder.spans.single { it.name == "bot.turn" }
        assertEquals("the real title", attr(turn, "bot.turn.title"))
        assertNull(attr(turn, "bot.turn.fork_turn")) // absent stays absent
    }

    @Test
    fun aFailedTurnCarriesItsErrorAndTheErrorStatus() {
        taps.on(SpanTapEvent.TurnStart("conv-1", startedAtMs = 1_000, forkTurn = null, midFlight = false))
        taps.on(SpanTapEvent.TurnEnd("conv-1", outcome = "failed", error = "model exploded", endAtMs = 2_000))

        val turn = recorder.spans.single { it.name == "bot.turn" }
        assertEquals("failed", attr(turn, "bot.turn.outcome"))
        assertEquals("model exploded", attr(turn, "bot.turn.error"))
        assertEquals(StatusCode.ERROR, turn.status.statusCode)
    }

    @Test
    fun theToolChildParentsToItsTurnOnTheSameTrace() {
        taps.on(SpanTapEvent.TurnStart("conv-1", startedAtMs = 1_000, forkTurn = "t7", midFlight = false))
        taps.on(SpanTapEvent.ToolStart("conv-1", "t1", "Bash", atMs = 1_100))
        taps.on(SpanTapEvent.ToolEnd("conv-1", "t1", ok = true, chars = 6, atMs = 1_500, unclosed = false))
        taps.on(SpanTapEvent.TurnEnd("conv-1", outcome = "completed", error = null, endAtMs = 2_000))

        val turn = recorder.spans.single { it.name == "bot.turn" }
        val tool = recorder.spans.single { it.name == "bot.tool" }
        assertEquals(INVALID_PARENT, turn.parentSpanId)
        assertEquals(turn.spanId, tool.parentSpanId) // the tree, not a flat list
        assertEquals(turn.traceId, tool.traceId)
        assertEquals("Bash", attr(tool, "bot.tool.name"))
        assertEquals("conv-1", attr(tool, "bot.sid"))
        assertEquals(true, boolAttr(tool, "bot.tool.ok"))
        assertEquals(6L, longAttr(tool, "bot.tool.chars"))
        assertNull(boolAttr(tool, "bot.tool.unclosed"))
        assertEquals(1_100L, tool.startEpochNanos / NANOS_PER_MS)
        assertEquals(1_500L, tool.endEpochNanos / NANOS_PER_MS)
    }

    @Test
    fun aDeniedToolIsAnErrorWithItsRealVerdict() {
        taps.on(SpanTapEvent.TurnStart("conv-1", startedAtMs = 1_000, forkTurn = null, midFlight = false))
        taps.on(SpanTapEvent.ToolStart("conv-1", "t1", "Bash", atMs = 1_100))
        taps.on(SpanTapEvent.ToolEnd("conv-1", "t1", ok = false, chars = 0, atMs = 1_300, unclosed = false))
        taps.on(SpanTapEvent.TurnEnd("conv-1", outcome = "completed", error = null, endAtMs = 2_000))

        val tool = recorder.spans.single { it.name == "bot.tool" }
        assertEquals(false, boolAttr(tool, "bot.tool.ok"))
        assertEquals(StatusCode.ERROR, tool.status.statusCode)
        assertEquals(0L, longAttr(tool, "bot.tool.chars")) // zero is a REAL empty output, not an absence
    }

    @Test
    fun anUnclosedToolEndsWithoutAClaimedVerdict() {
        taps.on(SpanTapEvent.TurnStart("conv-1", startedAtMs = 1_000, forkTurn = null, midFlight = false))
        taps.on(SpanTapEvent.ToolStart("conv-1", "t1", "Bash", atMs = 1_100))
        taps.on(SpanTapEvent.ToolEnd("conv-1", "t1", ok = null, chars = null, atMs = 2_000, unclosed = true))
        taps.on(SpanTapEvent.TurnEnd("conv-1", outcome = "completed", error = null, endAtMs = 2_000))

        val tool = recorder.spans.single { it.name == "bot.tool" }
        assertNull(boolAttr(tool, "bot.tool.ok")) // the verdict is honestly unknown
        assertNull(longAttr(tool, "bot.tool.chars"))
        assertEquals(true, boolAttr(tool, "bot.tool.unclosed"))
        assertEquals(StatusCode.UNSET, tool.status.statusCode) // unknown is not failed
    }

    @Test
    fun aMidFlightRootCarriesTheMarkAndTheWitnessStart() {
        taps.on(SpanTapEvent.TurnStart("conv-1", startedAtMs = 5_000, forkTurn = null, midFlight = true))
        taps.on(SpanTapEvent.TurnEnd("conv-1", outcome = "completed", error = null, endAtMs = 6_000))

        val turn = recorder.spans.single { it.name == "bot.turn" }
        assertEquals(true, boolAttr(turn, "bot.turn.midflight"))
        assertEquals(5_000L, turn.startEpochNanos / NANOS_PER_MS) // the witnessing moment
        assertNull(attr(turn, "bot.turn.fork_turn")) // never invented
    }

    @Test
    fun aToolEventWithoutALiveRootIsSilence() {
        // the edge is dumb: the tracker owns the state — an orphan child
        // event (impossible from the tracker, guarded here) spans nothing
        taps.on(SpanTapEvent.ToolStart("conv-1", "t1", "Bash", atMs = 1_100))
        taps.on(SpanTapEvent.TurnEnd("conv-1", outcome = "completed", error = null, endAtMs = 2_000))
        assertTrue(recorder.spans.isEmpty())
    }

    companion object {
        private const val NANOS_PER_MS = 1_000_000L
        private const val INVALID_PARENT = "0000000000000000"
    }
}
