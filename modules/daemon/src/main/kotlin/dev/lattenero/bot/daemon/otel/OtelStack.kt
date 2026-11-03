package dev.lattenero.bot.daemon.otel

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter

/**
 * E3's boot decision, made ONCE behind the `otel` knob (the wiring law):
 * with the knob ON the stack holds the SDK tracer provider wired to the
 * batch OTLP exporter and the [TurnSpanTracker] the daemon's wiring feeds
 * its frames to; with it OFF it holds NOTHING — no provider, no exporter,
 * no batch thread, no tracker ([tracker] is null and the wiring's tap
 * point is a null check). The daemon with the OFF stack is byte-for-byte
 * the pre-E3 one.
 *
 * The daemon EXPORTS and never binds: the exporter POSTs to the
 * configured endpoint on the batch's OWN thread — a dead collector is one
 * log line (the SDK's batch worker's), never a broken turn (the live-io
 * invariant, guarded twice: the batch thread's isolation plus
 * [GuardedSpanTaps] around the edge itself).
 *
 * [close] rides the daemon's shutdown hook: the provider's close flushes
 * the batch — the last spans land before the JVM dies.
 */
class OtelStack private constructor(
    /** The frame tap the wiring feeds — null with the knob off (the busTaps pattern). */
    val tracker: TurnSpanTracker?,
    private val provider: SdkTracerProvider?,
) : AutoCloseable {

    /** Flush + shutdown: the batch's last export dies with the daemon, never mid-flight. */
    override fun close() {
        provider?.close()
    }

    companion object {

        /** The OFF stack: no tracker, no provider, no exporter — the strict zero. */
        fun off(): OtelStack = OtelStack(tracker = null, provider = null)

        /**
         * The knob's reading decides ONCE. [exporter] is the span sink
         * (the OTLP HTTP exporter by default); the tests inject an
         * in-memory one and keep the whole production shape — the same
         * provider, the same batch processor, the same edge.
         */
        fun fromKnob(
            reading: OtelKnobReading,
            log: (String) -> Unit,
            exporter: (endpoint: String) -> SpanExporter = ::otlpExporter,
        ): OtelStack {
            if (!reading.on) return off()
            val provider = SdkTracerProvider.builder()
                .setResource(resource())
                .addSpanProcessor(BatchSpanProcessor.builder(exporter(reading.endpoint)).build())
                .build()
            val taps = GuardedSpanTaps(OtelSpanTaps(provider.get(TRACER_NAME)), log)
            return OtelStack(tracker = TurnSpanTracker(taps), provider = provider)
        }

        /** The daemon's own OTLP/HTTP leg: POST the batches to the configured endpoint. */
        private fun otlpExporter(endpoint: String): SpanExporter =
            OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build()

        /** Who is speaking: the daemon, honestly named — no version theater. */
        private fun resource(): Resource = Resource.getDefault().merge(
            Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "zcode-bot-daemon")),
        )

        /** The instrumentation scope — the daemon's own instrumentation, honestly named. */
        private const val TRACER_NAME = "dev.lattenero.bot.daemon"
    }
}
