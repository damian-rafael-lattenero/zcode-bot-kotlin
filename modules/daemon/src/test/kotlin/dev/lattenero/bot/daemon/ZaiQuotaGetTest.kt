package dev.lattenero.bot.daemon

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A MockEngine dressed as the factory zaiQuotaGet takes (transport's TestEngines shape). */
private fun mockEngineFactory(handler: MockRequestHandler): HttpClientEngineFactory<MockEngineConfig> =
    object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine =
            MockEngine(MockEngineConfig().apply(block).apply { addHandler(handler) })
    }

/**
 * /quota's direct HTTP leg: the Bearer GET carries the key in the HEADER
 * (never a URL), the JSON Accept rides along, and every failure speaks a
 * message that could never log the key (the redaction law — fake keys
 * only here; the real endpoint is never hit).
 */
class ZaiQuotaGetTest {

    private val url = "https://api.z.ai/api/monitor/usage/quota/limit"

    @Test
    fun theBearerGetCarriesTheHeadersAndReturnsTheBody() = runTest {
        var seenAuth: String? = null
        var seenAccept: String? = null
        var seenUrl: String? = null
        val engine = mockEngineFactory { request ->
            seenAuth = request.headers[HttpHeaders.Authorization]
            seenAccept = request.headers[HttpHeaders.Accept]
            seenUrl = request.url.toString()
            respondOk("""{"data":{"level":"PRO","limits":[]}}""")
        }
        val body = zaiQuotaGet(url, "sk-fake-test-key", engine)
        assertEquals("Bearer sk-fake-test-key", seenAuth, "the key rides the Authorization header")
        assertEquals("application/json", seenAccept)
        assertEquals(url, seenUrl)
        assertTrue(body.startsWith("""{"data"""), "the body comes home raw for the pure parser")
    }

    @Test
    fun anHttpRefusalThrowsWithoutEchoingTheBody() = runTest {
        val engine = mockEngineFactory { _ ->
            respondError(HttpStatusCode.InternalServerError, "boom with sk-fake-test-key inside")
        }
        val e = assertFailsWith<IOException> {
            zaiQuotaGet(url, "sk-fake-test-key", engine)
        }
        assertEquals("z.ai quota HTTP 500", e.message, "the status only — no body, no key")
    }

    @Test
    fun aDeadTransportThrowsAMessageWithoutTheKey() = runTest {
        val engine = mockEngineFactory { _ -> throw java.net.ConnectException("refused with sk-fake-test-key") }
        val e = assertFailsWith<IOException> {
            zaiQuotaGet(url, "sk-fake-test-key", engine)
        }
        assertTrue(
            Regex("z\\.ai quota fetch failed: \\w+").matches(e.message ?: ""),
            "the class name is the only witness: «${e.message}»",
        )
        assertTrue(!e.message!!.contains("sk-fake"), "the key never reaches the message")
    }
}
