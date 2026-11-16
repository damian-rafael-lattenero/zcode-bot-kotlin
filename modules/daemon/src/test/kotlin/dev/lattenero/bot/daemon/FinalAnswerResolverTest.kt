package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.ChannelQuery
import dev.lattenero.bot.channel.QueryAnswer
import dev.lattenero.bot.channel.TurnVoice
import kotlinx.coroutines.test.runTest
import java.io.File
import java.sql.DriverManager
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seal's voice resolver (measured 2026-11-03): the two final-answer
 * consumers queried TaskSnapshot WITHOUT the task's workspace, the fork
 * threw "workspacePath is required…", and every seal logged answer=0 —
 * the phone saw the card but never the conclusion. These tests pin the
 * fix: the index's own workspace rides the query, every miss speaks,
 * the retry is bounded, and nothing is ever invented. 16/11: the resolver
 * answers the ORGANIZED voice ([TurnVoice]) — the merged turn's steps, its
 * conclusion the trailing step.
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
        val voice = finalTurnOf(
            "session_x",
            query = { q ->
                queries += q
                QueryAnswer.Ok(snapshot("user" to "q", "assistant" to "the conclusion"))
            },
            workspaceOf = { "/ws/project" },
            delayMs = {},
            log = { warnings += it },
        )
        assertEquals("the conclusion", voice?.conclusion)
        val snap = queries.single() as ChannelQuery.TaskSnapshot
        assertEquals("session_x", snap.taskId)
        assertEquals("/ws/project", snap.workspacePath)
        assertEquals(1, snap.messageLimit, "one message keeps the fork's projection light — a limit-less snapshot times out on a giant session")
        assertTrue(warnings.isEmpty(), "a first-try answer logs nothing")
    }

    @Test
    fun `the merged message's parts rebuild the steps with the conclusion last`() = runTest {
        // THE 16/11 ROOT CAUSE: the fork coalesces every assistant step of a
        // turn into ONE message whose content is the plain concatenation —
        // the raw wall. The parts array is the truth the digest reads.
        val snapshot = """
            {"messages":[
              {"role":"assistant","content":"Voy a revisarPasa, ahora el diffConclusión final.",
               "tools":[{"toolName":"Bash"},{"toolName":"Edit"}],
               "parts":[
                 {"type":"content","content":"Voy a revisar"},
                 {"type":"tool-call","toolIndex":0},
                 {"type":"content","content":"Pasa, ahora el diff"},
                 {"type":"tool-call","toolIndex":1},
                 {"type":"content","content":"Conclusión final."}
               ]}
            ]}
        """.trimIndent()
        val voice = finalTurnOf(
            "session_x",
            query = { _ -> QueryAnswer.Ok(snapshot) },
            workspaceOf = { "/ws/project" },
            delayMs = {},
            log = {},
        )!!
        assertEquals(3, voice.segments.size)
        assertEquals("Bash", voice.segments[1].afterTool)
        assertEquals("Edit", voice.segments[2].afterTool)
        assertEquals("Conclusión final.", voice.conclusion)
        assertTrue(voice.segments.none { it.text.contains("Voy a revisarPasa") }, "the raw concatenation never becomes a step")
    }

    @Test
    fun `an Ok miss speaks, retries once, and stays null`() = runTest {
        var calls = 0
        var delayed = 0L
        val warnings = ArrayList<String>()
        val answer = finalTurnOf(
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
        val answer = finalTurnOf(
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
        val answer = finalTurnOf(
            "session_x",
            query = { q ->
                queries += q
                QueryAnswer.Ok(snapshot("assistant" to "still answered"))
            },
            workspaceOf = { error("index unreadable") },
            delayMs = {},
            log = {},
        )
        assertEquals("still answered", answer?.conclusion)
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
