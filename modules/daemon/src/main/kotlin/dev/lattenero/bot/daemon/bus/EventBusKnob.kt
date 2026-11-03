package dev.lattenero.bot.daemon.bus

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** One boolean switch read from `<home>/daemon.json`: its value plus the complaint, if any. */
data class SwitchReading(val on: Boolean, val warning: String? = null)

/**
 * The tolerant boolean reader behind the bus knobs (PollKnob's rules,
 * verbatim): missing file → OFF without a complaint (a daemon deployed
 * before the knob existed IS off); absent key → OFF; a typo or a non-boolean
 * → OFF loudly (the warning lands in daemon.jsonl — a typo must degrade to
 * "the feature does not run", never to a half-configured feature); the
 * accepted spellings match [PollKnob]'s (true/on/yes/1, false/off/no/0,
 * case and surrounding blanks tolerated).
 */
internal object SwitchKnob {

    @Suppress("TooGenericExceptionCaught") // any read failure is the same answer: OFF, loudly
    fun read(file: File, key: String): SwitchReading = when {
        !file.exists() -> SwitchReading(false)

        else -> try {
            val root = Json.parseToJsonElement(file.readText()) as? JsonObject
            when (val value = root?.get(key)) {
                null -> SwitchReading(false) // no key: the honest OFF

                is JsonPrimitive -> when {
                    value.isString -> spelling(value.content)
                        ?: SwitchReading(false, "unknown '$key' value \"${value.content}\" — staying OFF")

                    value.content == "true" -> SwitchReading(true)
                    value.content == "false" -> SwitchReading(false)
                    else -> SwitchReading(false, "unknown '$key' value ${value.content} — staying OFF")
                }

                else -> SwitchReading(false, "'$key' is not a boolean — staying OFF")
            }
        } catch (e: Exception) {
            SwitchReading(false, "unreadable (${e.message}) — staying OFF")
        }
    }

    private fun spelling(s: String): SwitchReading? = when (s.trim().lowercase()) {
        "true", "on", "yes", "1" -> SwitchReading(true)
        "false", "off", "no", "0" -> SwitchReading(false)
        else -> null
    }
}

/**
 * U1b's producer knob: `event_bus` in the same `<home>/daemon.json` the send
 * and poll knobs live in — read ONCE at boot (a change is a kickstart).
 * SHIPPED OFF: with the knob off the tap points do zero I/O (no file, no
 * query, no clock), so a daemon deployed before U1b behaves byte-for-byte as
 * before. ON appends one line per event to
 * `~/.zcode/agent-brain/bus-events.jsonl` (agent-brain's file tailer).
 */
object EventBusKnob {

    const val KEY = "event_bus"

    fun load(file: File): SwitchReading = SwitchKnob.read(file, KEY)
}
