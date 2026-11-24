package dev.lattenero.bot.daemon.notifications

import dev.lattenero.bot.contracts.ShadowDecision
import dev.lattenero.bot.daemon.send.SendLeg
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shell's laws against REAL sqlite with the app's own tasks-index
 * shape: the boot pass records silently, a witnessed transition speaks
 * LOUD through the injected leg, the persisted memory dedupes across
 * restarts, a stale end stays a ghost, and a missing store is silence.
 */
class GlobalNoticesIoTest {

    private val now = 2_000_000_000L

    private lateinit var base: File
    private lateinit var state: File
    private val sent = mutableListOf<Pair<ShadowDecision, Boolean>>()

    private val leg = SendLeg { decision, loud -> sent += decision to loud }

    private fun build(): GlobalNoticesIo = GlobalNoticesIo(base, state, leg, clockMs = { now })

    private fun db(): File = File(File(base, "v2"), "tasks-index.sqlite")

    /** The app's schema (the columns the read touches) in a temp home. */
    private fun setUpFixture() {
        base = Files.createTempDirectory("global-notices-base").toFile()
        state = Files.createTempDirectory("global-notices-state").toFile()
        val dir = File(base, "v2").apply { mkdirs() }
        DriverManager.getConnection("jdbc:sqlite:${File(dir, "tasks-index.sqlite").absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE tasks (task_id TEXT PRIMARY KEY, title TEXT, workspace_path TEXT NOT NULL," +
                        " workspace_key TEXT, pinned INTEGER, updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL," +
                        " archived INTEGER NOT NULL, title_overridden INTEGER, cli_title TEXT, cli_updated_at INTEGER," +
                        " cli_deleted INTEGER, task_status TEXT, cli_last_outcome TEXT)",
                )
            }
        }
    }

    /** One tasks-index row, replace = the transition overwrites the run. */
    private fun insert(
        sid: String,
        status: String,
        outcome: String,
        updatedMs: Long,
        workspacePath: String = "/Users/damian/Projects/app",
    ) {
        DriverManager.getConnection("jdbc:sqlite:${db().absolutePath}").use { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO tasks (task_id, title, workspace_path, workspace_key, pinned," +
                    " updated_at, deleted, archived, title_overridden, cli_title, cli_updated_at, cli_deleted," +
                    " task_status, cli_last_outcome) VALUES (?, ?, ?, ?, 0, ?, 0, 0, 0, ?, ?, 0, ?, ?)",
            ).use { ps ->
                ps.setString(1, sid)
                ps.setString(2, "tarea $sid")
                ps.setString(3, workspacePath)
                ps.setString(4, workspacePath)
                ps.setLong(5, updatedMs)
                ps.setString(6, "tarea $sid")
                ps.setLong(7, updatedMs)
                ps.setString(8, status)
                ps.setString(9, outcome)
                ps.executeUpdate()
            }
        }
    }

    @Test
    fun theBootPassIsSilentAndTheTransitionSpeaks() = runBlocking {
        setUpFixture()
        insert("a", "running", "", now - 2_000L)
        val io = build()
        assertTrue(io.pass().isEmpty(), "the first pass is the silent baseline")

        insert("a", "completed", "completed", now - 1_000L)
        val notices = io.pass()
        assertEquals(1, notices.size, "the transition speaks")
        io.speak(notices)
        assertEquals(1, sent.size)
        val (decision, loud) = sent[0]
        assertEquals("task_finished", decision.decision)
        assertEquals(true, loud, "the owner's order: bien fuertes")
        assertTrue(decision.text.startsWith("🏁 "))

        assertEquals(0, build().pass().size, "the persisted memory dedupes across restarts")
    }

    @Test
    fun aFailedTransitionSpeaksFailed() = runBlocking {
        setUpFixture()
        insert("a", "running", "", now - 2_000L)
        build().pass()
        insert("a", "error", "error", now - 1_000L)
        val io = build()
        io.speak(io.pass())
        assertEquals("task_failed", sent[0].first.decision)
        assertTrue(sent[0].first.text.startsWith("❌ "))
    }

    @Test
    fun aVoiceThatAlreadySpokeFoldsTheGlobalNotice() = runBlocking {
        setUpFixture()
        insert("a", "running", "", now - 2_000L)
        build().pass() // the silent baseline writes the memory
        insert("a", "completed", "completed", now - 1_000L)
        // the live card's seal spoke this very transition first
        val voice = TaskVoiceGate(clockMs = { now })
        voice.trySpeak("a", TaskSignal.Terminal.FINISHED, turnHintMs = now - 2_000L)
        val io = GlobalNoticesIo(base, state, leg, clockMs = { now }, voice = voice)
        io.speak(io.pass())
        assertTrue(sent.isEmpty(), "one transition, one voice — the global stays folded")
    }

    @Test
    fun aStaleEndAtBootIsTheGhostItAlreadyWas() = runBlocking {
        setUpFixture()
        insert("old", "completed", "completed", now - GlobalNotices.FRESH_WINDOW_MS * 4)
        assertTrue(build().pass().isEmpty())
    }

    @Test
    fun aMissingStoreIsSilence() = runBlocking {
        setUpFixture()
        File(base, "v2").deleteRecursively()
        assertTrue(build().pass().isEmpty())
    }
}
