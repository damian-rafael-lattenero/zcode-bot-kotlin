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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
        finalTurn = { null },
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
    private data class Edit(val messageId: Long, val text: String, val markup: JsonObject?)

    /**
     * The rich envelope folds line breaks into `<br>` outside pre/code; the
     * tests pin the LOGICAL html, so the inverse puts the newlines back.
     */
    private fun lastEdit(wire: Wire): Edit {
        val body = Json.parseToJsonElement(wire.bodies.last { it.second == "editMessageText" }.first).jsonObject
        // a doorless edit carries NO reply_markup (26/11: the box wears images only)
        val markup = (body["reply_markup"] as? JsonPrimitive)?.content
        val rich = ((body["rich_message"] as? JsonObject)?.get("html") as? JsonPrimitive)?.content
        return Edit(
            messageId = body["message_id"]!!.jsonPrimitive.content.toLong(),
            text = rich?.replace("<br>", "\n") ?: body["text"]!!.jsonPrimitive.content,
            markup = markup?.let { Json.parseToJsonElement(it).jsonObject },
        )
    }

    @Test
    fun theOutputsAreImagesOnlyNoTextDoorIsBornOrAnswered() = runTest {
        // THE OWNER, 26/11: the outputs are images and nothing that is not an
        // image is shown. A box is born with NO keyboard, an old «Ver salida»
        // tap answers the honest words and edits nothing, and the way back
        // folds to the header — still doorless.
        val hub = LiveHub()
        hub.apply(frame(taskId, "task_run_started", at = 1_000), nowMs = 1_000)
        hub.apply(frame(taskId, "agent_thought_chunk", at = 1_050, "content" to JsonPrimitive("arranco")), nowMs = 1_050)
        val wire = Wire()
        val spoke = mutableListOf<Pair<String, JsonObject?>>()
        val publisher = publisher(wire, hub, spoke)
        publisher.tick() // the birth — feedSeen snapshots here
        bashClose(hub, (1..27).joinToString("\n", transform = ::lineOf))
        publisher.tick() // the feed speaks the collapsed box

        val box = hub.peek()!!.boxes.single()
        assertEquals(BoxLive.FOLDED, box.page, "a box is born CLOSED")
        assertEquals(2, box.pages.size, "27 lines of 100 cp cut at the 2800-cp budget")
        assertEquals(boxMessageId, box.messageId, "the fold holds the message the send named")
        assertEquals(1, spoke.size)
        val (spokeText, spokeMarkup) = spoke.single()
        assertEquals(
            "⚡ <b>Nushell</b> · 27 líneas\n<i>📸 tocar para ver la imagen</i>\n" +
                "<pre><code class=\"language-nu\">ls</code></pre>",
            spokeText,
            "the collapsed header, byte-exact — its hint names the image",
        )
        assertNull(spokeMarkup, "no keyboard rides a box: nothing that is not an image")

        // an old text door answers the honest words and edits NOTHING
        val edits = wire.bodies.count { it.second == "editMessageText" }
        assertTrue(publisher.open(box.boxId, 0)!!.contains("📸"), "the retired door speaks")
        assertEquals(edits, wire.bodies.count { it.second == "editMessageText" }, "and edits nothing")

        // the way back: the exact header, and it regains no door
        assertEquals(null, publisher.fold(box.boxId))
        val folded = lastEdit(wire)
        assertEquals(
            "⚡ <b>Nushell</b> · 27 líneas\n<i>📸 tocar para ver la imagen</i>\n" +
                "<pre><code class=\"language-nu\">ls</code></pre>",
            folded.text,
            "the fold-back is the exact header, byte-exact",
        )
        assertNull(folded.markup, "and it regains no door")
        assertEquals(BoxLive.FOLDED, hub.peek()!!.boxes.single().page, "the fold mirrors the fold-back")

        // ☢️ nuclear: nothing survives it
        hub.clear()
        assertEquals(BoxTaps.MISS, publisher.fold(box.boxId))
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

}
