package dev.lattenero.bot.daemon.history

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Python's ctx_tasks against REAL sqlite files with the app's schema —
 * the usage filter, the dedupe, the tombstone-safe newborn merge and the
 * cap are the contract, and since the owner's 14/11 law the merged pool
 * rides in EXECUTION-TIME order (usage started_at, else updated_at, else
 * the CLI session's time_updated) — Python's pinned sidebar order feeds
 * the SQL, no longer the phone's list.
 */
class TasksHistoryIoTest {

    @TempDir
    lateinit var base: File

    private companion object {
        /** The newborn tests' frozen now — every birth time hangs off it. */
        const val NOW = 1_794_000_000_000L
    }

    @Test
    fun ctxTasksOrdersByExecutionTimeNotTheSidebar() {
        buildDbs()
        val rows = TasksHistoryIo(base).run { kotlinx.coroutines.runBlocking { ctxTasks(10) } }
        assertEquals(
            listOf("s-nouse", "s-pinned", "s-new", "s-plain"),
            rows.map { it.sid },
            "execution stamps rule: s-nouse's index updated_at 5 beats the pinned row's usage stamp 4 — " +
                "the pinned row no longer floats, the last executor leads",
        )
        assertEquals("sin uso", rows[0].title)
        assertEquals(
            30L,
            rows.first { it.sid == "s-pinned" }.ctx, // input 10 + output 20 of the LATEST usage row
        )
    }

    @Test
    fun aFreshTaskRidesEmptyNeverHidden() {
        buildDbs()
        val rows = TasksHistoryIo(base).run { kotlinx.coroutines.runBlocking { ctxTasks(10) } }
        val fresh = rows.first { it.sid == "s-nouse" }
        assertEquals(0L, fresh.ctx, "no usage yet: honestly empty")
        assertEquals("", fresh.model, "no model yet: honestly empty")
        assertEquals(5L, fresh.whenMs, "the INDEX's own updated_at, a real timestamp")
    }

    @Test
    fun ctxTasksDedupeHoldsAndCapsHold() {
        buildDbs()
        val history = TasksHistoryIo(base)
        val rows = kotlinx.coroutines.runBlocking { history.ctxTasks(2) }
        assertEquals(2, rows.size) // the cap, Python's limit
        val all = kotlinx.coroutines.runBlocking { history.ctxTasks(10) }
        assertEquals(4, all.size) // the s-new legacy dupe never appears twice; s-nouse RIDES
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
        assertNull(kotlinx.coroutines.runBlocking { history.freshestTask("/w/app") })
        assertEquals(false, kotlinx.coroutines.runBlocking { history.taskAlive("s") })
    }

    @Test
    fun freshestTaskIsTheNewestAliveRowOfTheWorkspace() {
        buildDbs()
        val row = kotlinx.coroutines.runBlocking { TasksHistoryIo(base).freshestTask("/w/app") }
        assertEquals("s-plain", row?.sid) // updated_at=100 beats s-new's 50 and the dead s-dead's 999
        assertEquals("tarea plain", row?.title)
        assertNull(kotlinx.coroutines.runBlocking { TasksHistoryIo(base).freshestTask("/w/other") })
    }

    @Test
    fun taskAliveIsTheTombstoneGuard() {
        buildDbs()
        val history = TasksHistoryIo(base)
        assertEquals(true, kotlinx.coroutines.runBlocking { history.taskAlive("s-plain") })
        assertEquals(false, kotlinx.coroutines.runBlocking { history.taskAlive("s-arch") }) // archived
        assertEquals(false, kotlinx.coroutines.runBlocking { history.taskAlive("s-dead") }) // deleted
        assertEquals(false, kotlinx.coroutines.runBlocking { history.taskAlive("unknown") })
    }

    // ---- the newborn law (the owner, 14/11: /run tasks missing from /tasks) ----

    @Test
    fun aNewbornSessionRidesUntilTheIndexCatchesUp() {
        buildNewbornDbs(indexHasRun = false)
        val rows = kotlinx.coroutines.runBlocking { TasksHistoryIo(base) { NOW }.ctxTasks(10) }
        assertEquals(listOf("s-run", "s-old"), rows.map { it.sid })
        val run = rows.first { it.sid == "s-run" }
        assertEquals("falta boton go to background", run.title, "the session's own first_input title")
        assertEquals("/w/app", run.ws, "the session's directory is its workspace")
        assertEquals(0L, run.ctx, "no usage yet: honestly empty")
        assertEquals("", run.model, "no model yet: honestly empty")
        assertEquals(NOW - 30_000L, run.whenMs, "the session's own time_updated — the execution time, never the birth time")
    }

    @Test
    fun theMergeFollowsExecutionTimeNotBirthOrder() {
        buildExecutionOrderDbs()
        val rows = kotlinx.coroutines.runBlocking { TasksHistoryIo(base) { NOW }.ctxTasks(10) }
        assertEquals(
            listOf("s-nb-active", "s-idx-fresh", "s-nb-idle", "s-idx-old"),
            rows.map { it.sid },
            "execution stamps rule: an active newborn outranks a fresher-born but idle one, " +
                "and an idle newborn does not jump an index row that executed after it",
        )
    }

    @Test
    fun aNewbornWithATombstonedIndexRowNeverRides() {
        buildNewbornDbs(indexHasRun = false, indexArchivesRun = true)
        val rows = kotlinx.coroutines.runBlocking { TasksHistoryIo(base) { NOW }.ctxTasks(10) }
        assertEquals(
            listOf("s-old"),
            rows.map { it.sid },
            "the archived index row tombstones the newborn — a row the tap would refuse is never shown",
        )
    }

    @Test
    fun aNewbornWithUsageCarriesItsRealExecutionStamp() {
        buildNewbornDbs(indexHasRun = false, withUsage = true)
        val rows = kotlinx.coroutines.runBlocking { TasksHistoryIo(base) { NOW }.ctxTasks(10) }
        val run = rows.first { it.sid == "s-run" }
        assertEquals(10L, run.ctx, "input 7 + output 3 of the CLI's own model_usage line")
        assertEquals("GLM-9", run.model, "the CLI's own model, no longer honestly empty")
        assertEquals(NOW - 10_000L, run.whenMs, "the usage's started_at — the real last model call")
    }

    @Test
    fun theNewbornRetiresTheMomentItsIndexRowLands() {
        buildNewbornDbs(indexHasRun = true)
        val rows = kotlinx.coroutines.runBlocking { TasksHistoryIo(base) { NOW }.ctxTasks(10) }
        // the merged row is gone and the INDEX's own order rules (updated_at 90 < s-old's 100)
        assertEquals(listOf("s-old", "s-run"), rows.map { it.sid }, "one row, never two of the same sid")
        assertEquals("tarea run del indice", rows[1].title, "the INDEX row wins once it exists")
    }

    @Test
    fun newbornsOutsideTheWindowOrNotConversationsNeverRide() {
        buildNewbornDbs(indexHasRun = false)
        val rows = kotlinx.coroutines.runBlocking { TasksHistoryIo(base) { NOW }.ctxTasks(10) }
        assertEquals(
            listOf("s-run", "s-old"),
            rows.map { it.sid },
            "ancient/subagent/child/deleted/archived sessions never ride",
        )
    }

    @Test
    fun theCapHoldsAcrossTheExecutionTimeMerge() {
        buildNewbornDbs(indexHasRun = false)
        val rows = kotlinx.coroutines.runBlocking { TasksHistoryIo(base) { NOW }.ctxTasks(1) }
        assertEquals(listOf("s-run"), rows.map { it.sid }, "the freshest executor rides first, the cap still trims")
    }

    /**
     * The newborn law's fixture: one index row, and the CLI db's session
     * table as the app writes it. [indexArchivesRun] tombstones the s-run
     * row in the index (the anti-tombstone law); [withUsage] gives the CLI
     * db a model_usage line for s-run (the execution stamp join).
     */
    private fun buildNewbornDbs(indexHasRun: Boolean, indexArchivesRun: Boolean = false, withUsage: Boolean = false) {
        val v2 = File(base, "v2").apply { mkdirs() }
        DriverManager.getConnection("jdbc:sqlite:${File(v2, "tasks-index.sqlite").absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE tasks (task_id TEXT, title TEXT, workspace_path TEXT," +
                        " workspace_key TEXT, pinned INTEGER, updated_at INTEGER, deleted INTEGER, archived INTEGER)",
                )
                st.executeUpdate("INSERT INTO tasks VALUES ('s-old', 'tarea vieja', '/w/app', '/w/app', 0, 100, 0, 0)")
                if (indexHasRun) {
                    // the real row, landed at the first turn's end: the merged one retires
                    st.executeUpdate(
                        "INSERT INTO tasks VALUES ('s-run', 'tarea run del indice', '/w/app', '/w/app', 0, 90, 0, 0)",
                    )
                }
                if (indexArchivesRun) {
                    // the app tombstoned the /run-born conversation: the newborn must not ride
                    st.executeUpdate(
                        "INSERT INTO tasks VALUES ('s-run', 'testeando', '/w/app', '/w/app', 0, 90, 0, 1)",
                    )
                }
            }
        }
        val db = File(File(base, "cli"), "db").apply { mkdirs() }
        DriverManager.getConnection("jdbc:sqlite:${File(db, "db.sqlite").absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE session (id TEXT, title TEXT, directory TEXT, parent_id TEXT," +
                        " task_type TEXT, time_created INTEGER, time_deleted INTEGER, time_archived INTEGER," +
                        " time_updated INTEGER)",
                )
                st.executeUpdate(
                    "INSERT INTO session VALUES ('s-run', 'falta boton go to background', '/w/app', NULL," +
                        " 'interactive', ${NOW - 60_000L}, NULL, NULL, ${NOW - 30_000L})",
                )
                st.executeUpdate(
                    "INSERT INTO session VALUES ('s-ancient', 'vieja', '/w/app', NULL," +
                        " 'interactive', ${NOW - 3 * 60 * 60 * 1000L}, NULL, NULL, ${NOW - 3 * 60 * 60 * 1000L})",
                )
                st.executeUpdate(
                    "INSERT INTO session VALUES ('s-sub', 'hijo', '/w/app', NULL," +
                        " 'subagent_child', ${NOW - 60_000L}, NULL, NULL, ${NOW - 30_000L})",
                )
                st.executeUpdate(
                    "INSERT INTO session VALUES ('s-child', 'hijo', '/w/app', 's-run'," +
                        " 'interactive', ${NOW - 60_000L}, NULL, NULL, ${NOW - 30_000L})",
                )
                st.executeUpdate(
                    "INSERT INTO session VALUES ('s-del', 'borrada', '/w/app', NULL," +
                        " 'interactive', ${NOW - 60_000L}, ${NOW - 30_000L}, NULL, ${NOW - 30_000L})",
                )
                st.executeUpdate(
                    "INSERT INTO session VALUES ('s-arch', 'archivada', '/w/app', NULL," +
                        " 'interactive', ${NOW - 60_000L}, NULL, ${NOW - 30_000L}, ${NOW - 30_000L})",
                )
            }
            if (withUsage) {
                c.createStatement().use { st ->
                    st.executeUpdate(
                        "CREATE TABLE model_usage (session_id TEXT, input_tokens INTEGER," +
                            " output_tokens INTEGER, model_id TEXT, started_at INTEGER)",
                    )
                    st.executeUpdate(
                        "INSERT INTO model_usage VALUES ('s-run', 7, 3, 'GLM-9', ${NOW - 10_000L})",
                    )
                }
            }
        }
    }

    /** The execution-time law's fixture: epoch-scale stamps, newborns and index rows interleaved. */
    private fun buildExecutionOrderDbs() {
        val v2 = File(base, "v2").apply { mkdirs() }
        DriverManager.getConnection("jdbc:sqlite:${File(v2, "tasks-index.sqlite").absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE tasks (task_id TEXT, title TEXT, workspace_path TEXT," +
                        " workspace_key TEXT, pinned INTEGER, updated_at INTEGER, deleted INTEGER, archived INTEGER)",
                )
                st.executeUpdate(
                    "INSERT INTO tasks VALUES ('s-idx-fresh', 'indice fresco', '/w/app', '/w/app', 0, ${NOW - 2_000L}, 0, 0)",
                )
                st.executeUpdate(
                    "INSERT INTO tasks VALUES ('s-idx-old', 'indice viejo', '/w/app', '/w/app', 0, ${NOW - 9_000L}, 0, 0)",
                )
            }
        }
        val db = File(File(base, "cli"), "db").apply { mkdirs() }
        DriverManager.getConnection("jdbc:sqlite:${File(db, "db.sqlite").absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE session (id TEXT, title TEXT, directory TEXT, parent_id TEXT," +
                        " task_type TEXT, time_created INTEGER, time_deleted INTEGER, time_archived INTEGER," +
                        " time_updated INTEGER)",
                )
                st.executeUpdate(
                    "INSERT INTO session VALUES ('s-nb-idle', 'recien nacido ocioso', '/w/app', NULL," +
                        " 'interactive', ${NOW - 4_000L}, NULL, NULL, ${NOW - 8_500L})",
                )
                st.executeUpdate(
                    "INSERT INTO session VALUES ('s-nb-active', 'recien nacido activo', '/w/app', NULL," +
                        " 'interactive', ${NOW - 6_000L}, NULL, NULL, ${NOW - 500L})",
                )
            }
        }
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
                st.executeUpdate("INSERT INTO tasks VALUES ('s-nouse', 'sin uso', '/w/app', '/w/app', 1, 5, 0, 0)")
                st.executeUpdate("INSERT INTO tasks VALUES ('s-arch', 'archivada', '/w/app', '/w/app', 1, 998, 0, 1)")
                // deleted but the FRESHEST: the tombstone guard must never aim here
                st.executeUpdate("INSERT INTO tasks VALUES ('s-dead', 'borrada', '/w/app', '/w/app', 0, 999, 1, 0)")
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
