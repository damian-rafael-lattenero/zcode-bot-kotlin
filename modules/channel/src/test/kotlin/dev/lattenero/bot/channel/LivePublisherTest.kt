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
 * network): the REAL TITLE resolution — the owner's 30/10 verdict, his
 * card's head wore the sid's tail «644150daf013». The birth carries the
 * history's honest name, the attempt is ONE per turn, the fallback stays
 * when the history knows nothing, and an Idle turn burns nothing.
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

    private fun publisher(wire: Wire, hub: LiveHub, resolveTitle: suspend (String) -> String?): LivePublisher =
        LivePublisher(
            hub = hub,
            telegram = LiveTelegram(
                api = TgApi({ cfg }, engineFactory = wire.factory),
                cooldown = Cooldown(readFile = { null }),
            ),
            config = { cfg },
            channelAlive = { true },
            speak = {},
            toolBox = { "" },
            finalAnswer = { null },
            clock = { TICK_MS },
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
    fun aStreamRealTitleSkipsTheHistoryResolution() = runTest {
        val taskId = "sess_abcd644150daf013"
        val hub = LiveHub()
        hub.apply(
            frame(taskId, "session_info_update", at = 1_000, "title" to JsonPrimitive("título del stream")),
            nowMs = 1_000,
        )
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_100, "content" to JsonPrimitive("pensando")), nowMs = 1_100)
        val wire = Wire()
        var calls = 0
        val publisher = publisher(wire, hub) { _ ->
            calls += 1
            error("the stream already titled this turn")
        }
        publisher.tick()
        assertEquals(0, calls) // a REAL title needs no history read
        val birth = wire.bodies.first { it.second == "sendMessage" }.first
        assertTrue("título del stream" in birth)
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

    @Test
    fun theSealShowerCarriesTheKeyboardDroppedBlocks() = runTest {
        // 9 blocks: the keyboard held the most recent 8 — the close's
        // shower keeps the promise the live render made (the honest cap)
        val taskId = "task-9"
        val hub = LiveHub()
        repeat(9) { pass ->
            hub.apply(
                frame(taskId, "agent_thought_chunk", at = 1_000L + pass, "content" to JsonPrimitive(pass.toString().repeat(3000))),
                nowMs = 1_000L + pass,
            )
        }
        hub.apply(frame(taskId, "task_complete", at = 9_999, "stopReason" to JsonPrimitive("end_turn")), nowMs = 9_999)
        val wire = Wire()
        val spoken = mutableListOf<String>()
        val publisher = LivePublisher(
            hub = hub,
            telegram = LiveTelegram(
                api = TgApi({ cfg }, engineFactory = wire.factory),
                cooldown = Cooldown(readFile = { null }),
            ),
            config = { cfg },
            channelAlive = { true },
            speak = { text -> spoken.add(text) },
            toolBox = { "" },
            finalAnswer = { null },
            clock = { TICK_MS },
        )
        publisher.tick() // the seal tick
        // the seal's record counts the blocks; the shower speaks the
        // dropped block's REAL text (verbatim — never a summary)
        val seal = wire.bodies.last { it.second == "editMessageText" }.first
        assertTrue("9 bloques CoT" in seal)
        assertTrue(spoken.any { "los primeros 1 bloque" in it && "000" in it })
    }

    private companion object {
        const val TICK_MS = 2_000L
    }
}
