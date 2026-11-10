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
import kotlinx.serialization.json.JsonObject
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
        resubscribe: suspend (String) -> Unit = {},
        richVoice: Boolean = true,
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
            resubscribe = resubscribe,
            richVoice = richVoice,
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
    fun anAttachToAnIdleSessionBirthsItsHonestCard() = runTest {
        // THE 5/11 BLANK CARD (the owner: «silencio de radio total»): the sa:
        // attach to a STORED child whose turn was over seeded an Idle state
        // the publisher refused to birth — the phone showed NOTHING. The
        // await law: the attach births the honest card, the watchdog never
        // barks at it (there is no bus to revive), and the first real frame
        // of the awaited turn takes the card over.
        val child = "sess_subagent_agent_quiet"
        val hub = LiveHub()
        var now = TICK_MS
        val wire = Wire()
        var resubscribes = 0
        val publisher = publisher(wire, hub, clock = { now }, resubscribe = { resubscribes += 1 })
        hub.watchTaskId(child, now)
        publisher.tick()
        val birth = wire.bodies.first { it.second == "sendMessage" }.first
        assertTrue("👁 <b>«_agent_quiet»</b> attachado" in birth, "the honest idle card BORN: $birth")
        assertTrue("ningún turno corriendo" in birth)
        // the watchdog stays silent: an awaited idle session has no dead bus
        now += 120_000L
        publisher.tick()
        assertEquals(0, resubscribes, "no resubscribe bark on an awaited idle card")
        // the awaited turn arrives: the card speaks its REAL phase
        hub.apply(frame(child, "task_run_started", at = now, "startedAt" to JsonPrimitive(now.toLong())), nowMs = now)
        hub.apply(frame(child, "agent_thought_chunk", at = now + 100, "content" to JsonPrimitive("p".repeat(2500))), nowMs = now + 100)
        now += 3_000L
        publisher.tick()
        // the card's update may ride the EDIT leg (the 5/11 reclaim window)
        val live = wire.bodies.last { it.second in setOf("sendMessage", "editMessageText") }.first
        assertTrue("Procesando" in live, "the real turn took the card over: $live")
        assertTrue("ningún turno corriendo" !in live)
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
        publisher.tick() // the block sealed → the NATIVE expandable quote speaks, the card swaps
        // THE NATIVE THINKING (5/11): the whole block travels INSIDE the
        // rich message — the client collapses/expands it, no custom button
        val rich = wire.bodies.firstOrNull { it.second == "sendRichMessage" }
        assertTrue(rich != null, "the block's violet message spoke")
        assertTrue("expandable_blockquote" in rich!!.first)
        // the swap's order: the card dies, the voice speaks, the card is
        // REBORN — delete → sendRichMessage → birth sendMessage
        val methods = wire.bodies.drop(afterBirth).map { it.second }
        assertEquals("sendRichMessage", methods.first(), "the voice speaks first")
        assertEquals("deleteMessage", methods[1], "then the stale card dies")
        assertEquals("sendMessage", methods[2], "the card is reborn LAST")
        val reborn = wire.bodies.last { it.second == "sendMessage" }.first
        assertTrue("⏳ <b>Procesando..." in reborn, "the re-born card IS the process card")
        assertTrue("📖 CoT: 1 bloque plegado" in reborn, "the card carries the CoT row (count only)")
    }

    @Test
    fun theTapExpandsTheBlockInPlaceAndFoldsItBack() = runTest {
        // THE LEGACY TOGGLE (richVoice=false — the knob's world): «el lugar
        // para que aparezca el texto ES EN EL BOTÓN del CoT» — the tap edits
        // the collapsed message with the block WHOLE; another tap folds it.
        // The RICH world's expand is native (theCoTSeals... pins that).
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        val publisher = publisher(wire, hub, spoken, richVoice = false)
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
        val publisher = publisher(wire, hub, spoken, cooldown = cooldown, richVoice = false)
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
    fun theCardEditsInPlaceWithinTheWindowAndReclaimsTheBottomAfterIt() = runTest {
        // THE 5/11 LAW («no estoy viendo el cartel»): a changed render EDITS
        // the card in place (the seconds tick without a new message); the
        // delete+re-birth that keeps it «debajo de todo» fires at most once
        // per spacing window — the 4/11 flicker (card missing up to 30s) died
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        var now = TICK_MS
        val publisher = publisher(wire, hub, clock = { now })
        publisher.tick() // the birth
        val afterBirth = wire.bodies.size
        // WITHIN the window: the render changed (the seconds ticked) — the
        // card EDITS itself, no delete, no new message, no flood
        now += 5_000L
        publisher.tick()
        val inWindow = wire.bodies.drop(afterBirth).map { it.second }
        assertEquals(listOf("editMessageText"), inWindow, "the seconds tick by EDITING the same message")
        val edited = wire.bodies.last { it.second == "editMessageText" }.first
        assertTrue("⏳ <b>Procesando..." in edited, "the card speaks the owner's wording: $edited")
        // the window lifts: the next change RECLAIMS the bottom — delete +
        // re-birth, once per window at most
        now += 31_000L
        publisher.tick()
        val reclaim = wire.bodies.drop(afterBirth + inWindow.size).map { it.second }
        assertTrue("deleteMessage" in reclaim, "the old card dies to reclaim the bottom")
        assertTrue(reclaim.last() == "sendMessage", "the card is REBORN last")
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
        val publisher = publisher(wire, hub, spoken, cooldown = cooldown, richVoice = false)
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
            richVoice = false,
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
        // the remainder first (the unseen close + the thought tail — sealed
        // AT the tool's arrival by the thinking's boundary law, traveling
        // COLLAPSED), then the record, then the answer LAST
        assertTrue(
            spoken.any { "⚡ <b>Bash</b>" in it && "tocar para ver" in it },
            "the unseen close speaks (collapsed) at the seal",
        )
        val tail = spoken.firstOrNull { "CoT 1" in it }
        assertTrue(tail != null, "the tail sealed at the tool's arrival — it travels collapsed")
        assertTrue("generado" in tail!!, "a boundary-sealed tail earns the SEALED verdict")
        assertTrue("tocar para leer" in tail)
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
            richVoice = false,
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
        assertTrue("🧠 thinking 3k" in spoken, "the first ping of the action counts 1: $spoken")
        // new chars, one cadence later: the counter climbs (the number law)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 6_050, "content" to JsonPrimitive("q".repeat(1500))), nowMs = 6_050)
        now = 11_000L
        publisher.pingTick()
        assertTrue("🧠 thinking 4,5k" in spoken, "the counter climbs with new chars: $spoken")
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
        assertTrue("🧠 thinking 5k" in spoken, "$spoken")
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
        assertTrue("✏️ Editando archivo report.txt +3 −2" in spoken, "the new action's pings count from 1: $spoken")
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
        assertTrue("✏️ Editando archivo report.txt +5 −4" in spoken, "$spoken")
    }

    @Test
    fun theEditCloseSpeaksItsDiffWithTheFileDoor() = runTest {
        // THE FILE DOOR (the owner, 5/11): a write/edit close carries the
        // diff AND «📄 archivo completo» — the tap reads the file fresh
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        val markups = mutableListOf<JsonObject?>()
        var now = 2_000L
        val fileMenu = FileMenu(
            read = { "el archivo entero" },
            edit = { _, _, _ -> true },
            send = { text, markup ->
                spoken += text
                markups += markup
                99L
            },
        )
        val publisher = LivePublisher(
            hub = hub,
            telegram = LiveTelegram(
                api = TgApi({ cfg }, engineFactory = wire.factory),
                cooldown = Cooldown(readFile = { null }),
            ),
            config = { cfg },
            channelAlive = { true },
            speak = { text, markup ->
                spoken += text
                markups += markup
                777L
            },
            finalAnswer = { null },
            clock = { now },
            toolBox = { lane -> "✏️ ${lane.input?.str("file_path")} listo" },
            fileMenu = fileMenu,
        )
        publisher.tick() // the birth
        // the lane OPENS (pending) and then closes — a close without a lane is dropped by the fold
        hub.apply(
            frame(
                taskId, "tool_call_update", at = 2_050, "status" to JsonPrimitive("pending"),
                "toolId" to JsonPrimitive("te"), "toolName" to JsonPrimitive("Edit"),
                "input" to buildJsonObject {
                    put("file_path", "/w/Main.kt"); put("old_string", "a"); put("new_string", "b\nc")
                },
            ),
            nowMs = 2_050,
        )
        hub.apply(
            frame(
                taskId, "tool_call_update", at = 2_100, "status" to JsonPrimitive("completed"),
                "toolId" to JsonPrimitive("te"), "toolName" to JsonPrimitive("Edit"),
            ),
            nowMs = 2_100,
        )
        now += 31_000L // the feed speaks; the card reclaims on the same tick
        publisher.tick()
        val withDoor = spoken.zip(markups).any { (text, markup) ->
            "Main.kt" in text && "📄 archivo completo" in (markup?.toString() ?: "")
        }
        assertTrue(withDoor, "the edit close carries the file door: ${spoken.joinToString(" | ")}")
    }

    @Test
    fun theEditPingAccumulatesTheFilesRunningTotalAcrossLanes() = runTest {
        // THE OWNER'S LADDER (5/11): «+21 −12 → +55 −12 → +94 −16» is the SAME
        // file accumulating across its edits — every lane the turn touched
        // that file counts, sealed lanes included, not just the live rung
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        var now = 6_000L
        val publisher = publisher(wire, hub, spoken, clock = { now })
        fun editFrame(toolId: String, old: String, new: String, status: String = "pending") = frame(
            taskId, "tool_call_update", at = now + 100,
            "status" to JsonPrimitive(status),
            "toolId" to JsonPrimitive(toolId), "toolName" to JsonPrimitive("Edit"),
            "input" to buildJsonObject {
                put("file_path", "/w/report.txt"); put("old_string", old); put("new_string", new)
            },
        )
        // first edit: +3 −2 — the ladder's first rung
        hub.apply(editFrame("te1", "a\nb\nc", "a\nX\nc\nd"), nowMs = now + 100)
        publisher.pingTick()
        assertTrue("✏️ Editando archivo report.txt +3 −2" in spoken, "$spoken")
        // it seals; a SECOND edit on the same file opens with +2 −1: the
        // running total is the FILE's (+3−2 + +2−1 = +5 −3), not the rung's
        hub.apply(editFrame("te1", "a\nb\nc", "a\nX\nc\nd", status = "completed"), nowMs = now + 100)
        hub.apply(editFrame("te2", "x\ny", "x\nz\nw"), nowMs = now + 100)
        now += 5_100L
        publisher.pingTick()
        assertTrue("✏️ Editando archivo report.txt +5 −3" in spoken, "the ladder accumulates the file: $spoken")
    }

    @Test
    fun theVoiceSpeaksProseSegmentsAsRichMessages() = runTest {
        // THE VIOLET CHANNEL (5/11): the LLM's mid-turn prose — «El meter es
        // real…» — accumulates while streaming and at the tool boundary it
        // SPENDS: one rich pullquote message, in order, then the close
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        var now = 2_000L
        val publisher = publisher(wire, hub, spoken, clock = { now })
        publisher.tick() // the birth
        // the model TALKS (the prose streams), then opens a tool
        hub.apply(
            frame(taskId, "agent_message_chunk", at = 2_100, "content" to JsonPrimitive("El meter es real: 55.337 tokens.")),
            nowMs = 2_100,
        )
        hub.apply(
            frame(taskId, "tool_call", at = 2_200, "toolId" to JsonPrimitive("tb"), "toolName" to JsonPrimitive("Read"),
                "input" to buildJsonObject { put("file_path", "/w/a.kt") }),
            nowMs = 2_200,
        )
        now += 31_000L
        publisher.tick()
        val rich = wire.bodies.firstOrNull { it.second == "sendRichMessage" }
        assertTrue(rich != null, "the prose flew as a RICH message: methods=${wire.bodies.map { it.second }}")
        assertTrue("El meter es real" in rich.first, "the violet body: ${rich.first.take(400)}")
        assertTrue("\"pullquote\"" in rich.first, "the violet block type: ${rich.first.take(300)}")
        // a SECOND segment spends at the NEXT boundary: the ladder keeps order
        hub.apply(
            frame(taskId, "tool_call_update", at = now + 100, "status" to JsonPrimitive("completed"), "toolId" to JsonPrimitive("tb")),
            nowMs = now + 100,
        )
        hub.apply(
            frame(taskId, "agent_message_chunk", at = now + 200, "content" to JsonPrimitive("Segundo tramo.")),
            nowMs = now + 200,
        )
        hub.apply(frame(taskId, "task_complete", at = now + 300, "stopReason" to JsonPrimitive("end_turn")), nowMs = now + 300)
        now += 31_000L
        publisher.tick()
        val richCount = wire.bodies.count { it.second == "sendRichMessage" }
        assertTrue(richCount >= 2, "each segment its own violet message: $richCount")
        assertTrue(
            wire.bodies.any { it.second == "sendRichMessage" && "Segundo tramo." in it.first },
            "the second segment spoke its own violet message",
        )
    }

    @Test
    fun theSealSpeaksTheFinalAnswerViolet() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("p")), nowMs = 1_050)
        val wire = Wire()
        val publisher = publisher(wire, hub, finalAnswer = { "La respuesta final con `codigo`." })
        publisher.tick()
        hub.apply(frame(taskId, "task_complete", at = 1_200, "stopReason" to JsonPrimitive("end_turn")), nowMs = 1_200)
        publisher.tick()
        val answerRich = wire.bodies.lastOrNull { it.second == "sendRichMessage" && "respuesta final" in it.first }
        assertTrue(answerRich != null, "the final answer is a violet message: ${wire.bodies.map { it.second }}")
    }

    @Test
    fun theCoTSealsAsTheNativeExpandableQuote() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        var now = 2_000L
        val publisher = publisher(wire, hub, spoken, clock = { now })
        publisher.tick()
        hub.apply(
            frame(taskId, "agent_thought_chunk", at = 2_100, "content" to JsonPrimitive("p".repeat(3400))),
            nowMs = 2_100,
        )
        now += 31_000L
        publisher.tick()
        val thinking = wire.bodies.firstOrNull { it.second == "sendRichMessage" && "expandable_blockquote" in it.first }
        assertTrue(thinking != null, "the CoT is a NATIVE expandable quote: ${wire.bodies.map { it.second }}")
    }

    @Test
    fun richVoiceOffKeepsTheOldShapes() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_message_chunk", at = 1_100, "content" to JsonPrimitive("hablo")), nowMs = 1_100)
        hub.apply(
            frame(taskId, "tool_call", at = 1_200, "toolId" to JsonPrimitive("tb"), "toolName" to JsonPrimitive("Read"),
                "input" to buildJsonObject { put("file_path", "/w/a.kt") }),
            nowMs = 1_200,
        )
        val wire = Wire()
        val spoken = mutableListOf<String>()
        var now = 2_000L
        val publisher = publisher(wire, hub, spoken, clock = { now }, richVoice = false)
        publisher.tick()
        now += 31_000L
        publisher.tick()
        assertEquals(0, wire.bodies.count { it.second == "sendRichMessage" }, "the knob OFF: no rich sends")
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
        assertTrue("🧠 thinking 3k" in spoken, "$spoken")
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
