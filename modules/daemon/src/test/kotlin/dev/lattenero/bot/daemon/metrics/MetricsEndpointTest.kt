package dev.lattenero.bot.daemon.metrics

import com.sun.net.httpserver.HttpServer
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The /metrics listener: loopback only, GET-only, the registry's text
 * exposition with the registered families — and the OFF contract: with
 * the knob off the stack hands out NO server at all (no socket is ever
 * opened).
 */
class MetricsEndpointTest {

    private fun get(server: HttpServer, path: String, method: String = "GET"): Triple<Int, String, String?> {
        val conn = URL("http://127.0.0.1:${server.address.port}$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        val code = conn.responseCode
        val err = conn.errorStream
        val body = when {
            err != null -> err.readBytes().toString(Charsets.UTF_8)
            code in HTTP_OK..HTTP_NO_CONTENT_MAX -> conn.inputStream.readBytes().toString(Charsets.UTF_8)
            else -> ""
        }
        val contentType = conn.contentType
        conn.disconnect()
        return Triple(code, body, contentType)
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_NO_CONTENT_MAX = 299
    }

    @Test
    fun theScrapeServesTheRegisteredFamiliesOnLoopback() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        MicrometerMetrics(registry).apply {
            turnStarted()
            busGauges(written = { 7 }, failures = { 0 })
            outboxDepth(Lane.MAIN) { 5 }
        }
        val server = MetricsEndpoint.start(registry, 0)
        try {
            assertTrue(server.address.address.isLoopbackAddress, "the exposition never leaves the machine")
            val (code, body, contentType) = get(server, "/metrics")
            assertEquals(200, code)
            assertTrue("bot_turns_started_total 1.0" in body)
            assertTrue("bot_bus_written 7.0" in body)
            assertTrue("""bot_outbox_depth{lane="main"} 5.0""" in body)
            assertEquals(MetricsEndpoint.CONTENT_TYPE, contentType)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aNonGetMethodIsRefused() {
        val server = MetricsEndpoint.start(PrometheusMeterRegistry(PrometheusConfig.DEFAULT), 0)
        try {
            val (code, _, _) = get(server, "/metrics", method = "POST")
            assertEquals(405, code)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun anotherPathIsNotFound() {
        val server = MetricsEndpoint.start(PrometheusMeterRegistry(PrometheusConfig.DEFAULT), 0)
        try {
            val (code, _, _) = get(server, "/nope")
            assertEquals(404, code)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun offOpensNoSocketEver() {
        val stack = MetricsStack.fromKnob(MetricsKnobReading(on = false))
        assertTrue(stack.metrics is NoopMetrics)
        assertNull(stack.startEndpoint(), "OFF: no server, no socket — nothing to stop")
    }

    @Test
    fun onBuildsARealStackOnTheKnobPort() {
        val stack = MetricsStack.fromKnob(MetricsKnobReading(on = true, port = 0))
        assertTrue(stack.metrics is MicrometerMetrics)
        assertTrue(stack.metrics.measures)
        val server = assertNotNull(stack.startEndpoint())
        try {
            assertTrue(server.address.address.isLoopbackAddress)
        } finally {
            server.stop(0)
        }
    }
}
