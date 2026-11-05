package dev.lattenero.bot.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** One boolean switch's reading: its value plus the complaint, if any. */
data class RichVoiceKnobReading(val on: Boolean, val warning: String? = null)

/**
 * THE VIOLET CHANNEL's knob: `rich_voice` in the same `<home>/daemon.json`
 * the notifications/poll/event_bus knobs live in — read ONCE at boot.
 *
 * THE DEFAULT IS ON — the owner's 5/11 order («quisiera que vayan por un
 * canal especial… fondo violeta… es el llm hablando») makes ON the safe
 * default: a missing file, an absent key or garbage all STAY ON (the
 * warning lands in daemon.jsonl); only an explicit false silences. OFF
 * means the publisher speaks the LLM's voice the old way (plain texts,
 * collapsed CoT buttons) — the pre-feature daemon, byte for byte.
 */
object RichVoiceKnob {

    const val KEY = "rich_voice"

    @Suppress("TooGenericExceptionCaught") // any read failure is the same answer: ON, loudly
    fun load(file: File): RichVoiceKnobReading = when {
        !file.exists() -> ON

        else -> try {
            val root = Json.parseToJsonElement(file.readText()) as? JsonObject
            when (val value = root?.get(KEY)) {
                null -> ON // no key: the file predates the feature — the order still stands

                is JsonPrimitive -> when {
                    value.content == "true" -> ON
                    value.content == "false" -> OFF
                    else -> onLoud("unknown '$KEY' value ${value.content} — staying ON")
                }

                else -> onLoud("'$KEY' is not a boolean — staying ON")
            }
        } catch (e: Exception) {
            onLoud("unreadable (${e.message}) — staying ON")
        }
    }

    private val ON = RichVoiceKnobReading(true)
    private val OFF = RichVoiceKnobReading(false)

    private fun onLoud(warning: String): RichVoiceKnobReading = RichVoiceKnobReading(true, warning)
}
