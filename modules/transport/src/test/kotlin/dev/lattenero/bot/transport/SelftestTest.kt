package dev.lattenero.bot.transport

import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B2a's boot proof: ONE read-only getMe through the real transport —
 * these tests pin that it is exactly that (the method on the wire is
 * getMe, never a send) and that no failure can break a boot.
 */
class SelftestTest {

    private var url: String? = null

    private suspend fun selftest(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) ->
        HttpResponseData,
    ): Selftest.Result {
        val api = TgApi({ TgConfig(token = "T") }, engineFactory = mockEngineFactory { req ->
            url = req.url.toString()
            handler(req)
        })
        return Selftest(api).run()
    }

    @Test
    fun answersOkWithTheBotIdentity() = runTest {
        val r = selftest {
            respond("""{"ok": true, "result": {"id": 123456, "username": "zcode_bot"}}""", HttpStatusCode.OK)
        }
        assertTrue(r.ok)
        assertEquals(123456L, r.botId)
        assertEquals("zcode_bot", r.username)
        assertNull(r.error)
        assertEquals("https://api.telegram.org/botT/getMe", url, "read-only: getMe, nothing else")
    }

    @Test
    fun anAnswerWithoutAnIdIsAFailure() = runTest {
        val r = selftest { respond("""{"ok": true, "result": {}}""", HttpStatusCode.OK) }
        assertFalse(r.ok)
        assertEquals("getMe answered without a bot id", r.error)
    }

    @Test
    fun anHttpFailureIsAResultNotAnException() = runTest {
        val r = selftest { respond("""{"ok": false, "description": "Not Found"}""", HttpStatusCode.NotFound) }
        assertFalse(r.ok)
        assertEquals("HTTP 404: Not Found", r.error)
    }

    @Test
    fun aTransportFailureIsAResultNotAnException() = runTest {
        val r = selftest { throw IllegalStateException("no route to host") }
        assertFalse(r.ok)
        assertEquals("no route to host", r.error)
    }
}
