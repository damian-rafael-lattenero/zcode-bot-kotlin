package dev.lattenero.bot.channel

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live view's reducer and render — the owner's MVP, event by event:
 * thinking grows CHARS, writing grows LINES, everything with its timer,
 * the pill tells the truth, and the render never goes quiet.
 */
class LiveTurnTest {

    private var seq = 0L
    private var now = 100_000L

    private fun ev(type: String, vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>): ServerFrame.StreamEvent {
        seq += 1
        now += 100
        return ServerFrame.StreamEvent(
            taskId = "task-9",
            seq = seq,
            at = now,
            event = buildJsonObject {
                put("type", type)
                put("taskId", "task-9")
                fields.forEach { (k, v) -> put(k, v) }
            },
        )
    }

    private fun str(s: String) = kotlinx.serialization.json.JsonPrimitive(s)
    private fun num(n: Long) = kotlinx.serialization.json.JsonPrimitive(n)

    private fun thought(content: String, parent: String? = null): ServerFrame.StreamEvent =
        if (parent == null) {
            ev("agent_thought_chunk", "content" to str(content), "parentToolUseId" to kotlinx.serialization.json.JsonNull)
        } else {
            ev("agent_thought_chunk", "content" to str(content), "parentToolUseId" to str(parent))
        }

    private fun reduce(state: TurnLive?, frame: ServerFrame.StreamEvent): TurnLive =
        LiveTurn.reduce(state, frame, frame.at)

    @Test
    fun turnStartsThinkingAndCharsGrow() {
        var s = reduce(null, thought("hola "))
        s = reduce(s, thought("mundo"))
        val phase = s.phase as TurnPhase.Thinking
        assertEquals(10, phase.chars)
        assertEquals(10, s.thoughtChars)
        assertEquals("hola mundo", s.cotTail)
        assertEquals("task-9".takeLast(12), s.title)
    }

    @Test
    fun theTailCapsAt2500CodePoints() {
        var s: TurnLive? = null
        repeat(300) { s = reduce(s, thought("0123456789")) } // 3000 chars
        assertEquals(LiveTurn.COT_TAIL_CP, s!!.cotTail.length)
        assertTrue(s!!.cotTail.endsWith("0123456789"))
        assertEquals(3000, s.thoughtChars) // the TOTAL never truncates
    }

    @Test
    fun writingGrowsLinesFromTheStreamedInput() {
        var s: TurnLive? = null
        s = reduce(s, thought("pienso"))
        // tool_call opens the lane (input pending, empty)
        s = reduce(s, ev("tool_call", "toolId" to str("t1"), "toolName" to str("Write"), "input" to buildJsonObject {}))
        var phase = s!!.phase as TurnPhase.Writing
        assertEquals("Write", phase.tool)
        assertEquals(0, phase.lines)

        // pending update streams the partial content: 3 lines
        s = reduce(
            s,
            ev(
                "tool_call_update", "toolId" to str("t1"), "toolName" to str("Write"), "status" to str("pending"),
                "input" to buildJsonObject { put("content", "l1\nl2\nl3") },
                "raw" to buildJsonObject { put("streamingRawInputLength", 4321) },
            ),
        )
        phase = s.phase as TurnPhase.Writing
        assertEquals(3, phase.lines)
        assertEquals(4321, phase.rawChars)

        // the FINAL tool_call is the authority: the whole file, 5 lines
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("t1"), "toolName" to str("Write"),
                "input" to buildJsonObject { put("content", "a\nb\nc\nd\ne") },
            ),
        )
        phase = s.phase as TurnPhase.Writing
        assertEquals(5, phase.lines)

        // completion closes the lane
        s = reduce(s, ev("tool_call_update", "toolId" to str("t1"), "status" to str("completed")))
        assertEquals(1, s.doneTools)
        assertEquals("completed", s.tools.first().status)
    }

    @Test
    fun editLinesComeFromNewString() {
        val s = reduce(
            null,
            ev(
                "tool_call_update", "toolId" to str("t2"), "toolName" to str("Edit"), "status" to str("pending"),
                "input" to buildJsonObject { put("new_string", "x\ny\nz") },
            ),
        )
        assertEquals(3, (s.phase as TurnPhase.Writing).lines)
    }

    @Test
    fun subagentThoughtGrowsItsOwnLane() {
        var s: TurnLive? = null
        s = reduce(s, thought("main thinking", parent = null))
        s = reduce(s, thought("sub thinking hard", parent = "tooluse-77"))
        assertEquals(13, s!!.thoughtChars) // the main lane is untouched
        assertEquals(17, s.subagentThought["tooluse-77"])
        // and the subagent's TOOL calls carry the parent too (/subagents' source)
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("t3"), "toolName" to str("Bash"), "parentToolUseId" to str("tooluse-77"),
                "input" to buildJsonObject { put("command", "ls") },
            ),
        )
        assertEquals("tooluse-77", s.tools.first().parentToolUseId)
    }

    @Test
    fun messageChunksFlipToWritingTheAnswerAndCompleteSeals() {
        var s: TurnLive? = null
        s = reduce(s, thought("razonando"))
        s = reduce(s, ev("agent_message_chunk", "content" to str("La respuesta")))
        val writing = s!!.phase as TurnPhase.Writing
        assertNull(writing.tool) // tool null = the ANSWER itself
        assertEquals(12, s.answerChars)

        s = reduce(s, ev("task_complete", "stopReason" to str("end_turn"), "usage" to buildJsonObject { put("totalTokens", 4242) }))
        val done = s.phase as TurnPhase.Done
        assertEquals("end_turn", done.stopReason)
        assertEquals(4242L, s.usageTokens)
    }

    @Test
    fun permissionsAndTheirResponses() {
        var s: TurnLive? = null
        s = reduce(s, ev("permission_request", "requestId" to str("p1"), "title" to str("Run rm -rf")))
        assertEquals("permission", s!!.interaction!!.kind)
        s = reduce(s, ev("permission_response", "requestId" to str("p1")))
        assertNull(s.interaction)
    }

    @Test
    fun apiRetryPillArrivesClearsOnNullAndOnProgress() {
        var s: TurnLive? = null
        s = reduce(
            s,
            ev(
                "session_info_update",
                "apiRetry" to buildJsonObject {
                    put("attempt", 1); put("maxRetries", 10); put("reason", "provider_overloaded")
                    put("nextRetryAtMs", 220_000); put("errorStatus", 503)
                },
            ),
        )
        val pill = s!!.apiRetry!!
        assertEquals("z.ai overloaded", pill.label)
        assertEquals(220_000L, pill.nextRetryAtMs)

        // a session_info_update with apiRetry:null CLEARS
        s = reduce(s, ev("session_info_update", "apiRetry" to kotlinx.serialization.json.JsonNull))
        assertNull(s!!.apiRetry)

        // set again, then REAL PROGRESS (a thought delta) clears it too
        s = reduce(
            s,
            ev("session_info_update", "apiRetry" to buildJsonObject { put("attempt", 2); put("reason", "rate_limited") }),
        )
        assertEquals("Rate limited", s!!.apiRetry!!.label)
        s = reduce(s, thought("ya entró"))
        assertNull(s.apiRetry)
    }

    @Test
    fun networkDebugSetsTheStalledPillAndStartedClears() {
        var s: TurnLive? = null
        s = reduce(s, ev("task_network_debug_status", "statusType" to str("model_stream_stalled")))
        assertEquals("Stream stalled", s!!.apiRetry!!.label)
        s = reduce(s, ev("task_network_debug_status", "statusType" to str("model_request_started")))
        assertNull(s.apiRetry)
    }

    @Test
    fun sessionInfoUpdatesTheTitle() {
        var s: TurnLive? = null
        s = reduce(s, ev("session_info_update", "title" to str("arreglá el login")))
        assertEquals("arreglá el login", s!!.title)
    }

    @Test
    fun renderShowsSpinnerCountersTimersHeartbeatAndPill() {
        var s: TurnLive? = null
        val startAt = now
        s = reduce(null, ServerFrame.StreamEvent("task-9", 1, startAt, buildJsonObject { put("type", "task_run_started") }))
        s = reduce(s, thought("estoy pensando mucho sobre este problema"))
        val text = LiveTurn.render(s!!, nowMs = startAt + 42_000, spin = 0, channelAlive = true)
        assertTrue("🧠" in text) // the even spin of the blink
        assertTrue("thinking" in text)
        assertTrue("40 chars" in text) // the counter, with the total
        assertTrue("42s" in text) // the turn's timer
        assertTrue("💓 alive" in text) // the visible heartbeat
        assertTrue("0 done" in text)

        // the odd spin blinks the OTHER face — visibly alive every edit
        val odd = LiveTurn.render(s, nowMs = startAt + 45_000, spin = 1, channelAlive = true)
        assertTrue("💭" in odd)
        // the dead channel switches the heartbeat's face
        val dead = LiveTurn.render(s, nowMs = startAt + 45_000, spin = 1, channelAlive = false)
        assertTrue("📡" in dead)
    }

    @Test
    fun renderShowsTheOpenLanesAndThePill() {
        var s: TurnLive? = null
        s = reduce(
            s,
            ev(
                "tool_call_update", "toolId" to str("t9"), "toolName" to str("Write"), "status" to str("pending"),
                "input" to buildJsonObject { put("content", "a\nb\nc\nd") },
                "raw" to buildJsonObject { put("streamingRawInputLength", 99) },
            ),
        )
        s = reduce(
            s,
            ev(
                "session_info_update",
                "apiRetry" to buildJsonObject { put("attempt", 2); put("maxRetries", 5); put("reason", "rate_limited") },
            ),
        )
        val text = LiveTurn.render(s!!, nowMs = s.lastEventAtMs!! + 10_000, spin = 0, channelAlive = true)
        assertTrue("🔧 <b>Write</b> · 4 lines · 99 chars raw" in text)
        assertTrue("⚠️ Rate limited — retrying 2/5" in text)
    }

    @Test
    fun sealTextSummarizesTheTurn() {
        var s: TurnLive? = null
        s = reduce(null, ServerFrame.StreamEvent("task-9", 1, 1000L, buildJsonObject { put("type", "task_run_started") }))
        repeat(3) { i -> s = reduce(s, thought("chunk$i".repeat(10))) }
        s = reduce(s, ev("task_complete", "stopReason" to str("end_turn")))
        val seal = LiveTurn.sealText(s!!, nowMs = 61_000)
        assertTrue("end_turn in 1m00s" in seal)
        assertTrue("🧠 180 chars" in seal)
    }

    @Test
    fun fmtDurSpellsTheTimers() {
        assertEquals("42s", LiveTurn.fmtDur(42_000))
        assertEquals("59s", LiveTurn.fmtDur(59_999))
        assertEquals("3m12s", LiveTurn.fmtDur(192_000))
        assertEquals("1h02m", LiveTurn.fmtDur(3_720_000))
    }

    @Test
    fun theCotPreviewIsCappedForTheMessage() {
        var s: TurnLive? = null
        repeat(200) { s = reduce(s, thought("0123456789")) } // 2000 chars of tail
        val text = LiveTurn.render(s!!, nowMs = 100_000, spin = 0, channelAlive = true)
        assertTrue(text.length < 4096) // the whole message fits Telegram
        assertTrue(s!!.cotTail.length == 2000) // the tail below the cap keeps everything
    }

    @Test
    fun idleIsTheHonestStart() {
        val s = LiveTurn.reduce(null, ev("usage_update"), nowMs = 5L)
        assertEquals(TurnPhase.Idle, s.phase)
        val text = LiveTurn.render(s, nowMs = 6L, spin = 0, channelAlive = true)
        assertTrue("nothing running" in text)
    }
}
