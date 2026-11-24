package dev.lattenero.bot.channel

import dev.lattenero.bot.transport.Cooldown
import dev.lattenero.bot.transport.TgApi
import dev.lattenero.bot.transport.TgConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rich-HTML legs: the wire they post, the cooldown gate, and the
 * best-effort contract (a rejection answers null/false — the CALLER keeps
 * its own HTML fallback; no dialect is ever laundered into the other).
 */
class LiveTelegramRichTest {

    private val cfg = TgConfig(token = "T", chatId = JsonPrimitive(42))
    private val seen = mutableListOf<HttpRequestData>()

    /** The transport test module's helper, local: MockEngine dressed as the factory TgApi takes. */
    private fun engine(handler: MockRequestHandler): HttpClientEngineFactory<MockEngineConfig> =
        object : HttpClientEngineFactory<MockEngineConfig> {
            override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine =
                MockEngine(MockEngineConfig().apply(block).apply { addHandler(handler) })
        }

    private fun telegram(status: HttpStatusCode, body: String): LiveTelegram {
        val api = TgApi({ cfg }, engineFactory = engine { req ->
            seen.add(req)
            respond(body, status)
        })
        return LiveTelegram(api, Cooldown(readFile = { null }))
    }

    private fun bodyOf(req: HttpRequestData): JsonObject =
        Json.parseToJsonElement((req.body as TextContent).text).jsonObject

    @Test
    fun theRichSendPostsHtmlAndAnswersTheMessageId() = runTest {
        val tg = telegram(HttpStatusCode.OK, """{"ok":true,"result":{"message_id":99}}""")
        val mid = tg.sendRichHtml(cfg, "uno\ndos", loud = false)
        assertEquals(99L, mid)
        val req = seen.single()
        assertTrue(req.url.toString().endsWith("/sendRichMessage"), req.url.toString())
        val rich = bodyOf(req)["rich_message"]!!.jsonObject
        assertEquals("uno<br>dos", rich["html"]!!.jsonPrimitive.content)
        assertEquals("true", bodyOf(req)["disable_notification"]!!.jsonPrimitive.content)
    }

    @Test
    fun aRejectedRichSendAnswersNullSoTheCallerFallsBack() = runTest {
        val tg = telegram(HttpStatusCode.BadRequest, """{"ok":false,"error_code":400,"description":"bad"}""")
        assertNull(tg.sendRichHtml(cfg, "x"), "a rejection is null, never a silent second dialect")
    }

    @Test
    fun theRichEditPostsTheMessageIdAndTheRichField() = runTest {
        val tg = telegram(HttpStatusCode.OK, """{"ok":true,"result":true}""")
        assertTrue(tg.editRichHtml(cfg, 7, "<b>x</b>"))
        val req = seen.single()
        assertTrue(req.url.toString().endsWith("/editMessageText"))
        val wire = bodyOf(req)
        assertEquals("7", wire["message_id"]!!.jsonPrimitive.content)
        assertEquals("<b>x</b>", wire["rich_message"]!!.jsonObject["html"]!!.jsonPrimitive.content)
        assertFalse("text" in wire)
    }

    @Test
    fun a429WidensTheSharedWindowAndTheEditFailsHonestly() = runTest {
        val cooldown = Cooldown(readFile = { null })
        val api = TgApi({ cfg }, engineFactory = engine {
            respond(
                """{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 7",""" +
                    """"parameters":{"retry_after":7}}""",
                HttpStatusCode.TooManyRequests,
            )
        })
        val tg = LiveTelegram(api, cooldown)
        assertFalse(tg.editRichHtml(cfg, 7, "x"))
        assertTrue(cooldown.active(), "the 429 widened the window")
    }

    @Test
    fun theCooldownGatesTheRichSendButNeverACriticalEdit() = runTest {
        val cooldown = Cooldown(readFile = { null })
        val tg = LiveTelegram(
            TgApi({ cfg }, engineFactory = engine { respond("""{"ok":true,"result":true}""", HttpStatusCode.OK) }),
            cooldown,
        )
        cooldown.extend(60)
        assertNull(tg.sendRichHtml(cfg, "x"), "a spent window births nothing")
        assertTrue(tg.editRichHtml(cfg, 7, "x", critical = true), "a critical edit outranks the window")
    }
}
