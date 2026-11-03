package dev.lattenero.bot.daemon.metrics

import com.sun.net.httpserver.HttpServer
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

/**
 * E2's boot decision, made ONCE behind the `metrics` knob (the wiring
 * law): with the knob ON the stack holds the Micrometer-backed facade,
 * its registry and the listener's port; with it OFF it holds the
 * [NoopMetrics] and NOTHING else — no registry exists, and
 * [startEndpoint] answers null so no socket is ever opened. The daemon
 * with the OFF stack is byte-for-byte the pre-E2 one.
 */
class MetricsStack private constructor(
    val metrics: Metrics,
    private val registry: PrometheusMeterRegistry?,
    private val port: Int,
) {

    /**
     * The /metrics listener (loopback only) — null with the knob off. The
     * caller owns the server: stop(0) rides the shutdown hook.
     */
    fun startEndpoint(): HttpServer? = registry?.let { MetricsEndpoint.start(it, port) }

    companion object {

        /** The OFF stack: the no-op facade, no registry, no port, no socket. */
        fun off(): MetricsStack = MetricsStack(metrics = NoopMetrics, registry = null, port = 0)

        fun fromKnob(reading: MetricsKnobReading): MetricsStack = if (!reading.on) {
            off()
        } else {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            MetricsStack(metrics = MicrometerMetrics(registry), registry = registry, port = reading.port)
        }
    }
}
