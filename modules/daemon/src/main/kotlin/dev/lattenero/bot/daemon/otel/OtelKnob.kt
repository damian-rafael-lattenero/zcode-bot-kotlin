package dev.lattenero.bot.daemon.otel

import dev.lattenero.bot.daemon.bus.SwitchKnob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.URI

/**
 * E3's knob pair, read ONCE at boot from the same `<home>/daemon.json`
 * every knob lives in (a change is a kickstart, like the send knob):
 *
 * - `otel` — the switch ([SwitchKnob]'s rules verbatim: missing file or
 *   key → OFF without a complaint, a typo or a non-boolean → OFF loudly).
 *   SHIPPED OFF, and OFF is the strict zero: no SDK, no exporter, no batch
 *   thread, not one byte on any wire — the daemon is byte-for-byte the
 *   pre-E3 one.
 * - `otel_endpoint` — the OTLP HTTP endpoint the spans are exported to
 *   (e.g. `http://127.0.0.1:4318/v1/traces`). Read even with the switch
 *   off (a typo must be loud whatever the switch says). Absent → the
 *   well-known OTLP default; a non-string or a non-http(s) URL falls back
 *   to the default LOUDLY — a typo must degrade to the standard endpoint,
 *   never to a random URL. The daemon EXPORTS to it and never binds it:
 *   a dead collector is one log line, never a broken turn.
 */
data class OtelKnobReading(
    val on: Boolean,
    val endpoint: String = OtelKnob.DEFAULT_ENDPOINT,
    val warning: String? = null,
)

object OtelKnob {

    const val KEY = "otel"
    const val ENDPOINT_KEY = "otel_endpoint"

    /** The OTLP/HTTP standard: port 4318, the traces path. */
    const val DEFAULT_ENDPOINT = "http://127.0.0.1:4318/v1/traces"

    fun load(file: File): OtelKnobReading {
        val switch = SwitchKnob.read(file, KEY)
        val endpoint = readEndpoint(file)
        val warning = listOfNotNull(switch.warning, endpoint.warning)
            .joinToString("; ")
            .ifEmpty { null }
        return OtelKnobReading(on = switch.on, endpoint = endpoint.value, warning = warning)
    }

    private data class EndpointReading(val value: String, val warning: String? = null)

    @Suppress("TooGenericExceptionCaught") // any read failure is the same answer: the default endpoint, loudly
    private fun readEndpoint(file: File): EndpointReading = when {
        !file.exists() -> EndpointReading(DEFAULT_ENDPOINT)

        else -> try {
            val root = Json.parseToJsonElement(file.readText()) as? JsonObject
            when (val value = root?.get(ENDPOINT_KEY)) {
                null -> EndpointReading(DEFAULT_ENDPOINT) // no key: the OTLP default of a pre-E3 file

                is JsonPrimitive -> primitiveEndpoint(value)

                else -> EndpointReading(
                    DEFAULT_ENDPOINT,
                    "'$ENDPOINT_KEY' is not a string — using $DEFAULT_ENDPOINT",
                )
            }
        } catch (e: Exception) {
            EndpointReading(DEFAULT_ENDPOINT, "unreadable (${e.message}) — using $DEFAULT_ENDPOINT")
        }
    }

    private fun primitiveEndpoint(value: JsonPrimitive): EndpointReading {
        if (!value.isString) {
            return EndpointReading(
                DEFAULT_ENDPOINT,
                "'$ENDPOINT_KEY' is not a string (${raw(value)}) — using $DEFAULT_ENDPOINT",
            )
        }
        val url = value.content.trim()
        return if (isHttpUrl(url)) {
            EndpointReading(url)
        } else {
            EndpointReading(
                DEFAULT_ENDPOINT,
                "'$ENDPOINT_KEY' \"$url\" is not an http(s) URL — using $DEFAULT_ENDPOINT",
            )
        }
    }

    /**
     * The exporter POSTs a full absolute URL: an http(s) scheme and a host
     * are the shape — anything else (no scheme, other scheme, bare host)
     * is a typo and gets the default loudly.
     */
    private fun isHttpUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    /** The value as the owner wrote it: quoted when it was a string. */
    private fun raw(value: JsonPrimitive): String =
        if (value.isString) "\"${value.content}\"" else value.content
}
