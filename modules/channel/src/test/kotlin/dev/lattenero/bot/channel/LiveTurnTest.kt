package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun renderShowsSpinnerCountersTimersAndTheSoberStatusLine() {
        var s: TurnLive? = null
        val startAt = now
        s = reduce(null, ServerFrame.StreamEvent("task-9", 1, startAt, buildJsonObject { put("type", "task_run_started") }))
        s = reduce(s, thought("estoy pensando mucho sobre este problema"))
        // LIVE: fresh event (2s old) — the clocks tick
        val last = s!!.lastEventAtMs!!
        val text = LiveTurn.render(s, nowMs = last + 2_000, spin = 0, channelAlive = true)
        assertTrue("🧠" in text) // the even spin of the blink
        assertTrue("pensando" in text)
        assertTrue("40 caracteres" in text) // the counter, with the total
        assertTrue("2s" in text) // the turn's timer, ticking
        assertTrue("💓" !in text) // THE HEART IS DEAD (the owner's 30/10 verdict)
        assertTrue("en vivo · último evento hace 2s · 0 listos" in text) // the sober line
        assertTrue("0 listos" in text)

        // the odd spin blinks the OTHER face — visibly alive every edit
        val odd = LiveTurn.render(s, nowMs = last + 5_000, spin = 1, channelAlive = true)
        assertTrue("💭" in odd)
        // the dead channel speaks frozen — the 📡 reconnect line AND the sober word
        val dead = LiveTurn.render(s, nowMs = last + 5_000, spin = 1, channelAlive = false)
        assertTrue("📡" in dead)
        assertTrue("frozen · 5s sin eventos · 0 listos" in dead)

        // THE OWNER'S RULE: 42s of silence — the clocks FREEZE at the last
        // real event (the elapsed stays 0s) and the silence speaks honestly
        val frozen = LiveTurn.render(s, nowMs = last + 42_000, spin = 0, channelAlive = true)
        assertTrue("(42s sin eventos)" in frozen)
        assertTrue("· 0s (42s sin eventos)" in frozen, "the turn timer froze at the last event")
        // 42s of silence is still «en vivo» — the line carries the honest age,
        // «frozen» is reserved for the cut stream (the hard word lives at 90s)
        assertTrue("en vivo · último evento hace 42s" in frozen)
        val cut = LiveTurn.render(s, nowMs = last + 120_000, spin = 0, channelAlive = true)
        assertTrue("frozen · 2m00s sin eventos" in cut)
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
        assertTrue("🔧 <b>Write</b> · 4 líneas" in text) // the MINIMAL fallback verb (the real map is daemon-side)
        assertTrue("4 líneas · " in text) // raw is gone — human copy only counts lines // the open lane keeps its counters
        assertTrue("⚠️ Rate limited — retrying 2/5" in text)
    }

    // ---- the owner's two hard rules (29/10): honesty and distinct verbs ----

    @Test
    fun thinkingDegradesHonestlyUnderSilence() {
        // the owner's exact scenario: the stream died and the bot kept
        // saying "thinking..." while the seconds passed — NEVER again
        var s: TurnLive? = null
        s = reduce(s, thought("pensando"))
        val last = s!!.lastEventAtMs!!
        val fresh = LiveTurn.render(s, nowMs = last + 5_000, spin = 0, channelAlive = true)
        assertTrue("sin eventos" !in fresh) // fresh = a plain honest thinking line
        val stale = LiveTurn.render(s, nowMs = last + 30_000, spin = 0, channelAlive = true)
        assertTrue("(30s sin eventos)" in stale) // 20-30s of silence: the degrade
        val cut = LiveTurn.render(s, nowMs = last + 120_000, spin = 0, channelAlive = true)
        assertTrue("(2m00s sin eventos — stream cortado?)" in cut) // the hard word
    }

    @Test
    fun aDroppedSocketIsAReconnectStateNotAFakeTurn() {
        var s: TurnLive? = null
        s = reduce(s, thought("pensando"))
        val text = LiveTurn.render(s!!, nowMs = s.lastEventAtMs!! + 8_000, spin = 0, channelAlive = false)
        assertTrue("📡 canal caído — reconectando…" in text)
    }

    @Test
    fun theTypedErrorResolvesTheTurnWithItsOwnPill() {
        // quota exhaustion — the owner's exact scenario («se me acabaron los
        // tokens»): the turn ends with the TYPED error visible, never a spinner
        var s: TurnLive? = null
        s = reduce(s, thought("pensando"))
        s = reduce(
            s,
            ev(
                "task_error",
                "error" to str("usage cap exceeded"),
                "code" to str("fault.provider.quotaExhausted"),
                "attribution" to buildJsonObject {
                    put("source", str("provider")); put("statusCode", 429); put("retryable", false)
                },
            ),
        )
        assertTrue(s!!.phase is TurnPhase.Done) // resolved — never a hanging thinking
        val pill = s.typedError!!
        assertTrue(pill.isQuota)
        val text = LiveTurn.render(s, nowMs = s.lastEventAtMs!! + 1_000, spin = 0, channelAlive = true)
        assertTrue("💀 <b>error:</b> usage cap exceeded" in text)
        assertTrue("fault.provider.quotaExhausted" in text)
        assertTrue("HTTP 429" in text)
        assertTrue("💳 CUOTA" in text)
        val seal = LiveTurn.sealText(s, nowMs = s.lastEventAtMs!! + 2_000)
        assertTrue("usage cap exceeded" in seal)
        assertTrue("💳 CUOTA" in seal)
    }

    @Test
    fun everyActionHasItsOwnVerb() {
        // «cada thinking debe ser distinto» — the render speaks the INJECTED
        // verbifier's vocabulary (the wiring passes the spectator's map)
        val verbs = ToolVerbifier { name, input, _ ->
            when (name) {
                "Read" -> ToolVerb("📖", "reading", input?.str("file_path") ?: "")
                "Bash" -> ToolVerb("⚡", "shell", input?.str("command") ?: "")
                else -> ToolVerb("🔧", name)
            }
        }
        var s: TurnLive? = null
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("tr"), "toolName" to str("Read"),
                "input" to buildJsonObject { put("file_path", str("/w/Main.kt")) },
            ),
        )
        var text = LiveTurn.render(s!!, nowMs = s.lastEventAtMs!!, spin = 0, channelAlive = true, verbs = verbs)
        assertTrue("📖 <b>reading</b> <code>/w/Main.kt</code>" in text)

        s = reduce(
            null,
            ev(
                "tool_call", "toolId" to str("tb"), "toolName" to str("Bash"),
                "input" to buildJsonObject { put("command", str("npm test")) },
            ),
        )
        text = LiveTurn.render(s!!, nowMs = s.lastEventAtMs!!, spin = 0, channelAlive = true, verbs = verbs)
        assertTrue("⚡ <b>shell</b> <code>npm test</code>" in text)

        // the minimal fallback knows MCP on its own (mcp__ or the plugin marker)
        s = reduce(
            null,
            ev(
                "tool_call", "toolId" to str("tm"), "toolName" to str("mcp__serper__search"),
                "pluginMetadata" to buildJsonObject { put("component", str("mcp")); put("pluginId", str("serper")) },
            ),
        )
        assertTrue(s!!.tools.first().isMcp)
        text = LiveTurn.render(s, nowMs = s.lastEventAtMs!!, spin = 0, channelAlive = true)
        assertTrue("🔌 <b>mcp</b> <code>serper__search</code>" in text)
    }

    @Test
    fun theEditStampCarriesTheDifferVerdict() {
        var s: TurnLive? = null
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("te"), "toolName" to str("Edit"),
                "input" to buildJsonObject {
                    put("old_string", str("a\nb\nc"))
                    put("new_string", str("a\nX\nc\nd"))
                },
            ),
        )
        s = reduce(s, ev("tool_call_update", "toolId" to str("te"), "status" to str("completed")))
        val lane = s!!.tools.first()
        assertEquals(3, lane.added) // X, c and d (the simple differ counts the whole sides)
        assertEquals(2, lane.removed) // b and c
        val text = LiveTurn.render(s, nowMs = s.lastEventAtMs!!, spin = 0, channelAlive = true)
        assertTrue("🟢 +3 · 🔴 −2" in text)
    }

    @Test
    fun theBlockSealsExactlyAt3KCodePointsAndTheNextDeltaOpensBlock2() {
        // Python's CotSealer.feed (reasoning.py): at the cap the box is
        // full and its number travels; the CoT continues and brakes there
        var s: TurnLive? = null
        repeat(300) { s = reduce(s, thought("0123456789")) } // exactly 3000 cp
        assertEquals(1, s!!.cotBlocks.size)
        assertEquals(3000, s!!.cotBlocks[0].length) // EXACTLY at the boundary
        assertEquals("0123456789".repeat(300), s!!.cotBlocks[0])
        assertEquals("", s!!.cotPending)
        // the next delta opens block 2 — block 1 never grows again
        s = reduce(s, thought("nuevo"))
        assertEquals(1, s!!.cotBlocks.size)
        assertEquals("nuevo", s!!.cotPending)

        // a delta STRADDLING the boundary: block 2 closes AT 3000 cp (the
        // exact slice), the remainder opens block 3
        s = reduce(s, thought("A".repeat(2900))) // 2905 cp pending («nuevo» + A's)
        assertEquals(1, s!!.cotBlocks.size)
        s = reduce(s, thought("B".repeat(200)))
        assertEquals(2, s!!.cotBlocks.size)
        assertEquals(3000, s!!.cotBlocks[1].length)
        assertEquals("nuevo" + "A".repeat(2900) + "B".repeat(95), s!!.cotBlocks[1])
        assertEquals("B".repeat(105), s!!.cotPending)
        assertEquals(6105, s!!.thoughtChars) // the total never truncates
    }

    @Test
    fun theBlockCutNeverSplitsASurrogatePair() {
        // the house ruler is CODE POINTS (PyText.cpLength): an emoji at the
        // boundary rides whole — offsetByCodePoints counts pairs as one
        val emoji = "🤖"
        var s: TurnLive? = null
        s = reduce(s, thought("a".repeat(2999) + emoji + "c".repeat(50)))
        assertEquals(1, s!!.cotBlocks.size)
        assertEquals(3000, s!!.cotBlocks[0].codePointCount(0, s!!.cotBlocks[0].length)) // 2999 a + the emoji
        assertEquals(3001, s!!.cotBlocks[0].length) // the pair is 2 UTF-16 units
        assertTrue(s!!.cotBlocks[0].endsWith(emoji))
    }

    @Test
    fun theTapExpandsTheBlockWholeAboveItsRowAndTheSameTapFolds() {
        var s: TurnLive? = null
        repeat(300) { s = reduce(s, thought("0123456789")) } // block 1 sealed
        s = reduce(s, thought("y sigue la segunda parte del pensamiento"))

        // folded: the keyboard carries the block's button, the live preview shows
        val kb = LiveTurn.cotMarkup(s!!)
        assertTrue(kb.toString().contains("cb:1"))
        assertTrue(kb.toString().contains("📖 CoT 1"))
        assertTrue("<blockquote>" in LiveTurn.render(s!!, nowMs = 1L, spin = 0, channelAlive = true))

        // EXPANDED (the owner's refinement: the text opens IN the card,
        // above the button row — the block WHOLE, never a summary)
        val open = s!!.copy(cotExpandedBlock = 1)
        val text = LiveTurn.render(open, nowMs = 1L, spin = 0, channelAlive = true)
        assertTrue("📖 <b>CoT 1</b>" in text)
        assertTrue("<blockquote>${"0123456789".repeat(300)}</blockquote>" in text) // the COMPLETE block
        assertTrue("⤴️ CoT 1" in LiveTurn.cotMarkup(open).toString()) // the fold mark on the same button
        assertTrue("📖 CoT 1" !in LiveTurn.cotMarkup(open).toString()) // …replacing the open mark
        // the live preview yields the surface to the reading (the budget
        // is the block's) — the LAST body element is the block, right
        // above the row
        assertTrue(text.trimEnd().endsWith("</blockquote>"))
        val preview = LiveTurn.cotBlock(open)
        assertNull(preview)

        // the SAME tap folds: the hub's toggle cycle
        runBlocking {
            val hub = LiveHub()
            hub.apply(thought("x".repeat(3000)), nowMs = 1L)
            hub.toggleCotBlock(1)
            assertEquals(1, hub.peek()!!.cotExpandedBlock)
            hub.toggleCotBlock(1)
            assertNull(hub.peek()!!.cotExpandedBlock)
        }
    }

    @Test
    fun theTailPreviewCoexistsWithTheBlocks() {
        // two blocks sealed, the third filling: the preview keeps flowing
        // (the LIVE window) while the buttons carry the reading
        var s: TurnLive? = null
        repeat(650) { s = reduce(s, thought("0123456789")) } // 6500 cp: 2 blocks + 500
        assertEquals(2, s!!.cotBlocks.size)
        assertEquals(500, s!!.cotPending.length)
        val text = LiveTurn.render(s!!, nowMs = 1L, spin = 0, channelAlive = true)
        assertTrue("<blockquote>" in text) // the preview lives
        assertTrue("tocá el botón para abrirlo" !in text) // the old collapsed line is GONE
        val kb = LiveTurn.cotMarkup(s!!).toString()
        assertTrue("cb:1" in kb && "cb:2" in kb)
        // a block tap during the ANSWER (Writing) still shows the row — the
        // blocks are sealed, the reading does not die with the phase
        s = reduce(s, ev("agent_message_chunk", "content" to str("la respuesta")))
        assertTrue("cb:1" in LiveTurn.cotMarkup(s!!).toString())
    }

    @Test
    fun theKeyboardCapsAt8BlocksAndTheRenderSaysSo() {
        var s: TurnLive? = null
        repeat(10) { _ ->
            repeat(300) { s = reduce(s, thought("0123456789")) } // one block per pass
        }
        assertEquals(10, s!!.cotBlocks.size)
        val datas = LiveTurn.cotMarkup(s!!)!!["inline_keyboard"]!!.jsonArray
            .flatMap { it.jsonArray }
            .map { it.jsonObject["callback_data"]!!.jsonPrimitive.content }
        assertEquals((3..10).map { "cb:$it" }, datas) // the most recent 8, in order
        assertTrue("hay 2 bloques CoT fuera del teclado" in LiveTurn.render(s!!, nowMs = 1L, spin = 0, channelAlive = true))

        // the shower keeps the promise: blocks 1..2 travel with the close,
        // the REAL text, chunked under the message law
        val shower = LiveTurn.overflowShower(s!!)
        assertTrue(shower.isNotEmpty())
        assertTrue("los primeros 2 bloques" in shower.first()) // the honest header
        assertTrue(shower.joinToString("").contains("0123456789".repeat(300))) // a WHOLE block, verbatim
        shower.forEach { chunk ->
            assertTrue(chunk.codePointCount(0, chunk.length) <= LiveTurn.COT_SHOWER_CHUNK_CP + 120)
        }
    }

    @Test
    fun anOutOfRangeTapFoldsSilently() {
        runBlocking {
            val hub = LiveHub()
            hub.apply(thought("x".repeat(3000)), nowMs = 1L) // one block
            hub.toggleCotBlock(7) // a stale button of a past card
            assertNull(hub.peek()!!.cotExpandedBlock)
            hub.toggleCotBlock(1)
            hub.toggleCotBlock(99)
            assertNull(hub.peek()!!.cotExpandedBlock)
        }
    }

    @Test
    fun sealedBlocksSurviveASeqGapResync() {
        // the resync re-baselines the LIVE windows (the tail dies) but the
        // sealed blocks are complete real text — a gap cuts the seam,
        // never a sealed box
        runBlocking {
            val hub = LiveHub()
            hub.apply(thought("x".repeat(3000)), nowMs = 1L)
            hub.toggleCotBlock(1)
            hub.resync("task-9")
            val after = hub.peek()!!
            assertEquals(listOf("x".repeat(3000)), after.cotBlocks)
            assertEquals("", after.cotTail)
            assertEquals("", after.cotPending)
            assertNull(after.cotExpandedBlock) // the tap's view folds with the window
        }
    }

    @Test
    fun sealTextCarriesTheBlockCount() {
        var s: TurnLive? = null
        s = reduce(null, ServerFrame.StreamEvent("task-9", 1, 1000L, buildJsonObject { put("type", "task_run_started") }))
        repeat(300) { s = reduce(s, thought("0123456789")) }
        s = reduce(s, ev("task_complete", "stopReason" to str("end_turn")))
        val seal = LiveTurn.sealText(s!!, nowMs = 61_000)
        assertTrue("1 bloque CoT" in seal)
    }

    @Test
    fun theExpandedBlockClipsOnlyUnderEscInflationAndSaysSo() {
        // a prose block opens WHOLE (3000 cp fit the 3400 window)
        var s: TurnLive? = null
        s = reduce(s, thought("palabra ".repeat(500))) // 3500 cp → block 1 = 3000 cp of prose
        val open = s!!.copy(cotExpandedBlock = 1)
        val text = LiveTurn.expandedBlockSection(open)!!
        assertTrue("3000 chars" in text)
        assertTrue("recortado" !in text)
        // an HTML-heavy block: esc() would inflate it past the law — the
        // clip happens and SAYS so (the transport must never silently cut)
        var h: TurnLive? = null
        h = reduce(h, thought("&".repeat(3000))) // esc → 15000 chars
        val clipped = LiveTurn.expandedBlockSection(h!!.copy(cotExpandedBlock = 1))!!
        assertTrue("recortado" in clipped)
        assertTrue("bloque de 3000 chars" in clipped)
        // the escaped text stays inside the budget (the 4096 law)
        val escapedLen = clipped.codePointCount(0, clipped.length)
        assertTrue(escapedLen < 4096)
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
        assertTrue("nada corriendo" in text)
    }

    // ---- the owner's 30/10 verdict on the corpse card («quedó fatal») ----

    @Test
    fun theFallbackTitleIsTheSidTail() {
        // the card's fallback IS the sid's tail — «644150daf013», the head
        // the owner refused; the resolver's guard is exactly this shape
        val sid = "sess_abcdef644150daf013"
        assertTrue(LiveTurn.isFallbackTitle(sid, sid.takeLast(12)))
        assertTrue(!LiveTurn.isFallbackTitle(sid, "arreglá el login"))
    }

    @Test
    fun editCountsGrowWhileTheInputStreams() {
        // the +N/−M breathe: the differ runs per delta on the PARTIAL pair —
        // the owner watches the edit grow, not a static 0/0 for seconds
        var s: TurnLive? = null
        s = reduce(
            s,
            ev(
                "tool_call_update", "toolId" to str("te"), "toolName" to str("Edit"), "status" to str("pending"),
                "input" to buildJsonObject { put("old_string", str("a")); put("new_string", str("a\nX")) },
            ),
        )
        assertEquals(1, s!!.tools.first().added)
        assertEquals(0, s.tools.first().removed)
        s = reduce(
            s,
            ev(
                "tool_call_update", "toolId" to str("te"), "toolName" to str("Edit"), "status" to str("pending"),
                "input" to buildJsonObject { put("old_string", str("a\nb")); put("new_string", str("a\nX\nc")) },
            ),
        )
        assertEquals(2, s!!.tools.first().added)
        assertEquals(1, s.tools.first().removed)
        val live = LiveTurn.render(s, nowMs = s.lastEventAtMs!!, spin = 0, channelAlive = true)
        assertTrue("🟢 +2 · 🔴 −1" in live)
        // the close recomputes on the COMPLETE input — the authority
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("te"), "toolName" to str("Edit"),
                "input" to buildJsonObject { put("old_string", str("a\nb\nc")); put("new_string", str("a\nX\nc\nd")) },
            ),
        )
        s = reduce(s, ev("tool_call_update", "toolId" to str("te"), "status" to str("completed")))
        assertEquals(3, s!!.tools.first().added)
        assertEquals(2, s.tools.first().removed)
    }

    @Test
    fun bashLaneShowsItsCommandAndPromisesTheOutputAtClose() {
        // the daemon's real map puts the command in the detail — the lane
        // shows WHAT runs, not just the verb
        val verbs = ToolVerbifier { name, input, _ ->
            if (name == "Bash") ToolVerb("⚡", "shell", input?.str("command") ?: "") else ToolVerb("🔧", name)
        }
        var s: TurnLive? = null
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("tb"), "toolName" to str("Bash"),
                "input" to buildJsonObject { put("command", str("npm test")) },
            ),
        )
        val text = LiveTurn.render(s!!, nowMs = s.lastEventAtMs!!, spin = 0, channelAlive = true, verbs = verbs)
        assertTrue("⚡ <b>shell</b> <code>npm test</code>" in text) // the command renders on the lane
        assertTrue("salida al cerrar" in text) // honest: this fork streams no live tool output
        assertTrue("0 líneas" !in text) // the invented zero is GONE
    }

    @Test
    fun theOutputLandsAtCloseCountedOnce() {
        var s: TurnLive? = null
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("tb"), "toolName" to str("Bash"),
                "input" to buildJsonObject { put("command", str("npm test")) },
            ),
        )
        // while the tool runs: NO output claim — the fork streams none
        val open = LiveTurn.render(s!!, nowMs = s.lastEventAtMs!!, spin = 0, channelAlive = true)
        assertTrue("líneas de salida" !in open)
        // the close carries the result content — counted ONCE, at close
        s = reduce(
            s,
            ev(
                "tool_call_update", "toolId" to str("tb"), "status" to str("completed"),
                "content" to str("passing 1\npassing 2\npassing 3"),
            ),
        )
        assertEquals(3, s!!.tools.first().outputLines)
        val text = LiveTurn.render(s, nowMs = s.lastEventAtMs!!, spin = 0, channelAlive = true)
        assertTrue("🔧 Bash: 3 líneas de salida" in text)
        // a structured payload invents no count (the close boxes render it)
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("tj"), "toolName" to str("Bash"),
                "input" to buildJsonObject { put("command", str("echo '{}'")) },
            ),
        )
        s = reduce(
            s,
            ev(
                "tool_call_update", "toolId" to str("tj"), "status" to str("completed"),
                "content" to buildJsonObject { put("ok", true) },
            ),
        )
        assertNull(s!!.tools.first { it.toolId == "tj" }.outputLines)
    }

    @Test
    fun theLaneCapKeepsTheCardBounded() {
        // seven lanes open — the card shows only the 5 MOST RECENT (the
        // command in the detail is how each lane is told apart)
        val verbs = ToolVerbifier { _, input, _ -> ToolVerb("🔧", "shell", input?.str("command") ?: "") }
        var s: TurnLive? = null
        repeat(7) { i ->
            s = reduce(
                s,
                ev(
                    "tool_call", "toolId" to str("t$i"), "toolName" to str("Bash"),
                    "input" to buildJsonObject { put("command", str("cmd$i")) },
                ),
            )
        }
        val text = LiveTurn.render(s!!, nowMs = s.lastEventAtMs!!, spin = 0, channelAlive = true, verbs = verbs)
        assertTrue("cmd6" in text)
        assertTrue("cmd2" in text) // the 5 most recent survive
        assertTrue("cmd1" !in text) // the corpses drop off entirely
        assertTrue("cmd0" !in text)
    }

    @Test
    fun aStaleLaneRendersFrozenWithItsClockStopped() {
        // the command in the detail is how the lane is told apart
        val verbs = ToolVerbifier { _, input, _ -> ToolVerb("🔧", "shell", input?.str("command") ?: "") }
        var s: TurnLive? = null
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("told"), "toolName" to str("Bash"),
                "input" to buildJsonObject { put("command", str("npm install")) },
            ),
        )
        val openedAt = s!!.tools.first().openedAtMs
        // the stream moves on (another lane) while the Bash lane hears nothing
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("tnew"), "toolName" to str("Bash"),
                "input" to buildJsonObject { put("command", str("sleep 5")) },
            ),
        )
        // past LANE_FROZEN_MS of lane silence: the lane dims, its timer
        // stops at its last real event, the silence speaks honestly
        val text = LiveTurn.render(
            s!!,
            nowMs = openedAt + LiveTurn.LANE_FROZEN_MS + 5_000,
            spin = 0,
            channelAlive = true,
            verbs = verbs,
        )
        assertTrue("❄️" in text)
        assertTrue("npm install" in text)
        assertTrue("frozen · 10m05s sin eventos" in text) // now − touchedAt
        assertTrue("· 0s · frozen" in text, "the frozen clock stops at the last real event")
        // an ACKed dispatch lane NEVER freezes — the child runs as its OWN
        // session (the sa: card is the live view there); a frozen claim the
        // parent stream cannot verify would be the lie in the other direction
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("td"), "toolName" to str("Agent"),
                "subagentAgentId" to str("agent-9"),
                "input" to buildJsonObject { put("description", str("fix the menu")) },
            ),
        )
        val later = LiveTurn.render(s, nowMs = openedAt + 2 * LiveTurn.LANE_FROZEN_MS, spin = 0, channelAlive = true, verbs = verbs)
        // the LANE line (the phase line has no counts): it must stay warm
        val dispatchLine = later.lineSequence().first { "<b>shell</b>" in it && "salida al cerrar" in it }
        assertTrue("❄️" !in dispatchLine)
    }

    @Test
    fun aDispatchCloseFoldsItsInnerLanes() {
        var s: TurnLive? = null
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("d1"), "toolName" to str("Agent"),
                "input" to buildJsonObject { put("description", str("fix the /plan menu")) },
            ),
        )
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("inner"), "toolName" to str("Bash"),
                "parentToolUseId" to str("d1"),
                "input" to buildJsonObject { put("command", str("ls")) },
            ),
        )
        s = reduce(s, thought("child thinking", parent = "d1"))
        assertEquals(14, s!!.subagentThought["d1"])
        // the dispatch closes: the child's world folds away WITH it — no
        // orphan lane ticking forever (the owner's 30/10 corpse card)
        s = reduce(s, ev("tool_call_update", "toolId" to str("d1"), "status" to str("completed")))
        assertTrue(s!!.tools.none { it.toolId == "inner" })
        assertTrue(s.subagentThought.isEmpty())
        assertEquals("completed", s.tools.first { it.toolId == "d1" }.status)
        val text = LiveTurn.render(s, nowMs = s.lastEventAtMs!! + 1_000, spin = 0, channelAlive = true)
        assertTrue("ls" !in text) // the inner lane left the card
        assertTrue("subagente" !in text) // its thought counter left with it
    }
}
