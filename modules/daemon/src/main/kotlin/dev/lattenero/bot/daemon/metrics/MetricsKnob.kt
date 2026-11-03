package dev.lattenero.bot.daemon.metrics

import dev.lattenero.bot.daemon.bus.SwitchKnob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * E2's knob pair, read ONCE at boot from the same `<home>/daemon.json`
 * every knob lives in (a change is a kickstart, like the send knob):
 *
 * - `metrics` — the switch ([SwitchKnob]'s rules verbatim: missing file or
 *   key → OFF without a complaint, a typo or a non-boolean → OFF loudly).
 *   SHIPPED OFF, and OFF is the strict zero: no registry, no listener, no
 *   clock read on any hot path — the daemon is byte-for-byte the pre-E2 one.
 * - `metrics_port` — the /metrics listener's port, default [MetricsKnob.DEFAULT_PORT].
 *   Read even with the switch off (a typo must be loud whatever the switch
 *   says); a garbage or out-of-range value falls back to the default
 *   LOUDLY — a typo must degrade to the well-known port, never to a random
 *   socket.
 */
data class MetricsKnobReading(
    val on: Boolean,
    val port: Int = MetricsKnob.DEFAULT_PORT,
    val warning: String? = null,
)

object MetricsKnob {

    const val KEY = "metrics"
    const val PORT_KEY = "metrics_port"

    /** The daemon's scrape port (the plan's 9102 — 9100/9101 are taken stocks). */
    const val DEFAULT_PORT = 9102

    fun load(file: File): MetricsKnobReading {
        val switch = SwitchKnob.read(file, KEY)
        val port = readPort(file)
        val warning = listOfNotNull(switch.warning, port.warning)
            .joinToString("; ")
            .ifEmpty { null }
        return MetricsKnobReading(on = switch.on, port = port.value, warning = warning)
    }

    private data class PortReading(val value: Int, val warning: String? = null)

    @Suppress("TooGenericExceptionCaught") // any read failure is the same answer: the default port, loudly
    private fun readPort(file: File): PortReading = when {
        !file.exists() -> PortReading(DEFAULT_PORT)

        else -> try {
            val root = Json.parseToJsonElement(file.readText()) as? JsonObject
            when (val value = root?.get(PORT_KEY)) {
                null -> PortReading(DEFAULT_PORT) // no key: the default port of a pre-E2 file

                is JsonPrimitive -> primitivePort(value)

                else -> PortReading(DEFAULT_PORT, "'$PORT_KEY' is not a number — using $DEFAULT_PORT")
            }
        } catch (e: Exception) {
            PortReading(DEFAULT_PORT, "unreadable (${e.message}) — using $DEFAULT_PORT")
        }
    }

    private fun primitivePort(value: JsonPrimitive): PortReading {
        val port = value.content.trim().toIntOrNull()
        return when {
            port == null ->
                PortReading(DEFAULT_PORT, "'$PORT_KEY' is not a number (${raw(value)}) — using $DEFAULT_PORT")

            port in MIN_PORT..MAX_PORT -> PortReading(port)

            else -> PortReading(
                DEFAULT_PORT,
                "'$PORT_KEY' $port is out of range ($MIN_PORT..$MAX_PORT) — using $DEFAULT_PORT",
            )
        }
    }

    /** The value as the owner wrote it: quoted when it was a string. */
    private fun raw(value: JsonPrimitive): String =
        if (value.isString) "\"${value.content}\"" else value.content

    private const val MIN_PORT = 1
    private const val MAX_PORT = 65535
}
