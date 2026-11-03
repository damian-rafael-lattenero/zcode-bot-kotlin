package dev.lattenero.bot.daemon.otel

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter

/**
 * The in-memory span exporter — the tests' sink (no mock framework, the
 * house's trap shape): every export lands in the log, exactly as the
 * collector would see it.
 */
class RecordingSpanExporter : SpanExporter {

    private val received = mutableListOf<SpanData>()

    /** The exported spans so far, in arrival order. */
    val spans: List<SpanData> get() = synchronized(this) { received.toList() }

    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        synchronized(this) { received.addAll(spans) }
        return CompletableResultCode.ofSuccess()
    }

    override fun flush() = CompletableResultCode.ofSuccess()

    override fun shutdown() = CompletableResultCode.ofSuccess()
}
