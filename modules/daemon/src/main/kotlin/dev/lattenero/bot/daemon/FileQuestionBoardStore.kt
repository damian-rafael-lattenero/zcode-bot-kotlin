package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.PendingQuestion
import dev.lattenero.bot.channel.QuestionBoardStore
import dev.lattenero.bot.channel.pendingQuestionOf
import dev.lattenero.bot.channel.storeEntry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private val logger = KotlinLogging.logger {}

/**
 * The question board's FILE-BACKED store (measured 4/11: a daemon restart
 * left every old card armed-but-dead and re-rendered the fork's announced
 * questions as NEW cards — the old taps then misrouted). One JSON array in
 * the daemon's own state dir (the queue's and LastPrompt's exact pattern:
 * ~/.zcode/bot-kotlin/state, one file, one law) — each object is the
 * shortId plus the FULL question; the wire codec lives in the channel
 * module, THIS class is IO only. The write is the house invariant: tmp +
 * fsync + rename. The read is honest: absent, torn or wrong-shape answers
 * EMPTY — nothing invented, nothing thrown (the SendKnob degrade law).
 */
class FileQuestionBoardStore(private val file: File) : QuestionBoardStore {

    @Suppress("TooGenericExceptionCaught") // the store's own degrade law: ANY read failure is the honest empty
    override fun load(): Map<String, PendingQuestion> = try {
        if (!file.exists()) {
            emptyMap()
        } else {
            (Json.parseToJsonElement(file.readText()) as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { entry ->
                    val sid = entry.str("shortId")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val question = pendingQuestionOf(entry) ?: return@mapNotNull null
                    sid to question
                }
                .toMap() // insertion order kept — the cap's ELDEST stays first
        }
    } catch (e: Exception) {
        logger.warn(e) { "question board store unreadable — the board starts empty: ${e.message}" }
        emptyMap()
    }

    override fun save(entries: Map<String, PendingQuestion>) {
        val body: JsonArray = buildJsonArray {
            entries.forEach { (sid, question) -> add(question.storeEntry(sid)) }
        }
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
            FileOutputStream(tmp).use { stream ->
                stream.write(body.toString().toByteArray())
                stream.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                // cross-device or platform rename miss: the honest fallback (LastPrompt's)
                file.writeText(body.toString())
                tmp.delete()
            }
        } catch (e: IOException) {
            logger.warn(e) { "question board persist failed — the board holds in memory: ${e.message}" }
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val TMP_SUFFIX = ".tmp"
    }
}
