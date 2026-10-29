package dev.lattenero.bot.channel

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The apiRetry pill against the fork's own sources: the label map is
 * display.tsx's, the countdown is formatRetryCountdown's arithmetic, the
 * patch semantics are normalizeZCodeApiRetryStatus's. One vocabulary with
 * the desktop — the pill the phone sees IS the pill the composer shows.
 */
class ApiRetryTextTest {

    @Test
    fun labelsAreTheDesktopsOwn() {
        assertEquals("z.ai server error", ApiRetryText.label("server_error"))
        assertEquals("z.ai overloaded", ApiRetryText.label("provider_overloaded"))
        assertEquals("Rate limited", ApiRetryText.label("rate_limited"))
        assertEquals("Stream stalled", ApiRetryText.label("timeout"))
        assertEquals("Stream stalled", ApiRetryText.label("stream_idle_timeout"))
        assertEquals("Connection lost", ApiRetryText.label("network_error"))
        assertEquals("Connection lost", ApiRetryText.label("stale_connection"))
        assertEquals("Recovering stream", ApiRetryText.label("stream_recovery"))
        assertEquals("Reconnecting...", ApiRetryText.label(null))
        assertEquals("Reconnecting...", ApiRetryText.label("brand_new_reason"))
        // the v4 fault.* convergence codes ride the same labels
        assertEquals("Rate limited", ApiRetryText.label("fault.provider.rateLimited"))
        assertEquals("z.ai server error", ApiRetryText.label("fault.provider.serverError"))
        assertEquals("Stream stalled", ApiRetryText.label("fault.network.sseStalled"))
        assertEquals("Connection lost", ApiRetryText.label("fault.network.unreachable"))
    }

    @Test
    fun countdownIsTheUisArithmetic() {
        assertEquals("00:00", ApiRetryText.countdown(0))
        assertEquals("00:01", ApiRetryText.countdown(1)) // CEILED
        assertEquals("00:01", ApiRetryText.countdown(1000))
        assertEquals("00:02", ApiRetryText.countdown(1500))
        assertEquals("05:00", ApiRetryText.countdown(300_000))
        assertEquals("1:00:00", ApiRetryText.countdown(3599_999)) // ceil(3599.999)=3600
        assertEquals("1:00:00", ApiRetryText.countdown(3600_000))
        assertEquals("1:01:01", ApiRetryText.countdown(3661_000))
        // the 1308 incident's own window: 37922285ms ≈ 10h32m03s, ceil'd
        assertEquals("10:32:03", ApiRetryText.countdown(37_922_285))
        // negatives clamp to zero (a clock that already passed)
        assertEquals("00:00", ApiRetryText.countdown(-5000))
    }

    @Test
    fun patchAbsentMeansUntouchedNullMeansClear() {
        val untouched = buildJsonObject { put("type", "session_info_update") }
        assertEquals(ApiRetryText.ApiRetryPatch.Untouched, ApiRetryText.fromPatch(untouched))

        val cleared = buildJsonObject { put("type", "session_info_update"); put("apiRetry", null as String?) }
        assertEquals(ApiRetryText.ApiRetryPatch.Clear, ApiRetryText.fromPatch(cleared))

        val set = buildJsonObject {
            put("type", "session_info_update")
            put("apiRetry", buildJsonObject {
                put("kind", "api_retry")
                put("attempt", 2)
                put("maxRetries", 10)
                put("retryDelayMs", 8000)
                put("nextRetryAtMs", 1761700010000)
                put("reason", "provider_overloaded")
                put("errorStatus", 503)
                put("error", "overloaded")
            })
        }
        val pill = ApiRetryText.fromPatch(set) as ApiRetryText.ApiRetryPatch.Set
        assertEquals("z.ai overloaded", pill.pill.label)
        assertEquals(2, pill.pill.attempt)
        assertEquals(10, pill.pill.maxRetries)
        assertEquals(1761700010000L, pill.pill.nextRetryAtMs)
        assertEquals(503, pill.pill.errorStatus)
    }

    @Test
    fun patchFallsBackLikeTheNormalizer() {
        // no attempt, nextAttempt=3 → attempt 2 (the UI's own arithmetic)
        val patch = ApiRetryText.fromPatch(
            buildJsonObject {
                put("apiRetry", buildJsonObject { put("nextAttempt", 3); put("delayMs", 4000) })
            },
        ) as ApiRetryText.ApiRetryPatch.Set
        assertEquals(2, patch.pill.attempt)
        assertEquals(2, patch.pill.maxRetries) // maxRetries ≥ attempt, always
        assertNull(patch.pill.nextRetryAtMs) // no absolute clock → no countdown
    }

    @Test
    fun networkDebugRetryScheduledUsesTsPlusDelayAsTheDeadline() {
        val event = buildJsonObject {
            put("type", "task_network_debug_status")
            put("statusType", "model_retry_scheduled")
            put("nextAttempt", 2)
            put("delayMs", 120_000)
            put("reason", "rate_limited")
            put("statusCode", 429)
        }
        val patch = ApiRetryText.fromNetworkDebug(event, atMs = 1000L) as ApiRetryText.ApiRetryPatch.Set
        assertEquals("Rate limited", patch.pill.label)
        assertEquals(1, patch.pill.attempt)
        assertEquals(121_000L, patch.pill.nextRetryAtMs) // 1000 + delayMs — nextAttemptAtMs is NOT logged
        assertEquals(429, patch.pill.errorStatus)
    }

    @Test
    fun networkDebugStalledSetsStartedAndCompletedClear() {
        val stalled = ApiRetryText.fromNetworkDebug(
            buildJsonObject { put("statusType", "model_stream_stalled") },
            atMs = 0L,
        ) as ApiRetryText.ApiRetryPatch.Set
        assertEquals("Stream stalled", stalled.pill.label)
        assertNull(stalled.pill.nextRetryAtMs)

        assertEquals(
            ApiRetryText.ApiRetryPatch.Clear,
            ApiRetryText.fromNetworkDebug(buildJsonObject { put("statusType", "model_request_started") }, 0L),
        )
        assertEquals(
            ApiRetryText.ApiRetryPatch.Clear,
            ApiRetryText.fromNetworkDebug(buildJsonObject { put("statusType", "model_request_completed") }, 0L),
        )
        // a NON-retryable failure clears; a retryable one keeps the pending pill
        assertEquals(
            ApiRetryText.ApiRetryPatch.Clear,
            ApiRetryText.fromNetworkDebug(
                buildJsonObject { put("statusType", "model_request_failed"); put("retryable", false) },
                0L,
            ),
        )
        assertEquals(
            ApiRetryText.ApiRetryPatch.Untouched,
            ApiRetryText.fromNetworkDebug(
                buildJsonObject { put("statusType", "model_request_failed"); put("retryable", true) },
                0L,
            ),
        )
    }

    @Test
    fun lineCarriesTheCountdownOnlyWhileADeadlineExists() {
        val pill = ApiRetryPill("Rate limited", 2, 10, nextRetryAtMs = 160_000)
        assertEquals("Rate limited — retrying 2/10 · 02:00 left", ApiRetryText.line(pill, nowMs = 40_000))
        // stream_recovery reopens instantly: no countdown line
        assertEquals(
            "Recovering stream — retrying 1/3",
            ApiRetryText.line(ApiRetryPill("Recovering stream", 1, 3), nowMs = 0L),
        )
    }

    @Test
    fun streamRecoveryLabelHasNoCountdownByConstruction() {
        // the coordinator's rule: stream_recovery carries no wait to count
        val patch = ApiRetryText.fromPatch(
            buildJsonObject {
                put("apiRetry", buildJsonObject { put("reason", "stream_recovery"); put("attempt", 1) })
            },
        ) as ApiRetryText.ApiRetryPatch.Set
        assertNull(patch.pill.nextRetryAtMs)
        assertTrue("Recovering stream" in ApiRetryText.line(patch.pill, 0L))
        assertEquals(-1, "Recovering stream".indexOf("left"))
    }
}
