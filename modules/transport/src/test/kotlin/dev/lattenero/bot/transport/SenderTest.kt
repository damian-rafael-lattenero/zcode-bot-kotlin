package dev.lattenero.bot.transport

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The send path (utils/http.py send/outbox_flush), exact: cooldown
 * pre-check → breaker around the (0,1,3) retry → tg-last-ph on success;
 * every failure lands in the outbox. The TgApi is a MockEngine — ZERO
 * real network — and what the tests pin is the ORDER of the gates and
 * the params on the wire.
 */
class SenderTest {

    @TempDir
    lateinit var dir: File

    private var clock = 1000.0
    private val cfg = TgConfig(token = "T0K", chatId = JsonPrimitive(555))
    private val requests = mutableListOf<HttpRequestData>()
    private var behavior: suspend MockRequestHandleScope.(Int) -> HttpResponseData = {
        respond("""{"ok": true, "result": {"message_id": 777}}""")
    }

    private val cooldownFile: File get() = File(dir, "tg-cooldown")
    private val outboxFile: File get() = File(dir, "outbox.json")
    private val lastPhFile: File get() = File(dir, "tg-last-ph")

    private fun sender(): Sender {
        val api = TgApi({ cfg }, engineFactory = mockEngineFactory { req ->
            requests.add(req)
            behavior(requests.size)
        })
        return Sender(
            api,
            Cooldown(readFile = { cooldownFile }, writeFile = { cooldownFile }),
            Outbox(outboxFile),
            LastPh({ lastPhFile }),
            TgBreaker(time = { clock }),
        )
    }

    private fun body(n: Int): JsonObject =
        Json.parseToJsonElement((requests[n - 1].body as TextContent).text).jsonObject

    private fun parkedTexts(): List<String> =
        Json.parseToJsonElement(outboxFile.readText())
            .let { it as kotlinx.serialization.json.JsonArray }
            .map { (it as JsonObject)["text"]!!.jsonPrimitive.content }

    @Test
    fun notConfiguredNeverTouchesTheWire() = runTest {
        val r = sender().send(TgConfig(token = "", chatId = JsonPrimitive(1)), "x")
        assertIs<SendResult.NotConfigured>(r)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun theCooldownWindowParksWithoutBurningAnAttempt() = runTest {
        cooldownFile.createNewFile() // setLastModified on a missing file is a no-op
        cooldownFile.setLastModified(System.currentTimeMillis() + 30_000)
        val r = sender().send(cfg, "throttled")
        assertIs<SendResult.Parked>(r)
        assertTrue(requests.isEmpty(), "F83: parking beats burning attempts inside the window")
        assertEquals(listOf("throttled"), parkedTexts())
    }

    @Test
    fun theHappyPathSendsTheExactParamsAndNotesThePh() = runTest {
        val r = sender().send(cfg, "hola <b>mundo</b>")
        assertIs<SendResult.Sent>(r)
        assertEquals(777L, r.messageId)
        val b = body(1)
        assertEquals("https://api.telegram.org/botT0K/sendMessage", requests[0].url.toString())
        assertEquals(JsonPrimitive(555), b["chat_id"], "chat_id passes through with its file type")
        assertEquals("hola <b>mundo</b>", b["text"]!!.jsonPrimitive.content)
        assertEquals("HTML", b["parse_mode"]!!.jsonPrimitive.content)
        assertEquals(false, b["disable_notification"]!!.jsonPrimitive.content.toBoolean(), "loud_all=true default")
        assertEquals("777", lastPhFile.readText(), "F84b: the ll floats under the newest")
    }

    @Test
    fun loudIsTheNotificationGate() = runTest {
        sender().send(cfg, "a", loud = false)
        assertEquals(true, body(1)["disable_notification"]!!.jsonPrimitive.content.toBoolean())
        requests.clear()
        sender().send(cfg.copy(loudAll = false), "b") // loud=null → follows loud_all
        assertEquals(true, body(1)["disable_notification"]!!.jsonPrimitive.content.toBoolean())
        requests.clear()
        sender().send(cfg.copy(loudAll = false), "c", loud = true)
        assertEquals(false, body(1)["disable_notification"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun markupRidesAsAJsonStringExactlyLikePythonsDumps() = runTest {
        val markup = JsonObject(
            linkedMapOf(
                "inline_keyboard" to kotlinx.serialization.json.JsonArray(
                    listOf(
                        kotlinx.serialization.json.JsonArray(
                            listOf(
                                JsonObject(
                                    linkedMapOf("text" to JsonPrimitive("a"), "callback_data" to JsonPrimitive("x")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        sender().send(cfg, "botones", markup = markup)
        assertEquals(
            """{"inline_keyboard": [[{"text": "a", "callback_data": "x"}]]}""",
            body(1)["reply_markup"]!!.jsonPrimitive.content,
            "params['reply_markup'] = json.dumps(markup) — a STRING inside the params",
        )
    }

    @Test
    fun overflowTextIsHtmlTruncatedToChReply() = runTest {
        sender().send(cfg, "x".repeat(5000))
        assertEquals(CH_REPLY, body(1)["text"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun oneNetworkResetRetriesOnTheTableAndSends() = runTest {
        behavior = { n ->
            if (n == 1) throw IOException("connection reset") else respond("""{"result": {"message_id": 42}}""")
        }
        val r = sender().send(cfg, "after a reset")
        assertIs<SendResult.Sent>(r)
        assertEquals(42L, r.messageId)
        assertEquals(2, requests.size, "backoff 1 s, then the second attempt")
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun exhaustedRetriesParkToTheOutbox() = runTest {
        behavior = { throw IOException("network down") }
        val r = sender().send(cfg, "the last word")
        assertIs<SendResult.Parked>(r)
        assertEquals(3, requests.size, "the (0,1,3) table: three attempts, no more")
        assertEquals(4_000L, testScheduler.currentTime)
        assertEquals(listOf("the last word"), parkedTexts())
    }

    @Test
    fun a429SetsTheSharedCooldownAndParksImmediately() = runTest {
        behavior = {
            respond(
                """{"ok": false, "description": "Too Many Requests", "parameters": {"retry_after": 7}}""",
                HttpStatusCode.TooManyRequests,
            )
        }
        val r = sender().send(cfg, "throttled away")
        assertIs<SendResult.Parked>(r)
        assertEquals(1, requests.size, "a 429 never retries — the outer catch extends the cooldown")
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(
            cooldownFile.lastModified() >= System.currentTimeMillis() + 6_000,
            "the shared window now holds the server's 7 s demand",
        )
        assertEquals(listOf("throttled away"), parkedTexts())
    }

    @Test
    fun a4xxFailsFastToTheOutbox() = runTest {
        behavior = { respond("""{"ok": false, "description": "Bad Request"}""", HttpStatusCode.BadRequest) }
        val r = sender().send(cfg, "bad one")
        assertIs<SendResult.Parked>(r)
        assertEquals(1, requests.size, "retrying a bad request is hammering with extra steps")
        assertEquals(listOf("bad one"), parkedTexts())
    }

    @Test
    fun aMissingMessageIdIsStillASendButNotesNoPh() = runTest {
        behavior = { respond("""{"ok": true}""") }
        val r = sender().send(cfg, "no id came back")
        assertIs<SendResult.Sent>(r)
        assertEquals(null, r.messageId, "Python's bare True — truthy, but nothing to float under")
        assertFalse(lastPhFile.exists())
    }

    @Test
    fun theBreakerOpensAfterFiveFailedSendsAndParksWithoutAsking() = runTest {
        behavior = { throw IOException("telegram is gone") }
        val s = sender()
        repeat(5) { assertIs<SendResult.Parked>(s.send(cfg, "fail $it")) }
        assertEquals("OPEN", s.breakerState)
        assertEquals(15, requests.size, "5 sends x 3 attempts")
        assertIs<SendResult.Parked>(s.send(cfg, "breaker says no"))
        assertEquals(15, requests.size, "the OPEN breaker rejects before the wire — to the outbox")
        assertEquals(6, parkedTexts().size)

        clock += TgBreaker.BREAKER_OPEN_S // the open window passes
        behavior = { respond("""{"result": {"message_id": 9}}""") }
        assertIs<SendResult.Sent>(s.send(cfg, "the probe"))
        assertEquals("HALF_OPEN", s.breakerState, "one clean send is not two")
        assertIs<SendResult.Sent>(s.send(cfg, "closed again"))
        assertEquals("CLOSED", s.breakerState)
    }

    @Test
    fun outboxFlushSendsThePileQuietly() = runTest {
        behavior = { throw IOException("down") }
        val s = sender()
        s.send(cfg, "primera")
        s.send(cfg, "segunda")
        assertEquals(2, parkedTexts().size)

        behavior = { respond("""{"result": {"message_id": 100}}""") }
        val ok = s.outboxFlush(cfg)
        assertEquals(2, ok)
        assertEquals("[]", outboxFile.readText(), "the box drained")
        assertEquals(true, body(7)["disable_notification"]!!.jsonPrimitive.content.toBoolean(), "flush sends loud=false")
        assertEquals("100", lastPhFile.readText())
    }

    @Test
    fun outboxFlushPutsTheFailedOnesBackAtTheFront() = runTest {
        behavior = { throw IOException("down") }
        val s = sender()
        s.send(cfg, "primera")
        s.send(cfg, "segunda")

        // the flush's first send fails fast (4xx), the second goes out
        behavior = { n ->
            if (n == 7) respond("""{"ok": false, "description": "Bad Request"}""", HttpStatusCode.BadRequest)
            else respond("""{"result": {"message_id": 101}}""")
        }
        val ok = s.outboxFlush(cfg)
        assertEquals(1, ok)
        assertEquals(listOf("primera"), parkedTexts(), "the failed one is back, at the FRONT, alone")
    }
}
