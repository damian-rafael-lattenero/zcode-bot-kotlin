package dev.lattenero.bot.channel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * B3b — the daemon-local channel's WIRE CONTRACT as pure values (the
 * client half of docs/fork/DAEMON-CHANNEL.md; the normative shapes live
 * in `packages/shared/src/daemon-channel.ts` and the tests pin the exact
 * spec fixtures). Framing is JSONL: one object per line, UTF-8, `\n`.
 *
 * Everything here is ENCODE/DECODE ONLY — no socket, no coroutine, no
 * clock. The client ([ChannelClient]) turns these values into a
 * supervised connection; the tests pin them against the spec's own
 * examples so a drift of the fork's contract fails a test, not a turn.
 *
 * The protocol is additive (new optional fields are ignored by old
 * readers): [decodeServerLine] answers `null` for every frame whose
 * `type` it does not know rather than failing the connection.
 */
object ChannelSpec {
    const val PROTOCOL_VERSION = 1
    const val SOCKET_FILENAME = "daemon-local.sock"
    const val TOKEN_FILENAME = "daemon-local.token"
    const val CLIENT_ID = "bot-kotlin"

    /** One JSONL line (attachments ride base64 inside the frame). */
    const val MAX_LINE_BYTES = 24 * 1024 * 1024

    /** The server closes when the first line is not a hello within this window. */
    const val HANDSHAKE_TIMEOUT_MS = 10_000L

    /** The daemon's own inbound dedupe window (same doctrine as F2d's offset). */
    const val INBOUND_DEDUPE_TTL_MS = 2 * 60_000L
}

/** One query of the channel (the service ops table, §3.4) — data in, JSON out. */
sealed interface ChannelQuery {
    data class ListTasks(val workspacePath: String) : ChannelQuery

    data class TaskList(
        val listKind: String,
        val workspaceScopes: List<WorkspaceScope>,
        val limit: Int? = null,
    ) : ChannelQuery

    data class ActiveTaskIds(val workspacePath: String) : ChannelQuery

    data class TaskMeta(val taskId: String, val workspacePath: String? = null) : ChannelQuery

    data class TaskSnapshot(val taskId: String, val workspacePath: String? = null) : ChannelQuery

    /**
     * The /context_window command's read: the conversation's REAL context
     * meter (used tokens + the model's window), the same source the fork's
     * own desktop meter restores — never estimated on either side. Wire
     * shape is the fork's contextWindow query (9cfb39a9; aditivo:
     * servidores viejos dicen unknown_query — el comando lo habla honesto).
     */
    data class ContextWindow(val taskId: String, val workspacePath: String? = null) : ChannelQuery

    data class TaskUsage(val taskId: String, val workspacePath: String? = null) : ChannelQuery

    /**
     * The owner's own quota. `taskId` (additive, 30/10): the active
     * conversation whose model selection names the provider — the fork
     * classifies it into the accountAccess hint that makes the Start Plan
     * path answer with REAL buckets instead of the lying not_configured.
     */
    data class Quota(val invalidateBalanceCache: Boolean = false, val taskId: String? = null) : ChannelQuery

    data object BotStatus : ChannelQuery

    /**
     * The /plan command's read: the CURRENT selection (plan/model/effort)
     * for the conversation plus the app's global default and the available
     * plans with their models (the tap-to-change rows' source). Wire shapes
     * are the fork's 3d07cace (aditivo: servidores viejos dicen
     * unknown_query — el comando lo habla honesto).
     */
    data class ModelSelection(val workspacePath: String? = null) : ChannelQuery

    /**
     * One field per tap: the server merges the partial update. scope
     * «conversation» = the focused conversation; «default» = the app's
     * global default (Z.ai individual BY OWNER ORDER).
     */
    data class SetModelSelection(
        val scope: String, // "conversation" | "default"
        val plan: String? = null,
        val model: String? = null,
        val effort: String? = null,
        val workspacePath: String? = null,
    ) : ChannelQuery

    data class Stop(val taskId: String) : ChannelQuery

    data class StopAll(val workspacePath: String? = null) : ChannelQuery

    data class Compact(val taskId: String) : ChannelQuery
}

/** `workspacePath` + optional identity — the isolation key of the fork's task stores. */
data class WorkspaceScope(val workspacePath: String, val workspaceIdentity: String? = null)

// ---- client → server --------------------------------------------------------

/**
 * One attachment of an inbound — the fork's DaemonChannelInboundAttachment
 * shape (localPath preferred over dataBase64; the host materializes the
 * file into bot-attachments/ and the agent reads it from disk).
 */
data class InboundAttachment(
    val id: String,
    /** «image» | «audio» | «video» | «file» — the fork's own vocabulary. */
    val kind: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long? = null,
    /** Absolute path readable by the host — how this daemon always ships. */
    val localPath: String? = null,
)

/** What the daemon sends. The hello carries the token — [describe] keeps it out of every log. */
sealed interface ClientFrame {
    data class Hello(val token: String, val clientId: String = ChannelSpec.CLIENT_ID) : ClientFrame

    data class Inbound(
        val id: String,
        val text: String,
        val messageId: String?,
        val receivedAt: Long?,
        /** La sesión DESTINO (una ORDEN del dueño a un subagente attachado) — null = el routing de siempre. */
        val taskId: String? = null,
        /** ATTACHMENTS IN (30/10): the owner's photo/document, already on the daemon's disk. */
        val attachments: List<InboundAttachment> = emptyList(),
    ) : ClientFrame

    data class Subscribe(val id: String, val taskId: String, val workspacePath: String) : ClientFrame

    data class Unsubscribe(val id: String, val taskId: String) : ClientFrame

    /**
     * STEER (aditivo, 30/10): redirigir el turno EN CURSO — el texto entra
     * al CLI con delivery «guide» (v4 sendText requestedDelivery=guide) y
     * el admission lo inyecta DENTRO del turno vivo (turn.steerQueued →
     * turn.steerDrained en el próximo límite de herramienta). El servidor
     * responde con el MISMO ack que el inbound (inbound_result / error).
     */
    data class Steer(val id: String, val taskId: String, val text: String) : ClientFrame

    /**
     * LA RESPUESTA del dueño a una pregunta pendiente (el loop cerrado, el
     * fork's respond frame — 6993d7e8): permission lleva el optionId de la
     * opción del evento (+ su response devuelta tal cual); elicitation lleva
     * action+content; plan (ExitPlanMode) viaja sin action — el fork la
     * toma como accept. El messageId arma el dedupe del lado del host.
     */
    data class Respond(
        val id: String,
        val taskId: String,
        val requestId: String,
        val kind: String, // "permission" | "elicitation" | "plan"
        val optionId: String? = null,
        val response: JsonObject? = null,
        val action: String? = null,
        val content: JsonObject? = null,
        val messageId: String? = null,
    ) : ClientFrame

    data class Query(val id: String, val query: ChannelQuery) : ClientFrame

    data class Ping(val id: String) : ClientFrame
}

/** Pure: the exact JSONL line for a client frame (the wire is `line + '\n'`). */
@Suppress("CyclomaticComplexMethod") // one branch per frame kind — the wire table's own shape, as the query's
fun encodeClient(frame: ClientFrame): String = when (frame) {
    is ClientFrame.Hello -> buildJsonObject {
        put("type", "hello")
        put("protocolVersion", ChannelSpec.PROTOCOL_VERSION)
        put("token", frame.token)
        put("clientId", frame.clientId)
    }

    is ClientFrame.Inbound -> buildJsonObject {
        put("type", "inbound")
        put("id", frame.id)
        put("text", frame.text)
        if (frame.messageId != null) put("messageId", frame.messageId)
        if (frame.receivedAt != null) put("receivedAt", frame.receivedAt)
        if (frame.taskId != null) put("taskId", frame.taskId)
        if (frame.attachments.isNotEmpty()) put("attachments", encodeAttachments(frame.attachments))
    }

    is ClientFrame.Subscribe -> buildJsonObject {
        put("type", "subscribe")
        put("id", frame.id)
        put("taskId", frame.taskId)
        put("workspacePath", frame.workspacePath)
    }

    is ClientFrame.Unsubscribe -> buildJsonObject {
        put("type", "unsubscribe")
        put("id", frame.id)
        put("taskId", frame.taskId)
    }

    is ClientFrame.Steer -> buildJsonObject {
        put("type", "steer")
        put("id", frame.id)
        put("taskId", frame.taskId)
        put("text", frame.text)
    }

    is ClientFrame.Respond -> buildJsonObject {
        put("type", "respond")
        put("id", frame.id)
        put("taskId", frame.taskId)
        put("requestId", frame.requestId)
        put("kind", frame.kind)
        if (frame.optionId != null) put("optionId", frame.optionId)
        if (frame.response != null) put("response", frame.response)
        if (frame.action != null) put("action", frame.action)
        if (frame.content != null) put("content", frame.content)
        if (frame.messageId != null) put("messageId", frame.messageId)
    }

    is ClientFrame.Query -> buildJsonObject {
        put("type", "query")
        put("id", frame.id)
        put("query", encodeQuery(frame.query))
    }

    is ClientFrame.Ping -> buildJsonObject {
        put("type", "ping")
        put("id", frame.id)
    }
}.toString()

// one branch per query kind — the wire table shape (section 3.4), a branch per line
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun encodeQuery(query: ChannelQuery): JsonObject = when (query) {
    is ChannelQuery.ListTasks -> buildJsonObject {
        put("kind", "listTasks")
        put("workspacePath", query.workspacePath)
    }

    is ChannelQuery.TaskList -> buildJsonObject {
        put("kind", "taskList")
        put("listKind", query.listKind)
        put(
            "workspaceScopes",
            kotlinx.serialization.json.buildJsonArray {
                query.workspaceScopes.forEach { scope ->
                    add(
                        buildJsonObject {
                            put("workspacePath", scope.workspacePath)
                            if (scope.workspaceIdentity != null) {
                                put("workspaceIdentity", scope.workspaceIdentity)
                            }
                        },
                    )
                }
            },
        )
        if (query.limit != null) put("limit", query.limit)
    }


    is ChannelQuery.ActiveTaskIds -> buildJsonObject {
        put("kind", "activeTaskIds")
        put("workspacePath", query.workspacePath)
    }

    is ChannelQuery.TaskMeta -> buildJsonObject {
        put("kind", "taskMeta")
        put("taskId", query.taskId)
        if (query.workspacePath != null) put("workspacePath", query.workspacePath)
    }

    is ChannelQuery.TaskSnapshot -> buildJsonObject {
        put("kind", "taskSnapshot")
        put("taskId", query.taskId)
        if (query.workspacePath != null) put("workspacePath", query.workspacePath)
    }

    is ChannelQuery.ContextWindow -> buildJsonObject {
        put("kind", "contextWindow")
        put("taskId", query.taskId)
        if (query.workspacePath != null) put("workspacePath", query.workspacePath)
    }

    is ChannelQuery.TaskUsage -> buildJsonObject {
        put("kind", "taskUsage")
        put("taskId", query.taskId)
        if (query.workspacePath != null) put("workspacePath", query.workspacePath)
    }

    is ChannelQuery.Quota -> buildJsonObject {
        put("kind", "quota")
        if (query.invalidateBalanceCache) put("invalidateBalanceCache", true)
        query.taskId?.let { put("taskId", it) }
    }

    is ChannelQuery.ModelSelection -> buildJsonObject {
        put("kind", "modelSelection")
        query.workspacePath?.let { put("workspacePath", it) }
    }

    is ChannelQuery.SetModelSelection -> buildJsonObject {
        put("kind", "setModelSelection")
        put("scope", query.scope)
        query.plan?.let { put("plan", it) }
        query.model?.let { put("model", it) }
        query.effort?.let { put("effort", it) }
        query.workspacePath?.let { put("workspacePath", it) }
    }

    ChannelQuery.BotStatus -> buildJsonObject { put("kind", "botStatus") }

    is ChannelQuery.Stop -> buildJsonObject {
        put("kind", "stop")
        put("taskId", query.taskId)
    }

    is ChannelQuery.StopAll -> buildJsonObject {
        put("kind", "stopAll")
        if (query.workspacePath != null) put("workspacePath", query.workspacePath)
    }

    is ChannelQuery.Compact -> buildJsonObject {
        put("kind", "compact")
        put("taskId", query.taskId)
    }
}

/** The inbound's attachment rows — the fork's DaemonChannelInboundAttachment, one object per file. */
private fun encodeAttachments(attachments: List<InboundAttachment>): kotlinx.serialization.json.JsonArray =
    buildJsonArray {
        attachments.forEach { att ->
            add(
                buildJsonObject {
                    put("id", att.id)
                    put("kind", att.kind)
                    put("filename", att.filename)
                    put("mimeType", att.mimeType)
                    if (att.sizeBytes != null) put("sizeBytes", att.sizeBytes)
                    if (att.localPath != null) put("localPath", att.localPath)
                },
            )
        }
    }

// ---- server → client --------------------------------------------------------

/** What the fork's host answers. The stream event stays raw JSON (the union is the fork's). */
sealed interface ServerFrame {
    data class HelloOk(
        val protocolVersion: Int,
        val serverPid: Long?,
        val serverStartedAt: Long?,
        val appVersion: String?,
        val botId: String,
        val botEnabled: Boolean,
        val capabilities: List<String>,
    ) : ServerFrame

    data class InboundResult(val id: String, val duplicate: Boolean, val replies: List<String>) : ServerFrame

    /**
     * El ACK honesto del respond (el fork's 6993d7e8): ok=true SOLO tras el
     * ACK del v4 command del host; el fallo viaja COMO ChannelError (con el
     * mismo id) — el tap edita el mensaje de la pregunta con la verdad.
     */
    data class RespondResult(
        val id: String,
        val taskId: String,
        val requestId: String,
        val kind: String,
        val ok: Boolean,
    ) : ServerFrame

    data class Subscribed(val id: String, val taskId: String) : ServerFrame

    data class Unsubscribed(val id: String, val taskId: String) : ServerFrame

    data class QueryResult(val id: String, val result: String) : ServerFrame

    data class Pong(val id: String, val at: Long) : ServerFrame

    data class ChannelError(
        val id: String?,
        val code: String,
        val message: String?,
        val fatal: Boolean,
    ) : ServerFrame

    data class StreamEvent(val taskId: String, val seq: Long, val at: Long, val event: JsonObject) : ServerFrame

    /**
     * The fork's stream heartbeat (f94af960): one per live subscription
     * every ~15s. ITS SEQ CONSUMES THE CONNECTION'S COUNTER — the daemon
     * must account it or every heartbeat in a quiet stretch reads as a
     * +2 seq jump = a FALSE gap = the pending CoT wiped (measured live
     * 31/10: the blocks never reached 3000). The bus's proof of life,
     * NOT the model's: lastEventAtMs never moves on it.
     */
    data class KeepAlive(val taskId: String, val seq: Long, val at: Long) : ServerFrame

    data class Bye(val reason: String) : ServerFrame
}

/**
 * Pure: one server line → a frame. `null` for anything the daemon does
 * not know (an unknown `type`, an unparseable line): the additive rule
 * says the reader IGNORES what it does not understand — a broken line is
 * counted by the caller, never fatal (§3.5: line errors do not close).
 */
// one branch per wire type — the additive table's own shape
@Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
fun decodeServerLine(line: String): ServerFrame? {
    val root = runCatching { Json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return null
    return when (val type = (root["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
        "hello_ok" -> ServerFrame.HelloOk(
            protocolVersion = root.int("protocolVersion") ?: 0,
            serverPid = root.obj("server")?.int("pid")?.toLong(),
            serverStartedAt = root.obj("server")?.long("startedAt"),
            appVersion = root.obj("server")?.str("appVersion"),
            botId = root.obj("bot")?.str("id") ?: "",
            botEnabled = root.obj("bot")?.bool("enabled") ?: false,
            capabilities = (root["capabilities"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                .orEmpty(),
        )

        "inbound_result" -> ServerFrame.InboundResult(
            id = root.str("id") ?: return null,
            duplicate = root.bool("duplicate") ?: false,
            replies = (root["replies"] as? JsonArray)
                ?.mapNotNull { reply -> (reply as? JsonObject)?.str("text") }
                .orEmpty(),
        )

        "respond_result" -> ServerFrame.RespondResult(
            id = root.str("id") ?: return null,
            taskId = root.str("taskId") ?: return null,
            requestId = root.str("requestId") ?: return null,
            kind = root.str("kind") ?: "unknown",
            ok = root.bool("ok") ?: false,
        )

        "subscribed" -> ServerFrame.Subscribed(
            id = root.str("id") ?: return null,
            taskId = root.str("taskId") ?: return null,
        )

        "unsubscribed" ->
            ServerFrame.Unsubscribed(id = root.str("id") ?: return null, taskId = root.str("taskId") ?: return null)

        "query_result" -> ServerFrame.QueryResult(
            id = root.str("id") ?: return null,
            result = root["result"].toString(),
        )

        "pong" -> ServerFrame.Pong(id = root.str("id") ?: return null, at = root.long("at") ?: 0L)

        "error" -> ServerFrame.ChannelError(
            id = root.str("id"),
            code = root.str("code") ?: "internal",
            message = root.str("message"),
            fatal = root.bool("fatal") ?: false,
        )

        "stream_event" -> {
            val event = root["event"] as? JsonObject ?: return null
            ServerFrame.StreamEvent(
                taskId = root.str("taskId") ?: return null,
                seq = root.long("seq") ?: return null,
                at = root.long("at") ?: 0L,
                event = event,
            )
        }

        "stream_keepalive" -> ServerFrame.KeepAlive(
            taskId = root.str("taskId") ?: return null,
            seq = root.long("seq") ?: return null,
            at = root.long("at") ?: 0L,
        )

        "bye" -> ServerFrame.Bye(reason = root.str("reason") ?: "unspecified")

        else -> null // unknown type (additive rule) or unparseable line — counted by the caller
    }
}

/**
 * The log-safe description of a client frame — the hello's token can
 * NEVER reach a log (the redactor doctrine of the whole transport); a
 * truncated text preview keeps inbound forensics useful without dumping
 * the owner's prompts into daemon.jsonl.
 */
fun describe(frame: ClientFrame): String = when (frame) {
    is ClientFrame.Hello -> "hello(clientId=${frame.clientId}, token=***)"
    is ClientFrame.Inbound -> "inbound(id=${frame.id}, messageId=${frame.messageId}, ${frame.text.length} chars" +
        (if (frame.attachments.isEmpty()) "" else ", ${frame.attachments.size} attachment(s)") + ")"
    is ClientFrame.Subscribe -> "subscribe(id=${frame.id}, taskId=${frame.taskId})"
    is ClientFrame.Unsubscribe -> "unsubscribe(id=${frame.id}, taskId=${frame.taskId})"
    is ClientFrame.Steer -> "steer(id=${frame.id}, taskId=${frame.taskId}, ${frame.text.length} chars)"
    is ClientFrame.Respond ->
        "respond(id=${frame.id}, taskId=${frame.taskId}, kind=${frame.kind}, request=${frame.requestId})"
    is ClientFrame.Query -> "query(id=${frame.id}, kind=${queryKind(frame.query)})"
    is ClientFrame.Ping -> "ping(id=${frame.id})"
}

/** The query kind name — for logs and errors, one spelling with the wire. */
@Suppress("CyclomaticComplexMethod") // one branch per query kind — the wire table's own shape
fun queryKind(query: ChannelQuery): String = when (query) {
    is ChannelQuery.ListTasks -> "listTasks"
    is ChannelQuery.TaskList -> "taskList"
    is ChannelQuery.ActiveTaskIds -> "activeTaskIds"
    is ChannelQuery.TaskMeta -> "taskMeta"
    is ChannelQuery.TaskSnapshot -> "taskSnapshot"
    is ChannelQuery.ContextWindow -> "contextWindow"
    is ChannelQuery.TaskUsage -> "taskUsage"
    is ChannelQuery.Quota -> "quota"
    is ChannelQuery.ModelSelection -> "modelSelection"
    is ChannelQuery.SetModelSelection -> "setModelSelection"
    ChannelQuery.BotStatus -> "botStatus"
    is ChannelQuery.Stop -> "stop"
    is ChannelQuery.StopAll -> "stopAll"
    is ChannelQuery.Compact -> "compact"
}

// ---- tiny JSON readers (JsonObject's stdlib accessors made shorter) ----------

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let {
    when (it.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
