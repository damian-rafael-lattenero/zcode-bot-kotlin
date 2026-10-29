package dev.lattenero.bot.daemon.spectator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Incremental tail of a rollout `model-io-<sid>.jsonl` — the same rules
 * as spectator._read_new: only COMPLETE records (a line without its
 * newline may be mid-write and waits), a monster record (8 MB+) is
 * skipped but the offset still advances, one read is capped at 64 MB,
 * and a malformed line is skipped, never thrown.
 */
internal class RolloutReader(private val file: File) {

    fun sizeAtStart(): Long = runCatching { file.length() }.getOrDefault(0L)

    /** New complete records since [pos] → (records, new pos); pos unchanged on IO error. */
    // One guard per line (BusEvents precedent).
    @Suppress("ReturnCount")
    fun read(pos: Long): Pair<Long, List<JsonObject>> {
        val data = readChunk(pos) ?: return pos to emptyList()
        val complete = completeLines(data)
        if (complete.isEmpty()) return pos to emptyList()
        val records = complete.toStrings()
            .filter { it.length <= REG_CAP } // monster record: skipped, the offset moves anyway
            .mapNotNull { line -> runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() }
        return (pos + complete.size.toLong()) to records
    }

    private fun readChunk(pos: Long): ByteArray? = try {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len <= pos) {
                ByteArray(0)
            } else {
                raf.seek(pos)
                val size = minOf(len - pos, CHUNK_CAP_BYTES).toInt()
                ByteArray(size).also { raf.readFully(it) }
            }
        }
    } catch (_: IOException) {
        null
    }

    private fun completeLines(data: ByteArray): ByteArray =
        when {
            data.isEmpty() || data.last() == NEWLINE -> data
            else -> data.copyOfRange(0, data.lastIndexOf(NEWLINE) + 1)
        }

    private fun ByteArray.toStrings(): List<String> =
        String(this, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }

    private companion object {
        const val KIB = 1024L
        const val CHUNK_CAP_BYTES = 64L * KIB * KIB
        const val REG_CAP = (8L * KIB * KIB).toInt() // a larger record is skipped, the offset moves anyway
        const val NEWLINE = '\n'.code.toByte()
    }
}
