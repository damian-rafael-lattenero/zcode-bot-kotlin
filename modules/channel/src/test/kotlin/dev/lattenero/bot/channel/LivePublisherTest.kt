package dev.lattenero.bot.channel

import dev.lattenero.bot.transport.Cooldown
import dev.lattenero.bot.transport.TgApi
import dev.lattenero.bot.transport.TgConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The publisher's TICK against a MockEngine (PollerTest's pattern, ZERO
 * network): the REAL TITLE resolution, and the TRANSCRIPT FEED — the
 * owner's 30/10 order (each sealed CoT block and each tool close its own
 * message, the card ALWAYS re-born last, verbose by default, the level
 * read per tick, the cooldown window as back-pressure).
 */
class LivePublisherTest {

    private val cfg = TgConfig(token = "tok", chatId = JsonPrimitive(42), ownerUserId = 7L)

    /** The captured wire: (json body, method) per call, answered OK always — each send a FRESH message id. */
    private class Wire {
        val bodies = mutableListOf<Pair<String, String>>()
        private var nextMid = 100L
        val factory: HttpClientEngineFactory<MockEngineConfig> =
            object : HttpClientEngineFactory<MockEngineConfig> {
                override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine =
                    MockEngine(
                        MockEngineConfig().apply(block).apply {
                            addHandler { request ->
                                bodies += (request.body as TextContent).text to request.url.segments.last()
                                respond("""{"result":{"message_id":${++nextMid}}}""", HttpStatusCode.OK)
                            }
                        },
                    )
            }
    }

    private var seq = 0L

    private fun frame(taskId: String, type: String, at: Long, vararg fields: Pair<String, JsonElement>): ServerFrame.StreamEvent {
        seq += 1
        return ServerFrame.StreamEvent(
            taskId = taskId,
            seq = seq,
            at = at,
            event = buildJsonObject {
                put("type", type)
                fields.forEach { (k, v) -> put(k, v) }
            },
        )
    }

    private fun publisher(
        wire: Wire,
        hub: LiveHub,
        spoken: MutableList<String> = mutableListOf(),
        toolBox: (ToolLive) -> String = { "" },
        verbosity: VerbositySetting = VolatileVerbosity(),
        cooldown: Cooldown = Cooldown(readFile = { null }),
        finalAnswer: suspend (String) -> String? = { null },
        clock: () -> Long = { TICK_MS },
        // NOT last: the existing tests' trailing lambda binds resolveTitle
        onSeal: suspend (SealFacts) -> Unit = {},
        resolveTitle: suspend (String) -> String? = { null },
    ): LivePublisher =
        LivePublisher(
            hub = hub,
            telegram = LiveTelegram(
                api = TgApi({ cfg }, engineFactory = wire.factory),
                cooldown = cooldown,
            ),
            config = { cfg },
            channelAlive = { true },
            speak = { text, _ -> spoken.add(text); 777L },
            toolBox = toolBox,
            finalAnswer = finalAnswer,
            clock = clock,
            verbosity = verbosity,
            resolveTitle = resolveTitle,
            onSeal = onSeal,
        )

    @Test
    fun theBirthCarriesTheRealTitleResolvedOnce() = runTest {
        val taskId = "sess_abcd644150daf013"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_100, "content" to JsonPrimitive("pensando")), nowMs = 1_100)
        val wire = Wire()
        var calls = 0
        val publisher = publisher(wire, hub) { _ ->
            calls += 1
            "arreglá el login"
        }
        publisher.tick()
        assertEquals(1, calls) // exactly one history read
        val birth = wire.bodies.first { it.second == "sendMessage" }.first
        assertTrue("«arreglá el login»" in birth, "the birth wears the REAL title, not the sid tail")
        assertTrue("«644150daf013»" !in birth) // the tail is not the head
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_200, "content" to JsonPrimitive("más")), nowMs = 1_200)
        publisher.tick() // a new event → the card re-draws; the resolution is ONE-SHOT per turn
        assertEquals(1, calls)
        val redraw = wire.bodies.last { it.second == "sendMessage" }.first
        assertTrue("arreglá el login" in redraw, "the re-drawn card carries the honest name too")
    }

    @Test
    fun theFallbackStaysWhenTheHistoryKnowsNothing() = runTest {
        val taskId = "sess_abcd644150daf013"
        val hub = LiveHub()
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_000, "content" to JsonPrimitive("pensando")), nowMs = 1_000)
        val wire = Wire()
        val publisher = publisher(wire, hub) { _ -> null }
        publisher.tick()
        val birth = wire.bodies.first { it.second == "sendMessage" }.first
        assertTrue("644150daf013" in birth) // the sid tail — nothing invented
    }

    @Test
    fun anIdleTurnDoesNotBurnTheOneShotAttempt() = runTest {
        val taskId = "sess_abcd644150daf013"
        val hub = LiveHub()
        hub.apply(frame(taskId, "usage_update", at = 1_000), nowMs = 1_000) // Idle — no card yet
        val wire = Wire()
        var calls = 0
        val publisher = publisher(wire, hub) { _ ->
            calls += 1
            "arreglá el login"
        }
        publisher.tick()
        assertEquals(0, calls) // nothing born, nothing burned
        assertTrue(wire.bodies.none { it.second == "sendMessage" })
        // the turn actually starts: the attempt fires exactly at the birth
        hub.apply(frame(taskId, "agent_thought_chunk", at = 2_000, "content" to JsonPrimitive("ya")), nowMs = 2_000)
        publisher.tick()
        assertEquals(1, calls)
        val birth = wire.bodies.first { it.second == "sendMessage" }.first
        assertTrue("«arreglá el login»" in birth)
    }

    @Test
    fun retitleOnlyTouchesTheWatchedTask() = runTest {
        val hub = LiveHub()
        hub.apply(frame("task-9", "agent_thought_chunk", at = 1_000, "content" to JsonPrimitive("x")), nowMs = 1_000)
        hub.retitle("another-task", "otra") // a foreign task's retitle is ignored
        assertEquals("task-9".takeLast(12), hub.peek()?.title)
        hub.retitle("task-9", "arreglá el login")
        assertEquals("arreglá el login", hub.peek()?.title)
    }

    // ---- the transcript feed -------------------------------------------------

    @Test
    fun aSealedBlockBecomesItsOwnCollapsedMessageAndTheCardIsRebornLast() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        var now = TICK_MS
        val publisher = publisher(wire, hub, spoken, clock = { now })
        publisher.tick() // the birth — feedSeen snapshots here
        val afterBirth = wire.bodies.size
        val blockText = "p".repeat(3000)
        hub.apply(
            frame(taskId, "agent_thought_chunk", at = 1_100, "content" to JsonPrimitive(blockText)),
            nowMs = 1_100,
        )
        now += 31_000L // the swap's re-birth is a SECOND birth: the spacing applies
        publisher.tick() // the block sealed → the COLLAPSED message speaks, the card swaps
        // THE COLLAPSE (2/11): the header + the affordance — the text never
        // rides the chat until the tap opens it
        val collapsed = spoken.firstOrNull { "CoT 1" in it }
        assertTrue(collapsed != null, "the block's collapsed message spoke")
        assertTrue("tocar para leer" in collapsed!!)
        assertTrue(blockText !in collapsed, "the whole-block send is GONE")
        // the swap's order: the card dies, the feed speaks (the speak lane),
        // the card is REBORN — deleteMessage then the birth sendMessage
        val methods = wire.bodies.drop(afterBirth).map { it.second }
        assertEquals("deleteMessage", methods.first())
        assertEquals("sendMessage", methods[1])
        val reborn = wire.bodies.last { it.second == "sendMessage" }.first
        assertTrue("⏳ <b>Working" in reborn, "the re-born card IS the process card")
        assertTrue("📖 CoT: 1 bloque plegado" in reborn, "the card carries the CoT row (count only)")
    }

    @Test
    fun theTapExpandsTheBlockInPlaceAndFoldsItBack() = runTest {
        // THE OWNER'S REFINEMENT, verbatim (2/11): «el lugar para que
        // aparezca el texto ES EN EL BOTÓN del CoT» — the tap edits the
        // collapsed message with the block WHOLE; another tap folds it
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        val publisher = publisher(wire, hub, spoken)
        publisher.tick() // the birth
        val blockText = "el pensamiento sellado completo"
        hub.apply(
            frame(taskId, "agent_thought_chunk", at = 1_100, "content" to JsonPrimitive(blockText.repeat(120))),
            nowMs = 1_100,
        )
        publisher.tick() // the collapsed message speaks + the registry records it
        spoken.clear()
        // THE TAP: the expansion is an EDIT on the block's own message —
        // no new message, the chat's order untouched
        assertEquals(null, publisher.toggle(1), "a working tap is silent — the edit IS the feedback")
        val expanded = wire.bodies.last { it.second == "editMessageText" }.first
        assertTrue("el pensamiento sellado completo" in expanded, "the block WHOLE, in place")
        assertTrue("<blockquote>" in expanded)
        assertTrue("🙈 plegar" in expanded, "the button offers the fold back")
        // THE SECOND TAP folds it back to the one-line header
        assertEquals(null, publisher.toggle(1))
        val folded = wire.bodies.last { it.second == "editMessageText" }.first
        assertTrue("tocar para leer" in folded)
        assertTrue("el pensamiento sellado completo" !in folded, "the text goes back behind the tap")
        // an unknown id answers the honest miss
        assertEquals("🤷 Ese CoT ya no está en el registro — el botón quedó de otro turno.", publisher.toggle(99))
    }

    @Test
    fun aThrottledTapFlipsNothing() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        var nowSec = 2.0
        val cooldown = Cooldown(now = { nowSec }, readFile = { null })
        val publisher = publisher(wire, hub, spoken, cooldown = cooldown)
        publisher.tick() // the birth
        hub.apply(
            frame(taskId, "agent_thought_chunk", at = 1_100, "content" to JsonPrimitive("q".repeat(3000))),
            nowMs = 1_100,
        )
        publisher.tick() // the collapsed message speaks, the registry records it
        cooldown.extend(raSeconds = 60) // Telegram named the window
        val edits = wire.bodies.count { it.second == "editMessageText" }
        assertEquals(null, publisher.toggle(1), "the tap answers no miss — it just couldn't edit")
        assertEquals(edits, wire.bodies.count { it.second == "editMessageText" }, "a throttled tap edits NOTHING")
        // the state did NOT flip: once the window lifts the tap opens it
        nowSec = 5_000.0
        assertEquals(null, publisher.toggle(1))
        assertTrue("q".repeat(50) in wire.bodies.last { it.second == "editMessageText" }.first)
    }

    @Test
    fun everyStateChangeRedrawsTheCardLastNeverAnEdit() = runTest {
        // THE CARD IS THE PROCESS (the owner, 2/11: «la quiero debajo de
        // todo SIEMPRE»): a changed render DELETES and RE-BIRTHS the card
        // as the last message — an in-place edit would stay buried under
        // whatever spoke after it
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        var now = TICK_MS
        val publisher = publisher(wire, hub, clock = { now })
        publisher.tick() // the birth
        val afterBirth = wire.bodies.size
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_100, "content" to JsonPrimitive("más")), nowMs = 1_100)
        now += 31_000L // the redraw is a SECOND birth: the spacing gate applies
        publisher.tick()
        val methods = wire.bodies.drop(afterBirth).map { it.second }
        assertTrue("deleteMessage" in methods, "the old card dies")
        assertTrue(methods.last() == "sendMessage", "the card is REBORN last")
        assertTrue("editMessageText" !in methods, "never an edit — an edit cannot move the card")
        // a quiet tick (no events, fixed clock): the render did not change —
        // nothing moves (the change law keeps the 429 budget alive)
        val before = wire.bodies.size
        publisher.tick()
        assertEquals(before, wire.bodies.size, "no change, no redraw")
    }

    @Test
    fun aThrottledWindowKeepsTheCardWhereItIsStaleButPresent() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        var nowSec = 2.0
        var clockMs = TICK_MS
        val cooldown = Cooldown(now = { nowSec }, readFile = { null })
        val publisher = publisher(wire, hub, cooldown = cooldown, clock = { clockMs })
        publisher.tick() // the birth
        val afterBirth = wire.bodies.size
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_100, "content" to JsonPrimitive("más")), nowMs = 1_100)
        cooldown.extend(raSeconds = 60) // Telegram named the window
        publisher.tick()
        assertTrue(
            wire.bodies.drop(afterBirth).none { it.second == "deleteMessage" },
            "no redraw under throttle — the card is never left deleted",
        )
        // the window lifts: the card re-draws with the CURRENT state
        nowSec = 5_000.0
        clockMs += 31_000L // the redraw is a SECOND birth: the spacing gate applies
        publisher.tick()
        val methods = wire.bodies.drop(afterBirth).map { it.second }
        assertTrue("deleteMessage" in methods && methods.last() == "sendMessage")
    }

    @Test
    fun verboseAnnouncesTheToolOpen() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        val publisher = publisher(wire, hub, spoken)
        publisher.tick() // the birth — feedSeen snapshots here
        spoken.clear()
        hub.apply(
            frame(
                taskId,
                "tool_call_update",
                at = 1_500,
                "status" to JsonPrimitive("pending"),
                "toolId" to JsonPrimitive("t1"),
                "toolName" to JsonPrimitive("Bash"),
                "input" to buildJsonObject { put("command", "git status") },
            ),
            nowMs = 1_500,
        )
        publisher.tick()
        assertTrue(spoken.any { "▶️" in it && "Bash" in it }, "VERBOSE: the open one-liner rides")
    }

    @Test
    fun normalKeepsTheOpensQuietButSpeaksTheClose() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        var now = TICK_MS
        val publisher = publisher(
            wire,
            hub,
            spoken,
            toolBox = { "BOX(${it.name})" },
            verbosity = VolatileVerbosity().apply { set(FeedVerbosity.NORMAL) },
            clock = { now },
        )
        publisher.tick() // the birth — feedSeen snapshots here
        hub.apply(
            frame(
                taskId,
                "tool_call_update",
                at = 1_500,
                "status" to JsonPrimitive("pending"),
                "toolId" to JsonPrimitive("t1"),
                "toolName" to JsonPrimitive("Bash"),
                "input" to buildJsonObject { put("command", "git status") },
            ),
            nowMs = 1_500,
        )
        publisher.tick()
        assertTrue(spoken.none { "▶️" in it }, "NORMAL: no open one-liners")
        spoken.clear()
        hub.apply(
            frame(
                taskId,
                "tool_call_update",
                at = 1_600,
                "status" to JsonPrimitive("completed"),
                "toolId" to JsonPrimitive("t1"),
                "content" to JsonPrimitive("on branch master"),
            ),
            nowMs = 1_600,
        )
        now += 31_000L // the close's card swap is a SECOND birth: the spacing applies
        publisher.tick()
        assertTrue(
            spoken.any { "⚡ <b>Bash</b>" in it && "tocar para ver" in it },
            "NORMAL: the close collapses behind its button (the owner's 3/11)",
        )
    }

    @Test
    fun summaryKeepsTheFeedSilentButTheCardStillRenders() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        var now = TICK_MS
        val publisher = publisher(
            wire,
            hub,
            spoken,
            verbosity = VolatileVerbosity().apply { set(FeedVerbosity.SUMMARY) },
            clock = { now },
        )
        publisher.tick() // birth
        val afterBirth = wire.bodies.size
        hub.apply(
            frame(taskId, "agent_thought_chunk", at = 1_100, "content" to JsonPrimitive("p".repeat(3000))),
            nowMs = 1_100,
        )
        hub.apply(
            frame(
                taskId,
                "tool_call_update",
                at = 1_200,
                "status" to JsonPrimitive("completed"),
                "toolId" to JsonPrimitive("t1"),
                "toolName" to JsonPrimitive("Bash"),
                "input" to buildJsonObject { put("command", "git status") },
                "content" to JsonPrimitive("out"),
            ),
            nowMs = 1_200,
        )
        now += 31_000L // the redraw is a SECOND birth: the spacing gate applies
        publisher.tick()
        assertEquals(emptyList(), spoken.toList(), "SUMMARY: the feed is silent — the DATA obeys the level")
        val methods = wire.bodies.drop(afterBirth).map { it.second }
        assertTrue(
            "deleteMessage" in methods && methods.last() == "sendMessage",
            "the card STILL re-draws last — the process is never hidden",
        )
    }

    @Test
    fun aCooldownWindowIsTheFeedBackPressureNothingLost() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        var nowSec = 2.0
        val cooldown = Cooldown(now = { nowSec }, readFile = { null })
        val publisher = publisher(wire, hub, spoken, cooldown = cooldown)
        publisher.tick() // birth — feedSeen snapshots here
        spoken.clear()
        hub.apply(
            frame(taskId, "agent_thought_chunk", at = 1_100, "content" to JsonPrimitive("q".repeat(3000))),
            nowMs = 1_100,
        )
        cooldown.extend(raSeconds = 60) // Telegram named the window
        publisher.tick()
        assertTrue(spoken.isEmpty(), "the swap skips itself whole while the window lasts")
        assertTrue(wire.bodies.none { it.second == "deleteMessage" }, "no delete under throttle")
        nowSec = 5_000.0 // the window lifted
        publisher.tick()
        assertTrue(spoken.any { "<b>CoT 1</b>" in it }, "the same diff speaks when the window lifts — nothing lost")
    }

    @Test
    fun theSealSpeaksTheRemainderTheTailThenTheRecordAndTheAnswer() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_100, "content" to JsonPrimitive("pensando")), nowMs = 1_100)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        val publisher = publisher(
            wire,
            hub,
            spoken,
            toolBox = { "BOX(${it.name})" },
            finalAnswer = { "LA RESPUESTA" },
        )
        publisher.tick() // birth — feedSeen snapshots (no tools yet)
        spoken.clear()
        // the arrival + the close + the complete fold BETWEEN ticks: the
        // seal's remainder (the reducer needs the lane to exist first)
        hub.apply(
            frame(
                taskId,
                "tool_call_update",
                at = 1_400,
                "status" to JsonPrimitive("pending"),
                "toolId" to JsonPrimitive("t1"),
                "toolName" to JsonPrimitive("Bash"),
                "input" to buildJsonObject { put("command", "git status") },
            ),
            nowMs = 1_400,
        )
        hub.apply(
            frame(
                taskId,
                "tool_call_update",
                at = 1_500,
                "status" to JsonPrimitive("completed"),
                "toolId" to JsonPrimitive("t1"),
                "toolName" to JsonPrimitive("Bash"),
                "input" to buildJsonObject { put("command", "git status") },
                "content" to JsonPrimitive("on branch master"),
            ),
            nowMs = 1_500,
        )
        hub.apply(frame(taskId, "task_complete", at = 9_999, "stopReason" to JsonPrimitive("end_turn")), nowMs = 9_999)
        publisher.tick() // the seal tick
        // the remainder first (the unseen close + the unsealed thought tail
        // COLLAPSED — the tap opens it), then the record, then the answer LAST
        assertTrue(
            spoken.any { "⚡ <b>Bash</b>" in it && "tocar para ver" in it },
            "the unseen close speaks (collapsed) at the seal",
        )
        val tail = spoken.firstOrNull { "sin sellar" in it }
        assertTrue(tail != null, "the unsealed tail loses nothing — it travels collapsed")
        assertTrue("tocar para leer" in tail!!)
        assertTrue("pensando" !in tail, "the tail's text hides behind the tap too")
        assertTrue("end_turn" in spoken[spoken.size - 2], "the record is second-to-last")
        assertEquals("LA RESPUESTA", spoken.last(), "the answer is the last word")
        assertEquals("deleteMessage", wire.bodies.last { it.second != "editMessageText" }.second)
    }

    @Test
    fun theSealTapReceivesTheSealsOwnFactsExactlyOnce() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("pensando")), nowMs = 1_050)
        // the lane arrives pending, then closes: the reducer counts the close
        hub.apply(
            frame(
                taskId,
                "tool_call_update",
                at = 1_100,
                "status" to JsonPrimitive("pending"),
                "toolId" to JsonPrimitive("t1"),
                "toolName" to JsonPrimitive("Bash"),
                "input" to buildJsonObject { put("command", "git status") },
            ),
            nowMs = 1_100,
        )
        hub.apply(
            frame(
                taskId,
                "tool_call_update",
                at = 1_150,
                "status" to JsonPrimitive("completed"),
                "toolId" to JsonPrimitive("t1"),
                "toolName" to JsonPrimitive("Bash"),
                "input" to buildJsonObject { put("command", "git status") },
            ),
            nowMs = 1_150,
        )
        val received = mutableListOf<SealFacts>()
        val publisher = publisher(
            Wire(),
            hub,
            finalAnswer = { "LA RESPUESTA" },
            onSeal = { received.add(it) },
        )
        publisher.tick() // birth
        hub.apply(frame(taskId, "task_complete", at = 9_999, "stopReason" to JsonPrimitive("end_turn")), nowMs = 9_999)
        publisher.tick() // the seal tick
        assertEquals(1, received.size, "one seal, one tap")
        val facts = received[0]
        assertEquals(taskId, facts.taskId)
        assertEquals("end_turn", facts.stopReason)
        assertEquals(1_000L, facts.startedAtMs, "the turn's REAL start rides along")
        assertEquals(1, facts.doneTools)
        assertEquals("LA RESPUESTA", facts.answer, "the answer the seal already computed — no re-query")
        // a broken tap never breaks the seal: the tick survives and the
        // seal's own cleanup ran (the turn is spent, the hub is clear)
        val hub2 = LiveHub()
        hub2.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub2.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("pensando")), nowMs = 1_050)
        val publisher2 = publisher(
            Wire(),
            hub2,
            finalAnswer = { "R" },
            onSeal = { error("the wire is down") },
        )
        publisher2.tick()
        hub2.apply(frame(taskId, "task_complete", at = 9_999, "stopReason" to JsonPrimitive("end_turn")), nowMs = 9_999)
        publisher2.tick() // must NOT throw
        assertEquals(null, hub2.peek(), "the seal's cleanup ran past the broken tap")
    }

    @Test
    fun theSummarySealEditsTheCardInPlaceWithTheAnswerOnly() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(
            frame(
                taskId,
                "tool_call_update",
                at = 1_100,
                "status" to JsonPrimitive("completed"),
                "toolId" to JsonPrimitive("t1"),
                "toolName" to JsonPrimitive("Bash"),
                "input" to buildJsonObject { put("command", "git status") },
                "content" to JsonPrimitive("out"),
            ),
            nowMs = 1_100,
        )
        val wire = Wire()
        val spoken = mutableListOf<String>()
        val publisher = publisher(
            wire,
            hub,
            spoken,
            verbosity = VolatileVerbosity().apply { set(FeedVerbosity.SUMMARY) },
            finalAnswer = { "LA RESPUESTA" },
        )
        publisher.tick() // birth
        spoken.clear()
        hub.apply(frame(taskId, "task_complete", at = 9_999, "stopReason" to JsonPrimitive("end_turn")), nowMs = 9_999)
        publisher.tick()
        assertEquals(listOf("LA RESPUESTA"), spoken.toList(), "SUMMARY: the answer is the only word")
        assertTrue(wire.bodies.none { it.second == "deleteMessage" }, "no swap at the SUMMARY seal")
        assertTrue("end_turn" in wire.bodies.last { it.second == "editMessageText" }.first, "the record edits in place")
    }

    private companion object {
        const val TICK_MS = 2_000L
    }

    @Test
    fun theKeepaliveAdvancesTheSeqAndNothingElse() = runTest {
        val hub = LiveHub()
        hub.apply(
            ServerFrame.StreamEvent("t1", 77, 1_000, buildJsonObject { put("type", "agent_thought_chunk"); put("delta", "pensando") }),
            nowMs = 1_000,
        )
        val before = hub.peek()!!
        hub.keepalive("t1", 79)
        val after = hub.peek()!!
        assertEquals(79L, after.lastSeq)
        assertEquals(before.cotPending, after.cotPending) // the accumulation SURVIVES the heartbeat
        assertEquals(before.lastEventAtMs, after.lastEventAtMs) // a quiet model stays honestly quiet
        hub.keepalive("other", 100) // a foreign task's heartbeat moves nothing
        assertEquals(79L, hub.peek()!!.lastSeq)
        hub.keepalive("t1", 5) // an old seq never rewinds the counter
        assertEquals(79L, hub.peek()!!.lastSeq)
    }

    // ---- the 5s progress pings (v2 §4) ----------------------------------------

    @Test
    fun thePingSpeaksWhileTheCoTFlowsAndSkipsSilenceAndBash() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("p".repeat(3000))), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        var now = 6_000L
        val publisher = publisher(wire, hub, spoken, clock = { now })
        publisher.pingTick()
        assertTrue("1 - generando CoT 3k" in spoken, "the first ping of the action counts 1: $spoken")
        // new chars, one cadence later: the counter climbs (the number law)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 6_050, "content" to JsonPrimitive("q".repeat(1500))), nowMs = 6_050)
        now = 11_000L
        publisher.pingTick()
        assertTrue("2 - generando CoT 4,5k" in spoken, "the counter climbs with new chars: $spoken")
        // NO new chars since the last ping: silence is data — skip
        now = 16_000L
        publisher.pingTick()
        assertEquals(2, spoken.size, "no new chars, no ping")
        // a bash lane streams NO pings — the card already carries the command
        hub.apply(
            frame(
                taskId, "tool_call", at = 16_100,
                "toolId" to JsonPrimitive("tb"), "toolName" to JsonPrimitive("Bash"),
                "input" to buildJsonObject { put("command", "npm test") },
            ),
            nowMs = 16_100,
        )
        now = 21_000L
        publisher.pingTick()
        assertEquals(2, spoken.size, "bash streams no pings")
    }

    @Test
    fun thePingCounterResetsWhenTheActionChanges() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("c".repeat(2500))), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        var now = 6_000L
        val publisher = publisher(wire, hub, spoken, clock = { now })
        publisher.pingTick()
        hub.apply(frame(taskId, "agent_thought_chunk", at = 6_050, "content" to JsonPrimitive("d".repeat(2500))), nowMs = 6_050)
        now = 11_000L
        publisher.pingTick()
        assertTrue("2 - generando CoT 5k" in spoken, "$spoken")
        // an Edit with a LIVE diff opens: the identity changes — count from 1
        hub.apply(
            frame(
                taskId, "tool_call_update", at = 11_100, "status" to JsonPrimitive("pending"),
                "toolId" to JsonPrimitive("te"), "toolName" to JsonPrimitive("Edit"),
                "input" to buildJsonObject {
                    put("file_path", "/w/report.txt"); put("old_string", "a\nb\nc"); put("new_string", "a\nX\nc\nd")
                },
            ),
            nowMs = 11_100,
        )
        now = 16_200L
        publisher.pingTick()
        assertTrue("1 - editando archivo report.txt +3 −2" in spoken, "the new action's pings count from 1: $spoken")
        // the counts did not change: skip
        now = 21_300L
        publisher.pingTick()
        assertEquals(3, spoken.size, "unchanged counts, no ping")
        // the counts move: the counter climbs within the SAME action
        hub.apply(
            frame(
                taskId, "tool_call_update", at = 21_400, "status" to JsonPrimitive("pending"),
                "toolId" to JsonPrimitive("te"), "toolName" to JsonPrimitive("Edit"),
                "input" to buildJsonObject {
                    put("file_path", "/w/report.txt")
                    put("old_string", "a\nb\nc\nd\ne")
                    put("new_string", "a\nX\nc\nd\ne\nf")
                },
            ),
            nowMs = 21_400,
        )
        now = 26_500L
        publisher.pingTick()
        assertTrue("2 - editando archivo report.txt +5 −4" in spoken, "$spoken")
    }

    @Test
    fun thePingDropsUnderCooldownPressure() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("p".repeat(3000))), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        var nowSec = 2.0
        val cooldown = Cooldown(now = { nowSec }, readFile = { null })
        val publisher = publisher(wire, hub, spoken, cooldown = cooldown, clock = { 6_000L })
        cooldown.extend(raSeconds = 60) // Telegram named the window
        publisher.pingTick()
        assertTrue(spoken.isEmpty(), "a window would DELAY the ping — the ping DROPS (never queues, never bursts)")
        nowSec = 5_000.0 // the window lifted: the next cadence speaks it, counting from 1 still
        publisher.pingTick()
        assertTrue("1 - generando CoT 3k" in spoken, "$spoken")
    }

    @Test
    fun thePingSkipsAFrozenOrPilledTurnAndDiesWithIt() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("p".repeat(3000))), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        // 199s of silence: the frozen clock's territory — no pings
        val publisher = publisher(wire, hub, spoken, clock = { 200_000L })
        publisher.pingTick()
        assertTrue(spoken.isEmpty(), "a frozen turn pings nothing")
        // the fork's retry pill: not a live turn either
        hub.apply(
            frame(
                taskId, "session_info_update", at = 200_100,
                "apiRetry" to buildJsonObject { put("attempt", 1); put("reason", "rate_limited") },
            ),
            nowMs = 200_100,
        )
        publisher.pingTick()
        assertTrue(spoken.isEmpty(), "a pilled turn pings nothing")
        // the turn completes: the counter dies with it, nothing more speaks
        hub.apply(frame(taskId, "agent_thought_chunk", at = 200_200, "content" to JsonPrimitive("x")), nowMs = 200_200)
        hub.apply(frame(taskId, "task_complete", at = 200_300, "stopReason" to JsonPrimitive("end_turn")), nowMs = 200_300)
        publisher.pingTick()
        assertTrue(spoken.isEmpty(), "a Done turn pings nothing")
    }

    // ---- the pager's bash fence + the long-command feed law (v2 §3/§5) --------

    @Test
    fun theShellPagerOpensItsPagesInsideTheBashFence() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        var now = TICK_MS
        val publisher = publisher(wire, hub, spoken, toolBox = { "BOX(${it.name})" }, clock = { now })
        publisher.tick() // birth
        hub.apply(
            frame(
                taskId, "tool_call", at = 1_500,
                "toolId" to JsonPrimitive("tb"), "toolName" to JsonPrimitive("Bash"),
                "input" to buildJsonObject { put("command", "ls") },
            ),
            nowMs = 1_500,
        )
        hub.apply(
            frame(
                taskId, "tool_call_update", at = 1_600, "status" to JsonPrimitive("completed"),
                "toolId" to JsonPrimitive("tb"), "content" to JsonPrimitive("out one\nout two"),
            ),
            nowMs = 1_600,
        )
        now += 31_000L // the close's card swap is a SECOND birth: the spacing applies
        publisher.tick() // the close collapses behind its pager button
        val box = hub.peek()?.boxes?.single()
        assertEquals("bash", box?.language, "the fold carries the fence language for a shell's box")
        // THE TAP: the numbered page opens inside the bash code block (the
        // wire body is JSON — the fence's quotes ride escaped)
        assertEquals(null, publisher.open(box!!.boxId, 0))
        val opened = wire.bodies.last { it.second == "editMessageText" }.first
        assertTrue("language-bash" in opened, "the shell's page rides the bash fence: $opened")
        assertTrue("<pre><code" in opened && "code></pre>" in opened, opened)
        assertTrue("1│ out one" in opened, "the numbering law unchanged: $opened")
    }

    @Test
    fun aLongCommandTravelsTheFeedCompleteNeverElided() = runTest {
        // the card showed 1800 cp + «✂️ … el comando completo va en el feed» —
        // the feed keeps the promise: the command WHOLE, chunked only when a
        // single message would exceed Telegram's 4096, never cut mid-command
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        var now = TICK_MS
        val publisher = publisher(wire, hub, spoken, clock = { now })
        publisher.tick() // birth — feedSeen snapshots
        hub.apply(
            frame(
                taskId, "tool_call", at = 1_500,
                "toolId" to JsonPrimitive("tbig"), "toolName" to JsonPrimitive("Bash"),
                "input" to buildJsonObject { put("command", "y".repeat(4000)) },
            ),
            nowMs = 1_500,
        )
        publisher.tick() // the open drains: the long command speaks, the card swaps
        val chunks = spoken.filter { "language-bash" in it }
        assertEquals(2, chunks.size, "4000 cp over the 3000-chunk: two messages, the whole command: $chunks")
        assertTrue("y".repeat(3000) in chunks[0] && "(1/2)" in chunks[0], chunks[0])
        assertTrue("y".repeat(1000) in chunks[1] && "(2/2)" in chunks[1], chunks[1])
    }

    @Test
    fun theLongCommandIsDataEvenUnderNormalVerbosity() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        var now = TICK_MS
        val publisher = publisher(
            wire,
            hub,
            spoken,
            verbosity = VolatileVerbosity().apply { set(FeedVerbosity.NORMAL) },
            clock = { now },
        )
        publisher.tick() // birth — feedSeen snapshots
        hub.apply(
            frame(
                taskId, "tool_call", at = 1_500,
                "toolId" to JsonPrimitive("tbig"), "toolName" to JsonPrimitive("Bash"),
                "input" to buildJsonObject { put("command", "y".repeat(4000)) },
            ),
            nowMs = 1_500,
        )
        publisher.tick()
        assertTrue(
            spoken.any { "language-bash" in it && "(1/2)" in it },
            "the card promised «va en el feed» — NORMAL speaks the data too: $spoken",
        )
        assertTrue(spoken.none { "▶️" in it }, "but the open one-liner stays quiet under NORMAL")
    }
}
