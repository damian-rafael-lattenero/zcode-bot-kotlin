package dev.lattenero.bot.daemon.brain

import dev.lattenero.bot.channel.BrainGateway
import dev.lattenero.bot.channel.BrainReply
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.SocketTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The brain's base URL as read from `<home>/daemon.json` (read once at boot). */
data class BrainEndpointReading(val url: String, val warning: String? = null)

/**
 * `brain_url` in the same `<home>/daemon.json` the knobs live in. Absent
 * file/key → agent-brain's own default port (`http_port` 8765 in its
 * config); a non-string or blank value falls back to the default LOUDLY.
 */
object BrainEndpoint {

    const val KEY = "brain_url"
    const val DEFAULT_URL = "http://127.0.0.1:8765"

    @Suppress("TooGenericExceptionCaught") // any read failure keeps the default, loudly
    fun load(file: File): BrainEndpointReading = when {
        !file.exists() -> BrainEndpointReading(DEFAULT_URL)

        else -> try {
            val root = Json.parseToJsonElement(file.readText()) as? JsonObject
            val value = root?.get(KEY)
            when {
                value == null -> BrainEndpointReading(DEFAULT_URL)

                value is JsonPrimitive && value.isString && value.content.isNotBlank() ->
                    BrainEndpointReading(value.content.trim().trimEnd('/'))

                else -> BrainEndpointReading(DEFAULT_URL, "'$KEY' is not a URL — using $DEFAULT_URL")
            }
        } catch (e: Exception) {
            BrainEndpointReading(DEFAULT_URL, "unreadable (${e.message}) — using $DEFAULT_URL")
        }
    }
}

/**
 * The brain's ktor leg (QuotaFetch's shape: one scoped client per call,
 * explicit status, `expectSuccess = false`). The classification is the
 * command's honesty ladder: a timeout is ⏱️, everything else that never
 * produced an HTTP answer is 📡. Cancellation still propagates — the
 * memory inject's own 1.5 s window cancels the request, never the daemon.
 */
// every transport failure is classified, none flies raw; the timeout classes
// ARE the answer (⏱️), nothing is lost by not rethrowing them
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal suspend fun brainCall(
    baseUrl: String,
    method: HttpMethod,
    path: String,
    body: JsonObject? = null,
    timeout: Duration = COMMAND_TIMEOUT,
    engine: HttpClientEngineFactory<*> = CIO,
): BrainReply {
    val client = HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = timeout.inWholeMilliseconds
            connectTimeoutMillis = timeout.inWholeMilliseconds
        }
    }
    return try {
        client.use {
            val url = baseUrl.trimEnd('/') + path
            val response = when (method) {
                HttpMethod.Post -> it.post(url) {
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Application.Json)
                    setBody(Json.encodeToString(JsonObject.serializer(), body ?: JsonObject(emptyMap())))
                }

                else -> it.get(url) { accept(ContentType.Application.Json) }
            }
            BrainReply.Response(response.status.value, response.bodyAsText())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpRequestTimeoutException) {
        BrainReply.Timeout
    } catch (e: ConnectTimeoutException) {
        BrainReply.Timeout
    } catch (e: SocketTimeoutException) {
        BrainReply.Timeout
    } catch (e: Exception) {
        BrainReply.Down("${e::class.simpleName}: ${e.message?.take(DETAIL_CAP)}")
    }
}

/** The commands' budget (§ the spec's ~5 s). */
val COMMAND_TIMEOUT: Duration = 5.seconds

private const val DETAIL_CAP = 200

/** The production gateway: agent-brain over localhost HTTP, no auth (P2's contract). */
internal fun brainGateway(
    endpoint: BrainEndpointReading,
    engine: HttpClientEngineFactory<*> = CIO,
): BrainGateway = BrainGateway(
    url = endpoint.url,
    ragQuery = { query, k ->
        brainCall(endpoint.url, HttpMethod.Post, "/rag/query", body = queryBody(query, k), engine = engine)
    },
    ragStats = { brainCall(endpoint.url, HttpMethod.Get, "/rag/stats", engine = engine) },
    busStatus = { brainCall(endpoint.url, HttpMethod.Get, "/bus/status", engine = engine) },
    memorySearch = { query, k ->
        brainCall(endpoint.url, HttpMethod.Post, "/memory/search", body = queryBody(query, k), engine = engine)
    },
    memoryRecent = { limit -> brainCall(endpoint.url, HttpMethod.Get, "/memory/recent?limit=$limit", engine = engine) },
    memoryStatus = { brainCall(endpoint.url, HttpMethod.Get, "/memory/status", engine = engine) },
)

/** The two POST bodies share the same {query, k} shape (agent-brain's request types). */
private fun queryBody(query: String, k: Int): JsonObject = buildJsonObject {
    put("query", query)
    put("k", k)
}
