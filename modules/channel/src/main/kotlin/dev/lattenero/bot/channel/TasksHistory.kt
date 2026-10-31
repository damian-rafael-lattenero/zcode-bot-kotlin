package dev.lattenero.bot.channel

/**
 * Python's tasks_repo, read-only: ctx_tasks (reporting.py's `_ctx_tasks`)
 * and model_selection (core's `_cur_sel` source). The tasks-index rows
 * that ALSO have real usage in the CLI's db — the sidebar order frozen
 * by Python's SQL. The daemon implements it with the SAME two sqlite
 * files the spectator already reads (xerial, mode=ro); the channel only
 * sees the rows, never the files.
 */
interface TasksHistory {
    /** Usage-backed tasks in sidebar order, capped — Python's `ctx_tasks(limit)`. */
    suspend fun ctxTasks(limit: Int): List<TaskRow>

    /** The conversation's persisted model selection — Python's model_selection payload. */
    suspend fun modelSelection(sid: String): ModelSel?

    /**
     * The app's OPEN conversation: the freshest alive (not deleted, not
     * archived) task of the workspace — the router's target (29/10's
     * hot-fix: `activeTaskIds` only lists RUNNING tasks, so an idle open
     * conversation is invisible to the channel; the tasks-index sees it).
     */
    suspend fun freshestTask(workspacePath: String): TaskRow?

    /** True when the task exists alive in the index — the tombstone guard (deleted ≠ aimable). */
    suspend fun taskAlive(sid: String): Boolean

    /**
     * /subagents «inactivos»'s source: TODAY's ENDED subagent rows — the
     * cli db's own `session` rows (parent_id IS NOT NULL, last_outcome
     * != '' — an empty outcome is a STILL-RUNNING subagent), newest first,
     * capped at [SUBAGENTS_INACTIVE_CAP]. The daemon implements it with a
     * read-only query on the SAME db file it already opens for ctx_tasks —
     * the app itself persists these rows, so no second journal exists to
     * feed, corrupt or lose on restart (data honesty: every row is a REAL
     * session the CLI wrote, never a bot-invented one).
     */
    suspend fun subagentsToday(parentSid: String?, dayStartMs: Long): List<SubagentRow> = emptyList()

    /**
     * The RUNNING subagent session ids of one parent (last_outcome '' —
     * measured live 30/10), straight from the CLI's own session store. The
     * sa: attach's verification half: a constructed `sess_subagent_<agentId>`
     * is only trusted when the store itself holds it.
     */
    suspend fun subagentSessionIds(parentSid: String): List<String> = emptyList()

    /**
     * The RUNNING subagent sessions of one parent, WITH their titles — the
     * stream-independent truth (the cli db's own session rows, last_outcome
     * empty = running). The /subagents ACTIVE view unions these with the
     * stream's dispatch lanes: the bus can die mid-run (measured), the
     * store cannot — a running subagent is visible even when its events
     * never reached the watcher.
     */
    suspend fun subagentsRunning(parentSid: String): List<RunningSubagent> = emptyList()
}

/** One RUNNING subagent from the store — its session id IS the attach target. */
data class RunningSubagent(val sid: String, val title: String)

/**
 * One ENDED subagent of today — /subagents «inactivos»'s row. `outcome`
 * is the cli db's own last_outcome word (completed, interrupted, …);
 * started/ended are the session's real time_created/time_updated.
 */
data class SubagentRow(
    val title: String,
    val outcome: String,
    val startedMs: Long,
    val endedMs: Long,
)

/** /subagents «inactivos»'s cap — the owner's MAX 10 rows. */
const val SUBAGENTS_INACTIVE_CAP = 10

/** One row of Python's `Task` dataclass (reporting.py) — sid/title/ws carry the list. */
data class TaskRow(
    val sid: String,
    val title: String,
    val ws: String,
    val ctx: Long = 0,
    val model: String = "",
    val whenMs: Long = 0,
)

/** Python's _cur_sel shape: what /status's «🤖 model · 🧠 level» line renders. */
data class ModelSel(val modelId: String, val reasoningLevel: String)

/** Python's `_tasks_scope`: /tasks drops the DEFAULT workspace's loose tasks; /all_tasks keeps them. */
fun scopeRows(rows: List<TaskRow>, all: Boolean, defaultWs: String): List<TaskRow> =
    if (all) rows else rows.filter { it.ws != defaultWs }

/** The history nobody wired — honest emptiness (tests, or a missing index). */
val noHistory: TasksHistory = object : TasksHistory {
    override suspend fun ctxTasks(limit: Int): List<TaskRow> = emptyList()
    override suspend fun modelSelection(sid: String): ModelSel? = null
    override suspend fun freshestTask(workspacePath: String): TaskRow? = null
    override suspend fun taskAlive(sid: String): Boolean = false
}
