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
}

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
}
