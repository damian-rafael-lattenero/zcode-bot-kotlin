package dev.lattenero.bot.daemon.notifications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** One boolean switch's reading: its value plus the complaint, if any. */
data class NotifKnobReading(val on: Boolean, val warning: String? = null)

/**
 * The key-moment notifications' knob: `notifications` in the same
 * `<home>/daemon.json` the send/poll/event_bus/memory_inject knobs live
 * in — read ONCE at boot (a change is a kickstart).
 *
 * THE DEFAULT IS ON — the owner's explicit order («mensajes bien
 * fuertes» at the key moments) makes ON the safe default, the inverse of
 * every other knob in this file: a missing file, an absent key, a typo
 * or a non-boolean all STAY ON (loudly — the warning lands in
 * daemon.jsonl) and only an explicit false spelling silences. OFF means
 * the wiring builds nothing: the subagent watcher never runs and the
 * seal tap is never constructed — zero I/O, byte-for-byte the pre-feature
 * daemon.
 */
object NotificationsKnob {

    const val KEY = "notifications"

    @Suppress("TooGenericExceptionCaught") // any read failure is the same answer: ON, loudly
    fun load(file: File): NotifKnobReading = when {
        !file.exists() -> ON

        else -> try {
            val root = Json.parseToJsonElement(file.readText()) as? JsonObject
            when (val value = root?.get(KEY)) {
                null -> ON // no key: the file predates the feature — the order still stands

                is JsonPrimitive -> when {
                    value.isString -> spelling(value.content)
                        ?: onLoud("unknown '$KEY' value \"${value.content}\" — staying ON")

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

    private val ON = NotifKnobReading(true)
    private val OFF = NotifKnobReading(false)

    private fun onLoud(warning: String): NotifKnobReading = NotifKnobReading(true, warning)

    private fun spelling(s: String): NotifKnobReading? = when (s.trim().lowercase()) {
        "true", "on", "yes", "1" -> ON
        "false", "off", "no", "0" -> OFF
        else -> null
    }
}
