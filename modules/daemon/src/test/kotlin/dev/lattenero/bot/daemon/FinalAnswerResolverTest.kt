package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.ChannelQuery
import dev.lattenero.bot.channel.QueryAnswer
import kotlinx.coroutines.test.runTest
import java.io.File
import java.sql.DriverManager
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seal's answer resolver (measured 2026-11-03): the two finalAnswer
 * consumers queried TaskSnapshot WITHOUT the task's workspace, the fork
 * threw "workspacePath is required…", and every seal logged answer=0 —
 * the phone saw the card but never the conclusion. These tests pin the
 * fix: the index's own workspace rides the query, every miss speaks,
 * the retry is bounded, and nothing is ever invented.
 */
class FinalAnswerResolverTest {

    private fun snapshot(vararg messages: Pair<String, String>): String {
        val body = messages.joinToString(",") {
            """{"role":"${it.first}","content":"${it.second}"}"""
        }
        return """{"messages":[$body]}"""
    }

    @Test
    fun `asks with the task's own workspace and returns the last assistant answer`() = runTest {
        val queries = ArrayList<ChannelQuery>()
        val warnings = ArrayList<String>()
        val answer = finalAnswerOf(
            "session_x",
            query = { q ->
                queries += q
                QueryAnswer.Ok(snapshot("user" to "q", "assistant" to "the conclusion"))
            },
            workspaceOf = { "/ws/project" },
            delayMs = {},
            log = { warnings += it },
        )
        assertEquals("the conclusion", answer)
        val snap = queries.single() as ChannelQuery.TaskSnapshot
        assertEquals("session_x", snap.taskId)
        assertEquals("/ws/project", snap.workspacePath)
        assertTrue(warnings.isEmpty(), "a first-try answer logs nothing")
    }

    @Test
    fun `an Ok miss speaks, retries once, and stays null`() = runTest {
        var calls = 0
        var delayed = 0L
        val warnings = ArrayList<String>()
        val answer = finalAnswerOf(
            "session_x",
            query = { _ ->
                calls += 1
                QueryAnswer.Ok(snapshot("user" to "q"))
            },
            workspaceOf = { null },
            delayMs = { delayed = it },
            log = { warnings += it },
        )
        assertNull(answer)
        assertEquals(FINAL_ANSWER_ATTEMPTS, calls)
        assertEquals(FINAL_ANSWER_RETRY_MS, delayed)
        assertEquals(FINAL_ANSWER_ATTEMPTS, warnings.size)
        assertTrue(warnings.all { it.contains("no assistant message yet") })
    }

    @Test
    fun `a non-Ok answer logs loudly and stays null`() = runTest {
        val warnings = ArrayList<String>()
        val answer = finalAnswerOf(
            "session_x",
            query = { _ -> QueryAnswer.Failed("bad_request", "workspacePath is required") },
            workspaceOf = { null },
            delayMs = {},
            log = { warnings += it },
        )
        assertNull(answer)
        assertEquals(FINAL_ANSWER_ATTEMPTS, warnings.size)
        assertTrue(warnings.all { it.contains("Failed") })
    }

    @Test
    fun `a workspace lookup failure degrades to a null-workspace query`() = runTest {
        val queries = ArrayList<ChannelQuery>()
        val answer = finalAnswerOf(
            "session_x",
            query = { q ->
                queries += q
                QueryAnswer.Ok(snapshot("assistant" to "still answered"))
            },
            workspaceOf = { error("index unreadable") },
            delayMs = {},
            log = {},
        )
        assertEquals("still answered", answer)
        val snap = queries.single() as ChannelQuery.TaskSnapshot
        assertEquals(null, snap.workspacePath)
    }

    @Test
    fun `workspacePathOf reads the index read-only and degrades honestly`() {
        val dir = Files.createTempDirectory("bot-home").toFile()
        assertNull(workspacePathOf(dir, "s1"), "no index file, no workspace")
        val db = File(File(dir, "v2"), "tasks-index.sqlite")
        db.parentFile.mkdirs()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
            c.createStatement().execute("CREATE TABLE tasks (task_id TEXT, workspace_path TEXT)")
            c.createStatement().execute("INSERT INTO tasks VALUES ('s1', '/ws/a')")
            c.createStatement().execute("INSERT INTO tasks VALUES ('s2', '')")
        }
        assertEquals("/ws/a", workspacePathOf(dir, "s1"))
        assertNull(workspacePathOf(dir, "s2"), "a blank workspace is not a workspace")
        assertNull(workspacePathOf(dir, "missing"))
        db.delete()
        assertNull(workspacePathOf(dir, "s1"))
    }
}
