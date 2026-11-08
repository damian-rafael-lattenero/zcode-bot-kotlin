package dev.lattenero.bot.daemon.bus

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * The append-only JSONL sink (ADR-019): agent-brain tails
 * `~/.zcode/agent-brain/bus-events.jsonl` by BYTE OFFSET, hands every
 * COMPLETE line to the ingest and waits for the newline of a partial tail.
 * A torn line is therefore not a cosmetic bug — it is a stuck event, and a
 * file that SHRINKS is a truncation the tailer detects and restarts from.
 *
 * The rules that keep it honest:
 * - ONE `write` per line (O_APPEND: the offset moves atomically), then
 *   `flush`; no buffered writer that could split a line across calls;
 * - a failed write is rolled back to the file's pre-write length (best
 *   effort) so a half-line never survives;
 * - nothing here THROWS at the caller (the bus never breaks a turn): the
 *   failure is counted and logged as one line in daemon.jsonl.
 *
 * THE LOCK IS A kap/[Mutex] — suspending, single-writer (the audit's BUS
 * finding): the daemon's two writers (the pump's stream callback and the
 * poller's prompt tap) are COROUTINES, and the old `synchronized` block
 * pinned a thread across the file syscalls (exists-check, write+flush, the
 * rollback's `setLength`). A suspending mutex serializes them exactly the
 * same while the second writer waits WITHOUT holding a thread. Only this
 * JVM writes the file — the Python publisher that used to share it was
 * retired with the cutover.
 */
class BusSink(private val file: File, private val log: (String) -> Unit = {}) {

    private val lock = Mutex()

    /** Appends that failed — the in-process counter Python kept (`bus.failures`). */
    @Volatile
    var failures: Long = 0
        private set

    /** Appends that landed (one per event). */
    @Volatile
    var written: Long = 0
        private set

    /**
     * Appends ONE event line. True when written; false when the knob is off
     * (caller-gated) or the write failed. Suspend: both callers (the
     * producer's emit legs) already are — the append never pins a thread.
     */
    @Suppress("TooGenericExceptionCaught") // the bus never breaks a turn: every write failure is the same answer
    suspend fun append(line: String): Boolean {
        val payload = (if (line.endsWith("\n")) line else "$line\n").toByteArray(Charsets.UTF_8)
        return lock.withLock {
            val before = if (file.exists()) file.length() else 0L
            try {
                file.parentFile?.mkdirs()
                FileOutputStream(file, true).use { out ->
                    out.write(payload) // ONE write per line — O_APPEND
                    out.flush()
                }
                written += 1
                true
            } catch (e: Exception) {
                failures += 1
                rollbackTo(before)
                log("bus append failed (${e::class.simpleName}: ${e.message}) — event dropped, line rolled back")
                false
            }
        }
    }

    /** A failed write must not leave a torn tail: cut the file back to its pre-write length. */
    private fun rollbackTo(length: Long) {
        try {
            if (file.exists() && file.length() > length) {
                RandomAccessFile(file, "rw").use { it.setLength(length) }
            }
        } catch (_: Exception) {
            // the tailer's own shrink detection is the second line of defense
        }
    }
}
