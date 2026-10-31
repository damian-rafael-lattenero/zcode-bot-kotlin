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

    /** The captured wire: (json body, method) per call, answered OK always. */
    private class Wire {
        val bodies = mutableListOf<Pair<String, String>>()
        val factory: HttpClientEngineFactory<MockEngineConfig> =
            object : HttpClientEngineFactory<MockEngineConfig> {
                override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine =
                    MockEngine(
                        MockEngineConfig().apply(block).apply {
                            addHandler { request ->
                                bodies += (request.body as TextContent).text to request.url.segments.last()
                                respond("""{"result":{"message_id":77}}""", HttpStatusCode.OK)
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
            speak = { text -> spoken.add(text) },
            toolBox = toolBox,
            finalAnswer = finalAnswer,
            clock = { TICK_MS },
            verbosity = verbosity,
            resolveTitle = resolveTitle,
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
        assertTrue("«644150daf013»" !in birth) // the tail is not the head (the taskId line stays, by design)
        publisher.tick() // the next tick re-renders — the resolution is ONE-SHOT per turn
        assertEquals(1, calls)
        val edit = wire.bodies.last { it.second == "editMessageText" }.first
        assertTrue("arreglá el login" in edit, "the status render carries the honest name too")
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
    fun aSealedBlockBecomesItsOwnMessageAndTheCardIsRebornLast() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        val publisher = publisher(wire, hub, spoken)
        publisher.tick() // the birth — feedSeen snapshots here
        val afterBirth = wire.bodies.size
        hub.apply(
            frame(taskId, "agent_thought_chunk", at = 1_100, "content" to JsonPrimitive("p".repeat(3000))),
            nowMs = 1_100,
        )
        publisher.tick() // the block sealed → the feed speaks, the card swaps
        assertTrue(spoken.any { "<b>CoT 1</b>" in it && "<blockquote>" in it }, "the block travels WHOLE")
        // the swap's order: the card dies, the feed speaks (the speak lane),
        // the card is REBORN — deleteMessage then the birth sendMessage
        val methods = wire.bodies.drop(afterBirth).map { it.second }
        assertEquals("deleteMessage", methods.first())
        assertEquals("sendMessage", methods[1])
        assertTrue("turn live" in wire.bodies.last { it.second == "sendMessage" }.first)
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
        val publisher = publisher(
            wire,
            hub,
            spoken,
            toolBox = { "BOX(${it.name})" },
            verbosity = VolatileVerbosity().apply { set(FeedVerbosity.NORMAL) },
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
        publisher.tick()
        assertTrue(spoken.any { it == "BOX(Bash)" }, "NORMAL: the close speaks the injected box")
    }

    @Test
    fun summarySpeaksNothingAndNeverSwapsTheCard() = runTest {
        val taskId = "task-9"
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        val publisher = publisher(
            wire,
            hub,
            spoken,
            verbosity = VolatileVerbosity().apply { set(FeedVerbosity.SUMMARY) },
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
        publisher.tick()
        assertEquals(emptyList(), spoken.toList(), "SUMMARY: the feed is silent")
        assertTrue(wire.bodies.drop(afterBirth).none { it.second == "deleteMessage" }, "no swap at SUMMARY")
        assertTrue(wire.bodies.drop(afterBirth).any { it.second == "editMessageText" }, "the card still ticks")
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
        // the remainder first (the unseen close + the unsealed thought tail),
        // then the record, then the answer LAST
        assertTrue(spoken.any { it == "BOX(Bash)" }, "the unseen close speaks at the seal")
        assertTrue(spoken.any { "sin sellar" in it && "pensando" in it }, "the unsealed tail loses nothing")
        assertTrue("end_turn" in spoken[spoken.size - 2], "the record is second-to-last")
        assertEquals("LA RESPUESTA", spoken.last(), "the answer is the last word")
        assertEquals("deleteMessage", wire.bodies.last { it.second != "editMessageText" }.second)
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
}
