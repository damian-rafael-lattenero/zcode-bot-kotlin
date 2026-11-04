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
import kotlin.test.assertTrue

/**
 * U5b's BYTE-EXACT goldens: the collapsed output box through the WHOLE
 * pipeline — the feed speaks the header with its one 📜 button, the fold
 * holds the box (born closed), a `bp:` tap renders FROM the fold and the
 * exact numbered page lands on the wire, a flip marks the new page, `bf:`
 * folds back to the exact header, and the fold mirrors every landing.
 * Plus the fail-closed miss and the throttled edit's honest no-flip.
 *
 * The page arithmetic is pinned BY CONSTRUCTION: 27 lines of exactly 100
 * code points at the 2800-cp budget cut at line 26 (2781 cp) — page 1 is
 * lines 1..26, page 2 is line 27 alone.
 */
class BoxFoldTest {

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

    private val taskId = "task-9"
    private val boxMessageId = 555L

    /** The publisher over the real hub + a spoofed wire; the feed's sends land in [spoke]. */
    private fun publisher(
        wire: Wire,
        hub: LiveHub,
        spoke: MutableList<Pair<String, JsonObject?>>,
        cooldown: Cooldown = Cooldown(readFile = { null }),
        speakAnswer: Long? = boxMessageId,
    ): LivePublisher = LivePublisher(
        hub = hub,
        telegram = LiveTelegram(api = TgApi({ cfg }, engineFactory = wire.factory), cooldown = cooldown),
        config = { cfg },
        channelAlive = { true },
        speak = { text, markup -> spoke += text to markup; speakAnswer },
        finalAnswer = { null },
        clock = { 2_000L },
        verbosity = VolatileVerbosity().apply { set(FeedVerbosity.NORMAL) },
    )

    /** One line of exactly 100 code points — the page split is by construction. */
    private fun lineOf(n: Int): String = ("l$n ").padEnd(100, 'y')

    /** A Bash lane's close with a VERBATIM output (the feed's collapse carrier). */
    private suspend fun bashClose(hub: LiveHub, output: String) {
        hub.apply(
            frame(
                taskId,
                "tool_call_update",
                at = 1_500,
                "status" to JsonPrimitive("pending"),
                "toolId" to JsonPrimitive("t1"),
                "toolName" to JsonPrimitive("Bash"),
                "input" to buildJsonObject { put("command", "ls") },
            ),
            nowMs = 1_500,
        )
        hub.apply(
            frame(
                taskId,
                "tool_call_update",
                at = 1_600,
                "status" to JsonPrimitive("completed"),
                "toolId" to JsonPrimitive("t1"),
                "content" to JsonPrimitive(output),
            ),
            nowMs = 1_600,
        )
    }

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
    private fun buttonObject(text: String, data: String): JsonObject = buildJsonObject {
        put("text", text)
        put("callback_data", data)
    }

    /** The folded box's one door: `📜 Ver salida` → `bp:<id>:0`. */
    private fun boxOpenMarkup(id: Int): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            add(buildJsonArray { add(buttonObject("📜 Ver salida", "bp:$id:0")) })
        }
    }

    /** The opened page's navigator: one numbered button per page (▸ on the shown one) + `🔙 Cerrar`. */
    private fun pagesMarkup(id: Int, index: Int, count: Int): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            (0 until count).toList().chunked(PAGES_PER_ROW).forEach { row ->
                add(
                    buildJsonArray {
                        row.forEach { i ->
                            add(buttonObject(if (i == index) "▸${i + 1}" else "${i + 1}", "bp:$id:$i"))
                        }
                    },
                )
            }
            add(buildJsonArray { add(buttonObject("🔙 Cerrar", "bf:$id")) })
        }
    }

    @Test
    fun theClosedBoxOpensInByteExactPagesAndFoldsBack() = runTest {
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoke = mutableListOf<Pair<String, JsonObject?>>()
        val publisher = publisher(wire, hub, spoke)
        publisher.tick() // the birth — feedSeen snapshots here
        val lines = (1..27).map(::lineOf)
        bashClose(hub, lines.joinToString("\n"))
        publisher.tick() // the feed speaks the collapsed box

        // ---- THE CLOSED BOX: the exact header + its one door, born closed ----
        val box = hub.peek()!!.boxes.single()
        assertEquals(BoxLive.FOLDED, box.page, "a box is born CLOSED")
        assertEquals(2, box.pages.size, "27 lines of 100 cp cut at the 2800-cp budget")
        assertEquals(boxMessageId, box.messageId, "the fold holds the message the send named")
        assertEquals(1, spoke.size)
        val (spokeText, spokeMarkup) = spoke.single()
        assertEquals(
            "⚡ <b>Bash</b> — <code>ls</code> · 27 líneas\n<i>📜 tocar para ver</i>",
            spokeText, "the collapsed header, byte-exact",
        )
        assertEquals(boxOpenMarkup(box.boxId), spokeMarkup, "the one 📜 door carries the fold's own id")

        // ---- THE OPEN: page 1 (lines 1..26) in its exact numbered bytes ----
        assertEquals(null, publisher.open(box.boxId, 0), "a working tap answers silence")
        val opened = lastEdit(wire)
        assertEquals(boxMessageId, opened.messageId, "the edit targets the born message")
        assertEquals(
            "⚡ <b>Bash</b> — <code>ls</code> · 27 líneas\n<i>📜 tocar para ver</i>\n" +
                "<pre><code class=\"language-bash\">" +
                (1..26).joinToString("\n") { n -> "${n.toString().padStart(4)}│ ${lines[n - 1]}" } +
                "</code></pre>",
            opened.text, "page 1: the header + the numbered body, byte-exact",
        )
        assertEquals(pagesMarkup(box.boxId, 0, 2), opened.markup, "▸1 marks the shown page")
        assertEquals(0, hub.peek()!!.boxes.single().page, "the fold mirrors the page the message shows")

        // ---- THE FLIP: page 2 (line 27 alone), ▸2 ----
        assertEquals(null, publisher.open(box.boxId, 1))
        val flipped = lastEdit(wire)
        assertEquals(
            "⚡ <b>Bash</b> — <code>ls</code> · 27 líneas\n<i>📜 tocar para ver</i>\n" +
                "<pre><code class=\"language-bash\">  27│ ${lines[26]}</code></pre>",
            flipped.text, "page 2: line 27 with its REAL number, byte-exact",
        )
        assertEquals(pagesMarkup(box.boxId, 1, 2), flipped.markup, "▸2 marks the shown page")
        assertEquals(1, hub.peek()!!.boxes.single().page)

        // ---- THE CLAMP: an out-of-range page is the last one, same bytes ----
        assertEquals(null, publisher.open(box.boxId, 99))
        val clamped = lastEdit(wire)
        assertEquals(flipped.text, clamped.text, "the tap edge clamps before the render")
        assertEquals(pagesMarkup(box.boxId, 1, 2), clamped.markup)

        // ---- THE FOLD-BACK: the header again + its one door ----
        assertEquals(null, publisher.fold(box.boxId))
        val folded = lastEdit(wire)
        assertEquals(
            "⚡ <b>Bash</b> — <code>ls</code> · 27 líneas\n<i>📜 tocar para ver</i>",
            folded.text, "the fold-back is the exact header, byte-exact",
        )
        assertEquals(boxOpenMarkup(box.boxId), folded.markup)
        assertEquals(BoxLive.FOLDED, hub.peek()!!.boxes.single().page, "the fold mirrors the fold-back")

        // ---- THE MISS: an unknown box answers the honest words, edits NOTHING ----
        val edits = wire.bodies.count { it.second == "editMessageText" }
        assertEquals(BoxTaps.MISS, publisher.open(box.boxId + 999, 0))
        assertEquals(BoxTaps.MISS, publisher.fold(box.boxId + 999))
        assertEquals(edits, wire.bodies.count { it.second == "editMessageText" }, "a miss edits NOTHING")
    }

    @Test
    fun aSealedTurnsBoxStillOpensItsPagesUntilNuclear() = runTest {
        // THE OWNER'S USE (3/11): he taps a paged output AFTER the turn
        // ended. The fold's live state resets at the seal; the MESSAGE (and
        // its button) stays in the chat, so the box must stay readable.
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val publisher = publisher(wire, hub, mutableListOf())
        publisher.tick()
        bashClose(hub, (1..27).joinToString("\n", transform = ::lineOf))
        publisher.tick()
        val box = hub.peek()!!.boxes.single()

        // the seal: the live world dies, the chat's box does not
        hub.clear(taskId)
        assertEquals(null, hub.peek(), "the live state is gone — the seal's own law")
        val edits = wire.bodies.count { it.second == "editMessageText" }
        assertEquals(null, publisher.open(box.boxId, 0), "the sealed box still opens — no miss")
        assertEquals(edits + 1, wire.bodies.count { it.second == "editMessageText" })
        assertEquals(box.messageId, lastEdit(wire).messageId, "it edits the SAME message")

        // ☢️ nuclear: nothing survives it
        hub.clear()
        assertEquals(BoxTaps.MISS, publisher.open(box.boxId, 0))
    }

    @Test
    fun aThrottledTapFlipsNothingInTheFold() = runTest {
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        var nowSec = 2.0
        val cooldown = Cooldown(now = { nowSec }, readFile = { null })
        val publisher = publisher(wire, hub, mutableListOf(), cooldown = cooldown)
        publisher.tick()
        bashClose(hub, (1..27).joinToString("\n", transform = ::lineOf))
        publisher.tick()
        val box = hub.peek()!!.boxes.single()
        cooldown.extend(raSeconds = 60) // Telegram named the window
        val edits = wire.bodies.count { it.second == "editMessageText" }
        assertEquals(null, publisher.open(box.boxId, 1), "the tap answers no miss — the edit just could not fly")
        assertEquals(edits, wire.bodies.count { it.second == "editMessageText" }, "a throttled tap edits NOTHING")
        assertEquals(BoxLive.FOLDED, hub.peek()!!.boxes.single().page, "and the fold keeps its truth — nothing flipped")
        // the window lifts: the SAME tap lands and the fold mirrors it
        nowSec = 5_000.0
        assertEquals(null, publisher.open(box.boxId, 1))
        assertEquals(1, hub.peek()!!.boxes.single().page)
    }

    @Test
    fun aBoxWhoseSendNeverFlewFoldsNothing() = runTest {
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val publisher = publisher(wire, hub, mutableListOf(), speakAnswer = null)
        publisher.tick()
        bashClose(hub, (1..27).joinToString("\n", transform = ::lineOf))
        publisher.tick()
        assertTrue(
            hub.peek()!!.boxes.isEmpty(),
            "a send that never flew folds NOTHING — there is no message to edit, the tap would be an honest miss",
        )
    }

    private companion object {
        /** Keyboards.PAGES_PER_ROW, pinned independently (the golden's own arithmetic). */
        const val PAGES_PER_ROW = 5
    }
}
