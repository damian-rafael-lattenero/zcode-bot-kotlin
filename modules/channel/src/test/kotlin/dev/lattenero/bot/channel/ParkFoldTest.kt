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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * U5c's PARK as FOLD STATE (the design's row: «the bg: button (`Park`)»):
 * the tap folds [RenderEvent.Park], the one landed edit folds
 * [RenderEvent.ParkLanded], and the publisher READS the quiet from the fold —
 * there is no park machine beside it anymore. The laws pinned here:
 *
 * (a) the tap edits the card ONCE, byte-exact («▶️ sigue en background»);
 * (b) a parked turn receives NO further edit (no redraw, no feed) while the
 *     fold keeps consuming the stream — the seal's facts stay complete;
 * (c) the turn seals and the 🏁 notification fires EXACTLY once;
 * (d) a foreign or expired park parks nothing (the honest miss);
 * (e) the state's Clear — the seal, a ☢️ nuclear — takes the park with the
 *     world, so a new turn for the same task is never parked.
 */
class ParkFoldTest {

    private val cfg = TgConfig(token = "tok", chatId = JsonPrimitive(42), ownerUserId = 7L)

    /** The captured wire: (json body, method) per call — each send a FRESH message id. */
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
        cooldown: Cooldown = Cooldown(readFile = { null }),
        finalAnswer: suspend (String) -> String? = { null },
        onSeal: suspend (SealFacts) -> Unit = {},
    ): LivePublisher = LivePublisher(
        hub = hub,
        telegram = LiveTelegram(api = TgApi({ cfg }, engineFactory = wire.factory), cooldown = cooldown),
        config = { cfg },
        channelAlive = { true },
        speak = { text, _ -> spoken.add(text); 777L },
        finalAnswer = finalAnswer,
        clock = { TICK_MS },
        onSeal = onSeal,
    )

    private val taskId = "task-9"

    /** The last edit's own fields — the wire bytes Telegram receives. */
    private data class Edit(val messageId: Long, val text: String, val markup: JsonObject)

    private fun lastEdit(wire: Wire): Edit {
        val body = Json.parseToJsonElement(wire.bodies.last { it.second == "editMessageText" }.first).jsonObject
        val markup = (body["reply_markup"] as JsonPrimitive).content
        return Edit(
            messageId = body["message_id"]!!.jsonPrimitive.content.toLong(),
            text = body["text"]!!.jsonPrimitive.content,
            markup = Json.parseToJsonElement(markup).jsonObject,
        )
    }

    /** The expected button shape, built independently of the production builder. */
    private fun parkMarkup(id: String): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            add(
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("text", "⏸️ A background")
                            put("callback_data", "bg:$id")
                        },
                    )
                },
            )
        }
    }

    /** A live card born through the real publisher: one turn, one thought, one birth. */
    private suspend fun bornCard(wire: Wire, hub: LiveHub, publisher: LivePublisher) {
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(
            frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")),
            nowMs = 1_050,
        )
        publisher.tick()
    }

    // ---- the fold's own laws ----------------------------------------------------

    private fun live(parked: ParkLive? = null): TurnLive =
        TurnLive(taskId = "task-9", title = "task-9", parked = parked)

    @Test
    fun theParkIsFoldStateAndAForeignOrSecondParkFoldsNothing() {
        val parked = reduce(live(), RenderEvent.Park("task-9", atMs = 2_000L), SimpleLineDiffer)!!
        assertEquals(ParkLive(atMs = 2_000L, landed = false), parked.parked, "the tap's fact is the fold's state")
        // the one-shot: a second Park on a parked card folds NOTHING
        assertEquals(
            parked,
            reduce(parked, RenderEvent.Park("task-9", atMs = 3_000L), SimpleLineDiffer),
            "a second park changes nothing — not even the stamp",
        )
        // (d) a FOREIGN task's park parks nothing — the state stays, never a null
        assertEquals(
            parked,
            reduce(parked, RenderEvent.Park("task-2", atMs = 3_000L), SimpleLineDiffer),
        )
        assertEquals(
            live(),
            reduce(live(), RenderEvent.Park("task-2", atMs = 3_000L), SimpleLineDiffer),
            "a foreign park parks NOTHING — the honest miss",
        )
        assertNull(reduce(null, RenderEvent.Park("task-9", atMs = 3_000L), SimpleLineDiffer), "no card, no park")
    }

    @Test
    fun theLandingIsFoldedOnceAndAnUnlandedEditFoldsNothing() {
        val parked = reduce(live(), RenderEvent.Park("task-9", atMs = 2_000L), SimpleLineDiffer)!!
        // (a) the ONE final edit's fact
        val landed = reduce(parked, RenderEvent.ParkLanded("task-9"), SimpleLineDiffer)!!
        assertEquals(true, landed.parked!!.landed)
        assertEquals(2_000L, landed.parked.atMs, "the landing moves nothing but its own flag")
        // the second landing folds nothing (the tap edits ONCE)
        assertEquals(landed, reduce(landed, RenderEvent.ParkLanded("task-9"), SimpleLineDiffer))
        // a landing with nothing parked folds nothing (a throttled edit's miss)
        assertEquals(live(), reduce(live(), RenderEvent.ParkLanded("task-9"), SimpleLineDiffer))
        // a foreign landing touches nothing
        assertEquals(landed, reduce(landed, RenderEvent.ParkLanded("task-2"), SimpleLineDiffer))
        assertNull(reduce(null, RenderEvent.ParkLanded("task-9"), SimpleLineDiffer))
    }

    @Test
    fun theClearTakesTheParkWithTheWorld() {
        // (e) the seal's own Clear (and a ☢️ nuclear) drops the park with the turn
        val parked = reduce(live(), RenderEvent.Park("task-9", atMs = 2_000L), SimpleLineDiffer)!!
        assertNull(reduce(parked, RenderEvent.Clear, SimpleLineDiffer), "the world's death takes the park")
        // a NEW turn for the same task is a fresh state: never parked
        val fresh = reduce(
            parked,
            RenderEvent.Started("task-9", seq = 9, atMs = 10_000, nowMs = 10_000),
            SimpleLineDiffer,
        )
        assertEquals(parked.parked, fresh!!.parked, "a frame is not a task switch — the park survives the stream")
        val switched = reduce(
            parked,
            RenderEvent.Started("task-2", seq = 10, atMs = 11_000, nowMs = 11_000),
            SimpleLineDiffer,
        )
        assertNull(switched!!.parked, "the task switch's fresh card carries no park")
    }

    // ---- the publisher reads the fold --------------------------------------------

    @Test
    fun theTapEditsTheCardOnceWithTheByteExactParkedRender() = runTest {
        val hub = LiveHub()
        val wire = Wire()
        val publisher = publisher(wire, hub)
        bornCard(wire, hub, publisher)

        // (a) THE TAP: the ack, and ONE edit — the exact parked bytes
        assertEquals(ParkTapTexts.ACK, publisher.park(taskId))
        assertEquals(ParkLive(atMs = TICK_MS, landed = true), hub.peek()!!.parked, "the landing is fold state")
        val parked = lastEdit(wire)
        assertEquals(
            "⏸️ <b>«task-9»</b> — ▶️ sigue en background\n" +
                "🔔 te aviso al terminar: el 🏁 llega cuando el turno selle",
            parked.text,
            "the parked card's ONE render, byte-exact",
        )
        assertEquals(parkMarkup(taskId), parked.markup, "the ⏸️ keeps its button — a second tap stays answerable")
        val edits = wire.bodies.count { it.second == "editMessageText" }

        // the second tap: the honest no-op, no edit at all
        assertEquals(ParkTapTexts.ALREADY, publisher.park(taskId))
        assertEquals(edits, wire.bodies.count { it.second == "editMessageText" }, "the no-op edits nothing")

        // (b) the stream keeps folding, the chat hears NOTHING — no redraw, no feed, no delete
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
                at = 1_200,
                "status" to JsonPrimitive("completed"),
                "toolId" to JsonPrimitive("t1"),
                "content" to JsonPrimitive("on branch master"),
            ),
            nowMs = 1_200,
        )
        publisher.tick()
        publisher.tick()
        assertEquals(edits, wire.bodies.count { it.second == "editMessageText" }, "the parked card is never edited again")
        assertTrue(wire.bodies.none { it.second == "deleteMessage" }, "nor swapped")
        assertEquals(1, hub.peek()!!.doneTools, "the fold kept consuming while parked — the facts stay complete")
    }

    @Test
    fun theParkedSealSpeaksOnceThroughTheBellAndNeverTouchesTheCard() = runTest {
        val hub = LiveHub()
        val wire = Wire()
        val spoken = mutableListOf<String>()
        val bells = mutableListOf<SealFacts>()
        val publisher = publisher(
            wire,
            hub,
            spoken,
            finalAnswer = { "LA RESPUESTA" },
            onSeal = { bells.add(it) },
        )
        bornCard(wire, hub, publisher)
        assertEquals(ParkTapTexts.ACK, publisher.park(taskId))
        val afterPark = wire.bodies.size

        // (c) the turn seals unseen: the card keeps its promise, the 🔔 speaks ONCE
        hub.apply(
            frame(taskId, "task_complete", at = 9_999, "stopReason" to JsonPrimitive("end_turn")),
            nowMs = 9_999,
        )
        publisher.tick() // the seal tick
        assertEquals(afterPark, wire.bodies.size, "the parked seal edits nothing")
        assertEquals(emptyList(), spoken.toList(), "no record, no answer — the bell is the only mouth")
        assertEquals(1, bells.size, "one seal, one bell")
        assertEquals("LA RESPUESTA", bells[0].answer)
        assertEquals(false, bells[0].answerSpoken, "the bell carries the conclusion the card never spoke")
        assertEquals(null, hub.peek(), "the cleanup ran — and took the park with it")
        // (e) the one-shot: the card is gone, the button is the honest refusal
        assertEquals(ParkTapTexts.REFUSAL, publisher.park(taskId))
        // a further tick has nothing left to seal: the bell never repeats
        publisher.tick()
        assertEquals(1, bells.size, "the 🏁 fires EXACTLY once per parked turn")
        assertEquals(afterPark, wire.bodies.size)
    }

    @Test
    fun aThrottledTapFoldsNothingAndTheTickLandsTheOneEditLater() = runTest {
        val hub = LiveHub()
        val wire = Wire()
        var nowSec = 2.0
        val cooldown = Cooldown(now = { nowSec }, readFile = { null })
        val publisher = publisher(wire, hub, cooldown = cooldown)
        bornCard(wire, hub, publisher)
        cooldown.extend(raSeconds = 60) // Telegram named the window
        val edits = wire.bodies.count { it.second == "editMessageText" }

        // the tap parks (the ack is the toast, not the edit) but nothing lands
        assertEquals(ParkTapTexts.ACK, publisher.park(taskId))
        assertEquals(edits, wire.bodies.count { it.second == "editMessageText" }, "the render obeys the cooldown")
        assertEquals(ParkLive(atMs = TICK_MS, landed = false), hub.peek()!!.parked, "the park is state; the edit is not")

        // the window lifts: the tick lands the ONE edit, and then the gate holds
        nowSec = 5_000.0
        publisher.tick()
        assertEquals(edits + 1, wire.bodies.count { it.second == "editMessageText" }, "the deferred render landed")
        assertEquals(true, hub.peek()!!.parked!!.landed)
        publisher.tick() // a quiet parked tick
        assertEquals(edits + 1, wire.bodies.count { it.second == "editMessageText" }, "the parked card receives no more edits")
    }

    @Test
    fun aThrottledWindowNeverDelaysTheParkedSeal() = runTest {
        val hub = LiveHub()
        val wire = Wire()
        val bells = mutableListOf<SealFacts>()
        var nowSec = 2.0
        val cooldown = Cooldown(now = { nowSec }, readFile = { null })
        val publisher = publisher(wire, hub, cooldown = cooldown, onSeal = { bells.add(it) })
        bornCard(wire, hub, publisher)
        assertEquals(ParkTapTexts.ACK, publisher.park(taskId))
        // a tool opens and closes unseen while parked: the seal HAS a remainder
        hub.apply(
            frame(
                taskId,
                "tool_call_update",
                at = 1_100,
                "status" to JsonPrimitive("pending"),
                "toolId" to JsonPrimitive("t1"),
                "toolName" to JsonPrimitive("Bash"),
                "input" to buildJsonObject { put("command", "ls") },
            ),
            nowMs = 1_100,
        )
        hub.apply(
            frame(
                taskId,
                "tool_call_update",
                at = 1_200,
                "status" to JsonPrimitive("completed"),
                "toolId" to JsonPrimitive("t1"),
                "content" to JsonPrimitive("out"),
            ),
            nowMs = 1_200,
        )
        hub.apply(
            frame(taskId, "task_complete", at = 9_999, "stopReason" to JsonPrimitive("end_turn")),
            nowMs = 9_999,
        )
        cooldown.extend(raSeconds = 60) // Telegram named the window
        publisher.tick() // the seal tick
        // (c) the parked seal speaks NOTHING, so the feed's back-pressure must
        // never hold the 🔔 hostage: the turn seals and the bell fires now
        assertEquals(1, bells.size, "the parked seal never waits for a feed it will not speak")
        assertEquals(null, hub.peek(), "the cleanup ran — the park died with the turn")
    }

    private companion object {
        const val TICK_MS = 2_000L
    }
}
