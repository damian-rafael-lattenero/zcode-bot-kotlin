package dev.lattenero.bot.daemon.notifications

import dev.lattenero.bot.contracts.ShadowDecision
import dev.lattenero.bot.daemon.db.typed
import dev.lattenero.bot.daemon.send.SendLeg
import dev.lattenero.bot.daemon.shadow.TimeIso
import io.github.oshai.kotlinlogging.KotlinLogging
import kap.Kap
import kap.Process
import kap.RestartPolicy
import kap.Schedule
import kap.process
import kap.repeat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
 * M4's IO shell: the tasks-index read (read-only, the house's short-lived
 * xerial pattern), the state file under the daemon's own `state/` dir
 * (atomic tmp+fsync+rename), and the send leg (the COMMANDS lane — the
 * same shared cooldown/outbox throat every notification rides).
 *
 * One pass per tick: read → the PURE [GlobalNotices] fold → persist-if-
 * changed → announce. The first pass after boot (no memory yet) is the
 * SILENT baseline: it records everything and speaks nothing.
 */
class GlobalNoticesIo(
    /** The CLI/app data root: `<base>/v2/tasks-index.sqlite` lives here. */
    private val baseDir: File,
    stateDir: File,
    private val leg: SendLeg,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val stateFile = File(stateDir, STATE_FILE)

    /** One pass: the store read, the fold, the persisted memory, the notices. */
    suspend fun pass(): List<GlobalNotice> {
        val nowMs = clockMs()
        val memory = load()
        val rows = readRows(if (memory.isEmpty()) 0L else nowMs - READ_WINDOW_MS)
        val fold = if (memory.isEmpty()) {
            // THE SILENT BASELINE: a boot must not announce yesterday's ends
            GlobalFold(GlobalNotices.baseline(rows), emptyList())
        } else {
            GlobalNotices.pass(memory, rows, nowMs)
        }
        if (fold.seen != memory) save(fold.seen)
        return fold.announce
    }

    /** The announcements, LOUD on the commands lane (the owner's «bien fuertes»). */
    suspend fun speak(notices: List<GlobalNotice>) {
        val now = clockMs() / MILLIS_PER_SECOND
        notices.forEach { notice ->
            val decision = when (notice) {
                is GlobalNotice.Finished -> DECISION_FINISHED
                is GlobalNotice.Failed -> DECISION_FAILED
            }
            val text = when (notice) {
                is GlobalNotice.Finished -> NotifText.taskFinished(notice.task)
                is GlobalNotice.Failed -> NotifText.taskFailed(notice.task)
            }
            val task = when (notice) {
                is GlobalNotice.Finished -> notice.task
                is GlobalNotice.Failed -> notice.task
            }
            leg.deliver(
                ShadowDecision(
                    ts = now,
                    tsIso = TimeIso.format(now),
                    watcher = NotifText.WATCHER,
                    decision = decision,
                    text = text,
                ),
                loud = true,
            )
            logger.info {
                "global notice: $decision ${task.taskId.takeLast(LOG_TAIL)} «${task.title.take(LOG_PREVIEW)}»"
            }
        }
    }

    /** The never-miss Process — one pass per tick, restarts under the convergence law. */
    fun asProcess(every: Duration, onHeartbeat: () -> Unit = {}): Process<Unit> =
        Kap { speak(pass()) }
            .repeat(Schedule.spaced(every))
            .process(
                name = PROCESS_NAME,
                restart = RestartPolicy.exponential(base = 1.seconds),
                heartbeatEvery = HEARTBEAT_EVERY,
                onHeartbeat = onHeartbeat,
                onRestart = { attempt, error, delay ->
                    logger.warn(error) { "global notices pass failed — restart $attempt in $delay" }
                },
            )

    private suspend fun readRows(floorMs: Long): List<TaskSignal> = withContext(Dispatchers.IO) {
        val index = File(File(baseDir, "v2"), "tasks-index.sqlite")
        if (!index.exists()) return@withContext emptyList()
        runCatching {
            DriverManager.getConnection("jdbc:sqlite:${index.absolutePath}", roProps()).use { conn ->
                conn.typed { bot ->
                    bot.tasksQueries.notifyRows(floor = floorMs, lim = READ_CAP.toLong())
                        .executeAsList()
                        .map { row ->
                            TaskSignal(
                                taskId = row.task_id,
                                title = row.t,
                                workspacePath = row.workspace_path,
                                status = row.task_status,
                                lastOutcome = row.cli_last_outcome,
                                updatedMs = row.u,
                            )
                        }
                }
            }
        }.getOrElse { error ->
            logger.warn(error) { "global notices: tasks-index read failed — silence this tick" }
            emptyList()
        }
    }

    private fun load(): List<TaskSignal> {
        if (!stateFile.exists()) return emptyList()
        return runCatching {
            (Json.parseToJsonElement(stateFile.readText()) as? JsonArray).orEmpty().mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                TaskSignal(
                    taskId = (row["sid"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
                    title = (row["title"] as? JsonPrimitive)?.content.orEmpty(),
                    workspacePath = (row["ws"] as? JsonPrimitive)?.content.orEmpty(),
                    status = (row["status"] as? JsonPrimitive)?.content.orEmpty(),
                    lastOutcome = (row["outcome"] as? JsonPrimitive)?.content.orEmpty(),
                    updatedMs = (row["at"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
                )
            }
        }.getOrElse { error ->
            logger.warn { "global notices state unreadable (${error.message}) — starting with the empty memory" }
            emptyList()
        }
    }

    private fun save(memory: List<TaskSignal>) {
        val root = buildJsonArray {
            memory.forEach { task ->
                add(
                    buildJsonObject {
                        put("sid", task.taskId)
                        put("title", task.title)
                        put("ws", task.workspacePath)
                        put("status", task.status)
                        put("outcome", task.lastOutcome)
                        put("at", task.updatedMs)
                    },
                )
            }
        }
        try {
            stateFile.parentFile?.mkdirs()
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            FileOutputStream(tmp).use {
                it.write(root.toString().toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            if (!tmp.renameTo(stateFile)) throw IOException("atomic rename failed: $stateFile")
        } catch (e: IOException) {
            logger.warn { "global notices state save failed: ${e.message} (worst case one notice repeats)" }
        }
    }

    private fun roProps(): Properties = Properties().apply { put("open_mode", SQLITE_READONLY.toString()) }

    private companion object {
        const val STATE_FILE = "global-notices.json"
        const val PROCESS_NAME = "global-notices"
        const val DECISION_FINISHED = "task_finished"
        const val DECISION_FAILED = "task_failed"
        const val SQLITE_READONLY = 0x00000001
        const val READ_CAP = 200
        const val LOG_TAIL = 8
        const val LOG_PREVIEW = 50
        const val MILLIS_PER_SECOND = 1000.0
        val HEARTBEAT_EVERY = 30.seconds

        /** The read's floor window — five cadences of slack, so a slow tick never misses a transition. */
        const val READ_WINDOW_MS = 5L * 60L * 1000L
    }
}
