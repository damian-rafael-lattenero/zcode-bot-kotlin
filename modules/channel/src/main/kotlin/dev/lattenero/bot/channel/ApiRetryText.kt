package dev.lattenero.bot.channel

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The API-retry pill — the fork's own «chat input» pill (display.tsx),
 * 1:1 with its labels, as PURE text. Two producers carry it to the
 * daemon: `session_info_update.apiRetry` (the normalized patch, with the
 * ABSOLUTE nextRetryAtMs) and `task_network_debug_status` (per-task raw
 * statusType; no absolute clock — the countdown is event-ts + delayMs).
 * The pill is part of the never-silence contract: «z.ai overloaded» and
 * «I typed too much» are different situations and the phone must see
 * which one it is.
 */
data class ApiRetryPill(
    /** The UI's own label: "z.ai server error", "Rate limited", ... */
    val label: String,
    val attempt: Int,
    val maxRetries: Int,
    /** ABSOLUTE epoch ms of the next try; null = no countdown (stream recovery reopens instantly). */
    val nextRetryAtMs: Long? = null,
    val errorStatus: Int? = null,
)

/** The reason → label map and the countdown format, both 1:1 with the fork's UI (B2). */
object ApiRetryText {

    /** display.tsx's API_RETRY_REASON_MESSAGE_IDS + the en-US locale texts, one table. */
    private val REASON_LABELS = mapOf(
        "server_error" to "z.ai server error",
        "provider_overloaded" to "z.ai overloaded",
        "rate_limited" to "Rate limited",
        "timeout" to "Stream stalled",
        "stream_idle_timeout" to "Stream stalled",
        "network_error" to "Connection lost",
        "stale_connection" to "Connection lost",
        "stream_recovery" to "Recovering stream",
        // v4 fault.* convergence codes (old producers without a raw reason)
        "fault.provider.rateLimited" to "Rate limited",
        "fault.provider.serverError" to "z.ai server error",
        "fault.network.timeout" to "Stream stalled",
        "fault.network.sseStalled" to "Stream stalled",
        "fault.network.sseDisconnected" to "Connection lost",
        "fault.network.unreachable" to "Connection lost",
    )

    private const val DEFAULT_LABEL = "Reconnecting..."

    /** apiRetryStatusMessageId's answer, already resolved to the en-US text. */
    fun label(reason: String?): String = REASON_LABELS[reason] ?: DEFAULT_LABEL

    /**
     * formatRetryCountdown: `mm:ss`, `h:mm:ss` at ≥1h, negatives clamped
     * to 0, seconds CEILED (the UI's exact arithmetic).
     */
    fun countdown(remainingMs: Long): String {
        val totalSeconds = maxOf(0L, (remainingMs + 999) / 1000)
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return when (hours) {
            0L -> "%02d:%02d".format(minutes, seconds)
            else -> "$hours:%02d:%02d".format(minutes, seconds)
        }
    }

    /** The pill's whole line: label — retrying a/max (+ countdown while a deadline exists). */
    fun line(pill: ApiRetryPill, nowMs: Long): String {
        val base = "${pill.label} — retrying ${pill.attempt}/${pill.maxRetries}"
        val left = pill.nextRetryAtMs?.let { it - nowMs } ?: return base
        return "$base · ${countdown(left)} left"
    }

    /** The patch's three answers — the fork's own apiRetry patch semantics. */
    sealed interface ApiRetryPatch {
        /** The event did not touch the retry state (absent key). */
        data object Untouched : ApiRetryPatch

        /** Explicit clear (JSON null) — or cleared by real progress. */
        data object Clear : ApiRetryPatch

        /** Enter/update the retry state. */
        data class Set(val pill: ApiRetryPill) : ApiRetryPatch
    }

    /**
     * session_info_update.apiRetry → the patch. ABSENT key = Untouched,
     * JSON null = Clear (the [JsonNull] marker), object = Set. The
     * attempt/maxRetries fallbacks mirror normalizeZCodeApiRetryStatus
     * (`attempt = max(nextAttempt-1, 1)`, `maxRetries = max(x, attempt)`).
     */
    @Suppress("ReturnCount") // the patch three answers, one guard per shape
    fun fromPatch(event: JsonObject): ApiRetryPatch {
        if (!event.containsKey("apiRetry")) return ApiRetryPatch.Untouched
        val raw = event["apiRetry"]
        if (raw == null || raw is JsonNull) return ApiRetryPatch.Clear
        val obj = raw as? JsonObject ?: return ApiRetryPatch.Untouched
        val attempt = obj.int("attempt")
            ?: ((obj.int("nextAttempt") ?: FIRST_ATTEMPT + 1) - 1).coerceAtLeast(FIRST_ATTEMPT)
        val maxRetries = obj.int("maxRetries")
            ?: ((obj.int("maxAttempts") ?: attempt + 1) - 1).coerceAtLeast(attempt)
        return ApiRetryPatch.Set(
            ApiRetryPill(
                label = label(obj.str("reason")),
                attempt = attempt,
                maxRetries = maxRetries,
                nextRetryAtMs = obj.long("nextRetryAtMs")?.takeIf { it > 0 },
                errorStatus = obj.int("errorStatus"),
            ),
        )
    }

    /**
     * task_network_debug_status → the patch from the RAW statusType (the
     * same projection the UI consumes): retry_scheduled carries delayMs
     * (countdown = at + delayMs — nextAttemptAtMs is NOT logged),
     * stream_stalled is the pill with no countdown, started/completed
     * and a non-retryable fail CLEAR, a retryable fail KEEPS the pill
     * (the retry_scheduled that follows refreshes it).
     */
    fun fromNetworkDebug(event: JsonObject, atMs: Long): ApiRetryPatch {
        val statusType = event.str("statusType") ?: return ApiRetryPatch.Untouched
        return when (statusType) {
            "model_retry_scheduled" -> {
                val attempt = ((event.int("nextAttempt") ?: FIRST_ATTEMPT + 1) - ATTEMPT_OFFSET)
                    .coerceAtLeast(FIRST_ATTEMPT)
                ApiRetryPatch.Set(
                    ApiRetryPill(
                        label = label(event.str("reason")),
                        attempt = attempt,
                        maxRetries = event.int("maxAttempts")?.coerceAtLeast(attempt) ?: attempt,
                        nextRetryAtMs = (event.long("delayMs")?.takeIf { it > 0 })?.let { atMs + it },
                        errorStatus = event.int("statusCode"),
                    ),
                )
            }

            "model_stream_stalled" -> ApiRetryPatch.Set(
                ApiRetryPill(
                    label = label("stream_idle_timeout"),
                    attempt = event.int("attempt") ?: FIRST_ATTEMPT,
                    maxRetries = event.int("maxAttempts") ?: FIRST_ATTEMPT,
                ),
            )

            // real progress: the stream is flowing again — the pill goes away
            "model_request_started", "model_request_completed" -> ApiRetryPatch.Clear

            // a retryable fail keeps the pending pill; a hard fail clears it (the UI's own rule)
            "model_request_failed" ->
                if (event.bool("retryable") == true) ApiRetryPatch.Untouched else ApiRetryPatch.Clear

            else -> ApiRetryPatch.Untouched
        }
    }

    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val ATTEMPT_OFFSET = 1
    private const val FIRST_ATTEMPT = 1
}
