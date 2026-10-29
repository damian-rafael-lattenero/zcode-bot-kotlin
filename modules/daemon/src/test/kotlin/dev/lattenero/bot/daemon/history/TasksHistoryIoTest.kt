package dev.lattenero.bot.daemon.history

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Python's ctx_tasks against REAL sqlite files with the app's schema —
 * the frozen SQL order (pinned → updated_at desc, loose rows last),
 * the usage filter, the dedupe and the cap are the contract.
 */
class TasksHistoryIoTest {

    @TempDir
    lateinit var base: File

    @Test
    fun ctxTasksFollowsPythonsFrozenOrderAndFilters() {
        buildDbs()
        val rows = TasksHistoryIo(base).run { kotlinx.coroutines.runBlocking { ctxTasks(10) } }
        assertEquals(
            listOf("s-pinned", "s-new", "s-plain"),
            rows.map { it.sid },
        )
        assertEquals("tarea pinned", rows[0].title)
        assertEquals(30L, rows[0].ctx) // input 10 + output 20 of the LATEST usage row
    }

    @Test
    fun ctxTasksDropsNoUsageLegacyDupesAndCaps() {
        buildDbs()
        val history = TasksHistoryIo(base)
        val rows = kotlinx.coroutines.runBlocking { history.ctxTasks(2) }
        assertEquals(2, rows.size) // the cap, Python's limit
        val all = kotlinx.coroutines.runBlocking { history.ctxTasks(10) }
        assertEquals(3, all.size) // s-nouse and the s-new legacy dupe never appear
    }

    @Test
    fun modelSelectionReadsTheNewestRow() {
        buildDbs()
        val history = TasksHistoryIo(base)
        val sel = kotlinx.coroutines.runBlocking { history.modelSelection("s-plain") }
        assertEquals("GLM-5.3", sel?.modelId)
        assertEquals("high", sel?.reasoningLevel)
        assertNull(kotlinx.coroutines.runBlocking { history.modelSelection("unknown") })
    }

    @Test
    fun missingDbsAnswerEmpty() {
        val history = TasksHistoryIo(base)
        assertEquals(
            emptyList(),
            kotlinx.coroutines.runBlocking { history.ctxTasks(10) },
        )
        assertNull(kotlinx.coroutines.runBlocking { history.modelSelection("s") })
    }

    /** The app's schema (the columns Python's frozen SQL touches) with Python-like rows. */
    private fun buildDbs() {
        val v2 = File(base, "v2").apply { mkdirs() }
        DriverManager.getConnection("jdbc:sqlite:${File(v2, "tasks-index.sqlite").absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE tasks (task_id TEXT, title TEXT, workspace_path TEXT," +
                        " workspace_key TEXT, pinned INTEGER, updated_at INTEGER, deleted INTEGER, archived INTEGER)",
                )
                // the loose default-workspace row sorts LAST (workspace_key = workspace_path is false)
                st.executeUpdate("INSERT INTO tasks VALUES ('s-plain', 'tarea plain', '/w/app', 'x', 0, 100, 0, 0)")
                st.executeUpdate("INSERT INTO tasks VALUES ('s-new', 'nueva', '/w/app', '/w/app', 0, 50, 0, 0)")
                st.executeUpdate("INSERT INTO tasks VALUES ('s-pinned', 'tarea pinned', '/w/app', '/w/app', 1, 10, 0, 0)")
                st.executeUpdate("INSERT INTO tasks VALUES ('s-nouse', 'sin uso', '/w/app', '/w/app', 1, 999, 0, 0)")
                st.executeUpdate("INSERT INTO tasks VALUES ('s-arch', 'archivada', '/w/app', '/w/app', 1, 998, 0, 1)")
                // legacy duplicate of s-new: deduped, never listed twice
                st.executeUpdate("INSERT INTO tasks VALUES ('s-new', 'nueva', '/w/app', '/w/app', 0, 40, 0, 0)")
            }
        }
        val db = File(File(base, "cli"), "db").apply { mkdirs() }
        DriverManager.getConnection("jdbc:sqlite:${File(db, "db.sqlite").absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE model_usage (session_id TEXT, input_tokens INTEGER," +
                        " output_tokens INTEGER, model_id TEXT, started_at INTEGER)",
                )
                st.executeUpdate("INSERT INTO model_usage VALUES ('s-plain', 1, 1, 'GLM-4', 1)")
                st.executeUpdate("INSERT INTO model_usage VALUES ('s-plain', 10, 20, 'GLM-5.3', 2)")
                st.executeUpdate("INSERT INTO model_usage VALUES ('s-new', 5, 5, 'GLM-5.3', 3)")
                st.executeUpdate("INSERT INTO model_usage VALUES ('s-pinned', 10, 20, 'GLM-5.3', 4)")
                st.executeUpdate("INSERT INTO model_usage VALUES ('sess_subagent-9', 99, 99, 'GLM-5.3', 99)")
            }
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE session_entry (session_id TEXT, type TEXT, data TEXT, time_created INTEGER)",
                )
                st.executeUpdate(
                    "INSERT INTO session_entry VALUES ('s-plain', 'runtime/model_selection', " +
                        "'{\"modelSelection\":{\"modelId\":\"GLM-5.3\",\"options\":{\"reasoningLevel\":\"high\"}}}', 2)",
                )
                st.executeUpdate(
                    "INSERT INTO session_entry VALUES ('s-plain', 'runtime/model_selection', " +
                        "'{\"modelSelection\":{\"modelId\":\"GLM-4\",\"options\":{\"reasoningLevel\":\"low\"}}}', 1)",
                )
            }
        }
    }
}
