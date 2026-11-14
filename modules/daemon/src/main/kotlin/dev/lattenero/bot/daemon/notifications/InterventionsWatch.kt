package dev.lattenero.bot.daemon.notifications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 🧭 THE ANY-TASK WATCHER'S PURE CORE (14/11, the owner's order: every
 * input zcode needs must reach his phone, even when he is not in the
 * selected task — any task, any subagent).
 *
 * The questions of the SELECTED task already reach the phone (the channel
 * client follows it and the board renders every `permission_request` /
 * `elicitation_request` it sees). The gap is every OTHER conversation: the
 * bot only hears the tasks it subscribes to, and the fork's stream has no
 * replay. This watcher closes it:
 *
 * 1. DISCOVERY — the tasks-index (the same read-only sqlite every reader
 *    of the house uses) names the tasks with recent activity. A task that
 *    can ask a question is a task whose turn is live, and a live turn
 *    refreshes its row. The BOOT pass ignores the window (a question asked
 *    before a daemon restart may still be pending — the newest rows are
 *    scanned once, whatever their age).
 * 2. FOLLOW — the watcher subscribes those tasks on a SECOND channel
 *    connection (the live card is never touched by foreign traffic) and
 *    reads ONE taskSnapshot per attach: the continuous stream does not
 *    replay, so whatever is ALREADY pending is picked from the snapshot's
 *    `runtime.pendingPermissions` / `runtime.pendingElicitations` (the
 *    fork projects them as the very same event shapes the board parses).
 * 3. KEEP — a task with a question still on the phone's board stays
 *    followed even after it falls out of the activity window (its
 *    resolution must still seal the card).
 * 4. DROP — everything else is unsubscribed (the fork's map stays small,
 *    the socket quiet).
 *
 * Subagents need no separate row: the fork's delegating broker rewrites a
 * child's blocking request onto the PARENT session (`origin.kind =
 * "subagent"`), so the parent's stream/snapshot carries it — and the
 * board renders the origin tag from that same event.
 */

/** One task the watcher may follow — the index's own words. */
data class WatchRow(
    val taskId: String,
    val workspacePath: String,
    /** The task's title (the card's context line). */
    val title: String,
    val updatedMs: Long,
)

/** The reconciliation plan: what to follow, what to drop, and the next memory. */
data class WatchPlan(
    val subscribe: List<WatchRow>,
    val unsubscribe: List<String>,
    val next: Map<String, WatchRow>,
)

object InterventionsWatch {

    /** A task updated within this window is a live candidate (a turn's events refresh it). */
    const val ACTIVE_WINDOW_MS = 10L * 60L * 1000L

    /** The windowed set's ceiling — a burst of tasks never balloons the subscriptions. */
    const val WATCH_CAP = 12

    /** The boot pass's ceiling, IGNORING the window (a pre-restart question may still be pending). */
    const val BOOT_SCAN_CAP = 16

    /** One snapshot per attach, retried a bounded number of times (the channel may still be connecting). */
    const val SNAPSHOT_ATTEMPTS = 6

    /**
     * One pass's reconciliation. [fresh] is the index read (already filtered
     * and capped by the caller; newest first); [pendingTasks] are the tasks
     * with a question still on the board — they are KEPT even when absent
     * from the fresh read. PURE: the same inputs answer the same plan.
     */
    fun plan(prev: Map<String, WatchRow>, fresh: List<WatchRow>, pendingTasks: Set<String>): WatchPlan {
        val next = LinkedHashMap<String, WatchRow>()
        fresh.forEach { next[it.taskId] = it }
        // the board's own tasks outlive the window: their resolution must still land
        pendingTasks.forEach { taskId ->
            if (taskId !in next) prev[taskId]?.let { next[taskId] = it }
        }
        val subscribe = next.values.filter { it.taskId !in prev }
        val unsubscribe = prev.keys.filter { it !in next }
        return WatchPlan(subscribe = subscribe, unsubscribe = unsubscribe, next = next)
    }

    /**
     * The snapshot's ALREADY-pending interactions as the event shapes the
     * board parses (`runtime.pendingPermissions` — permission_request —
     * plus `runtime.pendingElicitations` — elicitation_request, the
     * AskUserQuestion/ExitPlanMode projections). A foreign or torn result
     * answers empty (the additive law: never a crash on the wire's
     * shapes).
     */
    fun pendingQuestionEvents(resultJson: String): List<JsonObject> {
        val root = runCatching { Json.parseToJsonElement(resultJson) as? JsonObject }.getOrNull()
        val runtime = root?.get("runtime") as? JsonObject
        if (runtime == null) return emptyList()
        return PENDING_KEYS.flatMap { key ->
            (runtime[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        }
    }

    /** The two snapshot arrays that carry pending interactions (the fork's projection names). */
    private val PENDING_KEYS = listOf("pendingPermissions", "pendingElicitations")
}
