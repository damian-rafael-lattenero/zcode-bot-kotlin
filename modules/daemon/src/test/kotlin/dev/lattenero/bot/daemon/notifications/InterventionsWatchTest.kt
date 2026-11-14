package dev.lattenero.bot.daemon.notifications

import dev.lattenero.bot.channel.QuestionBoard
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The any-task watcher (14/11): the pure reconciliation (follow the fresh,
 * keep the tasks with a pending card, drop the rest), the snapshot's
 * projection into the board's own event shapes, and the shell against REAL
 * sqlite with the app's tasks-index schema — the boot pass ignores the
 * window, the steady pass keeps it, and a snapshot that never answers is
 * retried a bounded number of ticks.
 */
class InterventionsWatchTest {

    private val now = 2_000_000_000L

    private fun row(id: String, updatedMs: Long = now, title: String = "task $id"): WatchRow =
        WatchRow(taskId = id, workspacePath = "/w/$id", title = title, updatedMs = updatedMs)

    // ---- the pure plan ----

    @Test
    fun freshTasksAreFollowedAndDroppedOnesUnsubscribed() {
        val plan = InterventionsWatch.plan(
            prev = mapOf("a" to row("a"), "b" to row("b")),
            fresh = listOf(row("a"), row("c")),
            pendingTasks = emptySet(),
        )
        assertEquals(listOf("c"), plan.subscribe.map { it.taskId })
        assertEquals(listOf("b"), plan.unsubscribe)
        assertEquals(setOf("a", "c"), plan.next.keys)
    }

    @Test
    fun aPendingTasksQuestionOutlivesTheWindow() {
        val plan = InterventionsWatch.plan(
            prev = mapOf("a" to row("a")),
            fresh = emptyList(), // the task fell out of the window…
            pendingTasks = setOf("a"), // …but its card is still on the phone
        )
        assertTrue(plan.subscribe.isEmpty())
        assertTrue(plan.unsubscribe.isEmpty())
        assertEquals(setOf("a"), plan.next.keys)
    }

    @Test
    fun aSecondPassReconcilesToTheSameSet() {
        val first = InterventionsWatch.plan(emptyMap(), listOf(row("a")), emptySet())
        val second = InterventionsWatch.plan(first.next, listOf(row("a")), emptySet())
        assertTrue(second.subscribe.isEmpty(), "an already-followed task is never re-subscribed")
        assertTrue(second.unsubscribe.isEmpty())
        assertEquals(first.next, second.next)
    }

    // ---- the snapshot's projection ----

    @Test
    fun theSnapshotsPendingInteractionsBecomeTheBoardsEvents() {
        val json = """
            {"meta":{"taskId":"t1"},
             "runtime":{
               "pendingPermissions":[
                 {"type":"permission_request","taskId":"t1","requestId":"rp","description":"¿corro?"}],
               "pendingElicitations":[
                 {"type":"elicitation_request","taskId":"t1","requestId":"re","message":"¿y esto?",
                  "questions":[{"question":"¿y esto?","options":[{"value":"si","label":"sí"}]}]}]}}
        """.trimIndent()
        val events = InterventionsWatch.pendingQuestionEvents(json)
        assertEquals(listOf("permission_request", "elicitation_request"), events.map { it["type"].toString().trim('"') })
    }

    @Test
    fun aForeignOrTornSnapshotAnswersEmpty() {
        assertTrue(InterventionsWatch.pendingQuestionEvents("no soy json").isEmpty())
        assertTrue(InterventionsWatch.pendingQuestionEvents("""{"meta":{}}""").isEmpty())
        assertTrue(InterventionsWatch.pendingQuestionEvents("""{"runtime":[]}""").isEmpty())
    }

    // ---- the shell against real sqlite ----

    private class Seams {
        val subscribed = mutableListOf<Pair<String, String>>()
        val unsubscribed = mutableListOf<String>()
        var snapshots: MutableMap<String, String?> = mutableMapOf()
        val snapshotCalls = mutableListOf<String>()

        fun ops(): WatchOps = object : WatchOps {
            override suspend fun subscribe(taskId: String, workspacePath: String) {
                subscribed.add(taskId to workspacePath)
            }

            override suspend fun unsubscribe(taskId: String) {
                unsubscribed.add(taskId)
            }

            override suspend fun snapshot(taskId: String, workspacePath: String): String? {
                snapshotCalls.add(taskId)
                return snapshots[taskId]
            }
        }
    }

    private fun pendingSnapshot(taskId: String, requestId: String) =
        """{"runtime":{"pendingElicitations":[{"type":"elicitation_request","taskId":"$taskId",
            "requestId":"$requestId","questions":[{"question":"¿sigo?","options":[{"value":"si","label":"sí"}]}]}]}}"""

    private lateinit var base: File
    private lateinit var board: QuestionBoard
    private val cardTexts = mutableListOf<String>()

    private fun setUpFixture(): Seams {
        base = Files.createTempDirectory("interventions-base").toFile()
        val v2 = File(base, "v2").apply { mkdirs() }
        DriverManager.getConnection("jdbc:sqlite:${File(v2, "tasks-index.sqlite").absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE tasks (task_id TEXT, workspace_path TEXT, title TEXT, " +
                        "title_overridden INTEGER DEFAULT 0, cli_title TEXT, " +
                        "cli_updated_at INTEGER, updated_at INTEGER, " +
                        "deleted INTEGER DEFAULT 0, cli_deleted INTEGER, archived INTEGER DEFAULT 0)",
                )
            }
        }
        cardTexts.clear()
        board = QuestionBoard(
            sendQuestion = { text, _ ->
                cardTexts.add(text)
                7L
            },
        )
        return Seams()
    }

    private fun insert(id: String, updatedMs: Long, title: String = "task $id", cliTitle: String? = null) {
        val db = File(File(base, "v2"), "tasks-index.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
            c.prepareStatement("INSERT INTO tasks VALUES (?, '', '', 0, ?, ?, ?, 0, 0, 0)").use { ps ->
                ps.setString(1, id)
                ps.setString(2, cliTitle)
                ps.setLong(3, updatedMs)
                ps.setLong(4, updatedMs)
                ps.executeUpdate()
            }
            c.prepareStatement("UPDATE tasks SET workspace_path=?, title=? WHERE task_id=?").use { ps ->
                ps.setString(1, "/w/$id")
                ps.setString(2, title)
                ps.setString(3, id)
                ps.executeUpdate()
            }
        }
    }

    private fun watcher(seams: Seams): InterventionsNotifs = InterventionsNotifs(
        baseDir = base,
        board = board,
        ops = seams.ops(),
        titles = mutableMapOf(),
        clockMs = { now },
    )

    @Test
    fun theBootPassScansBeyondTheWindowAndTheCardCarriesTheTaskTitle() = runTest {
        val seams = setUpFixture()
        insert("fresh", now - 1_000L, title = "kap — detekt laws")
        insert("stale", now - 3L * 60L * 60L * 1000L, title = "viejo")
        seams.snapshots["fresh"] = pendingSnapshot("fresh", "r1")
        val watcher = watcher(seams)

        assertEquals(2, watcher.pass(), "the boot pass follows BOTH: the window is ignored once")
        assertEquals(setOf("fresh", "stale"), seams.subscribed.map { it.first }.toSet())
        assertTrue(seams.unsubscribed.isEmpty())
        assertTrue("kap — detekt laws" in cardTexts.single())
        assertEquals("fresh", board.pendingTaskIds().single())
    }

    @Test
    fun theSteadyPassKeepsTheWindowAndDropsTheRest() = runTest {
        val seams = setUpFixture()
        insert("fresh", now - 1_000L)
        val watcher = watcher(seams)
        watcher.pass() // boot
        // a task that ended 30 minutes ago never enters the steady window
        insert("old", now - 30L * 60L * 1000L)
        seams.snapshots["old"] = pendingSnapshot("old", "r2")
        watcher.pass()
        assertTrue("old" !in seams.subscribed.map { it.first })
        assertTrue("fresh" in seams.subscribed.map { it.first })
    }

    @Test
    fun aTaskWithAPendingCardStaysFollowedAfterItLeavesTheWindow() = runTest {
        val seams = setUpFixture()
        insert("live", now - 1_000L)
        seams.snapshots["live"] = pendingSnapshot("live", "r1")
        val watcher = watcher(seams)
        watcher.pass() // boot: followed + the card is out
        assertEquals(setOf("live"), board.pendingTaskIds())
        // the turn went quiet (no more events): the row ages out of the window
        insert("live", now - 40L * 60L * 1000L)
        watcher.pass()
        assertTrue(seams.unsubscribed.isEmpty(), "the pending question keeps its stream alive")
    }

    @Test
    fun aSnapshotThatNeverAnswersIsRetriedThenGivenUp() = runTest {
        val seams = setUpFixture()
        insert("q", now - 1_000L)
        seams.snapshots["q"] = null
        val watcher = watcher(seams)
        repeat(InterventionsWatch.SNAPSHOT_ATTEMPTS) { watcher.pass() }
        assertEquals(
            InterventionsWatch.SNAPSHOT_ATTEMPTS,
            seams.snapshotCalls.count { it == "q" },
            "the owed snapshot is retried exactly up to the cap",
        )
        watcher.pass()
        assertEquals(
            InterventionsWatch.SNAPSHOT_ATTEMPTS,
            seams.snapshotCalls.count { it == "q" },
            "after the cap the watcher stops asking (the live stream carries the rest)",
        )
        assertTrue(seams.unsubscribed.isEmpty(), "giving up on the snapshot never drops the stream")
    }
}
