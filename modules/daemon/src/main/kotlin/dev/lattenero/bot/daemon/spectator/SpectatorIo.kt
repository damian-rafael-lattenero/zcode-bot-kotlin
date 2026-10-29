package dev.lattenero.bot.daemon.spectator
import dev.lattenero.bot.contracts.PyText

import dev.lattenero.bot.contracts.BotSession
import dev.lattenero.bot.contracts.StateFiles
import dev.lattenero.bot.contracts.WorkersRegistry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.sql.DriverManager
import java.util.Properties

/**
 * The spectator's read-only view of the Python bot's world: turn markers,
 * the workers registry, the active-session record, the agent-view pids,
 * the config knobs, and the tasks index for titles. Everything here only
 * READS — the shadow contract (ADR-023/024) never writes Python state.
 */
// One function per Python-side source — the count is the port's shape.
@Suppress("TooManyFunctions")
internal object SpectatorIo {

    private const val VIEW_FRESH_S = 10.0
    private const val TITLE_CAP = 60
    private val SAFE_RE = Regex("[^A-Za-z0-9_.-]+")
    private val LEGACY_FILTERS = mapOf("todas" to "all", "accion" to "action", "minimo" to "minimal")

    // ── markers ────────────────────────────────────────────────────────────

    /** config.marker_path: state/turn-<safe>.marker (safe is lossy — see [sidIndex]). */
    fun markerPath(stateDir: File, sid: String): File =
        File(stateDir, "turn-${safeSid(sid)}.marker")

    fun safeSid(sid: String): String = SAFE_RE.replace(sid.ifEmpty { "ses" }, "_")

    /** The marker's token (unique per prompt), "" while missing or mid-write. */
    fun markerToken(stateDir: File, sid: String): String =
        runCatching { markerPath(stateDir, sid).readText() }.getOrNull()
            ?.let(StateFiles::parseObject)
            ?.get("token")
            ?.let { it as? JsonPrimitive }
            ?.content
            .orEmpty()

    /** Every live marker: (safeSid, file). */
    fun markers(stateDir: File): List<Pair<String, File>> =
        stateDir.listFiles { f -> f.name.startsWith("turn-") && f.name.endsWith(".marker") }
            ?.map { it.name.removePrefix("turn-").removeSuffix(".marker") to it }
            .orEmpty()

    /**
     * safe(marker) -> real sid, derived from the rollout files present (the
     * marker name is lossy; the rollout name is not). A safe-name collision
     * between two sids is possible in theory and never seen in practice.
     */
    fun sidIndex(rolloutsDir: File): Map<String, String> {
        val files = rolloutsDir.listFiles { f -> f.name.startsWith("model-io-") && f.name.endsWith(".jsonl") }
            ?: return emptyMap()
        val index = mutableMapOf<String, String>()
        files.forEach { f ->
            val sid = f.name.removePrefix("model-io-").removeSuffix(".jsonl")
            index.getOrPut(safeSid(sid)) { sid }
        }
        return index
    }

    // ── gates ──────────────────────────────────────────────────────────────

    /** Sessions owned by a bot worker: the worker streams its own turn (F15). */
    fun workerSids(stateDir: File): Set<String> =
        runCatching { File(stateDir, "bot-workers.json").readText() }.getOrNull()
            ?.let(StateFiles::parseObject)
            ?.let(WorkersRegistry::fromJson)
            ?.keys
            .orEmpty()

    /** F15/F76: only the stream focus (or the active sid) may push; no active sid = no push. */
    // One guard per line (BusEvents precedent).
    @Suppress("ReturnCount")
    fun taskCurrentOk(baseDir: File, cfg: SpectatorCfg, sid: String): Boolean {
        if (!cfg.onlyActiveTask) return true
        if (sid.isEmpty()) return false
        val act = runCatching { File(File(baseDir, "hooks"), "state").resolve("bot-session.json").readText() }
            .getOrNull()
            ?.let(StateFiles::parseObject)
            ?.let(BotSession::fromJson)
            ?: return false
        return sid == (act.focusSid?.takeIf { it.isNotEmpty() } ?: act.sid)
    }

    /** F67: a live agent view mutes its parent session's stream (freshness, not existence). */
    fun viewAlive(stateDir: File, sid: String, now: Double): Boolean {
        val mtime = File(stateDir, "agent-view-$sid.pid").lastModified() / MILLIS_PER_SECOND
        return mtime > 0.0 && now - mtime <= VIEW_FRESH_S
    }

    // ── labels and knobs ───────────────────────────────────────────────────

    /** task_label: the task's title from the app's index, best-effort like Python's. */
    fun taskLabel(baseDir: File, sid: String): String = try {
        val db = File(File(baseDir, "v2"), "tasks-index.sqlite")
        val ro = Properties().apply { put("open_mode", SQLITE_READONLY.toString()) }
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}", ro).use { conn -> queryLabel(conn, sid) }
    } catch (_: java.sql.SQLException) {
        "ZCode" // best-effort label, exactly like Python's broad except
    }

    private fun queryLabel(conn: java.sql.Connection, sid: String): String =
        conn.prepareStatement(TASK_QUERY).use { ps ->
            ps.setString(1, sid)
            ps.executeQuery().use { rs -> if (rs.next()) labelOf(rs) else "ZCode" }
        }

    /** The row to the label: the title if present, else the workspace's basename, else ZCode. */
    private fun labelOf(rs: java.sql.ResultSet): String {
        val title = PyText.strip(rs.getString(TITLE_COL) ?: "")
        if (title.isNotEmpty()) return PyText.take(title, TITLE_CAP)
        val cwd = rs.getString(WORKSPACE_COL) ?: rs.getString(ROOT_COL) ?: ""
        return PyText.take(baseName(cwd), TITLE_CAP).ifEmpty { "ZCode" }
    }

    /** The three knobs the spectator reads, defaults from config.py's DEFAULTS. */
    fun cfg(baseDir: File): SpectatorCfg {
        val o = runCatching { File(baseDir, "telegram-remote.json").readText() }
            .getOrNull()
            ?.let(StateFiles::parseObject)
        val filter = pickString(o, "tools_box_filter", "tools_caja_filtro")
            ?.let { LEGACY_FILTERS[it] ?: it }
            .orEmpty()
        return SpectatorCfg(
            toolsStream = pickBool(o, "tools_stream") ?: true,
            toolsBoxFilter = filter.ifEmpty { "action" },
            onlyActiveTask = pickBool(o, "only_active_task") ?: true,
            macSpectator = pickBool(o, "mac_spectator", "mac_espectador") ?: true,
        )
    }

    private fun pickBool(o: JsonObject?, vararg keys: String): Boolean? {
        for (k in keys) {
            val v = o?.get(k) as? JsonPrimitive ?: continue
            if (!v.isString) return v.content == "true"
        }
        return null
    }

    private fun pickString(o: JsonObject?, vararg keys: String): String? {
        for (k in keys) {
            val v = o?.get(k) as? JsonPrimitive ?: continue
            if (v.isString) return v.content
        }
        return null
    }

    private fun baseName(path: String): String =
        PyText.strip(path).trimEnd('/').substringAfterLast('/')

    private const val SQLITE_READONLY = 0x00000001
    private const val MILLIS_PER_SECOND = 1000.0
    private const val TITLE_COL = 1
    private const val WORKSPACE_COL = 2
    private const val ROOT_COL = 3
    private const val TASK_QUERY =
        "SELECT title, workspace_path, root_path FROM tasks WHERE task_id = ? AND deleted = 0 LIMIT 1"
}
