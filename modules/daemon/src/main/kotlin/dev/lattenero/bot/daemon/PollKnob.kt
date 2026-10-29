package dev.lattenero.bot.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * B3b's second knob: `poll` in the same `<home>/daemon.json` the send
 * knob lives in — read ONCE at boot (a change is a kickstart). SHIPPED
 * OFF: the poller exists but does not run, because Python still owns
 * getUpdates (ADR-001's single reader — two pollers on one token fight
 * Telegram's 409). The retirement sequence (`scripts/retire-python.sh`)
 * is the ONLY thing that flips it: unload Python's LaunchAgents FIRST,
 * then this knob to true + kickstart, then verify getUpdates answers
 * without a 409. A bad reading is OFF loudly (a typo must degrade to
 * "Python keeps the phone", never to a second poller).
 */
data class PollKnobReading(val on: Boolean, val warning: String? = null)

object PollKnob {

    const val KEY = "poll"

    @Suppress("TooGenericExceptionCaught") // any read failure is the same answer: OFF, loudly
    fun load(file: File): PollKnobReading = when {
        !file.exists() -> PollKnobReading(false)

        else -> try {
            val root = Json.parseToJsonElement(file.readText()) as? JsonObject
            when (val value = root?.get(KEY)) {
                null -> PollKnobReading(false) // no key: the honest OFF of a pre-B3b file

                is JsonPrimitive -> when {
                    value.isString ->
                        parseSpelling(value.content)
                            ?: PollKnobReading(false, "unknown '$KEY' value \"${value.content}\" — staying OFF")

                    value.content == "true" -> PollKnobReading(true)
                    value.content == "false" -> PollKnobReading(false)
                    else -> PollKnobReading(false, "unknown '$KEY' value ${value.content} — staying OFF")
                }

                else -> PollKnobReading(false, "'$KEY' is not a boolean — staying OFF")
            }
        } catch (e: Exception) {
            PollKnobReading(false, "unreadable (${e.message}) — staying OFF")
        }
    }

    private fun parseSpelling(s: String): PollKnobReading? = when (s.trim().lowercase()) {
        "true", "on", "yes", "1" -> PollKnobReading(true)
        "false", "off", "no", "0" -> PollKnobReading(false)
        else -> null
    }
}
