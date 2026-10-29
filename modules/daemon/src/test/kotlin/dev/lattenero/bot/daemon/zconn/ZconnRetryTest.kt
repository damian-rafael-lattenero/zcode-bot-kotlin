package dev.lattenero.bot.daemon.zconn

import dev.lattenero.bot.daemon.clilogs.CliEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The error inventory's zconn half, pinned to the incidents that taught
 * each rule: the 1308 five-hour window announced as «next retry 2s», the
 * ⌘-stop that announced a false «connection lost», and the retry storm
 * that must not become a notification storm.
 */
class ZconnRetryTest {

    private fun context(vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        buildJsonObject { fields.forEach { (k, v) -> put(k, v) } }

    private fun retryEvent(
        ts: Double,
        attempt: Long? = null,
        nextAttempt: Long? = null,
        maxAttempts: Long? = null,
        delayMs: Long,
        reason: String = "rate_limited",
    ): CliEvent {
        val ctx = buildJsonObject {
            if (attempt != null) put("attempt", attempt)
            if (nextAttempt != null) put("nextAttempt", nextAttempt)
            if (maxAttempts != null) put("maxAttempts", maxAttempts)
            put("delayMs", delayMs)
            put("reason", reason)
            put("statusCode", 1308)
        }
        return CliEvent(ts, "ses1", "model.network.retry_scheduled", ctx)
    }

    @Test
    fun the1308IncidentAnnouncesTheRealWindow() {
        // production: usage-limit 1308, delayMs=37_922_285 (10.5 h) — the old
        // synthesized pill said «next retry 2s»
        val track = ConnTrack()
        val scheduled = ConnTrackMachine.retryContextOf(
            context("nextAttempt" to kotlinx.serialization.json.JsonPrimitive(2), "delayMs" to kotlinx.serialization.json.JsonPrimitive(37_922_285L), "reason" to kotlinx.serialization.json.JsonPrimitive("rate_limited")),
        )!!
        val (next, notices) = ConnTrackMachine.feedRetry(track, ts = 1000.0, scheduled = scheduled)
        assertEquals(1, notices.size)
        val notice = notices.single() as ZconnNotice.RetryWait
        // nextAttempt=2 → THIS is retry #1 (the UI's own arithmetic)
        assertTrue("z.ai Rate limited — retrying 1/11" in notice.pill, notice.pill)
        assertTrue("10:32:03 from now" in notice.pill, notice.pill) // the REAL countdown
        assertEquals(1L, next.announcedRetry)
    }

    @Test
    fun theAdapterSpellingRidesTheSameTransition() {
        // the adapter logs `model.retry.scheduled` with attempt/maxAttempts spelled out
        val scheduled = ConnTrackMachine.retryContextOf(
            context(
                "attempt" to kotlinx.serialization.json.JsonPrimitive(3),
                "maxAttempts" to kotlinx.serialization.json.JsonPrimitive(8),
                "delayMs" to kotlinx.serialization.json.JsonPrimitive(90_000),
                "reason" to kotlinx.serialization.json.JsonPrimitive("provider_overloaded"),
            ),
        )!!
        val (_, notices) = ConnTrackMachine.feedRetry(ConnTrack(), 5.0, scheduled)
        val pill = (notices.single() as ZconnNotice.RetryWait).pill
        assertTrue("z.ai overloaded — retrying 3/8" in pill, pill)
        assertTrue("01:30 from now" in pill, pill)
    }

    @Test
    fun routineBackoffStaysQuiet() {
        // the 2^n sputter (≤60s) is the episode the Down notice already spoke
        listOf(2000L, 4000L, 8000L, 16_000L, 32_000L).forEach { delay ->
            val scheduled = ConnTrackMachine.retryContextOf(context("delayMs" to kotlinx.serialization.json.JsonPrimitive(delay)))!!
            val (_, notices) = ConnTrackMachine.feedRetry(ConnTrack(), 5.0, scheduled)
            assertEquals(emptyList(), notices, "delayMs=$delay should stay quiet")
        }
    }

    @Test
    fun oneNoticePerAttemptEvenWhenTheLineRepeats() {
        val scheduled = ConnTrackMachine.retryContextOf(context("attempt" to kotlinx.serialization.json.JsonPrimitive(2), "delayMs" to kotlinx.serialization.json.JsonPrimitive(120_000)))!!
        var track = ConnTrack()
        val first = ConnTrackMachine.feedRetry(track, 5.0, scheduled)
        assertEquals(1, first.second.size)
        track = first.first
        // the same attempt rescheduled (the line re-logs): silence
        val repeat = ConnTrackMachine.feedRetry(track, 9.0, scheduled)
        assertEquals(emptyList(), repeat.second)
        // the NEXT attempt speaks again (a NEW long wait is new information)
        val nextScheduled = ConnTrackMachine.retryContextOf(context("attempt" to kotlinx.serialization.json.JsonPrimitive(3), "delayMs" to kotlinx.serialization.json.JsonPrimitive(240_000)))!!
        val advance = ConnTrackMachine.feedRetry(repeat.first, 12.0, nextScheduled)
        assertEquals(1, advance.second.size)
    }

    @Test
    fun recoveryClearsTheRetryMemory() {
        var track = ConnTrack(
            down = true,
            firstDown = 1.0,
            announcedRetry = 2L,
        )
        track = ConnTrackMachine.feed(track, 100.0, "model.network.completed", "", "").first
        assertEquals(null, track.announcedRetry)
    }

    @Test
    fun aUserStopIsNotAnOutage() {
        // reason/status «cancelled» on model.network.failed = the USER's ⌘-stop
        assertTrue(
            ConnTrackMachine.isCancelledDown(
                "model.network.failed",
                context("reason" to kotlinx.serialization.json.JsonPrimitive("cancelled")),
            ),
        )
        assertTrue(
            ConnTrackMachine.isCancelledDown(
                "model.network.failed",
                context("status" to kotlinx.serialization.json.JsonPrimitive("cancelled"), "reason" to kotlinx.serialization.json.JsonPrimitive("other")),
            ),
        )
        // a REAL failure is not cancelled
        assertEquals(
            false,
            ConnTrackMachine.isCancelledDown(
                "model.network.failed",
                context("reason" to kotlinx.serialization.json.JsonPrimitive("server_error")),
            ),
        )
        // and the check never touches other events
        assertEquals(false, ConnTrackMachine.isCancelledDown("model.network.completed", null))
    }

    @Test
    fun zconnPassSkipsCancelledDownsEntirely() {
        val state = dev.lattenero.bot.contracts.ZconnState(since = 0.0)
        val events = listOf(
            CliEvent(
                10.0,
                "ses1",
                "model.network.failed",
                context("reason" to kotlinx.serialization.json.JsonPrimitive("cancelled"), "status" to kotlinx.serialization.json.JsonPrimitive("cancelled")),
            ),
        )
        val result = zconnPass(state, events, now = 20.0)
        // the ⌘-stop never announced a cut: quiet, and the machine never went down
        assertTrue(result is PassResult.Quiet)
        assertEquals(false, (result as PassResult.Quiet).state.down)
    }

    @Test
    fun zconnPassRoutesTheRetryNeedlesAndPersistsTheDedupe() {
        val state = dev.lattenero.bot.contracts.ZconnState(since = 0.0)
        val events = listOf(
            retryEvent(ts = 10.0, nextAttempt = 2, delayMs = 37_922_285, reason = "rate_limited"),
            retryEvent(ts = 11.0, nextAttempt = 2, delayMs = 37_922_285, reason = "rate_limited"), // re-log: quiet
        )
        val result = zconnPass(state, events, now = 20.0) as PassResult.Spoke
        assertEquals(1, result.decisions.size)
        val decision = result.decisions.single()
        assertEquals("retry_wait", decision.decision)
        assertTrue(decision.text.startsWith("⏳ <b>z.ai Rate limited"))
        assertTrue("10:32:03" in decision.text)
        assertEquals(1.0, result.state.announcedRetry) // persisted: the next cycle stays quiet
    }

    @Test
    fun retryEventsWithoutADelayAreIgnoredSafely() {
        assertEquals(null, ConnTrackMachine.retryContextOf(context("reason" to kotlinx.serialization.json.JsonPrimitive("x"))))
        assertEquals(null, ConnTrackMachine.retryContextOf(null))
    }

    @Test
    fun theNeedlesCoverBothSpellings() {
        assertTrue("model.network.retry_scheduled" in ConnTrackMachine.NEEDLES)
        assertTrue("model.retry.scheduled" in ConnTrackMachine.NEEDLES)
        // the needles family still feeds the episode machine unchanged
        assertTrue("model.network.failed" in ConnTrackMachine.NEEDLES)
        assertTrue("model.network.completed" in ConnTrackMachine.NEEDLES)
    }
}
