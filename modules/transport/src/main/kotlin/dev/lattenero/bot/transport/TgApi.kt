package dev.lattenero.bot.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The Bot API call (utils/http.py tg_api): one POST per method, JSON in,
 * JSON out, 35 s budget. The token never appears in an exception —
 * messages are redacted through [TgConfig.redact] before they can reach
 * a log.
 */
class TgApi(
    private val configProvider: () -> TgConfig,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    engineFactory: HttpClientEngineFactory<*> = CIO,
) : AutoCloseable {

    private val client = HttpClient(engineFactory) {
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = timeout.inWholeMilliseconds }
    }

    @Suppress("TooGenericExceptionCaught") // every failure must pass the redactor — none flies raw
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement {
        val cfg = configProvider()
        val url = "${cfg.apiBase}/bot${cfg.token}/$method"
        val resp = try {
            client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(params.toString())
            }
        } catch (e: Exception) { // Python lets urllib's errors fly raw; ours must ALL pass the redactor
            // transport failures carry the URL in their message — the
            // redaction happens HERE, once, before anything can log it
            throw IOException(cfg.redact(e.message ?: e::class.simpleName.orEmpty()), e)
        }
        val body = resp.bodyAsText()
        if (!resp.status.isSuccess()) throw TgHttpException(resp.status.value, body)
        return Json.parseToJsonElement(body)
    }

    /**
     * ATTACHMENTS IN (30/10): the Bot API's own download leg — getFile
     * names the file, then a GET on file/bot<token>/<file_path> brings the
     * bytes home (the bot's token, the 20 MB bot law upstream). The target
     * file's parent must exist; the written byte count is the receipt.
     * Every failure passes the redactor — the download URL carries the
     * token, exactly like the POST's.
     */
    @Suppress("TooGenericExceptionCaught") // the same redactor law as call()
    suspend fun downloadFile(fileId: String, target: File): Long {
        val cfg = configProvider()
        val info = call("getFile", buildJsonObject { put("file_id", fileId) })
        val filePath = ((info as? JsonObject)?.get("result") as? JsonObject)
            ?.get("file_path")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
            ?: throw IOException(cfg.redact("getFile answered no file_path for the download"))
        val url = "${cfg.apiBase}/file/bot${cfg.token}/$filePath"
        val resp = try {
            client.get(url)
        } catch (e: Exception) {
            throw IOException(cfg.redact(e.message ?: e::class.simpleName.orEmpty()), e)
        }
        if (!resp.status.isSuccess()) throw TgHttpException(resp.status.value, resp.bodyAsText())
        val bytes = resp.bodyAsBytes()
        target.writeBytes(bytes)
        return bytes.size.toLong()
    }

    override fun close() = client.close()

    companion object {
        val DEFAULT_TIMEOUT = 35.seconds
    }
}

/**
 * An HTTP failure from the Bot API. Python memoizes the retry_after on
 * the exception because HTTPError's body is single-read (the first
 * caller got the TRUE demand, later readers saw b""); here the value is
 * parsed ONCE at construction — every reader sees the same truth.
 */
class TgHttpException(val code: Int?, bodyText: String) :
    IOException(describe(code, bodyText)) {

    /** utils/http.py _retry_after: the 429's demand in seconds — unreadable
     * body → 5, never below 1, null if it isn't a 429. */
    val retryAfter: Int? = parseRetryAfter(code, message ?: "", bodyText)

    companion object {
        private fun describe(code: Int?, bodyText: String): String {
            val description = try {
                (Json.parseToJsonElement(bodyText) as? JsonObject)
                    ?.get("description") as? JsonPrimitive
            } catch (_: Exception) {
                null
            }
            return "HTTP $code: ${description?.content ?: "request failed"}"
        }

        @Suppress("TooGenericExceptionCaught") // unreadable 429 body → conservative default (Python's bare except)
        private fun parseRetryAfter(
            code: Int?,
            message: String,
            bodyText: String,
        ): Int? {
            // the Python gate reads the exception STRING ("429"/"Too Many");
            // ours knows the status — the description check keeps the parity
            if (code != TOO_MANY && TOO_MANY_TEXT !in message) return null
            return try {
                val params = (Json.parseToJsonElement(bodyText) as? JsonObject)
                    ?.get("parameters") as? JsonObject
                val ra = (params?.get("retry_after") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                maxOf(ra, 1)
            } catch (_: Exception) {
                CONSERVATIVE_RETRY_AFTER
            }
        }

        private const val TOO_MANY = 429
        private const val TOO_MANY_TEXT = "Too Many"
        private const val CONSERVATIVE_RETRY_AFTER = 5
    }
}

/** _retry_after(exc) for any throwable: only TgHttpException can answer. */
fun retryAfterOf(t: Throwable): Int? = (t as? TgHttpException)?.retryAfter

/**
 * _message_id(r): the message_id of a sendMessage response, or null.
 * Python: `isinstance(mid, int) and not isinstance(mid, bool)` — a JSON
 * true is not an id.
 */
@Suppress("ReturnCount") // one guard per line — _message_id's exact shape (agent-brain's BusEvents style)
fun messageIdOf(r: JsonElement?): Long? {
    val result = (r as? JsonObject)?.get("result") as? JsonObject ?: return null
    val mid = result["message_id"] as? JsonPrimitive ?: return null
    if (mid.isString || mid.content == "true" || mid.content == "false") return null
    return mid.content.toLongOrNull()
}
