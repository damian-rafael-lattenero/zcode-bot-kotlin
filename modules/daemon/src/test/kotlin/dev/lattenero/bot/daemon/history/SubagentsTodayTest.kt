package dev.lattenero.bot.daemon.history

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * /subagents «inactivos»'s source — the cli db's OWN `session` rows
 * (parent_id IS NOT NULL, last_outcome != ''): the round-trip is REAL
 * sqlite in, read-only rows out. The fork's own words decide what
 * «ended» means ('completed'/'interrupted' in, '' — still running — out),
 * the day boundary and the MAX 10 cap are the query's contract.
 */
class SubagentsTodayTest {

    @TempDir
    lateinit var base: File

    private val dayStart = 1_798_000_000_000L

    @Test
    fun readsOnlyTodaysEndedSubagentsNewestFirst() {
        buildDb()
        val rows = runBlocking { TasksHistoryIo(base).subagentsToday(null, dayStart) }
        // the running one ('') and yesterday's never appear; newest first
        assertEquals(listOf("the interrupt", "the second", "another task sub", "the first"), rows.map { it.title })
        assertEquals("interrupted", rows[0].outcome)
        assertEquals("completed", rows[1].outcome)
        assertEquals(dayStart + 3_000_000L + 1_800_000L, rows[1].startedMs)
    }

    @Test
    fun parentScopeFiltersTheRows() {
        buildDb()
        val rows = runBlocking { TasksHistoryIo(base).subagentsToday("task-2", dayStart) }
        assertEquals(listOf("another task sub"), rows.map { it.title })
    }

    @Test
    fun capsAtTenRows() {
        buildDb(extraEnded = 12)
        val rows = runBlocking { TasksHistoryIo(base).subagentsToday("task-1", dayStart) }
        assertEquals(10, rows.size, "the MAX 10, newest kept")
        assertTrue(rows[0].endedMs >= rows.last().endedMs, "newest first")
    }

    @Test
    fun missingDbAnswersEmpty() {
        val rows = runBlocking { TasksHistoryIo(base).subagentsToday("task-1", dayStart) }
        assertEquals(emptyList(), rows)
    }

    /** The app's session table with the rows the contract must sort out. */
    private fun buildDb(extraEnded: Int = 0) {
        val db = File(File(base, "cli"), "db").apply { mkdirs() }
        DriverManager.getConnection("jdbc:sqlite:${File(db, "db.sqlite").absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE session (id TEXT PRIMARY KEY, parent_id TEXT, title TEXT," +
                        " last_outcome TEXT, time_created INTEGER, time_updated INTEGER)",
                )
                fun row(id: String, parent: String, title: String, outcome: String, created: Long, updated: Long) =
                    st.executeUpdate(
                        "INSERT INTO session VALUES ('$id', '$parent', '$title', " +
                            "'$outcome', $created, $updated)",
                    )

                val t0 = dayStart + 3_000_000L
                row("sa-1", "task-1", "the first", "completed", t0 - 600_000, t0)
                row("sa-2", "task-1", "the second", "completed", t0 + 2_400_000 - 600_000, t0 + 2_400_000)
                row("sa-3", "task-1", "the interrupt", "interrupted", t0 + 4_800_000 - 900_000, t0 + 4_800_000)
                row("sa-run", "task-1", "still running", "", t0 + 6_000_000, t0 + 6_500_000)
                row("sa-old", "task-1", "from yesterday", "completed", dayStart - 100_000, dayStart - 1)
                row("sa-other", "task-2", "another task sub", "completed", t0, t0 + 1_000)
                repeat(extraEnded) { i ->
                    row("sa-x$i", "task-1", "extra $i", "completed", t0 + 10_000 * i, t0 + 10_000 * i + 5_000)
                }
            }
        }
    }
}
