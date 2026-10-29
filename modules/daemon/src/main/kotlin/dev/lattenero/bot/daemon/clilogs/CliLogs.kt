package dev.lattenero.bot.daemon.clilogs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Instant

/**
 * The CLI's JSONL logs (`<base>/cli/log/zcode-YYYY-MM-DD.jsonl`), the same
 * source the Python bot scans (`cli_logs.iter_events`): one JSON event per
 * line `{"timestamp": ISO-8601-UTC, "event": "...", "sessionId": "...",
 * "context": {...}}`. The parser mirrors the Python filters in the same
 * order — cheap needle prefilter, parse, event-prefix match, subagent skip,
 * timestamp floor — so the shadow watchers see exactly the events the
 * Python side sees.
 */
data class CliEvent(val ts: Double, val sid: String, val event: String, val context: JsonObject?)

/** Where the tailer stands: the file it is inside of, and how far into it. */
data class LogCursor(val path: String, val offset: Long)

data class ReadResult(val cursor: LogCursor, val events: List<CliEvent>)

object CliLogParser {

    /** A re-read tolerance: the Python scanner keeps `ts >= since - 1`. */
    const val SINCE_TOLERANCE_S = 1.0

    private const val SUBAGENT_PREFIX = "sess_subagent"
    private const val MILLIS_PER_SECOND = 1000.0
    private val json = Json { ignoreUnknownKeys = true }

    /** One line to one event through the shared filter chain; null = skip. */
    // The mirror of cli_logs.iter_events keeps one guard per line (BusEvents precedent).
    @Suppress("ReturnCount")
    fun parseLine(line: String, needles: Set<String>, since: Double): CliEvent? {
        if (needles.isNotEmpty() && needles.none { line.contains(it) }) return null
        val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
        val event = (obj["event"] as? JsonPrimitive)?.content ?: return null
        if (needles.isNotEmpty() && needles.none { event.startsWith(it) }) return null
        val sid = (obj["sessionId"] as? JsonPrimitive)?.content ?: ""
        if (sid.startsWith(SUBAGENT_PREFIX)) return null
        val ts = parseTs((obj["timestamp"] as? JsonPrimitive)?.content) ?: return null
        if (ts < since - SINCE_TOLERANCE_S) return null
        return CliEvent(ts, sid, event, obj["context"] as? JsonObject)
    }

    /** A chunk of complete lines to events, in order. */
    fun parseChunk(text: String, needles: Set<String>, since: Double): List<CliEvent> =
        text.lineSequence()
            .filter { it.isNotEmpty() }
            .mapNotNull { parseLine(it, needles, since) }
            .toList()

    fun parseTs(iso: String?): Double? =
        iso?.let { s -> runCatching { Instant.parse(s).toEpochMilli() / MILLIS_PER_SECOND }.getOrNull() }
}

/**
 * Incremental tail of the newest CLI log, `iter_events(last_files=1)`
 * semantics: after the midnight rollover the previous file's unwritten
 * tail is NOT drained — a Python wart this shadow mirrors on purpose,
 * because diverging here would fail the byte-parity gate (ADR-023). The
 * cursor is process memory; across restarts the persisted `since` floor
 * is the protection (the whole newest file is re-read but old events are
 * filtered out, exactly like Python's every-cycle whole-file scan).
 */
class CliLogTailer(private val baseDir: File) {

    private val logDir: File get() = File(File(baseDir, "cli"), "log")

    // One guard per line: each early return is one failure mode (BusEvents precedent).
    @Suppress("ReturnCount")
    fun read(cursor: LogCursor?, needles: Set<String>, since: Double): ReadResult {
        val newest = newestLog() ?: return ReadResult(cursor ?: LogCursor("", 0), emptyList())
        val start = if (cursor != null && cursor.path == newest.path) cursor.offset else 0L
        val data = readChunk(newest, start) ?: return ReadResult(LogCursor(newest.path, start), emptyList())
        val complete = completeLines(data)
        if (complete.isEmpty()) return ReadResult(LogCursor(newest.path, start), emptyList())
        val events = CliLogParser.parseChunk(String(complete, Charsets.UTF_8), needles, since)
        return ReadResult(LogCursor(newest.path, start + complete.size), events)
    }

    private fun newestLog(): File? =
        logDir.listFiles { f -> f.name.startsWith(LOG_PREFIX) && f.name.endsWith(LOG_SUFFIX) }
            ?.maxByOrNull { it.name }

    /** Bytes from [offset] capped per read; null on IO error (the cycle retries — Python's silent OSError pass). */
    private fun readChunk(f: File, offset: Long): ByteArray? = try {
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            if (len <= offset) {
                ByteArray(0)
            } else {
                raf.seek(offset)
                val size = minOf(len - offset, CHUNK_CAP_BYTES).toInt()
                ByteArray(size).also { raf.readFully(it) }
            }
        }
    } catch (_: IOException) {
        null
    }

    /** Drops a trailing partial line: a line without its `\n` may be mid-write and stays for the next pass. */
    private fun completeLines(data: ByteArray): ByteArray =
        when {
            data.isEmpty() || data.last() == NEWLINE -> data
            else -> data.copyOfRange(0, data.lastIndexOf(NEWLINE) + 1)
        }

    private companion object {
        const val LOG_PREFIX = "zcode-"
        const val LOG_SUFFIX = ".jsonl"
        const val KIB = 1024L
        const val CHUNK_CAP_BYTES = 64L * KIB * KIB
        const val NEWLINE = '\n'.code.toByte()
    }
}
