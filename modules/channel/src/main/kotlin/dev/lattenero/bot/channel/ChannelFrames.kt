package dev.lattenero.bot.channel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

    data class TaskUsage(val taskId: String, val workspacePath: String? = null) : ChannelQuery

    data class Quota(val invalidateBalanceCache: Boolean = false) : ChannelQuery

    data object BotStatus : ChannelQuery

    data class Stop(val taskId: String) : ChannelQuery

    data class StopAll(val workspacePath: String? = null) : ChannelQuery

    data class Compact(val taskId: String) : ChannelQuery
}

/** `workspacePath` + optional identity — the isolation key of the fork's task stores. */
data class WorkspaceScope(val workspacePath: String, val workspaceIdentity: String? = null)

// ---- client → server --------------------------------------------------------

/** What the daemon sends. The hello carries the token — [describe] keeps it out of every log. */
sealed interface ClientFrame {
    data class Hello(val token: String, val clientId: String = ChannelSpec.CLIENT_ID) : ClientFrame

    data class Inbound(
        val id: String,
        val text: String,
        val messageId: String?,
        val receivedAt: Long?,
    ) : ClientFrame

    data class Subscribe(val id: String, val taskId: String, val workspacePath: String) : ClientFrame

    data class Unsubscribe(val id: String, val taskId: String) : ClientFrame

    data class Query(val id: String, val query: ChannelQuery) : ClientFrame

    data class Ping(val id: String) : ClientFrame
}

/** Pure: the exact JSONL line for a client frame (the wire is `line + '\n'`). */
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

    is ChannelQuery.TaskUsage -> buildJsonObject {
        put("kind", "taskUsage")
        put("taskId", query.taskId)
        if (query.workspacePath != null) put("workspacePath", query.workspacePath)
    }

    is ChannelQuery.Quota -> buildJsonObject {
        put("kind", "quota")
        if (query.invalidateBalanceCache) put("invalidateBalanceCache", true)
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

    data class Bye(val reason: String) : ServerFrame
}

/**
 * Pure: one server line → a frame. `null` for anything the daemon does
 * not know (an unknown `type`, an unparseable line): the additive rule
 * says the reader IGNORES what it does not understand — a broken line is
 * counted by the caller, never fatal (§3.5: line errors do not close).
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod") // one guard per frame — the additive rule is the shape
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
    is ClientFrame.Inbound -> "inbound(id=${frame.id}, messageId=${frame.messageId}, ${frame.text.length} chars)"
    is ClientFrame.Subscribe -> "subscribe(id=${frame.id}, taskId=${frame.taskId})"
    is ClientFrame.Unsubscribe -> "unsubscribe(id=${frame.id}, taskId=${frame.taskId})"
    is ClientFrame.Query -> "query(id=${frame.id}, kind=${queryKind(frame.query)})"
    is ClientFrame.Ping -> "ping(id=${frame.id})"
}

/** The query kind name — for logs and errors, one spelling with the wire. */
fun queryKind(query: ChannelQuery): String = when (query) {
    is ChannelQuery.ListTasks -> "listTasks"
    is ChannelQuery.TaskList -> "taskList"
    is ChannelQuery.ActiveTaskIds -> "activeTaskIds"
    is ChannelQuery.TaskMeta -> "taskMeta"
    is ChannelQuery.TaskSnapshot -> "taskSnapshot"
    is ChannelQuery.TaskUsage -> "taskUsage"
    is ChannelQuery.Quota -> "quota"
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
