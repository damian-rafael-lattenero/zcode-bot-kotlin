package dev.lattenero.bot.daemon.brain

import dev.lattenero.bot.channel.BrainReply
import dev.lattenero.bot.daemon.metrics.MicrometerMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ConnectException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The brain's HTTP leg: the exact paths, the POST {query,k} body, and the
 * two-classified failures the commands render (📡 down, ⏱️ timeout).
 */
class BrainFetchTest {

    private fun mockFactory(handler: MockRequestHandler): HttpClientEngineFactory<MockEngineConfig> =
        object : HttpClientEngineFactory<MockEngineConfig> {
            override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine =
                MockEngine(MockEngineConfig().apply(block).apply { addHandler(handler) })
        }

    @Test
    fun aGetAnswerComesBackWithItsStatus() = runBlocking {
        val reply = brainCall(
            baseUrl = "http://127.0.0.1:8765",
            method = HttpMethod.Get,
            path = "/rag/stats",
            engine = mockFactory { request ->
                assertEquals("/rag/stats", request.url.encodedPath)
                assertEquals(HttpMethod.Get, request.method)
                respond("""{"documents":1,"chunks":2,"bySource":{}}""", HttpStatusCode.OK)
            },
        )
        assertEquals(BrainReply.Response(200, """{"documents":1,"chunks":2,"bySource":{}}"""), reply)
    }

    @Test
    fun aNon2xxIsAFullAnswerNotADown() = runBlocking {
        val reply = brainCall(
            baseUrl = "http://127.0.0.1:8765",
            method = HttpMethod.Post,
            path = "/rag/query",
            engine = mockFactory { respond("""{"error":"k must be between 1 and 50"}""", HttpStatusCode.BadRequest) },
        )
        assertTrue(reply is BrainReply.Response)
        assertEquals(400, (reply as BrainReply.Response).status)
        assertTrue("k must be" in reply.body)
    }

    @Test
    fun thePostBodyIsTheQueryAndK() = runBlocking {
        var seen = ""
        brainCall(
            baseUrl = "http://127.0.0.1:8765/", // the trailing slash is normalized
            method = HttpMethod.Post,
            path = "/memory/search",
            body = Json.parseToJsonElement("""{"query":"ñandú 🧠","k":3}""").jsonObject,
            engine = mockFactory { request ->
                assertEquals("/memory/search", request.url.encodedPath)
                seen = (request.body as TextContent).text
                respond("""{"memories":[]}""", HttpStatusCode.OK)
            },
        )
        val body = Json.parseToJsonElement(seen).jsonObject
        assertEquals("ñandú 🧠", body["query"]!!.jsonPrimitive.content)
        assertEquals("3", body["k"]!!.jsonPrimitive.content)
    }

    @Test
    fun aRefusedConnectionIsDown() = runBlocking {
        val reply = brainCall(
            baseUrl = "http://127.0.0.1:8765",
            method = HttpMethod.Get,
            path = "/bus/status",
            engine = mockFactory { throw ConnectException("Connection refused") },
        )
        assertTrue(reply is BrainReply.Down)
        assertTrue((reply as BrainReply.Down).detail!!.contains("ConnectException"))
    }

    @Test
    fun aSlowBrainIsATimeoutNotADown() = runBlocking {
        val reply = brainCall(
            baseUrl = "http://127.0.0.1:8765",
            method = HttpMethod.Get,
            path = "/rag/stats",
            timeout = 100.milliseconds,
            engine = mockFactory {
                delay(2_000)
                respond("late", HttpStatusCode.OK)
            },
        )
        assertEquals(BrainReply.Timeout, reply)
    }

    @Test
    fun theEndpointReadsTheKnobFile() {
        val dir = Files.createTempDirectory("brain-endpoint").toFile()
        assertEquals(BrainEndpoint.DEFAULT_URL, BrainEndpoint.load(File(dir, "daemon.json")).url)
        assertEquals(null, BrainEndpoint.load(File(dir, "daemon.json")).warning)

        val file = File(dir, "daemon.json")
        file.writeText("""{"send":"live","brain_url":"http://127.0.0.1:9999/"}""")
        assertEquals("http://127.0.0.1:9999", BrainEndpoint.load(file).url)

        file.writeText("""{"brain_url":42}""")
        val bad = BrainEndpoint.load(file)
        assertEquals(BrainEndpoint.DEFAULT_URL, bad.url)
        assertTrue(bad.warning!!.contains("not a URL"))

        file.writeText("not json")
        val garbage = BrainEndpoint.load(file)
        assertEquals(BrainEndpoint.DEFAULT_URL, garbage.url)
        assertTrue(garbage.warning!!.contains("unreadable"))
    }

    /** E2's taps: EVERY request's latency on the timer; non-2xx, timeout and down counted as failures. */
    @Test
    fun theMetricsTapTimesTheRequestsAndCountsTheFailures() = runBlocking {
        val reg = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val m = MicrometerMetrics(reg)
        brainCall(
            baseUrl = "http://127.0.0.1:8765", method = HttpMethod.Get, path = "/rag/stats",
            engine = mockFactory { respond("{}", HttpStatusCode.OK) }, metrics = m,
        )
        brainCall(
            baseUrl = "http://127.0.0.1:8765", method = HttpMethod.Get, path = "/bus/status",
            engine = mockFactory { respond("{}", HttpStatusCode.InternalServerError) }, metrics = m,
        )
        brainCall(
            baseUrl = "http://127.0.0.1:8765", method = HttpMethod.Get, path = "/memory/status",
            engine = mockFactory { throw ConnectException("Connection refused") }, metrics = m,
        )
        val scrape = reg.scrape()
        assertEquals(3.0, valueOf(scrape, "bot_brain_request_seconds_count"))
        assertEquals(2.0, valueOf(scrape, "bot_brain_failures_total"), "500 and refused; the 200 was fine")
    }

    private fun valueOf(scrape: String, metric: String): Double? =
        scrape.lineSequence()
            .firstOrNull { it.startsWith(metric) }
            ?.substringAfterLast(' ')
            ?.toDoubleOrNull()
}
