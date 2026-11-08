package dev.lattenero.bot.daemon

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException

/** /quota's direct leg budget: ONE attempt, short — the channel fallback is the retry. */
private val QUOTA_HTTP_TIMEOUT = 5.seconds

/**
 * /quota's direct legs (incident 30/10, made provider-aware on 3/11):
 * ONE Bearer GET — TgApi's own Ktor shape (CIO engine, explicit status,
 * `expectSuccess = false`), a 5 s budget, and the REDACTION LAW: the key
 * rides a request HEADER (never a URL), and the thrown messages carry
 * neither URL, body nor headers — nothing that could log it. [headers]
 * carries the provider's own non-secret requirements (OpenCode's
 * Cloudflare signature), [service] names the leg in the thrown line. The
 * client is SCOPED: the rare /quota pays its own setup and nothing
 * outlives the call (no daemon-lifecycle plumbing for a command typed by
 * hand). Failures THROW — the caller's honest-null boundary owns them.
 */
// the redaction law + the cancellation rethrow — each refusal its own honest failure
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
internal suspend fun quotaBearerGet(
    url: String,
    token: String,
    engine: HttpClientEngineFactory<*> = CIO,
    headers: Map<String, String> = emptyMap(),
    service: String = "quota",
): String {
    val client = HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = QUOTA_HTTP_TIMEOUT.inWholeMilliseconds }
    }
    return client.use {
        val resp = try {
            it.get(url) {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, "application/json")
                headers.forEach { (name, value) -> header(name, value) }
            }
        } catch (e: CancellationException) {
            throw e // structured concurrency: cancellation is never laundered into an IOException
        } catch (e: Exception) {
            // the exception's own text may embed anything — the class name
            // is the only witness the log gets (TgApi's law, header-flavored)
            throw IOException("$service quota fetch failed: ${e::class.simpleName}", e)
        }
        if (!resp.status.isSuccess()) throw IOException("$service quota HTTP ${resp.status.value}")
        resp.bodyAsText()
    }
}

/** z.ai's leg — the original call site's name (QuotaDirect's endpoint, Python's key path). */
internal suspend fun zaiQuotaGet(
    url: String,
    token: String,
    engine: HttpClientEngineFactory<*> = CIO,
): String = quotaBearerGet(url, token, engine, service = "z.ai")
