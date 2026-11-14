package dev.lattenero.bot.daemon.notifications

import dev.lattenero.bot.channel.PendingQuestion
import dev.lattenero.bot.channel.QuestionBoard
import dev.lattenero.bot.channel.ServerFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kap.Kap
import kap.Process
import kap.RestartPolicy
import kap.Schedule
import kap.process
import kap.repeat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.sql.DriverManager
import java.util.Properties
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * The channel's three calls, in the watcher's own words — the seam that
 * keeps the shell testable without a socket. Implemented by the wiring
 * over the WATCH client (the second connection: foreign traffic never
 * touches the live card's hub).
 */
interface WatchOps {
    suspend fun subscribe(taskId: String, workspacePath: String)

    suspend fun unsubscribe(taskId: String)

    /** The channel's `taskSnapshot` (ONE message) — the raw result JSON, null when it can't answer. */
    suspend fun snapshot(taskId: String, workspacePath: String): String?
}

/**
 * 🧭 THE ANY-TASK WATCHER'S IO SHELL (14/11): the tasks-index read (the
 * same short-lived read-only sqlite pattern as every reader of the house),
 * the follow/unfollow calls and the ONE snapshot per attach — all through
 * [ops], injected so the watcher's laws are pinned without a socket.
 *
 * One pass per daemon tick: read → plan (the pure core) → subscribe the
 * new tasks and hand their already-pending interactions to the SAME
 * [board] the selected task's stream feeds → unsubscribe the dropped ones.
 * A failed snapshot is retried a bounded number of ticks (the channel may
 * still be connecting at boot); the live stream covers the rest.
 */
class InterventionsNotifs(
    /** The CLI's data root: `<base>/v2/tasks-index.sqlite` lives here. */
    private val baseDir: File,
    /** THE board — the same instance the stream callback feeds (one registry, one dedupe). */
    private val board: QuestionBoard,
    private val ops: WatchOps,
    /** taskId → title, shared with the stream callbacks (the card's context line). */
    private val titles: MutableMap<String, String>,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    /** The tasks this watcher follows (its own memory — the client's map is the wire's). */
    private var watched: Map<String, WatchRow> = emptyMap()

    /** The boot pass reads the newest rows REGARDLESS of the window, exactly once. */
    private var booted = false

    /** Attach snapshots still owed, with the attempts already spent. */
    private val owedSnapshots = HashMap<String, Int>()

    /**
     * One pass. Answers how many tasks it started following (the log's
     * number). Environmental failures degrade: a missing index is silence,
     * a dead channel leaves the snapshot owed (retried while it matters).
     */
    suspend fun pass(): Int {
        val nowMs = clockMs()
        val fresh = readRows(nowMs)
        booted = true
        val plan = InterventionsWatch.plan(watched, fresh, board.pendingTaskIds())
        plan.next.forEach { (taskId, row) -> titles[taskId] = row.title }
        plan.subscribe.forEach { row ->
            ops.subscribe(row.taskId, row.workspacePath)
            owedSnapshots[row.taskId] = 0
            logger.info {
                "any-task: following ${row.taskId.takeLast(TASK_TAIL)} «${row.title.take(LOG_PREVIEW)}» " +
                    "(snapshot owed — the stream does not replay)"
            }
        }
        plan.unsubscribe.forEach { taskId ->
            ops.unsubscribe(taskId)
            owedSnapshots.remove(taskId)
            logger.info { "any-task: dropped ${taskId.takeLast(TASK_TAIL)} (outside the window, no pending question)" }
        }
        plan.next.keys.filter { it in owedSnapshots }.forEach { taskId ->
            val row = plan.next.getValue(taskId)
            if (snapshot(taskId, row)) owedSnapshots.remove(taskId)
        }
        watched = plan.next
        return plan.subscribe.size
    }

    /**
     * One attach's snapshot: the channel's taskSnapshot (ONE message — the
     * fork's own light read) whose pending interactions are handed to the
     * board as the SAME frames the stream would have delivered (dedupe by
     * requestId makes a double delivery harmless). True = settled (the
     * answer arrived, even empty); false = retry next tick until
     * [InterventionsWatch.SNAPSHOT_ATTEMPTS].
     */
    private suspend fun snapshot(taskId: String, row: WatchRow): Boolean {
        val result = ops.snapshot(taskId, row.workspacePath)
        if (result == null) return retrySnapshot(taskId)
        registerPending(taskId, row, result)
        return true
    }

    /** A failed attach read: owed again next tick, given up (honestly) after the cap. */
    private fun retrySnapshot(taskId: String): Boolean {
        val attempts = (owedSnapshots[taskId] ?: 0) + 1
        if (attempts < InterventionsWatch.SNAPSHOT_ATTEMPTS) {
            owedSnapshots[taskId] = attempts
            return false
        }
        logger.warn {
            "any-task: snapshot for ${taskId.takeLast(TASK_TAIL)} never answered " +
                "($attempts attempts) — the live stream carries the rest"
        }
        return true
    }

    /** The snapshot's already-pending interactions, frame by frame, into the board. */
    private suspend fun registerPending(taskId: String, row: WatchRow, result: String) {
        InterventionsWatch.pendingQuestionEvents(result).forEach { event ->
            val frame = ServerFrame.StreamEvent(taskId = taskId, seq = 0, at = clockMs(), event = event)
            val registered: PendingQuestion? = board.onStreamEvent(frame, row.title)
            if (registered != null) {
                logger.info {
                    "any-task: question from ${taskId.takeLast(TASK_TAIL)} «${row.title.take(LOG_PREVIEW)}» " +
                        "(${registered.kind}) — the phone's card is out"
                }
            }
        }
    }

    /**
     * The index read: the tasks a live turn could belong to, newest first.
     * The boot pass reads the newest [InterventionsWatch.BOOT_SCAN_CAP]
     * whatever their age (a pre-restart question may still be pending);
     * every later pass keeps the activity window.
     */
    private fun readRows(nowMs: Long): List<WatchRow> {
        val index = File(File(baseDir, "v2"), "tasks-index.sqlite")
        if (!index.exists()) return emptyList()
        val boot = !booted
        val limit = if (boot) InterventionsWatch.BOOT_SCAN_CAP else InterventionsWatch.WATCH_CAP
        val floor = if (boot) 0L else nowMs - InterventionsWatch.ACTIVE_WINDOW_MS
        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:${index.absolutePath}", roProps()).use { conn ->
                conn.prepareStatement(ROWS_QUERY).use { ps ->
                    ps.setLong(1, floor)
                    ps.setInt(2, limit)
                    ps.executeQuery().use { rs ->
                        val rows = mutableListOf<WatchRow>()
                        while (rs.next()) {
                            val taskId = rs.getString(ID_COL).orEmpty().ifBlank { null }
                            val ws = rs.getString(WS_COL).orEmpty().ifBlank { null }
                            if (taskId != null && ws != null) {
                                rows.add(
                                    WatchRow(
                                        taskId = taskId,
                                        workspacePath = ws,
                                        title = rs.getString(TITLE_COL).orEmpty(),
                                        updatedMs = rs.getLong(UPDATED_COL),
                                    ),
                                )
                            }
                        }
                        rows
                    }
                }
            }
        }.getOrElse { error ->
            logger.warn(error) { "any-task: tasks-index read failed — silence this tick" }
            emptyList()
        }
    }

    /**
     * The never-miss Process — one pass per tick at the daemon's own
     * cadence, restarts under the convergence law.
     */
    fun asProcess(every: Duration, onHeartbeat: () -> Unit = {}): Process<Unit> =
        Kap {
            val followed = pass()
            if (followed > 0) logger.info { "any-task: $followed new task(s) followed this tick" }
        }
            .repeat(Schedule.spaced(every))
            .process(
                name = PROCESS_NAME,
                restart = RestartPolicy.exponential(base = 1.seconds),
                heartbeatEvery = HEARTBEAT_EVERY,
                onHeartbeat = onHeartbeat,
                onRestart = { attempt, error, delay ->
                    logger.warn(error) { "any-task watcher pass failed — restart $attempt in $delay" }
                },
            )

    private fun roProps(): Properties = Properties().apply { put("open_mode", SQLITE_READONLY.toString()) }

    private companion object {
        const val SQLITE_READONLY = 0x00000001
        const val PROCESS_NAME = "interventions-watch"
        const val TASK_TAIL = 8
        const val LOG_PREVIEW = 50
        const val ID_COL = 1
        const val WS_COL = 2
        const val TITLE_COL = 3
        const val UPDATED_COL = 4
        val HEARTBEAT_EVERY = 30.seconds

        /**
         * The candidate rows: not deleted/archived, with a real update stamp
         * at or after the floor. The title follows the index's own override
         * law (`title_overridden` wins over the CLI's title).
         */
        const val ROWS_QUERY =
            "SELECT task_id, workspace_path, " +
                "COALESCE(NULLIF(CASE WHEN title_overridden=1 THEN title " +
                "ELSE COALESCE(cli_title, title) END,''), '') AS t, " +
                "COALESCE(cli_updated_at, updated_at) AS u FROM tasks " +
                "WHERE deleted=0 AND COALESCE(cli_deleted,0)=0 AND archived=0 " +
                "AND COALESCE(cli_updated_at, updated_at) >= ? " +
                "ORDER BY u DESC LIMIT ?"
    }
}
