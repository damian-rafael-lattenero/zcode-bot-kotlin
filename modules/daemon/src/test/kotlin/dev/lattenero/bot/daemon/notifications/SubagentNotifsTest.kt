package dev.lattenero.bot.daemon.notifications

import dev.lattenero.bot.contracts.ShadowDecision
import dev.lattenero.bot.daemon.send.SendLeg
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The IO shell against REAL sqlite with the app's own schema: the read
 * filters to the fold's material (running + fresh ends), the state file
 * dedupes across restarts (a NEW instance re-reading the same store
 * announces nothing), a broken state file degrades to an empty memory,
 * and every announcement rides the injected leg LOUD on the commands
 * lane's watcher name.
 */
class SubagentNotifsTest {

    private val now = 2_000_000_000L

    private lateinit var base: File
    private lateinit var state: File
    private val sent = mutableListOf<Pair<ShadowDecision, Boolean>>()

    private val leg = SendLeg { decision, loud -> sent += decision to loud }

    private fun build(): SubagentNotifs = SubagentNotifs(base, state, leg, clockMs = { now })

    /** The app's schema (the columns the read touches) in a temp home. */
    private fun setUpFixture() {
        base = Files.createTempDirectory("subagent-notifs-base").toFile()
        state = Files.createTempDirectory("subagent-notifs-state").toFile()
        val db = File(File(base, "cli"), "db").apply { mkdirs() }
        DriverManager.getConnection("jdbc:sqlite:${File(db, "db.sqlite").absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE session (id TEXT PRIMARY KEY, parent_id TEXT, title TEXT," +
                        " time_created INTEGER, time_updated INTEGER, last_outcome TEXT)",
                )
            }
        }
    }

    /** One child session row, the store's own truth (replace = the end overwrites the run). */
    private fun insert(
        sid: String,
        outcome: String,
        updatedMs: Long,
        createdMs: Long = updatedMs - 600_000L,
        title: String = "misión $sid",
    ) {
        val db = File(File(base, "cli"), "db").resolve("db.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
            c.prepareStatement("INSERT OR REPLACE INTO session VALUES (?, 'p', ?, ?, ?, ?)").use { ps ->
                ps.setString(1, sid)
                ps.setString(2, title)
                ps.setLong(3, createdMs)
                ps.setLong(4, updatedMs)
                ps.setString(5, outcome)
                ps.executeUpdate()
            }
        }
    }

    private fun stateFile(): File = File(state, "subagent-notifs.json")

    @Test
    fun theWitnessedTransitionAnnouncesAndDedupesAcrossRestarts() = runTest {
        setUpFixture()
        insert("sess_subagent_a", "", now - 2_000L)
        val first = build()
        assertTrue(first.pass().isEmpty(), "running speaks nothing")
        // the child ends; every NEW instance (a restart re-loading the
        // state file) must still announce the transition exactly ONCE
        insert("sess_subagent_a", "completed", now - 1_000L)
        val second = build()
        val announced = second.pass()
        second.speak(announced)
        assertEquals(1, announced.size, "the transition announces exactly once")
        assertEquals(0, build().pass().size, "the persisted memory dedupes across restarts")
        assertEquals(1, sent.size)
        val (decision, loud) = sent[0]
        assertEquals(NotifText.WATCHER, decision.watcher)
        assertEquals("subagent_done", decision.decision)
        assertEquals(true, loud, "the owner's order: bien fuertes")
        assertTrue(decision.text.startsWith("🔔 "))
    }

    @Test
    fun aFreshNeverSeenEndAnnouncesAStaleOneDoesNot() {
        setUpFixture()
        insert("fresh", "completed", now - 1_000L)
        insert("ghost", "interrupted", now - SubagentWatch.FRESH_WINDOW_MS * 4)
        val announced = build().pass()
        assertEquals(listOf("fresh"), announced.map { it.sid }, "the ghost earns silence")
    }

    @Test
    fun aMissingDbIsAnEmptyPassAndACrashIsSilence() {
        setUpFixture()
        val dbDir = File(base, "cli/db").apply { deleteRecursively() }
        assertTrue(!dbDir.exists())
        assertTrue(build().pass().isEmpty())
        // a broken store degrades to emptiness too — the fold never sees garbage
        dbDir.mkdirs()
        File(dbDir, "db.sqlite").writeText("this is not a database")
        assertTrue(build().pass().isEmpty())
    }

    @Test
    fun aBrokenStateFileDegradesToAnEmptyMemory() {
        setUpFixture()
        stateFile().writeText("{{{ not json")
        insert("fresh", "completed", now - 1_000L)
        assertEquals(1, build().pass().size, "a broken memory is no memory: the fresh end speaks")
    }

    @Test
    fun theStateFileIsAtomicJsonAndReadableBack() {
        setUpFixture()
        insert("a", "", now - 2_000L)
        build().pass() // records the running witness
        val seen = StateProbe.read(stateFile())
        assertEquals("", seen["a"]?.outcome, "the running witness rides the file")
    }
}

/** The tests' read-back window over the state file's JSON. */
internal object StateProbe {
    private val json = kotlinx.serialization.json.Json

    fun read(file: File): Map<String, SeenOutcome> {
        val root = json.parseToJsonElement(file.readText()) as kotlinx.serialization.json.JsonObject
        return root.entries.associate { (sid, el) ->
            val obj = el as kotlinx.serialization.json.JsonObject
            sid to SeenOutcome(
                outcome = (obj["outcome"] as kotlinx.serialization.json.JsonPrimitive).content,
                atMs = (obj["atMs"] as kotlinx.serialization.json.JsonPrimitive).longOrNull ?: 0L,
            )
        }
    }
}
