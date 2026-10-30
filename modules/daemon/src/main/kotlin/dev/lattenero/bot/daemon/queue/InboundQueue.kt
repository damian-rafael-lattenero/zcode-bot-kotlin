package dev.lattenero.bot.daemon.queue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Python's F17 dormant queue, live mode: texts that arrived while the
 * bound conversation was mid-turn. A plain JSON contract on disk (the
 * daemon's own state dir — never Python's), one atomic write per add,
 * the whole list taken by the drain when a turn's task_complete lands.
 */
internal class InboundQueue(private val file: File) {

    /** One parked text — the original update's identity, kept for the re-fire. */
    data class Pending(val text: String, val messageId: String, val receivedAt: Long)

    /** Parks the text; answers the queue size after (Python's _queue_put + peek count). */
    @Synchronized
    fun add(text: String, messageId: String, receivedAt: Long): Int {
        val items = read().toMutableList()
        items.add(Pending(text, messageId, receivedAt))
        write(items)
        return items.size
    }

    /** Takes everything (the drain owns the items from here on). */
    @Synchronized
    fun drain(): List<Pending> {
        val items = read()
        if (items.isNotEmpty()) write(emptyList())
        return items
    }

    /** The parked list as-is, no drain — /queue's honest peek. */
    @Synchronized
    fun entries(): List<Pending> = read()

    /**
     * Removes ONE parked text by its update identity — /queue's 🚀 launch
     * and 🗑 discard both take it out; null means it is already gone (the
     * turn's drain beat the tap — the honest answer, not an error).
     */
    @Synchronized
    fun take(messageId: String): Pending? {
        val items = read()
        val hit = items.firstOrNull { it.messageId == messageId } ?: return null
        write(items.filterNot { it.messageId == messageId })
        return hit
    }

    private fun read(): List<Pending> = runCatching {
        Json.parseToJsonElement(file.readText()).jsonArray.map { el ->
            val o = el.jsonObject
            Pending(
                text = o["text"]?.jsonPrimitive?.content.orEmpty(),
                messageId = o["messageId"]?.jsonPrimitive?.content.orEmpty(),
                receivedAt = o["receivedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            )
        }
    }.getOrDefault(emptyList())

    private fun write(items: List<Pending>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            val json: JsonArray = buildJsonArray {
                items.forEach { p ->
                    add(
                        buildJsonObject {
                            put("text", JsonPrimitive(p.text))
                            put("messageId", JsonPrimitive(p.messageId))
                            put("receivedAt", JsonPrimitive(p.receivedAt))
                        },
                    )
                }
            }
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                // cross-device or platform rename miss: the honest fallback
                file.writeText(json.toString())
                tmp.delete()
            }
        } catch (_: java.io.IOException) {
            // best-effort like Python's queue store: a lost park is a retry, never a crash
        }
    }
}
