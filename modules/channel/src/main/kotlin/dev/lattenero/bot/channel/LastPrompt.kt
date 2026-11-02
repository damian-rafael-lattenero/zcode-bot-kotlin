package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import dev.lattenero.bot.transport.CH_REPLY
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The LAST PROMPT store — /retry's ammo (Python's F22, workers.py's
 * `_save_last_prompt`: the prompt is saved when a turn starts, so /retry
 * always has something to relaunch after a failure, crash or timeout).
 *
 * One JSON object `{sid, text, ts}` on disk (the daemon's own state dir —
 * never Python's hooks/state: the daemon never shared that file, this is
 * a NEW additive contract). The write is the house invariant: tmp +
 * fsync + rename (FileVerbositySetting's atomicWrite). The read is
 * honest: a missing file, torn JSON or wrong shape answers null —
 * nothing invented, nothing thrown, /retry says the truthful «nada».
 */
class LastPrompt(private val file: File) {

    /** The saved prompt: the session it fired at, the verbatim text, the fire's clock. */
    data class Saved(val sid: String, val text: String, val ts: Long)

    /** Saves (overwrites) the last prompt — the text capped to the outbox's own CH_REPLY. */
    @Synchronized
    fun save(sid: String, text: String, ts: Long) {
        val body: JsonObject = buildJsonObject {
            put("sid", JsonPrimitive(sid))
            put("text", JsonPrimitive(PyText.take(text, CH_REPLY)))
            put("ts", JsonPrimitive(ts))
        }
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
            FileOutputStream(tmp).use { stream ->
                stream.write(body.toString().toByteArray())
                stream.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                // cross-device or platform rename miss: the honest fallback (InboundQueue's)
                file.writeText(body.toString())
                tmp.delete()
            }
        } catch (_: IOException) {
            // best-effort: a lost save is a /retry with nothing newer, never a crash
        }
    }

    /** The saved prompt, or null when there is none (missing/torn/unreadable — the honest default). */
    @Synchronized
    fun load(): Saved? = try {
        if (!file.exists()) {
            null
        } else {
            (Json.parseToJsonElement(file.readText()) as? JsonObject)?.let { root ->
                val text = root.str("text")?.takeIf { it.isNotBlank() } ?: return@let null
                Saved(sid = root.str("sid").orEmpty(), text = text, ts = root.long("ts") ?: 0L)
            }
        }
    } catch (_: Exception) {
        null // torn JSON, unreadable: the file only ever holds this one shape (LiveOrphanStore's law)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private companion object {
        const val TMP_SUFFIX = ".tmp"
    }
}
