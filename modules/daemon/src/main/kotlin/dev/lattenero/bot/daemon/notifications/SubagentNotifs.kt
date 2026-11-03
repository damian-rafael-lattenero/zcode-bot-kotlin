package dev.lattenero.bot.daemon.notifications

import dev.lattenero.bot.contracts.ShadowDecision
import dev.lattenero.bot.daemon.send.SendLeg
import dev.lattenero.bot.daemon.shadow.TimeIso
import io.github.oshai.kotlinlogging.KotlinLogging
import kap.Kap
import kap.Process
import kap.RestartPolicy
import kap.Schedule
import kap.process
import kap.repeat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.sql.DriverManager
import java.util.Properties
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * 🔔 MOMENT 1's IO shell: the store read (read-only sqlite, the SAME
 * short-lived-connection pattern every other reader of the CLI db uses),
 * the state file under the daemon's OWN state dir (`<home>/state`,
 * atomic tmp+fsync+rename — the house write invariant) and the send leg
 * (the COMMANDS lane — the same shared tg-cooldown/outbox throat zconn
 * and the command replies ride; never a new un-cooled throat).
 *
 * One pass per tick: read → fold → persist-if-changed → announce; the
 * speak and the pass split like zconn's, so the sync shell stays
 * non-suspend inside the same Kap body.
 */
class SubagentNotifs(
    /** The CLI's db root: `<base>/cli/db/db.sqlite` (the tasks-index's twin). */
    private val baseDir: File,
    /** The daemon's state home: `<home>/state` — the state file lives here. */
    stateDir: File,
    /** The COMMANDS lane's leg (the shared cooldown/outbox files ride it). */
    private val leg: SendLeg,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val stateFile = File(stateDir, STATE_FILE)

    /**
     * One sync pass: the store's children, the persisted memory, the
     * fold — and the state file moves only when the fold learned
     * something. Environmental failures degrade to emptiness (zconn's
     * own degrade): a missing db or an unreadable state file is silence,
     * never a crash — the KAP Process restarts only the BUGS.
     */
    fun pass(): List<SubagentDone> {
        val nowMs = clockMs()
        val seen = loadSeen()
        val fold = SubagentWatch.pass(seen, readChildren(nowMs), nowMs)
        if (fold.seen != seen) saveSeen(fold.seen)
        return fold.announce
    }

    /**
     * The send leg, zconn's `_notify` order: each announcement delivers
     * LOUD — the owner's «bien fuertes» — through the commands lane the
     * knob's mode names (shadow logs nothing, dry-run composes, LIVE
     * posts with the shared cooldown/outbox/last-ph).
     */
    suspend fun speak(done: List<SubagentDone>) {
        val now = clockMs() / MILLIS_PER_SECOND
        done.forEach { d ->
            leg.deliver(
                ShadowDecision(
                    ts = now,
                    tsIso = TimeIso.format(now),
                    watcher = NotifText.WATCHER,
                    decision = DECISION,
                    text = NotifText.subagentDone(d),
                ),
                loud = true,
            )
            logger.info {
                "notification: subagent ended «${d.title.take(LOG_PREVIEW)}» → ${d.outcome.take(LOG_PREVIEW)}"
            }
        }
    }

    /**
     * The children the fold needs in ONE read: the RUNNING ones (their
     * '' is the future transition's witness) plus everything that ended
     * within the freshness window (the fresh ends; anything older is the
     * ghost it already was — the fold's law, pre-filtered here for the
     * query's weight only, never for correctness).
     */
    private fun readChildren(nowMs: Long): List<ChildSession> {
        val db = cliDb()
        if (!db.exists()) return emptyList()
        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}", roProps()).use { conn ->
                conn.prepareStatement(CHILDREN_QUERY).use { ps ->
                    ps.setLong(1, nowMs - SubagentWatch.FRESH_WINDOW_MS)
                    ps.executeQuery().use { rs ->
                        val rows = mutableListOf<ChildSession>()
                        while (rs.next()) {
                            rows.add(
                                ChildSession(
                                    sid = rs.getString(ID_COL).orEmpty(),
                                    title = rs.getString(TITLE_COL).orEmpty(),
                                    outcome = rs.getString(OUTCOME_COL).orEmpty(),
                                    startedMs = rs.getLong(CREATED_COL),
                                    updatedMs = rs.getLong(UPDATED_COL),
                                ),
                            )
                        }
                        rows
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun loadSeen(): Map<String, SeenOutcome> {
        if (!stateFile.exists()) return emptyMap()
        // a broken file is an empty memory: the fold re-records it
        return runCatching {
            (Json.parseToJsonElement(stateFile.readText()) as? JsonObject)
                ?.entries
                ?.mapNotNull { (sid, el) ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val outcome = (obj["outcome"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    val atMs = (obj["atMs"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                    sid to SeenOutcome(outcome, atMs)
                }
                ?.toMap()
        }.getOrNull().orEmpty()
    }

    private fun saveSeen(seen: Map<String, SeenOutcome>) {
        try {
            val root = buildJsonObject {
                seen.forEach { (sid, s) ->
                    put(
                        sid,
                        buildJsonObject {
                            put("outcome", s.outcome)
                            put("atMs", s.atMs)
                        },
                    )
                }
            }
            stateFile.parentFile?.mkdirs()
            atomicWrite(stateFile, root.toString().toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            logger.warn(e) { "subagent-notifs state save failed — worst case a transition repeats once" }
        }
    }

    /** tmp + fsync + rename — the cross-process write invariant (state/store.py). */
    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync()
        }
        if (!tmp.renameTo(target)) throw IOException("atomic rename failed: $target")
    }

    private fun cliDb(): File = File(File(baseDir, "cli"), "db").resolve("db.sqlite")

    private fun roProps(): Properties = Properties().apply { put("open_mode", SQLITE_READONLY.toString()) }

    /**
     * The never-miss Process — one pass per tick at the daemon's own
     * cadence, restarts under the convergence law, the heartbeat beats
     * at its own pace (Main wires the hb file).
     */
    fun asProcess(every: Duration, onHeartbeat: () -> Unit = {}): Process<Unit> =
        Kap { speak(pass()) }
            .repeat(Schedule.spaced(every))
            .process(
                name = PROCESS_NAME,
                restart = RestartPolicy.exponential(base = 1.seconds),
                heartbeatEvery = HEARTBEAT_EVERY,
                onHeartbeat = onHeartbeat,
                onRestart = { attempt, error, delay ->
                    logger.warn(error) { "subagent-notifs pass failed — restart $attempt in $delay" }
                },
            )

    private companion object {
        const val STATE_FILE = "subagent-notifs.json"
        const val TMP_SUFFIX = ".tmp"
        const val DECISION = "subagent_done"
        const val PROCESS_NAME = "subagent-notifs"
        const val SQLITE_READONLY = 0x00000001
        const val LOG_PREVIEW = 60
        const val MILLIS_PER_SECOND = 1000.0
        val HEARTBEAT_EVERY = 30.seconds

        /** The one read: running children (the future's witnesses) + fresh ends. */
        const val CHILDREN_QUERY =
            "SELECT id, title, COALESCE(last_outcome,''), time_created, time_updated FROM session " +
                "WHERE parent_id IS NOT NULL AND (COALESCE(last_outcome,'') = '' OR time_updated >= ?)"

        const val ID_COL = 1
        const val TITLE_COL = 2
        const val OUTCOME_COL = 3
        const val CREATED_COL = 4
        const val UPDATED_COL = 5
    }
}
