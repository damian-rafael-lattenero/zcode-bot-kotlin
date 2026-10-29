package dev.lattenero.bot.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Every structured on-disk format of the Python bot, in one file. The spec
 * is `zcode_bot/schemas.py` in the bot repo: one type per persisted file,
 * **bilingual forever** — readers accept the legacy Spanish keys noted per
 * type (the P6 migration renamed them; the on-disk forms are permanent
 * compat), writers emit the English keys only. A shape change here is a
 * wire-format change.
 *
 * These are READ-side contracts for the Kotlin migration phases (B1
 * watchers, B2 transport): decode any real file, past or present, into one
 * canonical shape. Round-trip tests pin current AND legacy fixtures.
 */
object StateFiles {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parses text as a JSON object; null for absent, malformed or non-object content. */
    fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}

// ── legacy-aware field pickers: first key wins ───────────────────────────

private fun JsonObject.pickString(vararg keys: String): String? {
    for (k in keys) (this[k] as? JsonPrimitive)?.let { return it.content }
    return null
}

private fun JsonObject.pickDouble(vararg keys: String): Double? {
    for (k in keys) (this[k] as? JsonPrimitive)?.let { return it.doubleOrNull }
    return null
}

private fun JsonObject.pickLong(vararg keys: String): Long? {
    for (k in keys) (this[k] as? JsonPrimitive)?.let { return it.longOrNull }
    return null
}

private fun JsonObject.pickBoolean(vararg keys: String): Boolean? {
    for (k in keys) (this[k] as? JsonPrimitive)?.let { return it.booleanOrNull }
    return null
}

private fun JsonObject.pickStringList(vararg keys: String): List<String>? {
    for (k in keys) {
        (this[k] as? JsonArray)?.let { arr ->
            return arr.mapNotNull { (it as? JsonPrimitive)?.content }
        }
    }
    return null
}

// ── bot-session.json — the active conversation ───────────────────────────

/**
 * bot-session.json (core.set_active_bot_session). model/mode/mode_set only
 * go into the JSON when set; focus_sid (F76) and bot_mode (F75) are newer
 * optional keys.
 */
@Serializable
data class BotSession(
    val sid: String = "",
    val title: String = "",
    val ws: String = "",
    val model: JsonObject? = null,
    val mode: String? = null,
    @SerialName("mode_set") val modeSet: String? = null,
    @SerialName("focus_sid") val focusSid: String? = null,
    @SerialName("bot_mode") val botMode: String? = null,
) {
    companion object {
        fun fromJson(o: JsonObject) = BotSession(
            sid = o.pickString("sid") ?: "",
            title = o.pickString("title") ?: "",
            ws = o.pickString("ws") ?: "",
            model = o["model"] as? JsonObject,
            mode = o.pickString("mode"),
            modeSet = o.pickString("mode_set"),
            focusSid = o.pickString("focus_sid"),
            botMode = o.pickString("bot_mode"),
        )
    }
}

// ── bot-workers.json — live worker registry ──────────────────────────────

/**
 * bot-workers.json entries (state/registry.py). Core keys are written by
 * WorkerEntry.to_dict; stopped/waiting are added by flag_stop/heartbeat.
 * Legacy key: **titulo** (P6 rename; the reader accepts both, forever).
 */
@Serializable
data class WorkersEntry(
    @SerialName("t0") val t0: Double = 0.0,
    val prompt: String = "",
    val ws: String = "",
    val title: String = "",
    val pid: Long = 0,
    val stopped: Boolean = false,
    val waiting: Boolean = false,
) {
    companion object {
        fun fromJson(o: JsonObject) = WorkersEntry(
            t0 = o.pickDouble("t0") ?: 0.0,
            prompt = o.pickString("prompt") ?: "",
            ws = o.pickString("ws") ?: "",
            title = o.pickString("title", "titulo") ?: "",
            pid = o.pickLong("pid") ?: 0,
            stopped = o.pickBoolean("stopped") ?: false,
            waiting = o.pickBoolean("waiting") ?: false,
        )
    }
}

/** bot-workers.json: map of session id to its live worker. */
object WorkersRegistry {
    fun fromJson(o: JsonObject): Map<String, WorkersEntry> =
        o.entries.associate { (sid, v) ->
            sid to WorkersEntry.fromJson(v as? JsonObject ?: JsonObject(emptyMap()))
        }
}

// ── bot-queue.json — queued prompts ──────────────────────────────────────

/**
 * bot-queue.json items (workers._queue_put). `act` is a BotSession dict at
 * queue time. Legacy: pre-F30 items carry no ts.
 */
@Serializable
data class QueueItem(
    val act: JsonObject = JsonObject(emptyMap()),
    val text: String = "",
    val ts: Double = 0.0,
) {
    companion object {
        fun fromJson(o: JsonObject) = QueueItem(
            act = o["act"] as? JsonObject ?: JsonObject(emptyMap()),
            text = o.pickString("text") ?: "",
            ts = o.pickDouble("ts") ?: 0.0,
        )
    }
}

/** bot-queue.json: map of session id to its queue (max 5 per session). */
object Queues {
    fun fromJson(o: JsonObject): Map<String, List<QueueItem>> =
        o.entries.associate { (sid, v) ->
            sid to (v as? JsonArray ?: JsonArray(emptyList()))
                .mapNotNull { (it as? JsonObject)?.let(QueueItem::fromJson) }
        }
}

// ── bot-active.json — the in-flight turn ─────────────────────────────────

/**
 * bot-active.json entries (workers._active_turn_register); the poller's
 * supervisor delivers the ending if the worker dies without its finally.
 * `stop` is set by bot_stop's flag. Legacy key: **titulo**.
 */
@Serializable
data class ActiveTurn(
    val title: String = "",
    @SerialName("t0") val t0: Double = 0.0,
    val pid: Long = 0,
    val ws: String? = null,
    @SerialName("last_id") val lastId: Long? = null,
    val stop: Boolean = false,
) {
    companion object {
        fun fromJson(o: JsonObject) = ActiveTurn(
            title = o.pickString("title", "titulo") ?: "",
            t0 = o.pickDouble("t0") ?: 0.0,
            pid = o.pickLong("pid") ?: 0,
            ws = o.pickString("ws"),
            lastId = o.pickLong("last_id"),
            stop = o.pickBoolean("stop") ?: false,
        )
    }
}

/** bot-active.json: map of session id to its in-flight turn. */
object ActiveTurns {
    fun fromJson(o: JsonObject): Map<String, ActiveTurn> =
        o.entries.associate { (sid, v) ->
            sid to ActiveTurn.fromJson(v as? JsonObject ?: JsonObject(emptyMap()))
        }
}

// ── outbox.json — parked replies ─────────────────────────────────────────

/**
 * outbox.json (utils/http.py): a list of `[{"ts": <int>, "text": <str>}]`,
 * capped at 20 entries, each text clipped to CH_REPLY. Multi-process file:
 * written under flock; the Kotlin writer (B2) must keep the same contract.
 */
@Serializable
data class OutboxItem(
    val ts: Long = 0,
    val text: String = "",
) {
    companion object {
        fun fromJson(o: JsonObject) = OutboxItem(
            ts = o.pickLong("ts") ?: 0,
            text = o.pickString("text") ?: "",
        )
    }

    object Box {
        fun fromJson(a: JsonArray): List<OutboxItem> =
            a.mapNotNull { (it as? JsonObject)?.let(OutboxItem::fromJson) }
    }
}

// ── zconn.json — the connection episode machine's state ──────────────────

/** state/zconn.json (zconn.py): the cursor + the current episode anchor. */
@Serializable
data class ZconnState(
    val down: Boolean = false,
    @SerialName("first_down") val firstDown: Double? = null,
    @SerialName("last_up") val lastUp: Double? = null,
    val reason: String = "",
    val since: Double = 0.0,
) {
    companion object {
        fun fromJson(o: JsonObject) = ZconnState(
            down = o.pickBoolean("down") ?: false,
            firstDown = o.pickDouble("first_down"),
            lastUp = o.pickDouble("last_up"),
            reason = o.pickString("reason") ?: "",
            since = o.pickDouble("since") ?: 0.0,
        )
    }
}

// ── bot-audit.json — failure log ─────────────────────────────────────────

/**
 * bot-audit.json entries (audit.record). Legacy: keys **tipo/detalle/clave**
 * normalize to type/detail/key, and the Spanish kind VALUES map below
 * (audit.LEGACY_KINDS, permanent). The anti-spam `key` is "namespace|scope|kind".
 */
@Serializable
data class AuditEntry(
    val ts: Double = 0.0,
    val sid: String? = null,
    val type: String = "",
    val detail: String = "",
    val key: String? = null,
) {
    /** One spelling for one failure kind, old or new (audit.norm_kind). */
    val normalizedType: String get() = normalizeKind(type)

    companion object {
        val LEGACY_KINDS = mapOf(
            "turno-muerto" to "dead-turn",
            "marker-huerfano" to "orphan-marker",
            "cola-dormida" to "idle-queue",
            "cot-huerfano" to "orphan-cot",
            "alerta" to "alert",
            "envio" to "send",
            "espectador" to "spectator",
        )

        /** Legacy anti-spam key WORDS → English namespaces (audit.LEGACY_KEY_WORDS). */
        val LEGACY_KEY_WORDS = mapOf(
            "fallos" to "failures",
            "cola" to "idle-queue",
            "fantasma" to "ghost",
        )

        fun normalizeKind(kind: String): String = LEGACY_KINDS[kind] ?: kind

        /** Normalizes each "a|b|c" segment through [LEGACY_KEY_WORDS]. */
        fun normalizeKey(key: String): String =
            key.split("|").joinToString("|") { LEGACY_KEY_WORDS[it] ?: it }

        fun fromJson(o: JsonObject) = AuditEntry(
            ts = o.pickDouble("ts") ?: 0.0,
            sid = o.pickString("sid"),
            type = o.pickString("type", "tipo") ?: "",
            detail = o.pickString("detail", "detalle") ?: "",
            key = o.pickString("key", "clave"),
        )
    }
}

// ── cot-<sid>-<t0>.json — the turn's CoT boxes ────────────────────────────

/**
 * The CoT boxes cache (reasoning.save_cache). Legacy keys forever:
 * **titulo/cajas/rotulos** (P6 rename), **vivo/finalizado** (P6 rename),
 * **bloques** (pre-F46 boxes); **ph** is the pre-F46 single box. phs is
 * message_id per box, aligned to labels; perbox (F57+) means one message
 * PER box (legacy phs share one round message).
 */
// The field list mirrors schemas.py field-for-field; no config object.
@Suppress("LongParameterList")
@Serializable
data class CotCache(
    val sid: String = "",
    @SerialName("t0") val t0: Long = 0,
    val title: String = "",
    val boxes: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    val live: Boolean? = null,
    val done: Boolean? = null,
    val ph: Long? = null,
    val phs: List<Long?> = emptyList(),
    val perbox: Boolean? = null,
    val error: String? = null,
) {
    companion object {
        fun fromJson(o: JsonObject) = CotCache(
            sid = o.pickString("sid") ?: "",
            t0 = o.pickLong("t0") ?: 0,
            title = o.pickString("title", "titulo") ?: "",
            boxes = o.pickStringList("boxes", "cajas", "bloques") ?: emptyList(),
            labels = o.pickStringList("labels", "rotulos") ?: emptyList(),
            live = o.pickBoolean("live", "vivo"),
            done = o.pickBoolean("done", "finalizado"),
            ph = o.pickLong("ph"),
            phs = (o["phs"] as? JsonArray)?.map { (it as? JsonPrimitive)?.longOrNull } ?: emptyList(),
            perbox = o.pickBoolean("perbox"),
            error = o.pickString("error"),
        )
    }
}

// ── bot-nuclear-report.json — nuclear body ↔ watchdog contract ───────────

/**
 * The only contract between the nuclear body and the detached watchdog
 * (nuclear.NuclearReport). Legacy keys: **frenadas/totales/cola/vivos**
 * (P6 rename; the reader accepts both, forever).
 */
// The field list mirrors schemas.py field-for-field; no config object.
@Suppress("LongParameterList")
@Serializable
data class NuclearReport(
    @SerialName("old_pid") val oldPid: Long = 0,
    @SerialName("t0") val t0: Double = 0.0,
    val dur: Long = 0,
    val stopped: Int = 0,
    val total: Int = 0,
    val mac: Int = 0,
    val queued: Int = 0,
    val alive: Int = 0,
    @SerialName("app_ok") val appOk: Boolean = false,
    val notes: List<String> = emptyList(),
) {
    companion object {
        fun fromJson(o: JsonObject) = NuclearReport(
            oldPid = o.pickLong("old_pid") ?: 0,
            t0 = o.pickDouble("t0") ?: 0.0,
            dur = o.pickLong("dur") ?: 0,
            stopped = (o.pickLong("stopped", "frenadas") ?: 0).toInt(),
            total = (o.pickLong("total", "totales") ?: 0).toInt(),
            mac = (o.pickLong("mac") ?: 0).toInt(),
            queued = (o.pickLong("queued", "cola") ?: 0).toInt(),
            alive = (o.pickLong("alive", "vivos") ?: 0).toInt(),
            appOk = o.pickBoolean("app_ok") ?: false,
            notes = o.pickStringList("notes") ?: emptyList(),
        )
    }
}
