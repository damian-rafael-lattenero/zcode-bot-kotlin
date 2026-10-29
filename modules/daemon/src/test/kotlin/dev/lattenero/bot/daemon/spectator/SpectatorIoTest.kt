package dev.lattenero.bot.daemon.spectator

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The IO layer against real files (and a real sqlite). */
class SpectatorIoTest {

    @TempDir
    lateinit var base: File

    private val stateDir: File get() = File(File(base, "hooks"), "state").apply { mkdirs() }

    @Test
    fun markerNamesUseTheSafeForm() {
        assertEquals(
            File(stateDir, "turn-session_a-b.1.marker").path,
            SpectatorIo.markerPath(stateDir, "session_a-b.1").path,
        )
        // lossy: illegal runs collapse to one underscore (config.marker_path)
        assertEquals("session_x_y", SpectatorIo.safeSid("session/x  y"))
        assertEquals("ses", SpectatorIo.safeSid(""))
    }

    @Test
    fun markerTokenToleratesMissingAndMidWrite() {
        assertEquals("", SpectatorIo.markerToken(stateDir, "s1"))
        SpectatorIo.markerPath(stateDir, "s1").writeText("""{"token":"t1","prompt":"p"}""")
        assertEquals("t1", SpectatorIo.markerToken(stateDir, "s1"))
        SpectatorIo.markerPath(stateDir, "s1").writeText("") // mid-write
        assertEquals("", SpectatorIo.markerToken(stateDir, "s1"))
    }

    @Test
    fun theSidIndexMapsSafeNamesToRolloutSids() {
        File(File(base, "cli"), "rollout").apply { mkdirs() }.let { dir ->
            File(dir, "model-io-session_abc.jsonl").writeText("")
            File(dir, "model-io-session_xy z.jsonl").writeText("")
            File(dir, "other.txt").writeText("")
        }
        val index = SpectatorIo.sidIndex(File(File(base, "cli"), "rollout"))
        assertEquals("session_abc", index["session_abc"])
        assertEquals("session_xy z", index["session_xy_z"])
    }

    @Test
    fun taskLabelReadsTheRealIndexAndFallsBack() {
        val v2 = File(base, "v2").apply { mkdirs() }
        DriverManager.getConnection("jdbc:sqlite:${File(v2, "tasks-index.sqlite").absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE tasks (task_id TEXT, title TEXT, workspace_path TEXT," +
                        " root_path TEXT, deleted INTEGER)",
                )
                st.executeUpdate("INSERT INTO tasks VALUES ('s1', '  mi tarea  ', '/w', '/w', 0)")
                st.executeUpdate("INSERT INTO tasks VALUES ('s2', '', '/Users/x/Projects/bot', '/w', 0)")
                st.executeUpdate("INSERT INTO tasks VALUES ('s3', 'borrada', '/w', '/w', 1)")
            }
        }
        assertEquals("mi tarea", SpectatorIo.taskLabel(base, "s1"))
        assertEquals("bot", SpectatorIo.taskLabel(base, "s2"))
        assertEquals("ZCode", SpectatorIo.taskLabel(base, "s3"))
        assertEquals("ZCode", SpectatorIo.taskLabel(base, "nope"))
    }

    @Test
    fun theF15GateFollowsTheStreamFocus() {
        stateDir.resolve("bot-session.json").writeText(
            """{"sid":"session_main","title":"t","focus_sid":"session_pinned"}""",
        )
        val cfg = SpectatorCfg(onlyActiveTask = true)
        assertTrue(SpectatorIo.taskCurrentOk(base, cfg, "session_pinned"))
        assertFalse(SpectatorIo.taskCurrentOk(base, cfg, "session_main"))
        assertFalse(SpectatorIo.taskCurrentOk(base, cfg, ""))
        assertTrue(SpectatorIo.taskCurrentOk(base, cfg.copy(onlyActiveTask = false), "any"))
    }

    @Test
    fun aLiveViewMutesWithinTenSeconds() {
        val pid = File(stateDir, "agent-view-session_x.pid")
        assertFalse(SpectatorIo.viewAlive(stateDir, "session_x", now = 1000.0))
        pid.writeText("1")
        pid.setLastModified(995_000) // 995.0 s
        assertTrue(SpectatorIo.viewAlive(stateDir, "session_x", now = 1000.0))
        assertFalse(SpectatorIo.viewAlive(stateDir, "session_x", now = 1011.0))
    }

    @Test
    fun cfgReadsKnobsAndLegacySpellings() {
        base.resolve("telegram-remote.json").writeText(
            """{"tools_stream": false, "tools_caja_filtro": "minimo"}""",
        )
        val cfg = SpectatorIo.cfg(base)
        assertEquals(SpectatorCfg(toolsStream = false, toolsBoxFilter = "minimal", onlyActiveTask = true), cfg)
        // absent file → the defaults, exactly config.py's
        assertEquals(SpectatorCfg(), SpectatorIo.cfg(File(base, "nope")))
    }

    @Test
    fun workerSidsComeFromTheRegistry() {
        assertEquals(emptySet(), SpectatorIo.workerSids(stateDir))
        stateDir.resolve("bot-workers.json").writeText(
            """{"session_w": {"t0": 1.0, "prompt": "p", "ws": "/w", "title": "t", "pid": 7}}""",
        )
        assertEquals(setOf("session_w"), SpectatorIo.workerSids(stateDir))
    }
}
