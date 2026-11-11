package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
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

    /**
     * A UNIQUE variant of the given content, per call (the GLOBAL seq, zero
     * padded — never repeats within a test): the reburst guards key on
     * CONTENT (a repeated identical chunk IS a re-delivery to them), so the
     * volume-building loops must stream DISTINCT chunks — same length, the
     * counter rides the tail.
     */
    private fun numberedThought(content: String): ServerFrame.StreamEvent {
        val tail: String = seq.toString().padStart(4, '0')
        return thought(content.dropLast(4) + tail)
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
        repeat(300) { s = reduce(s, numberedThought("0123456789")) } // 3000 chars, 300 distinct chunks
        assertEquals(LiveTurn.COT_TAIL_CP, s!!.cotTail.length)
        assertTrue(s!!.cotTail.endsWith("0123450299"), "the tail carries the NEWEST words")
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
        // THE WORDS, NOT ONLY THE COUNT (owner, 3/11): the tail keeps the
        // streamed text so the card renders it (Python's text[-3200:]).
        assertEquals("La respuesta", s.answerTail)
        s = reduce(s, ev("agent_message_chunk", "content" to str(" sigue")))
        assertEquals("La respuesta sigue", s!!.answerTail)
        assertEquals(18, s.answerChars)

        // the replay guard: a sentence-sized re-emission the tail already
        // ends with folds NOTHING (the fork's recovery, measured 30/10)
        val sentence = " y esta es una frase completa que ya viajó."
        s = reduce(s, ev("agent_message_chunk", "content" to str(sentence)))
        val chars = s!!.answerChars
        s = reduce(s, ev("agent_message_chunk", "content" to str(sentence)))
        assertEquals(chars, s!!.answerChars, "a replayed sentence never doubles")
        assertEquals(1, s.answerTail.split(sentence.trim()).size - 1, "…and never doubles in the tail")

        s = reduce(s, ev("task_complete", "stopReason" to str("end_turn"), "usage" to buildJsonObject { put("totalTokens", 4242) }))
        val done = s.phase as TurnPhase.Done
        assertEquals("end_turn", done.stopReason)
        assertEquals(4242L, s.usageTokens)
    }

    @Test
    fun theCardSpeaksTheAnswerAsItStreams() {
        var s: TurnLive? = null
        s = reduce(s, ev("task_run_started"))
        s = reduce(s, ev("agent_message_chunk", "content" to str("Voy a revisar <el> stream & volver.")))
        val text = LiveTurn.render(s!!, nowMs = s.lastEventAtMs!! + 2_000, channelAlive = true)
        assertTrue("<pre>" in text, "the live box carries the words in a pre block: $text")
        assertTrue("Voy a revisar &lt;el&gt; stream &amp; volver." in text, "escaped, verbatim: $text")
        assertTrue("escribiendo la respuesta" in text, text)
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
    fun renderShowsTheWorkingHeaderTheNowLineAndTheSoberStatusLine() {
        var s: TurnLive? = null
        val startAt = now
        s = reduce(null, ServerFrame.StreamEvent("task-9", 1, startAt, buildJsonObject { put("type", "task_run_started") }))
        s = reduce(s, thought("estoy pensando mucho sobre este problema"))
        // LIVE: fresh event (2s old) — the clocks tick
        val last = s!!.lastEventAtMs!!
        val text = LiveTurn.render(s, nowMs = last + 2_000, channelAlive = true)
        // THE WORKING HEADER (the owner's 5/11 words: «Procesando... xS» —
        // hace cuánto empezó la tarea) + the NOW line beneath it
        assertTrue("⏳ <b>Procesando... 2s</b>" in text)
        assertTrue("🧠 <b>generando CoT</b> · 40 caracteres" in text, "a fresh delta IS the CoT flowing: $text")
        assertTrue("40 caracteres" in text) // the counter, with the total
        assertTrue("💓" !in text) // THE HEART IS DEAD (the owner's 30/10 verdict)
        assertTrue("💭" !in text) // the blink died with the redraw law: only REAL change re-draws
        assertTrue("en vivo · último evento hace 2s · 0 listos" in text) // the sober line
        assertTrue("0 listos" in text)
        assertTrue("<blockquote>" !in text, "the tail is a ONE-LINE char count — it never spills")

        // the dead channel speaks frozen — the 📡 reconnect line AND the sober word
        val dead = LiveTurn.render(s, nowMs = last + 5_000, channelAlive = false)
        assertTrue("📡" in dead)
        assertTrue("frozen · 5s sin eventos · 0 listos" in dead)

        // THE OWNER'S 5/11 RULE: 42s of silence with the channel ALIVE — the
        // clock KEEPS RUNNING (a silent model may be legitimately grinding;
        // «Procesando... xS» ticks on) while the silence itself speaks
        val quiet = LiveTurn.render(s, nowMs = last + 42_000, channelAlive = true)
        assertTrue("(42s sin eventos)" in quiet)
        assertTrue("⏳ <b>Procesando... 42s</b>" in quiet, "the timer runs while the channel lives")
        assertTrue("🧠 <b>procesando…</b> (42s sin eventos)" in quiet, "a quiet card never claims «generando»: $quiet")
        // 42s of silence is still «en vivo» — the line carries the honest age,
        // «frozen» is reserved for the cut stream (the hard word lives at 90s)
        assertTrue("en vivo · último evento hace 42s" in quiet)
        val cut = LiveTurn.render(s, nowMs = last + 120_000, channelAlive = true)
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
        val text = LiveTurn.render(s!!, nowMs = s.lastEventAtMs!! + 10_000, channelAlive = true)
        // the retry pill holds the frozen clock — the NOW timer runs on it
        assertTrue("🔧 <b>Write</b> · 0s · 4 líneas" in text, text) // the write family's line: timer + streamed lines
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
        val fresh = LiveTurn.render(s, nowMs = last + 5_000, channelAlive = true)
        assertTrue("sin eventos" !in fresh) // fresh = a plain honest thinking line
        val stale = LiveTurn.render(s, nowMs = last + 30_000, channelAlive = true)
        assertTrue("(30s sin eventos)" in stale) // 20-30s of silence: the degrade
        val cut = LiveTurn.render(s, nowMs = last + 120_000, channelAlive = true)
        assertTrue("(2m00s sin eventos — stream cortado?)" in cut) // the hard word
    }

    @Test
    fun aDroppedSocketIsAReconnectStateNotAFakeTurn() {
        var s: TurnLive? = null
        s = reduce(s, thought("pensando"))
        val text = LiveTurn.render(s!!, nowMs = s.lastEventAtMs!! + 8_000, channelAlive = false)
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
        val text = LiveTurn.render(s, nowMs = s.lastEventAtMs!! + 1_000, channelAlive = true)
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
            null,
            ev(
                "tool_call", "toolId" to str("tr"), "toolName" to str("Read"),
                "input" to buildJsonObject { put("file_path", str("/w/Main.kt")) },
            ),
        )
        var text = LiveTurn.render(s!!, nowMs = s.lastEventAtMs!!, channelAlive = true, verbs = verbs)
        assertTrue("📖 <b>reading</b> <code>/w/Main.kt</code>" in text)

        s = reduce(
            null,
            ev(
                "tool_call", "toolId" to str("tb"), "toolName" to str("Bash"),
                "input" to buildJsonObject { put("command", str("npm test")) },
            ),
        )
        text = LiveTurn.render(s!!, nowMs = s.lastEventAtMs!!, channelAlive = true, verbs = verbs)
        // v2 §3/§5: the bash NOW line IS the command — complete, in the bash block
        assertTrue("⚡ <b>Bash</b> · 0s" in text, text)
        assertTrue("<pre><code class=\"language-bash\">npm test</code></pre>" in text, text)

        // the minimal fallback knows MCP on its own (mcp__ or the plugin marker)
        s = reduce(
            null,
            ev(
                "tool_call", "toolId" to str("tm"), "toolName" to str("mcp__serper__search"),
                "pluginMetadata" to buildJsonObject { put("component", str("mcp")); put("pluginId", str("serper")) },
            ),
        )
        assertTrue(s!!.tools.first().isMcp)
        text = LiveTurn.render(s, nowMs = s.lastEventAtMs!!, channelAlive = true)
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
        val text = LiveTurn.render(s, nowMs = s.lastEventAtMs!!, channelAlive = true)
        assertTrue("🟢 +3 · 🔴 −2" in text)
    }

    @Test
    fun theBlockSealsExactlyAt3KCodePointsAndTheNextDeltaOpensBlock2() {
        // Python's CotSealer.feed (reasoning.py): at the cap the box is
        // full and its number travels; the CoT continues and brakes there
        var s: TurnLive? = null
        repeat(300) { s = reduce(s, numberedThought("0123456789")) } // exactly 3000 cp
        assertEquals(1, s!!.cotBlocks.size)
        assertEquals(3000, s!!.cotBlocks[0].length) // EXACTLY at the boundary
        val expectedBlock = (0 until 300).joinToString("") { "012345" + it.toString().padStart(4, '0') }
        assertEquals(expectedBlock, s!!.cotBlocks[0])
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
    fun theCardCarriesTheCoTCountOnlyNeverTheText() {
        // THE LEY RAÍZ (the owner, 2/11): the card is the PROCESS, the CoT
        // text is data — the card carries the COUNT (the collapsed blocks
        // live as their own messages below, the tap opens them), never a
        // preview, never a button
        var s: TurnLive? = null
        repeat(300) { s = reduce(s, numberedThought("0123456789")) } // block 1 sealed
        s = reduce(s, thought("y sigue la segunda parte del pensamiento"))
        val text = LiveTurn.render(s!!, nowMs = 1L, channelAlive = true)
        assertTrue("<blockquote>" !in text, "the tail never spills")
        assertTrue("cb:" !in text, "no block buttons on the card")
        assertTrue("0123456789" !in text, "no block text on the card")
        assertTrue("📖 CoT: 1 bloque plegado" in text)
    }

    @Test
    fun theCoTRowCountsBlocksAndThePendingFill() {
        // two blocks sealed, the third filling: the row speaks both counts
        var s: TurnLive? = null
        repeat(650) { s = reduce(s, numberedThought("0123456789")) } // 6500 cp: 2 blocks + 500
        assertEquals(2, s!!.cotBlocks.size)
        assertEquals(500, s!!.cotPending.length)
        val text = LiveTurn.render(s!!, nowMs = 1L, channelAlive = true)
        assertTrue("📖 CoT: 2 bloques plegados · 500 en camino" in text)
        assertTrue("<blockquote>" !in text)
    }

    @Test
    fun sealedBlocksSurviveASeqGapResync() {
        // the resync re-baselines the LIVE windows (the tail dies) but the
        // sealed blocks are complete real text — a gap cuts the seam,
        // never a sealed box (and the feed already spoke them)
        runBlocking {
            val hub = LiveHub()
            hub.apply(thought("x".repeat(3000)), nowMs = 1L)
            hub.resync("task-9")
            val after = hub.peek()!!
            assertEquals(listOf("x".repeat(3000)), after.cotBlocks)
            assertEquals("", after.cotTail)
            assertEquals("", after.cotPending)
        }
    }

    @Test
    fun sealTextCarriesTheBlockCount() {
        var s: TurnLive? = null
        s = reduce(null, ServerFrame.StreamEvent("task-9", 1, 1000L, buildJsonObject { put("type", "task_run_started") }))
        repeat(300) { s = reduce(s, numberedThought("0123456789")) }
        s = reduce(s, ev("task_complete", "stopReason" to str("end_turn")))
        val seal = LiveTurn.sealText(s!!, nowMs = 61_000)
        assertTrue("1 bloque CoT" in seal)
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
    fun theCardStaysUnderTheMessageLawWithLongThinking() {
        var s: TurnLive? = null
        repeat(200) { s = reduce(s, numberedThought("0123456789")) } // 2000 chars of tail
        val text = LiveTurn.render(s!!, nowMs = 100_000, channelAlive = true)
        assertTrue(text.length < 4096) // the whole message fits Telegram
        assertTrue(s!!.cotTail.length == 2000) // the tail below the cap keeps everything (the replay guard)
        assertTrue("2000 caracteres" in text) // the one-line count, chars only
    }

    @Test
    fun idleIsTheHonestStart() {
        val s = LiveTurn.reduce(null, ev("usage_update"), nowMs = 5L)
        assertEquals(TurnPhase.Idle, s.phase)
        val text = LiveTurn.render(s, nowMs = 6L, channelAlive = true)
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
        val live = LiveTurn.render(s, nowMs = s.lastEventAtMs!!, channelAlive = true)
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
    fun bashLaneShowsItsCompleteCommandInTheBashBlock() {
        // v2 §3/§5: the NOW line for a shell speaks the command WHOLE inside
        // the bash block (Telegram's closest to «fondo negro, letras verdes»)
        val verbs = ToolVerbifier { name, _, _ ->
            if (name == "Bash") ToolVerb("⚡", "shell", "") else ToolVerb("🔧", name)
        }
        var s: TurnLive? = null
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("tb"), "toolName" to str("Bash"),
                "input" to buildJsonObject { put("command", str("npm test")) },
            ),
        )
        val text = LiveTurn.render(s!!, nowMs = s.lastEventAtMs!!, channelAlive = true, verbs = verbs)
        assertTrue("⚡ <b>Bash</b> · 0s" in text, text)
        assertTrue("<pre><code class=\"language-bash\">npm test</code></pre>" in text, text)
        assertTrue("0 líneas" !in text) // the invented zero is GONE
        // a >1800 cp command: the card shows the head and promises the feed —
        // the command itself is NEVER elided mid-way there (v2 §3)
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("tbig"), "toolName" to str("Bash"),
                "input" to buildJsonObject { put("command", str("y".repeat(2000))) },
            ),
        )
        val big = LiveTurn.render(s, nowMs = s.lastEventAtMs!!, channelAlive = true, verbs = verbs)
        assertTrue("y".repeat(1800) in big, "the first 1800 cp ride the card")
        assertTrue("y".repeat(1801) !in big, "…and not one more")
        assertTrue("✂️ … el comando completo va en el feed" in big, big)
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
        val open = LiveTurn.render(s!!, nowMs = s.lastEventAtMs!!, channelAlive = true)
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
        val text = LiveTurn.render(s, nowMs = s.lastEventAtMs!!, channelAlive = true)
        assertTrue("🔧 Bash · 0s · 3 líneas de salida" in text, "the stamp carries the runtime (v2 §5): $text")
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
        val text = LiveTurn.render(s!!, nowMs = s.lastEventAtMs!!, channelAlive = true, verbs = verbs)
        assertTrue("cmd6" in text) // the NOW line (the most recent lane speaks first)
        assertTrue("cmd2" in text) // the 5 most recent OF THE REST survive (the NOW lane's slot is its own)
        assertTrue("cmd1" in text)
        assertTrue("cmd0" !in text) // the corpses drop off entirely — the card stays bounded
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
        val later = LiveTurn.render(s, nowMs = openedAt + 2 * LiveTurn.LANE_FROZEN_MS, channelAlive = true, verbs = verbs)
        // the DISPATCH LANE's line (no <code>: its input has no command
        // detail, unlike the NOW lane's) must stay warm — the child runs as
        // its own session and the parent stream cannot verify a freeze
        val dispatchLine = later.lineSequence()
            .first { "<b>shell</b>" in it && "salida al cerrar" in it && "<code>" !in it }
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
        val text = LiveTurn.render(s, nowMs = s.lastEventAtMs!! + 1_000, channelAlive = true)
        assertTrue("ls" !in text) // the inner lane left the card
        assertTrue("subagente" !in text) // its thought counter left with it
    }

    @Test
    fun thePrizeLineRidesTheWorkingCardOnly() {
        var s = reduce(null, thought("pienso"))
        val prize = "🎁 <b>Premios</b> · 1 reset 5h — /quota"
        val thinking = LiveTurn.render(s!!, nowMs = now, channelAlive = true, giftsLine = prize)
        assertTrue("Premios" in thinking)

        s = reduce(s, ev("task_complete", "stopReason" to str("end_turn")))
        val done = LiveTurn.render(s!!, nowMs = now, channelAlive = true, giftsLine = prize)
        assertTrue("Premios" !in done) // a Done card is a verdict, not a shelf
    }

    // ---- live card v2 §1: the header's clock is zcode's, mirrored ----

    @Test
    fun theHeaderClockAnchorsOnTheForksStartedAtAndNeverRestarts() {
        var s = reduce(
            null,
            ServerFrame.StreamEvent(
                "task-9", 1, 60_000L,
                buildJsonObject { put("type", "task_run_started"); put("startedAt", 10_000) },
            ),
        )
        assertEquals(10_000L, s!!.startedAtMs, "the payload's startedAt beats the delivery clock")
        s = reduce(
            s,
            ServerFrame.StreamEvent(
                "task-9", 2, 120_000L,
                buildJsonObject { put("type", "task_run_started"); put("startedAt", 30_000) },
            ),
        )
        assertEquals(10_000L, s!!.startedAtMs, "a re-emitted start (stream recovery) never restarts the clock")
    }

    @Test
    fun theWorkingHeadCountsFromTheTasksRealStart() {
        // delivery at 51s, the task REALLY started at 1s: the header says so
        var s = reduce(
            null,
            ServerFrame.StreamEvent(
                "task-9", 1, 51_000L,
                buildJsonObject { put("type", "task_run_started"); put("startedAt", 1_000) },
            ),
        )
        s = reduce(s, thought("pensando"))
        val text = LiveTurn.render(s!!, nowMs = s.lastEventAtMs!! + 1_000, channelAlive = true)
        assertTrue("⏳ <b>Procesando... 1m40s</b>" in text, "the header counts from zcode's start: $text")
    }

    @Test
    fun inProgressKeepsTheActionAliveWithItsOwnAnchor() {
        var s: TurnLive? = null
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("t1"), "toolName" to str("Bash"),
                "input" to buildJsonObject { put("command", str("npm test")) },
            ),
        )
        val openedAt = s!!.tools.first().openedAtMs
        // a sibling opens and closes — the phase drifts to Thinking
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("t2"), "toolName" to str("Bash"),
                "input" to buildJsonObject { put("command", str("ls")) },
            ),
        )
        s = reduce(s, ev("tool_call_update", "toolId" to str("t2"), "status" to str("completed")))
        assertTrue(s!!.phase is TurnPhase.Thinking)
        // t1's in_progress: back to Writing on t1's OWN anchor
        s = reduce(
            s,
            ev("tool_call_update", "toolId" to str("t1"), "toolName" to str("Bash"), "status" to str("in_progress")),
        )
        val writing = s.phase as TurnPhase.Writing
        assertEquals("t1", writing.toolId)
        assertEquals(openedAt, writing.sinceMs, "the action's elapsed keeps the openedAtMs anchor")
        assertEquals("Bash", writing.tool)
    }

    // ---- live card v2 §2: «procesando…» vs «generando CoT» ----

    @Test
    fun theThinkingSplitsGeneratingCoTFromPureProcessing() {
        var s: TurnLive? = null
        s = reduce(s, thought("pensando el problema")) // the delta just landed — the CoT IS flowing
        val last = s!!.lastEventAtMs!!
        val fresh = LiveTurn.render(s, nowMs = last + 2_000, channelAlive = true)
        assertTrue("🧠 <b>generando CoT</b> · 20 caracteres · 2s" in fresh, fresh)
        // 6s since the last delta: under the staleness window, past the CoT
        // one — the honest «procesando…» (the model may be computing pure)
        val quiet = LiveTurn.render(s, nowMs = last + 6_000, channelAlive = true)
        assertTrue("🧠 <b>procesando…</b>" in quiet, quiet)
        assertTrue("sin eventos" !in quiet, "the staleness degrade has not fired under 20s")
        // THE FROZEN CLOCK IS NEVER «GENERANDO»: the freshness reads the
        // real clock, so 42s of silence says «procesando…» + the honest age
        val frozen = LiveTurn.render(s, nowMs = last + 42_000, channelAlive = true)
        assertTrue("🧠 <b>procesando…</b> (42s sin eventos)" in frozen, frozen)
        // a subagent's narration does not keep the CoT fresh
        var sub = reduce(null, thought("main", parent = null))
        sub = reduce(sub, thought("sub narration", parent = "lane-A"))
        val subFresh = LiveTurn.render(sub!!, nowMs = sub.lastEventAtMs!! + 6_000, channelAlive = true)
        assertTrue("🧠 <b>procesando…</b>" in subFresh, "subagent lanes never refresh the card's CoT: $subFresh")
    }

    @Test
    fun afterAToolClosesTheThinkingStartsProcesandoUntilNewDeltas() {
        // v2 §2: lastThoughtAtMs is old right after a close — «procesando…»
        // immediately; the first new delta flips it to «generando CoT»
        var s: TurnLive? = null
        s = reduce(s, thought("antes de la acción"))
        val thoughtAt = s!!.lastEventAtMs!!
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("t1"), "toolName" to str("Bash"),
                "input" to buildJsonObject { put("command", str("ls")) },
            ),
        )
        // the tool runs 6s (past COT_QUIET_MS), then closes
        val closed = reduce(
            s,
            ServerFrame.StreamEvent(
                "task-9", seq + 1, thoughtAt + 6_000,
                buildJsonObject { put("type", "tool_call_update"); put("toolId", "t1"); put("status", "completed") },
            ),
        )
        val afterClose = LiveTurn.render(closed, nowMs = closed.lastEventAtMs!! + 1_000, channelAlive = true)
        assertTrue("🧠 <b>procesando…</b>" in afterClose, afterClose)
        val again = reduce(closed, thought("el CoT sigue"))
        val freshAgain = LiveTurn.render(again, nowMs = again.lastEventAtMs!! + 1_000, channelAlive = true)
        assertTrue("🧠 <b>generando CoT</b>" in freshAgain, freshAgain)
    }

    // ---- live card v2 §3/§6: the edit line and the filename law ----

    @Test
    fun theEditNowLineElidesThePathButNeverTheBasename() {
        val deep = "/home/owner/Projects/zcode-bot-kotlin/modules/channel/src/main/kotlin/dev/lattenero/bot/channel/LiveTurn.kt"
        val verbs = ToolVerbifier { name, _, _ ->
            if (name == "Edit") ToolVerb("✏️", "editing", "") else ToolVerb("🔧", name)
        }
        var s: TurnLive? = null
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("te"), "toolName" to str("Edit"),
                "input" to buildJsonObject {
                    put("file_path", str(deep))
                    put("old_string", str("a\nb"))
                    put("new_string", str("a\nX\nb\nc"))
                },
            ),
        )
        val text = LiveTurn.render(s!!, nowMs = s.lastEventAtMs!!, channelAlive = true, verbs = verbs)
        // the MINIMUM elision wins: the longest head that fits the budget
        assertTrue(
            "✏️ <b>Edit</b> · 0s — <code>…/src/main/kotlin/dev/lattenero/bot/channel/LiveTurn.kt</code>" in text,
            "dir chain elided from the left, basename+extension whole: $text",
        )
        assertTrue("LiveTurn.kt</code>" in text)
        assertTrue("🟢 +3 · 🔴 −1" in text, "the LIVE diff rides the NOW line: $text")
    }

    @Test
    fun theStampCarriesTheRealRuntime() {
        var s: TurnLive? = null
        s = reduce(
            s,
            ev(
                "tool_call", "toolId" to str("tb"), "toolName" to str("Bash"),
                "input" to buildJsonObject { put("command", str("npm test")) },
            ),
        )
        val openedAt = s!!.tools.first().openedAtMs
        // the close lands 12s after the open — BOTH anchors are real events
        val closed = reduce(
            s,
            ServerFrame.StreamEvent(
                "task-9", seq + 1, openedAt + 12_000,
                buildJsonObject {
                    put("type", "tool_call_update"); put("toolId", "tb"); put("status", "completed")
                    put("content", "out1\nout2")
                },
            ),
        )
        val text = LiveTurn.render(closed, nowMs = closed.lastEventAtMs!!, channelAlive = true)
        assertTrue("🔧 Bash · 12s · 2 líneas de salida" in text, "the measured runtime, frozen-clock-safe: $text")
    }

    // ---- live card v2: the number law and the filename law, as tables ----

    @Test
    fun fmtKDecimalSpeaksTheNumberLaw() {
        assertEquals("999", LiveTurn.fmtKDecimal(999))
        assertEquals("1k", LiveTurn.fmtKDecimal(1_000))
        assertEquals("1,25k", LiveTurn.fmtKDecimal(1_250))
        assertEquals("2,75k", LiveTurn.fmtKDecimal(2_750))
        assertEquals("3k", LiveTurn.fmtKDecimal(3_000))
        assertEquals("999,99k", LiveTurn.fmtKDecimal(999_999), "the K rung floors — «1000k» cannot exist")
        assertEquals("1M", LiveTurn.fmtKDecimal(1_000_000), "the M rung starts exactly AT the boundary")
        assertEquals("1,5M", LiveTurn.fmtKDecimal(1_500_000))
    }

    @Test
    fun elidePathNeverCutsInsideTheBasename() {
        assertEquals("/a/b/c.txt", PyText.elidePath("/a/b/c.txt", 100), "fits → unchanged")
        assertEquals("…/chain/file-name.kt", PyText.elidePath("/very/long/dir/chain/file-name.kt", 20))
        assertEquals("…/file-name.kt", PyText.elidePath("/very/long/dir/chain/file-name.kt", 14))
        // the basename alone over the budget is the CALLER's problem — the
        // helper returns it whole under «…/», never cut inside
        assertEquals("…/superlongname.txt", PyText.elidePath("/d/superlongname.txt", 5))
        assertEquals("…/nombre.txt", PyText.elidePath("nombre.txt", 5), "a relative path elides the same way")
    }

}
