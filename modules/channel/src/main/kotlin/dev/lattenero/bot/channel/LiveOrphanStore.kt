package dev.lattenero.bot.channel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * The live view's one durable fact: the status message currently being
 * edited, on disk (atomic tmp+rename). Its only job is the ORPHAN SEAL:
 * a daemon restart (nuclear, crash, deploy) loses the in-memory turn and
 * the old Telegram message would freeze with its last "thinking..." for
 * ever — the measured lie class (30/10: a quota-dead turn ticking hours
 * later). At boot the wiring reads this file, edits the orphan ONCE with
 * the honest terminal text, and clears it. Seals clear it too.
 */
class LiveOrphanStore(private val file: File) {

    data class Live(val messageId: Long, val taskId: String)

    fun save(messageId: Long, taskId: String) {
        val body: JsonObject = buildJsonObject {
            put("message_id", messageId)
            put("task_id", taskId)
        }
        try {
            file.parentFile?.mkdirs()
            val tmp = File("${file.path}.tmp")
            tmp.writeText(body.toString())
            tmp.renameTo(file)
        } catch (_: Exception) {
            // best-effort by design: the worst case is one orphaned message
        }
    }

    fun readLast(): Live? = try {
        if (!file.exists()) {
            null
        } else {
            (Json.parseToJsonElement(file.readText()) as? JsonObject)?.let { root ->
                val mid = root["message_id"]?.let { it.jsonPrimitive.longOrNull }
                val task = root["task_id"]?.let { it.jsonPrimitive.content }
                if (mid != null && mid > 0 && task != null) Live(mid, task) else null
            }
        }
    } catch (_: Exception) {
        null // unreadable = no orphan (the file only ever holds this one shape)
    }

    fun clear() {
        try {
            file.delete()
        } catch (_: Exception) {
            // silent: the boot edit is idempotent against a stale file
        }
    }
}
