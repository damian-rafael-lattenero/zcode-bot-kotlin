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
import kotlin.test.assertNotNull
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
        finalTurn: suspend (String) -> TurnVoice? = { null },
        clock: () -> Long = { TICK_MS },
        resubscribe: suspend (String) -> Unit = {},
        richVoice: Boolean = true,
        pngShots: PngShots? = null,
        /** The CLASSIC throat's own capture (null = the [speak] double). */
        speakPlain: (suspend (String, JsonObject?) -> Long?)? = null,
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
            finalTurn = finalTurn,
            clock = clock,
            verbosity = verbosity,
            resubscribe = resubscribe,
            richVoice = richVoice,
            pngShots = pngShots,
            speakPlain = speakPlain ?: { text, _ -> spoken.add(text); 777L },
            resolveTitle = resolveTitle,
            onSeal = onSeal,
        )

    /** A one-step voice — the injected resolver's shorthand (the tests' common shape). */
    private fun voiceOf(text: String): TurnVoice = TurnVoice(listOf(TurnProse(text)))

    /**
     * A CARD/FEED body: the rich HTML envelope (Bot API 10.1) or the classic
     * text fallback. The voice's rich BLOCKS never count as a card.
     */
    private fun cardBodies(wire: Wire): List<Pair<String, String>> =
        wire.bodies.filter { it.second == "sendMessage" || (it.second == "sendRichMessage" && "\"html\"" in it.first) }

    /** The VOICE: rich blocks only (pullquote/pre/expandable). */
    private fun blockBodies(wire: Wire): List<Pair<String, String>> =
        wire.bodies.filter { it.second == "sendRichMessage" && "\"blocks\"" in it.first }

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
        val birth = cardBodies(wire).first().first
        assertTrue("«arreglá el login»" in birth, "the birth wears the REAL title, not the sid tail")
        assertTrue("«644150daf013»" !in birth) // the tail is not the head
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_200, "content" to JsonPrimitive("más")), nowMs = 1_200)
        publisher.tick() // a new event → the card re-draws; the resolution is ONE-SHOT per turn
        assertEquals(1, calls)
        val redraw = cardBodies(wire).last().first
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
        val birth = cardBodies(wire).first().first
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
        val birth = cardBodies(wire).first().first
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
    fun aSilentCorpseSealsStaleAndNeverRebirthsWhileSilent() = runTest {
        // THE ZOMBIE'S LESSON (22/11): the stream died without a completion —
        // the card froze forever and the reclaim re-birthed it in a loop.
        // Past the stale horizon the card seals ONCE (the honest 🧊) and the
        // birth gate keeps the corpse quiet; a resumed turn's first frame
        // re-opens the gate and the card is born again — self-healing.
        val taskId = "sess_abcd644150daf013"
        val hub = LiveHub()
        var now = TICK_MS
        val wire = Wire()
        val publisher = publisher(wire, hub, clock = { now })
        hub.apply(frame(taskId, "task_run_started", at = now), nowMs = now)
        hub.apply(frame(taskId, "agent_thought_chunk", at = now, "content" to JsonPrimitive("pensando")), nowMs = now)
        publisher.tick()
        fun cardBirths(): Int = cardBodies(wire).count { "<b>" in it.first }
        assertEquals(1, cardBirths(), "the live card is born")
        now += STALE_SEAL_MS + 10_000L
        publisher.tick() // the stale horizon trips
        val edit = wire.bodies.last { it.second == "editMessageText" }.first
        assertTrue("🧊" in edit && "sellado por inactividad" in edit, "the honest stale seal: $edit")
        publisher.tick() // later ticks: the corpse does NOT rebirth
        publisher.tick()
        assertEquals(1, cardBirths(), "no rebirth while the corpse stays silent")
        // the turn genuinely resumes: silence re-lights and the card is born again
        hub.apply(frame(taskId, "agent_thought_chunk", at = now, "content" to JsonPrimitive("volvió")), nowMs = now)
        publisher.tick()
        assertEquals(2, cardBirths(), "the resumed turn re-births its card")
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
        assertTrue(cardBodies(wire).isEmpty())
        // the turn actually starts: the attempt fires exactly at the birth
        hub.apply(frame(taskId, "agent_thought_chunk", at = 2_000, "content" to JsonPrimitive("ya")), nowMs = 2_000)
        publisher.tick()
        assertEquals(1, calls)
        val birth = cardBodies(wire).first().first
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
        // rich message — the client collapses/expands it, no custom button.
        // 26/11: the reasoning rides a blockquote under its bold header.
        val rich = blockBodies(wire).firstOrNull { "blockquote" in it.first }
        assertTrue(rich != null, "the block's violet message spoke")
        assertTrue("blockquote" in rich!!.first)
        // the swap's order: the card dies, the voice speaks, the card is
        // REBORN — delete → sendRichMessage(blocks) → birth sendRichMessage(html)
        val methods = wire.bodies.drop(afterBirth).map { it.second }
        assertEquals("sendRichMessage", methods.first(), "the voice speaks first")
        assertEquals("deleteMessage", methods[1], "then the stale card dies")
        assertEquals("sendMessage", methods[2], "the card is reborn LAST (classic: scroll)")
        val reborn = cardBodies(wire).last().first
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
        assertEquals("sendMessage", wire.bodies.last().second, "the card is REBORN last (classic: scroll)")
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
        assertTrue("deleteMessage" in methods && wire.bodies.last().second == "sendMessage")
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
            spoken.any { "⚡ <b>Nushell</b>" in it && "tocar para ver" in it },
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
            "deleteMessage" in methods && wire.bodies.last().second == "sendMessage",
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
            finalTurn = { voiceOf("LA RESPUESTA") },
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
            spoken.any { "⚡ <b>Nushell</b>" in it && "tocar para ver" in it },
            "the unseen close speaks (collapsed) at the seal",
        )
        val tail = spoken.firstOrNull { "CoT 1" in it }
        assertTrue(tail != null, "the tail sealed at the tool's arrival — it travels collapsed")
        assertTrue("generado" in tail!!, "a boundary-sealed tail earns the SEALED verdict")
        assertTrue("tocar para leer" in tail)
        assertTrue("pensando" !in tail, "the tail's text hides behind the tap too")
        assertTrue("end_turn" in spoken[spoken.size - 2], "the record is second-to-last")
        assertEquals("🧾 Digest:\n\nLA RESPUESTA", spoken.last(), "the answer is the last word, behind the header")
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
            finalTurn = { voiceOf("LA RESPUESTA") },
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
            finalTurn = { voiceOf("R") },
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
            finalTurn = { voiceOf("LA RESPUESTA") },
            richVoice = false,
        )
        publisher.tick() // birth
        spoken.clear()
        hub.apply(frame(taskId, "task_complete", at = 9_999, "stopReason" to JsonPrimitive("end_turn")), nowMs = 9_999)
        publisher.tick()
        assertEquals(
            listOf("🧾 Digest:\n\nLA RESPUESTA"),
            spoken.toList(),
            "SUMMARY: the answer is the only word — behind the header",
        )
        assertTrue(wire.bodies.none { it.second == "deleteMessage" }, "no swap at the SUMMARY seal")
        assertTrue("end_turn" in wire.bodies.last { it.second == "editMessageText" }.first, "the record edits in place")
    }

    @Test
    fun aShellBoxCarriesTheImageDoorAndRegistersItsShot() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val shots = PngShots()
        val plain = mutableListOf<Pair<String, JsonObject?>>()
        var now = TICK_MS
        val publisher = publisher(
            wire,
            hub,
            clock = { now },
            pngShots = shots,
            speakPlain = { text, markup -> plain.add(text to markup); 778L },
        )
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
                "toolId" to JsonPrimitive("tb"), "content" to JsonPrimitive("out"),
            ),
            nowMs = 1_600,
        )
        now += 31_000L
        publisher.tick() // the close's box is born — the 📸 doors ride its keyboard
        assertEquals(2, shots.size(), "the box registers TWO shots — the command and the output")
        assertEquals(
            PngRender.Shot("ls", CodeLang.SHELL),
            shots.get(1),
            "the first door is the INPUT command",
        )
        assertEquals(
            PngRender.Shot("out", CodeLang.SHELL),
            shots.get(2),
            "the second door is the OUTPUT the run printed",
        )
        // the box speaks through the CLASSIC throat (the scroll law's lane)
        val box = plain.firstOrNull { "tocar para ver" in it.first }
        assertNotNull(box, "the box spoke: $plain")
        val markup = box.second?.toString().orEmpty()
        assertTrue("png:1" in markup && "png:2" in markup, "both doors carry their id: $markup")
        assertTrue("comando PNG" in markup, "the input door names what it shows: $markup")
        assertTrue("salida PNG" in markup, "the output door names what it shows: $markup")
        // 26/11, the owner: the outputs are IMAGES and nothing else — the
        // text pager's door no longer rides the box
        assertTrue("Ver salida" !in markup, "no non-PNG door: $markup")
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

    // ---- the child's own stream: the routing (12/11) ---------------------------

    @Test
    fun theChildsOwnStreamRoutesIntoItsDispatchLane() = runTest {
        val parent = "task-9"
        val child = subagentSessionId("agent_1")
        val hub = LiveHub()
        // the parent turn with an OPEN dispatch lane whose ACK named the child
        hub.apply(
            frame(
                parent, "tool_call", at = 1_000,
                "toolId" to JsonPrimitive("d1"), "toolName" to JsonPrimitive("Agent"),
                "subagentAgentId" to JsonPrimitive("agent_1"),
            ),
            nowMs = 1_000,
        )
        // the child's thought and answer grow the lane's window — never a task switch
        hub.apply(frame(child, "agent_thought_chunk", at = 1_100, "content" to JsonPrimitive("child thinks")), nowMs = 1_100)
        var s = hub.peek()!!
        assertEquals(parent, s.taskId, "the card stays the parent's")
        assertEquals(12, s.subagentLanes["d1"]?.cotChars)
        hub.apply(frame(child, "agent_message_chunk", at = 1_200, "content" to JsonPrimitive("child writes")), nowMs = 1_200)
        s = hub.peek()!!
        assertEquals(12, s.subagentLanes["d1"]?.answerChars)
        assertEquals("child writes", s.subagentLanes["d1"]?.answerTail)
        // the child's own seal: dropped whole — the parent's Task close tells that story
        hub.apply(frame(child, "task_complete", at = 1_300, "stopReason" to JsonPrimitive("end_turn")), nowMs = 1_300)
        s = hub.peek()!!
        assertEquals(parent, s.taskId, "the child's seal never switches the card")
        assertTrue(s.phase !is TurnPhase.Done)
        // an unknown child's frames: no open lane owns them — silence
        hub.apply(frame(subagentSessionId("agent_2"), "agent_thought_chunk", at = 1_400, "content" to JsonPrimitive("x")), nowMs = 1_400)
        assertTrue(hub.peek()!!.subagentLanes.keys.none { it == "agent_2" })
        // THE WATCH'S HOLD: the routing precedes it — a held parent still grows its child windows
        hub.watchTaskId(parent, 1_500)
        hub.apply(frame(child, "agent_thought_chunk", at = 1_600, "content" to JsonPrimitive(" more")), nowMs = 1_600)
        assertEquals(17, hub.peek()!!.subagentLanes["d1"]?.cotChars)
    }

    @Test
    fun theChildsToolEventsFoldAsTheDispatchsInnerLanes() = runTest {
        // THE CHILD'S EXECUTIONS (15/11): the child topic's tool frames fold as
        // the dispatch's INNER lanes — the owner reads WHAT the child runs, not
        // just its words — re-based so the fold never sees the child's address.
        val parent = "task-9"
        val child = subagentSessionId("agent_1")
        val hub = LiveHub()
        // the parent turn with an OPEN dispatch lane whose ACK named the child
        hub.apply(
            frame(
                parent, "tool_call", at = 1_000,
                "toolId" to JsonPrimitive("d1"), "toolName" to JsonPrimitive("Agent"),
                "subagentAgentId" to JsonPrimitive("agent_1"),
            ),
            nowMs = 1_000,
        )
        // the child runs something: a REAL lane, off the dispatch, in the parent card
        hub.apply(
            frame(
                child, "tool_call", at = 1_100,
                "toolId" to JsonPrimitive("call_c1"), "toolName" to JsonPrimitive("Bash"),
                "input" to buildJsonObject { put("command", "rg -n sess_subagent src") },
            ),
            nowMs = 1_100,
        )
        var s = hub.peek()!!
        assertEquals(parent, s.taskId, "the card stays the parent's — never a task switch")
        val innerId = childToolLaneId("agent_1", "call_c1")
        val inner = s.tools.firstOrNull { it.toolId == innerId }
        assertEquals("Bash", inner?.name, "the child's execution is a REAL lane")
        assertEquals("rg -n sess_subagent src", inner?.input?.str("command"), "the command rides — readable")
        assertEquals("d1", inner?.parentToolUseId, "the lane hangs off the dispatch")
        assertEquals("agent_1", inner?.subagentAgentId, "the lane names its child — never frozen")
        // the child's in_progress ping touches the SAME lane (the deterministic id)
        hub.apply(
            frame(
                child, "tool_call_update", at = 1_200, "status" to JsonPrimitive("in_progress"),
                "toolId" to JsonPrimitive("call_c1"), "toolName" to JsonPrimitive("Bash"),
            ),
            nowMs = 1_200,
        )
        s = hub.peek()!!
        assertEquals(1, s.tools.count { it.toolId == innerId }, "one lane, not one per frame")
        // the close lands the verdict on the same lane
        hub.apply(
            frame(
                child, "tool_call_update", at = 1_300, "status" to JsonPrimitive("completed"),
                "toolId" to JsonPrimitive("call_c1"), "content" to JsonPrimitive("line1\nline2"),
            ),
            nowMs = 1_300,
        )
        s = hub.peek()!!
        val closed = s.tools.first { it.toolId == innerId }
        assertEquals("completed", closed.status)
        assertEquals(2, closed.outputLines, "the close's output lines are counted")
        // the dispatch closes: its inner world folds away with it (the corpse law)
        hub.apply(
            frame(
                parent, "tool_call_update", at = 1_400, "status" to JsonPrimitive("completed"),
                "toolId" to JsonPrimitive("d1"), "content" to JsonPrimitive("the child's report"),
            ),
            nowMs = 1_400,
        )
        assertTrue(
            hub.peek()!!.tools.none { it.toolId == innerId },
            "the dispatch's close folds its child lanes away",
        )
    }

    @Test
    fun aPinnedChildStreamsAsItsOwnCard() = runTest {
        val child = subagentSessionId("agent_1")
        val hub = LiveHub()
        hub.pin(child)
        hub.apply(frame(child, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(child, "agent_message_chunk", at = 1_100, "content" to JsonPrimitive("the child's answer")), nowMs = 1_100)
        val s = hub.peek()!!
        assertEquals(child, s.taskId, "the pinned card IS the child's turn")
        assertEquals(18, s.answerChars, "the child's deltas fold as the main narration")
        assertEquals(subagentSessionId("agent_1").takeLast(LiveTurn.TITLE_TAIL), s.title)
        // the parent's traffic cannot steal the pinned card back
        hub.apply(frame("task-9", "agent_thought_chunk", at = 1_200, "content" to JsonPrimitive("parent noise")), nowMs = 1_200)
        assertEquals(child, hub.peek()!!.taskId)
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
    fun theEditCloseSpeaksItsDiffWithNoDoorButTheImage() = runTest {
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
            finalTurn = { null },
            clock = { now },
            toolBox = { lane -> "✏️ ${lane.input?.str("file_path")} listo" },
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
        val close = spoken.zip(markups).firstOrNull { (text, _) -> "Main.kt" in text }
        assertNotNull(close, "the edit close speaks its diff: ${spoken.joinToString(" | ")}")
        // 26/11, the owner: nothing that is not an image is shown — the file
        // viewer's door is gone and only the 📸 rows may ride a close
        val markup = close.second?.toString().orEmpty()
        assertTrue("archivo completo" !in markup, "no file door: $markup")
        assertTrue("bp:" !in markup, "no text pager: $markup")
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
    fun theFeedNeverSpeaksProseTheVoiceWaitsForTheSeal() = runTest {
        // THE 16/11 LAW (the owner: the raw mid-turn violet bubbles were the
        // unorganized wall): the LLM's mid-turn prose — «El meter es real…» —
        // accumulates and spends at the tool boundary as always, but the
        // FEED no longer speaks it. The seal's digest ([TurnDigest]) owns
        // the voice; the card's answer tail still streams the words.
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
        assertTrue(
            wire.bodies.none { it.second == "sendRichMessage" && "El meter es real" in it.first },
            "the mid-turn prose speaks NOTHING: ${wire.bodies.map { it.second }}",
        )
        // a second segment spends at the NEXT boundary; the seal's own copy
        // (the fold's answerAtSeal) is the only violet left in the old shape
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
        // the seal: the fold's digest speaks the spent steps ONCE, organized —
        // the words arrive at the end, labeled, never as mid-turn bubbles
        val digest = spoken.lastOrNull { "✅ Respuesta" in it }
        assertNotNull(digest, "the seal's digest spoke: $spoken")
        assertTrue("El meter es real" in digest, "the spent step rides the digest, organized")
        assertTrue(
            blockBodies(wire).none { "✅ Respuesta" in it.first },
            "the digest rides the classic HTML lane, never the rich blocks",
        )
    }

    @Test
    fun theSealSpeaksTheFinalAnswerAsFormattedHtml() = runTest {
        // THE 24/11 SCROLL LAW on the answer: the seal's digest rides the
        // classic HTML lane — inline `code` becomes a real run, prose keeps
        // its breaks, and a long line scrolls instead of wrapping. The rich
        // blocks stay for the CoT's pullquote.
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("p")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        val publisher = publisher(wire, hub, spoken, finalTurn = { voiceOf("La respuesta final con `codigo`.") })
        publisher.tick()
        hub.apply(frame(taskId, "task_complete", at = 1_200, "stopReason" to JsonPrimitive("end_turn")), nowMs = 1_200)
        publisher.tick()
        val answer = spoken.lastOrNull { "respuesta final" in it }
        assertNotNull(answer, "the final answer spoke: $spoken")
        assertTrue("<code>codigo</code>" in answer, "the inline code renders as a run: $answer")
        assertTrue(
            blockBodies(wire).none { "respuesta final" in it.first },
            "never the rich lane: ${wire.bodies.map { it.second }}",
        )
    }

    @Test
    fun theSealSpeaksTheOrganizedDigestAsHtml() = runTest {
        // THE 16/11 LAW (the owner chose the digest) on the 24/11 lane: the
        // turn's voice seals ORGANIZED — the steps labeled with the tool each
        // followed, the conclusion marked ✅ — packed classic-HTML pages,
        // never the raw concatenated wall and never a wrapping rich block.
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("p")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        val publisher = publisher(
            wire,
            hub,
            spoken,
            finalTurn = {
                TurnVoice(
                    listOf(
                        TurnProse("Reviso el handler primero."),
                        TurnProse("Los tests pasan; aplico el diff.", afterTool = "Bash"),
                        TurnProse("El fix consistió en X."),
                    ),
                )
            },
        )
        publisher.tick()
        hub.apply(frame(taskId, "task_complete", at = 1_200, "stopReason" to JsonPrimitive("end_turn")), nowMs = 1_200)
        publisher.tick()
        val digest = spoken.lastOrNull { "✅ Respuesta" in it }
        assertNotNull(digest, "the digest spoke: $spoken")
        assertTrue("Paso 1" in digest, "the opening step carries its number")
        assertTrue("tras 🔧 Bash" in digest, "the step names the tool it followed")
        assertTrue(
            digest.trimEnd().endsWith("El fix consistió en X.</blockquote>"),
            "the conclusion closes the digest — quoted, the voice's own highlight: $digest",
        )
        assertTrue(
            "<blockquote>🧾 Digest:\n\nPaso 1\n\nReviso el handler primero.</blockquote>" in digest,
            "the header leads, then the label and the words keep their REAL blank line: $digest",
        )
        assertTrue(blockBodies(wire).none { "✅ Respuesta" in it.first }, "the digest left the rich lane")
    }

    @Test
    fun anOversizedConclusionChunksAcrossMessagesAndNothingDrops() = runTest {
        // THE CONTINUATION LAW: a conclusion longer than one message CHUNKS
        // across classic pages — the pieces concatenate to the whole, byte
        // for byte. Truncation with no continuation is the 16/11 bug, dead.
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        val wire = Wire()
        val text = "x".repeat(9000)
        val spoken = mutableListOf<String>()
        val publisher = publisher(wire, hub, spoken, finalTurn = { voiceOf(text) })
        publisher.tick()
        hub.apply(frame(taskId, "task_complete", at = 1_200, "stopReason" to JsonPrimitive("end_turn")), nowMs = 1_200)
        publisher.tick()
        assertTrue(spoken.size >= 3, "9000 cp over the 3500 page: several messages: ${spoken.size}")
        // the prose rides quoted (the voice's highlight); the CHUNKS still
        // concatenate to the whole byte for byte once the wrapper and the
        // leading digest header are peeled
        val bare = spoken.joinToString("")
            .replace("<blockquote>", "")
            .replace("</blockquote>", "")
            .replace("🧾 Digest:\n\n", "")
        assertEquals(text, bare, "the chunks CONCATENATE to the whole — nothing dropped")
    }

    @Test
    fun theSnapshotMissSpeaksTheFoldsDigestWithNumberedSteps() = runTest {
        // NEVER LOST, organized (11/11 ladder + 16/11 digest): the snapshot
        // cannot answer → the fold's own projection speaks — the spent steps
        // numbered, the seal's copy as the ✅ conclusion.
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(
            frame(taskId, "agent_message_chunk", at = 1_100, "content" to JsonPrimitive("Primer tramo hablado.")),
            nowMs = 1_100,
        )
        hub.apply(
            frame(taskId, "tool_call", at = 1_200, "toolId" to JsonPrimitive("tb"), "toolName" to JsonPrimitive("Read"),
                "input" to buildJsonObject { put("file_path", "/w/a.kt") }),
            nowMs = 1_200,
        )
        hub.apply(
            frame(taskId, "agent_message_chunk", at = 1_300, "content" to JsonPrimitive("La conclusión del fold.")),
            nowMs = 1_300,
        )
        hub.apply(frame(taskId, "task_complete", at = 2_000, "stopReason" to JsonPrimitive("end_turn")), nowMs = 2_000)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        val publisher = publisher(wire, hub, spoken, finalTurn = { null })
        publisher.tick()
        publisher.tick()
        val digest = spoken.lastOrNull { "✅ Respuesta" in it }
        assertNotNull(digest, "the fold's digest spoke: $spoken")
        assertTrue("Paso 1" in digest, "the spent step rides numbered: ${digest.take(300)}")
        assertTrue("La conclusión del fold." in digest)
        assertEquals(1, spoken.count { "✅ Respuesta" in it }, "one digest, one message")
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
        val thinking = blockBodies(wire).firstOrNull { "blockquote" in it.first }
        assertTrue(thinking != null, "the CoT rides a native quote: ${wire.bodies.map { it.second }}")
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
        assertEquals(0, blockBodies(wire).size, "the knob OFF: no rich VOICE sends")
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
    fun theShellBoxKeepsItsLanguageAndTheTextDoorIsRetired() = runTest {
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
        publisher.tick() // the close collapses behind its image door
        val box = hub.peek()?.boxes?.single()
        assertEquals("nu", box?.language, "the fold carries the fence language for a shell's box")
        // 26/11, the owner: the text pager is retired — an old «Ver salida»
        // tap answers the honest words and the fold never leaves the header
        val edits = wire.bodies.count { it.second == "editMessageText" }
        assertTrue(publisher.open(box!!.boxId, 0)!!.contains("📸"), "the retired door speaks")
        assertEquals(edits, wire.bodies.count { it.second == "editMessageText" }, "and edits nothing")
    }

    @Test
    fun everyBoxIsBornFoldedTheChildsReportToo() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        var now = TICK_MS
        val publisher = publisher(wire, hub, spoken, toolBox = { "BOX(${it.name})" }, clock = { now })
        publisher.tick() // birth (a Working card — an Idle one never births)
        // a shell closes: the 3/11 verbosity cure — FOLDED, one button
        hub.apply(
            frame(
                taskId, "tool_call", at = 1_500,
                "toolId" to JsonPrimitive("ts"), "toolName" to JsonPrimitive("Bash"),
                "input" to buildJsonObject { put("command", "ls") },
            ),
            nowMs = 1_500,
        )
        hub.apply(
            frame(
                taskId, "tool_call_update", at = 1_600, "status" to JsonPrimitive("completed"),
                "toolId" to JsonPrimitive("ts"), "content" to JsonPrimitive("out one\nout two"),
            ),
            nowMs = 1_600,
        )
        now += 31_000L
        publisher.tick()
        val shellBox = hub.peek()?.boxes?.single()
        assertEquals(BoxLive.FOLDED, shellBox?.page, "a shell's box is born folded, as always")
        // THE CHILD'S REPORT (12/11): an Agent dispatch closes with its report —
        // born OPEN on page 1, the pager's buttons intact for the flips
        hub.apply(
            frame(
                taskId, "tool_call", at = 2_000,
                "toolId" to JsonPrimitive("d1"), "toolName" to JsonPrimitive("Agent"),
                "input" to buildJsonObject { put("description", "the fixer") },
            ),
            nowMs = 2_000,
        )
        hub.apply(
            frame(
                taskId, "tool_call_update", at = 2_100, "status" to JsonPrimitive("completed"),
                "toolId" to JsonPrimitive("d1"), "content" to JsonPrimitive("the child's report line"),
            ),
            nowMs = 2_100,
        )
        now += 31_000L
        publisher.tick()
        val born = hub.peek()?.boxes?.last()
        // 26/11, the owner: nothing that is not an image is shown — the
        // child's report folds like every other box; its words ride the image
        assertEquals(BoxLive.FOLDED, born?.page, "the report's box is born folded: no text door")
        val bornBody = spoken.lastOrNull { "the fixer" in it }
        assertNotNull(bornBody, "the header still names the spawn: $spoken")
        assertTrue("the child's report line" !in bornBody, "the report's words wait behind the image")
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
        val chunks = spoken.filter { "language-nu" in it }
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
            spoken.any { "language-nu" in it && "(1/2)" in it },
            "the card promised «va en el feed» — NORMAL speaks the data too: $spoken",
        )
        assertTrue(spoken.none { "▶️" in it }, "but the open one-liner stays quiet under NORMAL")
    }

    // ---- 11/11: the univocal answer ---------------------------------------------

    @Test
    fun theSealSpeaksTheConclusionExactlyOnce() = runTest {
        // THE 11/11 BUG (the owner: the conclusion arrived three times): the
        // seal feed's truncated copy + the full finalAnswer spoke the answer
        // TWICE back to back on every completion. The seal owns the answer:
        // ONE violet copy, the snapshot's full text.
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(
            frame(taskId, "agent_message_chunk", at = 1_100, "content" to JsonPrimitive("La respuesta completa y final.")),
            nowMs = 1_100,
        )
        hub.apply(frame(taskId, "task_complete", at = 1_200, "stopReason" to JsonPrimitive("end_turn")), nowMs = 1_200)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        val publisher = publisher(wire, hub, spoken, finalTurn = { voiceOf("La respuesta completa y final.") })
        publisher.tick() // birth
        publisher.tick() // the seal tick — the prose rides answerAtSeal, never the feed
        assertEquals(1, spoken.count { "La respuesta completa" in it }, "the conclusion is ONE message: $spoken")
    }

    @Test
    fun theSnapshotMissFallsBackToTheSealsOwnCopy() = runTest {
        // NEVER LOST (11/11): when the snapshot cannot answer at seal time,
        // the fold's own answerAtSeal copy speaks — one violet message, the
        // prose that streamed. The fallback is the degradation, not a second
        // copy: the feed never spoke it.
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(
            frame(taskId, "agent_message_chunk", at = 1_100, "content" to JsonPrimitive("La conclusión del fallback.")),
            nowMs = 1_100,
        )
        hub.apply(frame(taskId, "task_complete", at = 2_000, "stopReason" to JsonPrimitive("end_turn")), nowMs = 2_000)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        val publisher = publisher(wire, hub, spoken, finalTurn = { null })
        publisher.tick()
        publisher.tick()
        assertEquals(1, spoken.size, "one copy only: $spoken")
        assertTrue("La conclusión del fallback" in spoken.single(), "the never-lost copy: ${spoken.single().take(300)}")
    }

    @Test
    fun theGhostReburstNeverResealsNorReSpeaks() = runTest {
        // THE GHOST RE-SEAL (measured: the same taskId sealed twice 9s apart):
        // the fork's subscribe burst re-delivered the whole turn — head,
        // deltas, complete — a fresh fold re-seeded and the conclusion spoke
        // again. THE SEALED MEMORY: the turn that sealed gates its own
        // frames; only a task_run_started with a STRICTLY NEWER startedAt
        // stamp (a genuinely new turn — a reburst carries the same stamp)
        // lifts it.
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000, "startedAt" to JsonPrimitive(5_000L)), nowMs = 1_000)
        hub.apply(
            frame(taskId, "agent_message_chunk", at = 1_100, "content" to JsonPrimitive("La conclusión única.")),
            nowMs = 1_100,
        )
        val wire = Wire()
        val spoken = mutableListOf<String>()
        var now = TICK_MS
        var seals = 0
        val publisher = publisher(wire, hub, spoken, finalTurn = { voiceOf("La conclusión única.") }, onSeal = { seals += 1 }, clock = { now })
        publisher.tick()
        hub.apply(frame(taskId, "task_complete", at = 2_000, "stopReason" to JsonPrimitive("end_turn")), nowMs = 2_000)
        publisher.tick()
        assertEquals(1, seals, "one seal")
        assertEquals(1, spoken.count { "La conclusión" in it }, "the conclusion speaks exactly once: $spoken")
        // THE REBURST: head + deltas + complete, the same startedAt stamp —
        // every frame drops at the sealed memory
        hub.apply(frame(taskId, "task_run_started", at = 2_100, "startedAt" to JsonPrimitive(5_000L)), nowMs = 2_100)
        hub.apply(
            frame(taskId, "agent_message_chunk", at = 2_200, "content" to JsonPrimitive("La conclusión única.")),
            nowMs = 2_200,
        )
        hub.apply(frame(taskId, "task_complete", at = 2_300, "stopReason" to JsonPrimitive("end_turn")), nowMs = 2_300)
        publisher.tick()
        assertEquals(1, seals, "the reburst never re-seals")
        assertEquals(1, spoken.count { "La conclusión" in it }, "the conclusion never re-speaks: $spoken")
        // A REAL new turn (a strictly newer startedAt stamp) lifts the memory
        // — and like every turn it carries traffic before the seal (the birth
        // law: an Idle state never births the card). The clock moves past the
        // birth spacing: the same task's new card is a legitimate re-birth.
        now += 60_000L
        hub.apply(frame(taskId, "task_run_started", at = 3_000, "startedAt" to JsonPrimitive(9_000L)), nowMs = 3_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 3_100, "content" to JsonPrimitive("nueva ronda")), nowMs = 3_100)
        hub.apply(frame(taskId, "task_complete", at = 4_000, "stopReason" to JsonPrimitive("end_turn")), nowMs = 4_000)
        publisher.tick()
        assertEquals(2, seals, "the genuinely new turn seals normally")
    }

    @Test
    fun theBootSealEditsTheCapturedCardNotABirthOverwrite() = runTest {
        // THE 22/11 SEAL RACE (measured live: a boot landing mid-turn sealed
        // the LIVE card 30 s after its birth): births of THIS boot overwrite
        // the pointer file before the seal's first tick — the old code
        // re-read the store at tick time and sealed the freshest birth. The
        // capture now happens at CONSTRUCTION: the seal edits the
        // pre-restart record, never a this-boot birth's pointer.
        val file = java.io.File(System.getProperty("java.io.tmpdir"), "orphan-seal-race-${System.nanoTime()}.json")
        val store = LiveOrphanStore(file)
        store.save(999L, "sess_preRestart") // A: the pre-restart card
        val wire = Wire()
        val publisher = LivePublisher(
            hub = LiveHub(), // no frames: the first tick's only work is the seal
            telegram = LiveTelegram(
                api = TgApi({ cfg }, engineFactory = wire.factory),
                cooldown = Cooldown(readFile = { null }),
            ),
            config = { cfg },
            channelAlive = { true },
            speak = { _, _ -> 777L },
            toolBox = { "" },
            finalTurn = { null },
            clock = { TICK_MS },
            orphanStore = store, // the construction captures 999
        )
        store.save(888L, "sess_thisBoot") // B: THIS boot's birth overwrote the pointer
        publisher.tick()
        val edits = wire.bodies.filter { it.second == "editMessageText" }
        assertTrue(edits.any { "999" in it.first }, "the seal edits the CAPTURED pre-restart card")
        assertTrue(edits.none { "888" in it.first }, "a this-boot birth's pointer is never sealed")
        file.delete()
    }
}
