package dev.lattenero.bot.daemon.metrics

import java.util.concurrent.ConcurrentHashMap

/**
 * M0's attach clock: WHICH task's subscription was just fired and WHEN, so
 * the FIRST folded frame of that very task answers the tap→first-frame
 * latency. Thread-safe (the poller arms, the channel pump fires); a task
 * that never sends a frame simply never records — nothing invented.
 */
class AttachClock(private val nowMs: () -> Long = System::currentTimeMillis) {

    private val armedAt = ConcurrentHashMap<String, Long>()

    /** A subscribe just flew: this task's clock starts now (a re-attach restarts it). */
    fun arm(taskId: String) {
        armedAt[taskId] = nowMs()
    }

    /** The first folded frame of [taskId]: its latency in ms, or null when it was never armed. */
    fun fire(taskId: String): Long? = armedAt.remove(taskId)?.let { armed -> nowMs() - armed }
}
