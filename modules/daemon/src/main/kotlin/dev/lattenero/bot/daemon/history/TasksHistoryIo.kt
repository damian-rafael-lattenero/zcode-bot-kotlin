package dev.lattenero.bot.daemon.history

import dev.lattenero.bot.channel.ModelSel
import dev.lattenero.bot.channel.RunningSubagent
import dev.lattenero.bot.channel.SubagentRow
import dev.lattenero.bot.channel.TaskRow
import dev.lattenero.bot.channel.TasksHistory
import dev.lattenero.bot.daemon.db.typed
import dev.lattenero.bot.daemon.sq.IndexRows
import dev.lattenero.bot.daemon.sq.SubagentsTodayAll
import dev.lattenero.bot.daemon.sq.SubagentsTodayOfParent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.DriverManager
import java.util.Properties

/**
 * Python's tasks_repo, verbatim EXCEPT the fresh-task law: the CLI's
 * usage db JOINed with the app's tasks index — usage where the CLI db
 * holds it, and every alive index row rides even without usage yet (a
 * /run-born conversation's first model_usage line has not been written;
 * the old usage gate hid it from /tasks forever, the owner 12/11). The
 * sidebar order stays Python's SQL (pinned → updated_at desc, the loose
 * default-workspace rows last), plus each conversation's persisted model
 * selection. The same read-only pattern as the spectator: short-lived
 * xerial connections, mode=ro, zero writes. The SQL itself now lives typed
 * in `Cli.sq`/`Tasks.sq` (typing-only schema: both files belong to ZCode).
 *
 * THE NEWBORN LAW (the owner, 14/11: «the new /run tasks do not
 * appear» [translated]): the app writes a /run-born conversation's tasks-index row
 * only at its FIRST TURN'S END (created_at backdated — measured live:
 * the row was absent 3 min after birth), while the CLI db's own session
 * row exists from birth. Every interactive parent session younger than
 * [NEWBORN_WINDOW_MS] rides until the index catches up — the anti-join
 * retires the merged row the moment the real one lands.
 *
 * THE EXECUTION-TIME LAW (the owner, 14/11: «ordered by execution
 * time... and then the most recent ones» [translated]): the merged pool is ordered by
 * each row's EXECUTION stamp — the last model_usage line's started_at,
 * else the index's own updated_at, else the CLI session's time_updated
 * for newborns — never by birth order (the prepend used to hoist a 2h-old
 * idle newborn above a just-active conversation). The RUNNING-first half
 * of the same law is the channel's: it floats the running sids on top of
 * this order at render time.
 */
@Suppress("TooManyFunctions") // one read per contract surface — the class IS the repo port
internal class TasksHistoryIo(
    private val baseDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : TasksHistory {

    // M3: the SQLite walk rides the IO dispatcher — the poller's coroutine
    // (and every tap behind it) never blocks on a file read again.
    override suspend fun ctxTasks(limit: Int): List<TaskRow> = withContext(Dispatchers.IO) {
        val db = cliDb()
        val usage = usageBySession(db)
        val index = tasksIndex()
        val indexed = indexRows(index, usage, limit)
        withNewborns(
            indexed,
            newbornSessions(db, clock() - NEWBORN_WINDOW_MS, usage),
            indexSids(index),
            limit,
        )
    }

    override suspend fun modelSelection(sid: String): ModelSel? {
        val db = cliDb()
        if (!db.exists()) return null
        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}", roProps()).use { conn ->
                conn.typed { bot -> bot.cliQueries.modelSelection(sessionId = sid).executeAsOneOrNull() }
                    ?.let(::selectionOf)
            }
        }.getOrNull()
    }

    /** The open-conversation resolver: freshest alive task of the bot's workspace. */
    override suspend fun freshestTask(workspacePath: String): TaskRow? {
        val index = tasksIndex()
        if (!index.exists()) return null
        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:${index.absolutePath}", roProps()).use { conn ->
                conn.typed { bot -> bot.tasksQueries.freshest(workspacePath = workspacePath).executeAsOneOrNull() }
                    ?.let {
                        TaskRow(
                            sid = it.task_id,
                            title = it.title.orEmpty(),
                            ws = it.workspace_path,
                            whenMs = it.updated_at,
                        )
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
                conn.typed { bot -> bot.tasksQueries.aliveCount(taskId = sid).executeAsOne() > 0 }
            }
        }.getOrDefault(false)
    }

    /**
     * /subagents «inactivos» (the live command token, kept as is) — today's ENDED subagent rows of the cli db's
     * own `session` table (parent_id IS NOT NULL): last_outcome != ''
     * (empty = STILL RUNNING — measured live 30/10: the two running
     * subagents carried '', the ended ones 'completed'/'interrupted'),
     * updated today, newest first, MAX 10 (the owner's cap). Same
     * read-only pattern as every other read here.
     */
    override suspend fun subagentsToday(parentSid: String?, dayStartMs: Long): List<SubagentRow> =
        withContext(Dispatchers.IO) {
            val db = cliDb()
            if (!db.exists()) return@withContext emptyList()
            val parent = parentSid?.takeIf { it.isNotEmpty() }
            runCatching {
                DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}", roProps()).use { conn ->
                    conn.typed { bot ->
                        if (parent != null) {
                            bot.cliQueries.subagentsTodayOfParent(dayStart = dayStartMs, parentId = parent)
                                .executeAsList()
                                .map(::subagentRowOf)
                        } else {
                            bot.cliQueries.subagentsTodayAll(dayStart = dayStartMs)
                                .executeAsList()
                                .map(::subagentRowOf)
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }

    /** One ended-subagent row, from either scope's generated row. */
    private fun subagentRowOf(row: SubagentsTodayAll): SubagentRow =
        SubagentRow(row.title.orEmpty(), row.last_outcome.orEmpty(), row.time_created, row.time_updated)

    private fun subagentRowOf(row: SubagentsTodayOfParent): SubagentRow =
        SubagentRow(row.title.orEmpty(), row.last_outcome.orEmpty(), row.time_created, row.time_updated)

    /**
     * The sa: attach's verification half: the RUNNING subagent session ids
     * of one parent (last_outcome '' = still running — measured live 30/10
     * with two running subagents carrying ''). A constructed
     * `sess_subagent_<agentId>` is only trusted when the store itself holds
     * it; same read-only pattern as every other read here.
     */
    override suspend fun subagentSessionIds(parentSid: String): List<String> {
        val db = cliDb()
        if (!db.exists()) return emptyList()
        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}", roProps()).use { conn ->
                conn.typed { bot -> bot.cliQueries.runningSubIds(parentId = parentSid).executeAsList() }
            }
        }.getOrDefault(emptyList())
    }

    /** The /subagents ACTIVE view's stream-independent half: the parent's RUNNING children, titled, newest first. */
    override suspend fun subagentsRunning(parentSid: String): List<RunningSubagent> =
        withContext(Dispatchers.IO) {
            val db = cliDb()
            if (!db.exists()) return@withContext emptyList()
            runCatching {
                DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}", roProps()).use { conn ->
                    conn.typed { bot ->
                        bot.cliQueries.runningSubsTitled(parentId = parentSid)
                            .executeAsList()
                            .map { RunningSubagent(sid = it.id, title = it.title.orEmpty(), lastMs = it.time_updated) }
                    }
                }
            }.getOrDefault(emptyList())
        }

    /**
     * The merge: fresh CLI sessions whose sid the index does NOT hold —
     * alive OR tombstoned, the tap's taskAlive refuses the tombstoned ones,
     * so a visible row that cannot be attached is a lie — ride into the
     * pool and the WHOLE pool is ordered by execution stamp, then capped.
     */
    private fun withNewborns(
        indexed: List<TaskRow>,
        newborns: List<TaskRow>,
        knownAll: Set<String>,
        limit: Int,
    ): List<TaskRow> = (newborns.filter { it.sid !in knownAll } + indexed)
        .sortedByDescending { it.whenMs }
        .take(limit)

    /**
     * The CLI db's own `session` table — the one store that holds a
     * /run-born conversation from BIRTH (interactive parents only: subagent
     * children and side chats are not conversations). A session's stamp is
     * its usage's started_at when the CLI already logged model calls, else
     * its own time_updated — the execution time, never the birth time. A
     * missing table (older db, tests) answers empty — the index rows ride
     * alone, as before.
     */
    private fun newbornSessions(db: File, cutoffMs: Long, usage: Map<String, Usage>): List<TaskRow> {
        if (!db.exists()) return emptyList()
        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}", roProps()).use { conn ->
                conn.typed { bot ->
                    bot.cliQueries.newborns(cutoff = cutoffMs).executeAsList().map { row ->
                        val u = usage[row.id]
                        TaskRow(
                            sid = row.id,
                            title = row.title.orEmpty(),
                            ws = row.directory.orEmpty(),
                            ctx = u?.ctx ?: 0,
                            model = u?.model.orEmpty(),
                            whenMs = u?.whenMs ?: row.time_updated,
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Latest model_usage per session, subagents excluded — Python's usage dict. */
    private fun usageBySession(db: File): Map<String, Usage> {
        if (!db.exists()) return emptyMap()
        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}", roProps()).use { conn ->
                conn.typed { bot ->
                    bot.cliQueries.usageBySession().executeAsList().associate { row ->
                        row.session_id to Usage(
                            ctx = row.input_tokens + row.output_tokens,
                            model = row.model_id.orEmpty(),
                            whenMs = row.started_at,
                        )
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    /**
     * The index walk: dedupe, usage filter, cap — Python's row loop. The
     * generated query has no LIMIT (the cap applies AFTER the dedupe, so a
     * legacy duplicate never eats a slot); the rows materialize in the
     * alive-index order and the walk stops at [limit], exactly like the
     * cursor used to.
     */
    private fun indexRows(index: File, usage: Map<String, Usage>, limit: Int): List<TaskRow> {
        if (!index.exists()) return emptyList()
        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:${index.absolutePath}", roProps()).use { conn ->
                conn.typed { bot ->
                    val rows = mutableListOf<TaskRow>()
                    val seen = mutableSetOf<String>()
                    for (row in bot.tasksQueries.indexRows().executeAsList()) {
                        if (rows.size >= limit) break
                        rowOf(row, usage, seen)?.let(rows::add)
                    }
                    rows
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * EVERY task_id the index holds — alive, archived, deleted — the
     * newborn merge's anti-tombstone set: a session the app already
     * tombstoned must never ride as a newborn (the tap's taskAlive refuses
     * it; a visible row that cannot be attached is a lie).
     */
    private fun indexSids(index: File): Set<String> {
        if (!index.exists()) return emptySet()
        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:${index.absolutePath}", roProps()).use { conn ->
                conn.typed { bot -> bot.tasksQueries.indexSids().executeAsList().toSet() }
            }
        }.getOrDefault(emptySet())
    }

    /**
     * One index row or null — the dedupe guard lives here. THE FRESH-TASK
     * LAW (the owner, 12/11: «/tasks does not show the new /run tasks» [translated]):
     * a row without CLI usage still RIDES — its first model_usage line has
     * not been written yet, and that absence never hides a conversation the
     * index itself holds (the router's freshestTask already sees it). It
     * rides honestly EMPTY: ctx 0, no model, the index's own updated_at.
     */
    @Suppress("ReturnCount") // one guard per skip, Python's contiguous ifs
    private fun rowOf(row: IndexRows, usage: Map<String, Usage>, seen: MutableSet<String>): TaskRow? {
        val tid = row.task_id
        if (!seen.add(tid)) return null // legacy row of the same task
        val u = usage[tid]
        return TaskRow(
            sid = tid,
            title = row.title.orEmpty(),
            ws = row.workspace_path,
            ctx = u?.ctx ?: 0,
            model = u?.model.orEmpty(),
            whenMs = u?.whenMs ?: row.updated_at,
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

        /** The newborns' window: long enough for any first turn to end and flush its index row. */
        const val NEWBORN_WINDOW_MS = 2 * 60 * 60 * 1000L
    }
}
