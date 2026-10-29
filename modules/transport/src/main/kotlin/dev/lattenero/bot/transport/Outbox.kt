package dev.lattenero.bot.transport

import dev.lattenero.bot.contracts.OutboxItem
import dev.lattenero.bot.contracts.PyText
import io.github.oshai.kotlinlogging.KotlinLogging
import kap.Kap
import kap.Schedule
import kap.evalGraph
import kap.retry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger {}

/**
 * The busy signal as a VALUE: another process holds the flock right now.
 * The retry policy polls it away — this exception is never a transport
 * failure (those rethrow immediately, the schedule's doWhile gate sees
 * to it); it only ever reaches a caller wrapped as the deadline's
 * IOException.
 */
private class FlockBusy(path: File) : IOException("flock busy: $path")

/**
 * The outbox (utils/http.py _outbox_*): messages that failed to send
 * pile up here and the poller flushes them when the network comes back.
 *
 * The file contract is Python's outbox.json verbatim — a JSON array of
 * {"ts": int, "text": str} written with json.dump(ensure_ascii=False)
 * separators (PyText.jsonDumps) — so both bots can hold the queue
 * during the cutover. The cross-process pattern is state.AtomicStore's:
 * flock on a sidecar .lock (10 s deadline, a pathological hold degrades
 * with an exception instead of freezing), read-modify-write inside,
 * sends ALWAYS outside (the 15:47 self-deadlock lesson — flock is not
 * reentrant across fds).
 */
class Outbox(
    private val path: File,
    private val now: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    // the RLock: one flock attempt at a time in THIS process (a second
    // concurrent channel would throw OverlappingFileLockException, not
    // block — the mutex keeps the file lock a cross-process concern)
    private val jvmLock = Mutex()

    /** Enqueues a message: text capped to CH_REPLY code points, the box keeps the LAST [MAX_BOX]. */
    suspend fun put(text: String) {
        val item = JsonObject(
            mapOf(
                "ts" to JsonPrimitive(now().toLong()),
                "text" to JsonPrimitive(PyText.take(text, CH_REPLY)),
            ),
        )
        try {
            updateCrossProcess(default = JsonArray(emptyList())) { box ->
                val list = (box as? JsonArray)?.toMutableList() ?: mutableListOf()
                list.add(item)
                JsonArray(list.takeLast(MAX_BOX))
            }
        } catch (e: IOException) {
            // the message is lost from the outbox but send already tried
            // 3 times — degrade with a log, never raise
            logger.warn { "outbox_put: ${e.message}" }
        }
    }

    /**
     * The flush's swap: take everything, leave []. Returns null when the
     * flock deadline expires — nothing is lost, the caller just waits
     * for the next cycle.
     */
    suspend fun takeAll(): List<OutboxItem>? {
        var taken: List<OutboxItem> = emptyList()
        return try {
            updateCrossProcess(default = JsonArray(emptyList())) { box ->
                taken = box.asBox()
                JsonArray(emptyList())
            }
            taken
        } catch (e: IOException) {
            logger.warn { "outbox_flush swap: ${e.message}" }
            null
        }
    }

    /** Failed sends go back to the FRONT (they are the oldest). */
    suspend fun putBack(remaining: List<OutboxItem>) {
        if (remaining.isEmpty()) return
        try {
            updateCrossProcess(default = JsonArray(emptyList())) { box ->
                JsonArray(remaining.map { it.toJson() } + box.asBox().map { it.toJson() })
            }
        } catch (e: IOException) {
            logger.warn { "outbox_flush put_back (${remaining.size} lost): ${e.message}" }
        }
    }

    // ── the AtomicStore pattern ───────────────────────────────────────────

    /**
     * update_cross_process: flock with a deadline around a
     * read-modify-write. [fn] receives the current box (the default when
     * the file is missing or broken) and returns the new box — null
     * means "don't write" (update's contract). The wait for the lock is
     * a Kap retry policy (U0's no-manual-concurrency sweep): busy is an
     * EXCEPTION the schedule polls away at Python's cadence, and the
     * deadline exhaustion rethrows wrapped as the same timeout
     * IOException Python's flock deadline raised — byte-exact contract.
     */
    private suspend fun updateCrossProcess(default: JsonElement, fn: (JsonElement) -> JsonElement?): JsonElement? {
        val lockPath = File(path.path + ".lock")
        lockPath.parentFile?.mkdirs()
        return jvmLock.withLock {
            RandomAccessFile(lockPath, "rw").use { raf ->
                try {
                    Kap {
                        val lock = raf.channel.tryLock() ?: throw FlockBusy(lockPath)
                        try {
                            val cur = read() ?: default
                            val new = fn(cur)
                            if (new != null) write(new)
                            new
                        } finally {
                            lock.release()
                        }
                    }.retry(flockPolicy()).evalGraph()
                } catch (e: FlockBusy) {
                    throw IOException(
                        "flock timeout (${FLOCK_TIMEOUT_S.toInt()}s): $lockPath — " +
                            "lock held by another process (possible nesting)",
                        e,
                    )
                }
            }
        }
    }

    /**
     * The flock wait as schedule algebra: only the busy signal retries
     * (a transport failure rethrows immediately — doWhile's gate), at
     * the 100 ms poll cadence, for at most the 10 s deadline.
     */
    private fun flockPolicy(): Schedule<Throwable> =
        Schedule.doWhile<Throwable> { it is FlockBusy } and
            Schedule.spaced<Throwable>(POLL).withMaxDuration(FLOCK_TIMEOUT, timeSource)

    private fun read(): JsonElement? = try {
        Json.parseToJsonElement(path.readText())
    } catch (_: Exception) {
        null // missing file or broken JSON → the caller's default
    }

    /** _write_nolock: tmp carries the pid, fsync, atomic replace. */
    private fun write(data: JsonElement) {
        path.parentFile?.mkdirs()
        val tmp = File("${path.path}.tmp.${ProcessHandle.current().pid()}")
        FileOutputStream(tmp).use { fos ->
            fos.write(PyText.jsonDumps(data).toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        Files.move(tmp.toPath(), path.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun JsonElement.asBox(): List<OutboxItem> =
        (this as? JsonArray)?.let { OutboxItem.Box.fromJson(it) } ?: emptyList()

    private fun OutboxItem.toJson(): JsonObject = JsonObject(
        mapOf(
            "ts" to JsonPrimitive(ts),
            "text" to JsonPrimitive(text),
        ),
    )

    companion object {
        const val MAX_BOX = 20
        const val FLOCK_TIMEOUT_S = 10.0
        private val POLL: Duration = 100.milliseconds
        private val FLOCK_TIMEOUT: Duration = FLOCK_TIMEOUT_S.seconds
        private const val MILLIS_PER_SECOND = 1000.0
    }
}
