package dev.lattenero.bot.daemon.metrics

import com.sun.net.httpserver.HttpServer
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * The /metrics listener (E2): the JDK's own [HttpServer] — no ktor
 * server, the smallest honest dependency surface. LOOPBACK ONLY: the
 * socket binds 127.0.0.1, so the exposition never leaves the machine
 * (Prometheus scrapes it locally or not at all). One route: GET /metrics
 * → the registry's Prometheus text exposition; anything else is refused.
 *
 * Constructed ONLY behind the `metrics` knob: with it off,
 * [MetricsStack.startEndpoint] answers null and no socket is ever opened.
 */
object MetricsEndpoint {

    const val PATH = "/metrics"

    /** The text exposition format the default scrape() speaks (Prometheus v0.0.4). */
    const val CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"

    fun start(registry: PrometheusMeterRegistry, port: Int): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), BACKLOG)
        server.createContext(PATH) { exchange ->
            when (exchange.requestMethod.uppercase()) {
                HTTP_GET -> {
                    val body = registry.scrape().toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set(CONTENT_TYPE_HEADER, CONTENT_TYPE)
                    exchange.sendResponseHeaders(HTTP_OK, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }

                else -> exchange.sendResponseHeaders(HTTP_METHOD_NOT_ALLOWED, NO_BODY)
            }
            exchange.close()
        }
        // one daemon thread: a scrape is a registry walk, and the endpoint
        // must never hold the JVM's exit back
        server.executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "metrics-endpoint").apply { isDaemon = true }
        }
        server.start()
        return server
    }

    private const val HTTP_GET = "GET"
    private const val HTTP_OK = 200
    private const val HTTP_METHOD_NOT_ALLOWED = 405

    /** sendResponseHeaders's own "no response body" length. */
    private const val NO_BODY = -1L
    private const val BACKLOG = 16
    private const val CONTENT_TYPE_HEADER = "Content-Type"
}
