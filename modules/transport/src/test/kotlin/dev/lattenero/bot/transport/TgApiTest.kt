package dev.lattenero.bot.transport

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * tg_api + _retry_after + _message_id (utils/http.py), pinned at the
 * wire: one POST per method, JSON in, JSON out — and the token can
 * NEVER reach an exception message that a log could keep.
 */
class TgApiTest {

    private val cfg = TgConfig(token = "TOKEN123", chatId = JsonPrimitive(42))
    private val seen = mutableListOf<HttpRequestData>()

    private fun api(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) ->
        HttpResponseData,
    ): TgApi = TgApi({ cfg }, engineFactory = mockEngineFactory { req ->
        seen.add(req)
        handler(req)
    })

    @Test
    fun postsTheParamsToTheBotUrlAsJson() = runTest {
        val api = api { respond("""{"ok": true, "result": {"message_id": 42}}""", HttpStatusCode.OK) }
        val r = api.call("sendMessage", buildJsonObject { put("chat_id", 42); put("text", "hola") })

        val req = seen.single()
        assertEquals("https://api.telegram.org/botTOKEN123/sendMessage", req.url.toString())
        assertEquals(ContentType.Application.Json, (req.body as TextContent).contentType)
        // kotlinx's compact encoding is what goes on the wire (Telegram
        // parses the JSON — Python's json.dumps spacing is not the contract)
        assertEquals("""{"chat_id":42,"text":"hola"}""", (req.body as TextContent).text)
        assertEquals(JsonPrimitive(true), (r as JsonObject)["ok"])
        assertEquals(JsonPrimitive(42), ((r as JsonObject)["result"] as JsonObject)["message_id"])
    }

    @Test
    fun a429CarriesTheServersRetryAfter() = runTest {
        val api = api {
            respond(
                """{"ok": false, "error_code": 429, "description": "Too Many Requests: retry after 7",""" +
                    """ "parameters": {"retry_after": 7}}""",
                HttpStatusCode.TooManyRequests,
            )
        }
        val e = assertFailsWith<TgHttpException> { api.call("sendMessage") }
        assertEquals(429, e.code)
        assertEquals(7, e.retryAfter)
        assertEquals("HTTP 429: Too Many Requests: retry after 7", e.message)
    }

    @Test
    fun a429WithoutAReadableBodyGetsTheConservativeFive() = runTest {
        val api = api { respond("gateway garbage", HttpStatusCode.TooManyRequests) }
        val e = assertFailsWith<TgHttpException> { api.call("sendMessage") }
        assertEquals(5, e.retryAfter, "unreadable 429 body → the conservative default, never below 1")
        assertEquals("HTTP 429: request failed", e.message)
    }

    @Test
    fun a429AskingForZeroStillWaitsOneSecond() = runTest {
        val api = api {
            respond(
                """{"ok": false, "description": "Too Many", "parameters": {"retry_after": 0}}""",
                HttpStatusCode.TooManyRequests,
            )
        }
        assertEquals(1, assertFailsWith<TgHttpException> { api.call("sendMessage") }.retryAfter)
    }

    @Test
    fun a4xxIsNotAThrottleAndCarriesItsDescription() = runTest {
        val api = api { respond("""{"ok": false, "description": "Bad Request: unclosed tag"}""", HttpStatusCode.BadRequest) }
        val e = assertFailsWith<TgHttpException> { api.call("sendMessage") }
        assertNull(e.retryAfter, "only a 429 (or its text) names a window")
        assertNull(retryAfterOf(e))
        assertEquals("HTTP 400: Bad Request: unclosed tag", e.message)
    }

    @Test
    fun theTooManyTextGateIsPythonsStringCheck() = runTest {
        // Python gates on "429"/"Too Many" in str(exc) — a 500 whose
        // description says it still carries the demand
        val api = api {
            respond(
                """{"ok": false, "description": "Too Many Requests", "parameters": {"retry_after": 3}}""",
                HttpStatusCode.InternalServerError,
            )
        }
        assertEquals(3, assertFailsWith<TgHttpException> { api.call("sendMessage") }.retryAfter)
    }

    @Test
    fun a5xxHasNoRetryAfter() = runTest {
        val api = api { respond("oops", HttpStatusCode.InternalServerError) }
        val e = assertFailsWith<TgHttpException> { api.call("sendMessage") }
        assertNull(e.retryAfter)
        assertTrue(SendPolicy.tgRetryable(e), "5xx is retryable — the table applies")
    }

    @Test
    fun transportFailuresAreRedactedBeforeTheyCanReachALog() = runTest {
        val api = api { throw IllegalStateException("connect failed: https://api.telegram.org/botTOKEN123/sendMessage") }
        val e = assertFailsWith<IOException> { api.call("sendMessage") }
        assertContains(e.message ?: "", "***")
        assertFalse((e.message ?: "").contains("TOKEN123"), "the token NEVER reaches an exception message")
    }

    @Test
    fun messageIdOfFollowsPythonsIntButNotBoolGate() {
        fun r(json: String): kotlinx.serialization.json.JsonElement = Json.parseToJsonElement(json)
        assertEquals(42L, messageIdOf(r("""{"result": {"message_id": 42}}""")))
        assertNull(messageIdOf(r("""{"result": {"message_id": true}}""")), "bool is a subclass of int in Python — True is NOT an id")
        assertNull(messageIdOf(r("""{"result": {}}""")))
        assertNull(messageIdOf(r("""{"ok": true}""")))
        assertNull(messageIdOf(r("""{"result": {"message_id": "42"}}""")), "a string id is not an id")
        assertNull(messageIdOf(null), "Python's AttributeError path → None")
    }

    @Test
    fun retryAfterOfOnlyAnswersForTgHttpExceptions() {
        assertNull(retryAfterOf(IOException("Too Many Requests 429")))
        val e = TgHttpException(429, """{"parameters": {"retry_after": 9}}""")
        assertEquals(9, retryAfterOf(e))
        // memoized at construction: every reader sees the same truth
        assertEquals(9, e.retryAfter)
        assertEquals(9, e.retryAfter)
        assertIs<TgHttpException>(e)
    }
}
