package dev.lattenero.bot.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The /quota direct rung's pure core: the parser pins the REAL endpoint's
 * response shape (Python's quota_live is the source of behavior, its
 * guards frozen here), the render pins Python's quota_report copy
 * byte-a-byte, and the composition pins the honest null on every failure
 * (the user never sees an exception). Fake keys and fixtures only — the
 * real endpoint is never hit and the real key never enters a test.
 */
class QuotaDirectTest {

    /** The endpoint's REAL shape (fabricated values): data{level, limits[]}. */
    private val endpointJson = """
        {"success":true,"data":{"level":"PRO","limits":[
          {"type":"CREDIT_LIMIT","number":1,"currentValue":88000,"usage":88000,"remaining":0,"percentage":90,"nextResetTime":1767225600000},
          {"type":"CREDIT_LIMIT","number":5,"currentValue":31,"usage":120,"remaining":89,"percentage":26,"nextResetTime":1767225600000}
        ]}}
    """.trimIndent()

    /** bot-provider.json's REAL path, a FAKE key value. */
    private val providerJson = """
        {"schemaVersion":1,"config":{"providerConfigRules":{"providerRules":[
          {"providerId":"zai","enabled":true,"config":{"access":{"type":"zhipu-coding-plan-api-key","apiKey":"sk-fake-test-key"}}}
        ]}}}
    """.trimIndent()

    // ── parseQuotaResponse: the endpoint's shape ──

    @Test
    fun parserReadsTheEndpointWindowsFiveHoursFirst() {
        val snap = QuotaDirect.parseQuotaResponse(endpointJson)!!
        assertEquals("PRO", snap.level)
        assertEquals(2, snap.windows.size)
        val five = snap.windows[0]
        assertEquals(5, five.hours, "5h first, weekly after (Python's sort)")
        assertEquals(31.0, five.used)
        assertEquals(120.0, five.limit)
        assertEquals(26.0, five.pct)
        assertEquals(1767225600000L, five.resetMs)
        val week = snap.windows[1]
        assertEquals(1, week.hours)
        assertEquals(88000.0, week.used)
        assertEquals(88000.0, week.limit)
        assertEquals(90.0, week.pct)
    }

    @Test
    fun parserSkipsUnusableRowsLikePython() {
        val json = """
            {"data":{"level":"PRO","limits":[
              {"number":5,"currentValue":"31","usage":120},
              {"number":5,"currentValue":31,"usage":0},
              {"number":5,"currentValue":31},
              {"number":1,"currentValue":88000,"usage":88000,"percentage":90}
            ]}}
        """.trimIndent()
        val snap = QuotaDirect.parseQuotaResponse(json)!!
        assertEquals(1, snap.windows.size, "used not numeric + falsy limit rows skip")
        assertEquals(1, snap.windows[0].hours)
    }

    @Test
    fun parserHonorsTheOptionalFields() {
        val snap = QuotaDirect.parseQuotaResponse(
            """{"data":{"limits":[{"number":5,"currentValue":7.5,"usage":10}]}}""",
        )!!
        assertNull(snap.level)
        assertNull(snap.windows[0].pct, "no percentage → no pct, no alert")
        assertNull(snap.windows[0].resetMs, "no reset → no resets text")
        assertEquals(7.5, snap.windows[0].used, "credits can be fractions")
    }

    @Test
    fun parserAnswersNullOnEveryUnusableShape() {
        assertNull(QuotaDirect.parseQuotaResponse("not json at all"))
        assertNull(QuotaDirect.parseQuotaResponse("""{"error":"nope"}"""), "no data object")
        assertNull(QuotaDirect.parseQuotaResponse("""{"data":{"level":"PRO"}}"""), "no limits array")
        assertNull(
            QuotaDirect.parseQuotaResponse("""{"data":{"limits":[]}}"""),
            "empty limits → no data (Python's `out if out['windows'] else None`)",
        )
    }

    // ── providerKey: bot-provider.json's path (fake values only) ──

    @Test
    fun providerKeyWalksTheRealPath() {
        assertEquals("sk-fake-test-key", QuotaDirect.providerKey(providerJson))
    }

    @Test
    fun providerKeyTakesTheCodingPlanRuleOnly() {
        val json = """
            {"config":{"providerConfigRules":{"providerRules":[
              {"config":{"access":{"type":"other-type","apiKey":"sk-wrong"}}},
              {"config":{"access":{"type":"zhipu-coding-plan-api-key"}}},
              {"config":{"access":{"type":"zhipu-coding-plan-api-key","apiKey":"sk-right"}}}
            ]}}}
        """.trimIndent()
        assertEquals("sk-right", QuotaDirect.providerKey(json), "the typed rule with a truthy apiKey")
    }

    @Test
    fun providerKeyAnswersNullOnEveryUnusableShape() {
        assertNull(QuotaDirect.providerKey("{broken"))
        assertNull(QuotaDirect.providerKey("""{"config":{}}"""))
        assertNull(QuotaDirect.providerKey("""{"config":{"providerConfigRules":{}}}"""))
        assertNull(
            QuotaDirect.providerKey(
                """{"config":{"providerConfigRules":{"providerRules":[{"config":{"access":{"type":"other","apiKey":"k"}}}]}}}""",
            ),
            "a foreign access type is not the coding plan's key",
        )
    }

    // ── renderQuota: Python's quota_report copy byte-a-byte ──

    @Test
    fun renderSpeaksTheQuotaLines() {
        val text = QuotaDirect.renderQuota(
            QuotaDirect.QuotaSnapshot(
                level = "PRO",
                windows = listOf(
                    QuotaDirect.QuotaWindow(hours = 5, used = 31.0, limit = 120.0, pct = 26.0, resetMs = null),
                    QuotaDirect.QuotaWindow(hours = 1, used = 88000.0, limit = 88000.0, pct = 90.0, resetMs = null),
                ),
            ),
        )
        assertEquals(
            """
            📊 <b>Coding plan</b>
            ⏱ 5 hours: 31/120 credits (26%)
            📅 Weekly: 88,000/88,000 credits (90%) ⚠️
            """.trimIndent(),
            text,
            "the owner's separator law (2/11), the ⚠️ from 75%, 5h first",
        )
    }

    @Test
    fun renderBoundaryAtTheSeventyFivePercent() {
        fun render(pct: Double): String = QuotaDirect.renderQuota(
            QuotaDirect.QuotaSnapshot(
                level = null,
                windows = listOf(QuotaDirect.QuotaWindow(5, 1.0, 100.0, pct, null)),
            ),
        )
        assertFalse("⚠️" in render(74.0), "74% stays calm")
        assertTrue("⚠️" in render(75.0), "75% carries the alert (Python's ≥75)")
        assertTrue("⚠️" in render(76.0), "76% carries the alert")
        assertFalse("⚠️" in render(0.0), "0% stays calm")
    }

    @Test
    fun renderSpeaksTheOptionalFields() {
        val noPct = QuotaDirect.renderQuota(
            QuotaDirect.QuotaSnapshot(
                level = null,
                windows = listOf(QuotaDirect.QuotaWindow(5, 31.0, 120.0, null, null)),
            ),
        )
        assertTrue("⏱ 5 hours: 31/120 credits" in noPct, "no pct → no (n%) and no alert")
        assertFalse("resets" in noPct, "no reset timestamp → no resets text")

        val fractionPct = QuotaDirect.renderQuota(
            QuotaDirect.QuotaSnapshot(
                level = null,
                windows = listOf(QuotaDirect.QuotaWindow(1, 10.0, 100.0, 12.5, null)),
            ),
        )
        assertTrue("(12.5%)" in fractionPct, "a fraction keeps its decimal")
    }

    @Test
    fun renderNamesTheUnknownWindowAndTheSmallNumbers() {
        val text = QuotaDirect.renderQuota(
            QuotaDirect.QuotaSnapshot(
                level = null,
                windows = listOf(
                    QuotaDirect.QuotaWindow(hours = 24, used = 9999.0, limit = 10000.0, pct = 99.96, resetMs = null),
                ),
            ),
        )
        assertTrue(
            "🔒 Limit: 9,999/10,000 credits (99.96%) ⚠️" in text,
            "🔒 for any other number — the owner's separator law, the unit never moves",
        )
    }

    // ── report: the injected-effects composition (the ladder's rung) ──

    @Test
    fun reportComposesKeyFetchParseAndRender() = runBlocking {
        var fetchUrl: String? = null
        var fetchToken: String? = null
        val text = QuotaDirect.report(
            providerJson = { providerJson },
            fetch = { url, token ->
                fetchUrl = url
                fetchToken = token
                endpointJson
            },
        )!!
        assertTrue("⏱ 5 hours: 31/120 credits (26%)" in text)
        assertEquals(QuotaDirect.ENDPOINT, fetchUrl)
        assertEquals("sk-fake-test-key", fetchToken, "the key rides the fetch's argument only")
    }

    @Test
    fun reportWithoutAKeyNeverTouchesTheNetwork() = runBlocking {
        var fetched = false
        val fetch: suspend (String, String) -> String = { _, _ -> fetched = true; "" }
        assertNull(QuotaDirect.report(providerJson = { null }, fetch = fetch))
        assertNull(
            QuotaDirect.report(providerJson = { """{"config":{}}""" }, fetch = fetch),
            "a keyless provider file skips the fetch too",
        )
        assertFalse(fetched, "no key → the endpoint is never called")
    }

    @Test
    fun reportTurnsEveryFailureIntoAnHonestNull() = runBlocking {
        assertNull(
            QuotaDirect.report(providerJson = { providerJson }, fetch = { _, _ -> throw IOException("dead") }),
            "a dead network falls through (one attempt, the ladder owns the recovery)",
        )
        assertNull(
            QuotaDirect.report(providerJson = { providerJson }, fetch = { _, _ -> "<html>500</html>" }),
            "a non-JSON body falls through",
        )
    }

    @Test
    fun reportNeverSwallowsCancellation() = runBlocking {
        // cancellation is not a quota failure: the honest null is for
        // transport/shape trouble, never for a cancelled caller
        assertFailsWith<CancellationException> {
            QuotaDirect.report(
                providerJson = { providerJson },
                fetch = { _, _ -> throw CancellationException("caller cancelled") },
            )
        }
    }

    // ── the /quota ladder: direct → channel → honest degradation ──

    /** The minimal ops fake: canned answers by query kind + the recorder. */
    private class LadderOps : ChannelOps {
        val asked = mutableListOf<ChannelQuery>()
        var answers: Map<String, String> = emptyMap()
        var available = true

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            asked.add(query)
            if (!available) return QueryAnswer.Unavailable
            return answers[queryKind(query)]?.let { QueryAnswer.Ok(it) }
                ?: QueryAnswer.Failed("internal", "no canned answer")
        }

        override suspend fun sendInbound(
            text: String,
            messageId: String,
            receivedAt: Long,
            attachments: List<InboundAttachment>,
        ) = InboundReceipt.UNAVAILABLE

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
            bindTaskId: String?,
        ) = InboundAnswer.Unavailable

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long) = -1

        override suspend fun subscribe(taskId: String, workspacePath: String) {}
    }

    /** The channel's quota answer (the fork's remaining shape — the fallback rung). */
    private val channelQuotaJson = """
        {"generatedAt":1,"authenticated":true,"provider":{"id":"glm","name":"GLM"},
         "remaining":{"count":72,"isShow":true,"percentage":41,"nextResetTime":4700000}}
    """.trimIndent()

    private fun bot(o: LadderOps, direct: (suspend (String?) -> String?)?) = BotCommands(
        ops = o,
        hub = LiveHub(),
        daemonReset = { "" },
        quotaDirect = direct,
    )

    @Test
    fun ladderChannelWinsAndTheDirectRungNeverFires() = runBlocking {
        // the owner's 2/11: the conversation consumes the START PLAN — the
        // channel's answer (the app's own usage service, provider-classified)
        // is the truth; the bot-provider.json key is the OLD plan's view
        val o = LadderOps().apply { answers = mapOf("botStatus" to "null", "quota" to channelQuotaJson) }
        val bc = bot(o) { _ -> throw AssertionError("the direct rung must not fire when the channel answers") }
        val reply = bc.handle("/quota", 1L)!!.text
        assertTrue("41% remaining" in reply, "the channel's text IS the answer")
        assertTrue(o.asked.any { it is ChannelQuery.Quota })
    }

    @Test
    fun ladderNotConfiguredFallsThroughToTheDirectRung() = runBlocking {
        val o = LadderOps().apply {
            answers = mapOf(
                "botStatus" to "null",
                "quota" to """{"generatedAt":1,"unavailableReason":"not_configured"}""",
            )
        }
        val bc = bot(o) { _ -> "📊 <b>Coding plan</b>\n⏱ 5 hours: 31/120 credits (26%)" }
        val reply = bc.handle("/quota", 1L)!!.text
        assertTrue("31/120" in reply, "the fork's not_configured is NOT the final word — the coding-plan key still gets its shot")
    }

    @Test
    fun ladderNullRungFallsThroughToTheChannel() = runBlocking {
        val o = LadderOps().apply { answers = mapOf("botStatus" to "null", "quota" to channelQuotaJson) }
        val bc = bot(o) { _ -> null } // no key at the edge — the honest null
        val reply = bc.handle("/quota", 1L)!!.text
        assertTrue("41% remaining" in reply, "the channel path answers as always")
        assertTrue(o.asked.any { it is ChannelQuery.Quota }, "the fork's quota query ran")
    }

    @Test
    fun ladderThrowingRungNeverHitsTheUser() = runBlocking {
        val o = LadderOps().apply { answers = mapOf("botStatus" to "null", "quota" to channelQuotaJson) }
        val bc = bot(o) { _ -> throw IOException("boom") }
        val reply = bc.handle("/quota", 1L)!!.text
        assertTrue("41% remaining" in reply, "the usable channel wins — the throwing rung is never consulted")
        assertTrue(o.asked.any { it is ChannelQuery.Quota })
    }

    @Test
    fun ladderBothRungsFailSpeaksTheHonestCopy() = runBlocking {
        val o = LadderOps().apply { available = false } // the channel is down too
        val bc = bot(o) { _ -> null }
        assertEquals(BotCommands.DEGRADED, bc.handle("/quota", 1L)!!.text)
    }

    @Test
    fun ladderNotConfiguredWithoutKeySpeaksTheChannelsHonestWord() = runBlocking {
        val o = LadderOps().apply {
            answers = mapOf(
                "botStatus" to "null",
                "quota" to """{"generatedAt":1,"unavailableReason":"not_configured"}""",
            )
        }
        val bc = bot(o, direct = null) // no key at the edge either
        assertTrue("not_configured" in bc.handle("/quota", 1L)!!.text, "the channel's honest word is the last resort")
    }

    @Test
    fun ladderWithoutWiringKeepsTheChannelOnly() = runBlocking {
        val o = LadderOps().apply { answers = mapOf("botStatus" to "null", "quota" to channelQuotaJson) }
        val bc = bot(o, direct = null) // the default: unwired (old deployments, plain tests)
        assertTrue("41% remaining" in bc.handle("/quota", 1L)!!.text)
        assertTrue(o.asked.any { it is ChannelQuery.Quota })
    }
}
