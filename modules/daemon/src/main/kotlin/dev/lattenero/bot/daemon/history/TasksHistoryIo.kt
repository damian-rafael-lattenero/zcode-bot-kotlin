package dev.lattenero.bot.daemon.history

import dev.lattenero.bot.channel.ModelSel
import dev.lattenero.bot.channel.TaskRow
import dev.lattenero.bot.channel.TasksHistory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Properties

/**
 * Python's tasks_repo, verbatim: the CLI's usage db JOINed with the
 * app's tasks index — tasks with REAL usage in the sidebar order frozen
 * by Python's SQL (pinned → updated_at desc, the loose default-workspace
 * rows last), plus each conversation's persisted model selection. The
 * same read-only pattern as the spectator: short-lived xerial
 * connections, mode=ro, zero writes.
 */
internal class TasksHistoryIo(private val baseDir: File) : TasksHistory {

    override suspend fun ctxTasks(limit: Int): List<TaskRow> {
        val usage = usageBySession(cliDb())
        if (usage.isEmpty()) return emptyList()
        return indexRows(tasksIndex(), usage, limit)
    }

    override suspend fun modelSelection(sid: String): ModelSel? {
        val db = cliDb()
        if (!db.exists()) return null
        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}", roProps()).use { conn ->
                conn.prepareStatement(SELECTION_QUERY).use { ps ->
                    ps.setString(1, sid)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) null else selectionOf(rs.getString(1))
                    }
                }
            }
        }.getOrNull()
    }

    /** The open-conversation resolver: freshest alive task of the bot's workspace. */
    override suspend fun freshestTask(workspacePath: String): TaskRow? {
        val index = tasksIndex()
        if (!index.exists()) return null
        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:${index.absolutePath}", roProps()).use { conn ->
                conn.prepareStatement(FRESHEST_QUERY).use { ps ->
                    ps.setString(1, workspacePath)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            TaskRow(
                                sid = rs.getString(TID_COL),
                                title = rs.getString(TITLE_COL).orEmpty(),
                                ws = rs.getString(WS_COL).orEmpty(),
                                whenMs = rs.getLong(UPD_COL),
                            )
                        }
                    }
                }
            }
        }.getOrNull()
    }

    /** The tombstone guard: a deleted/archived task must never be aimed at again. */
    override suspend fun taskAlive(sid: String): Boolean {
        val index = tasksIndex()
        if (!index.exists()) return false
        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:${index.absolutePath}", roProps()).use { conn ->
                conn.prepareStatement(ALIVE_QUERY).use { ps ->
                    ps.setString(1, sid)
                    ps.executeQuery().use { rs -> rs.next() && rs.getLong(1) > 0 }
                }
            }
        }.getOrDefault(false)
    }

    /** Latest model_usage per session, subagents excluded — Python's usage dict. */
    private fun usageBySession(db: File): Map<String, Usage> {
        if (!db.exists()) return emptyMap()
        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}", roProps()).use { conn ->
                conn.prepareStatement(USAGE_QUERY).use { ps ->
                    ps.executeQuery().use { rs ->
                        val out = mutableMapOf<String, Usage>()
                        while (rs.next()) {
                            out[rs.getString(SESSION_COL)] = Usage(
                                ctx = rs.getLong(IN_COL) + rs.getLong(OUT_COL),
                                model = rs.getString(MODEL_COL).orEmpty(),
                                whenMs = rs.getLong(WHEN_COL),
                            )
                        }
                        out
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** The index walk: dedupe, usage filter, cap — Python's row loop. */
    private fun indexRows(index: File, usage: Map<String, Usage>, limit: Int): List<TaskRow> {
        if (!index.exists()) return emptyList()
        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:${index.absolutePath}", roProps()).use { conn ->
                conn.prepareStatement(INDEX_QUERY).use { ps ->
                    ps.executeQuery().use { rs ->
                        val rows = mutableListOf<TaskRow>()
                        val seen = mutableSetOf<String>()
                        while (rs.next() && rows.size < limit) {
                            rowOf(rs, usage, seen)?.let(rows::add)
                        }
                        rows
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    /** One index row or null — the dedupe/usage guards live here (Python's loop body). */
    @Suppress("ReturnCount") // one guard per skip, Python's three contiguous ifs
    private fun rowOf(rs: ResultSet, usage: Map<String, Usage>, seen: MutableSet<String>): TaskRow? {
        val tid = rs.getString(TID_COL) ?: return null
        if (!seen.add(tid)) return null // legacy row of the same task
        val u = usage[tid] ?: return null // no usage → no context
        return TaskRow(
            sid = tid,
            title = rs.getString(TITLE_COL).orEmpty(),
            ws = rs.getString(WS_COL).orEmpty(),
            ctx = u.ctx,
            model = u.model,
            whenMs = u.whenMs,
        )
    }

    /** The data JSON → {modelId, reasoningLevel} — Python's model_selection payload. */
    private fun selectionOf(data: String?): ModelSel? = runCatching {
        val sel = (Json.parseToJsonElement(data.orEmpty()) as? JsonObject)
            ?.get("modelSelection")?.jsonObject ?: return null
        val options = (sel["options"] as? JsonObject) ?: JsonObject(emptyMap())
        ModelSel(
            modelId = sel["modelId"]?.jsonPrimitive?.content ?: return null,
            reasoningLevel = options["reasoningLevel"]?.jsonPrimitive?.content ?: "high",
        )
    }.getOrNull()

    private fun cliDb(): File = File(File(baseDir, "cli"), "db").resolve("db.sqlite")

    private fun tasksIndex(): File = File(baseDir, "v2").resolve("tasks-index.sqlite")

    private fun roProps(): Properties = Properties().apply { put("open_mode", SQLITE_READONLY.toString()) }

    private data class Usage(val ctx: Long, val model: String, val whenMs: Long)

    private companion object {
        const val SQLITE_READONLY = 0x00000001
        const val SESSION_COL = 1
        const val IN_COL = 2
        const val OUT_COL = 3
        const val MODEL_COL = 4
        const val WHEN_COL = 5
        const val TID_COL = 1
        const val TITLE_COL = 2
        const val WS_COL = 3
        const val UPD_COL = 4

        /** Python's frozen SQL — the app's sidebar order depends on it. */
        const val USAGE_QUERY =
            "SELECT m.session_id, m.input_tokens, m.output_tokens, m.model_id, m.started_at " +
                "FROM model_usage m JOIN (SELECT session_id, MAX(started_at) ms FROM model_usage " +
                "GROUP BY session_id) l ON l.session_id=m.session_id AND l.ms=m.started_at " +
                "WHERE m.session_id NOT LIKE 'sess_subagent%'"

        const val INDEX_QUERY =
            "SELECT task_id, title, workspace_path FROM tasks WHERE deleted=0 AND archived=0 " +
                "ORDER BY (workspace_key = workspace_path) DESC, pinned DESC, updated_at DESC"

        /** Python's model_selection: the newest runtime/model_selection row of the session. */
        const val SELECTION_QUERY =
            "SELECT data FROM session_entry WHERE session_id=? " +
                "AND type='runtime/model_selection' ORDER BY time_created DESC LIMIT 1"

        /** The router's target: the workspace's freshest alive conversation (updated_at = "in front"). */
        const val FRESHEST_QUERY =
            "SELECT task_id, title, workspace_path, updated_at FROM tasks " +
                "WHERE deleted=0 AND archived=0 AND workspace_path=? " +
                "ORDER BY updated_at DESC LIMIT 1"

        /** The tombstone guard's count: alive ⇔ a row exists with deleted=0 AND archived=0. */
        const val ALIVE_QUERY =
            "SELECT COUNT(*) FROM tasks WHERE task_id=? AND deleted=0 AND archived=0"
    }
}
