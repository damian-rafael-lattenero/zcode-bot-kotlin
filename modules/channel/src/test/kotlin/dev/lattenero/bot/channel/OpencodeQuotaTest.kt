package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The OpenCode rung (owner's 3/11): the parser pins the endpoint's REAL
 * shape (measured against opencode.ai/zen/go/v1/usage — rolling / weekly /
 * monthly with a used percent and a reset instant), the render pins the
 * card's copy and thresholds, the rule pick pins that a z.ai provider can
 * NEVER be served the OpenCode meter (and the other way round), and the
 * composition pins the honest null on every failure. Fake keys and
 * fixtures only — the real endpoint is never hit here.
 */
class OpencodeQuotaTest {

    /** The endpoint's REAL shape (fabricated values). */
    private val endpointJson = """
        {"usage":{
          "rolling":{"status":"ok","percent":0,"resetsAt":"2026-11-03T15:35:41.000Z"},
          "weekly":{"status":"ok","percent":44,"resetsAt":"2026-11-09T00:00:00.000Z"},
          "monthly":{"status":"ok","percent":22,"resetsAt":"2026-12-02T13:14:20.000Z"}
        }}
    """.trimIndent()

    /** provider_config.json's REAL shape, a FAKE key. */
    private val providerJson = """
        {"schemaVersion":1,"config":{"providerConfigRules":{"providerRules":[
          {"providerId":"account:zai-individual-coding-plan","config":{"personalModelIds":[]}},
          {"providerId":"opencode-go-chat","templateId":"opencode-go-chat","config":{
            "group":"standard-personal",
            "access":{"type":"api-key","apiKey":"sk-fake-open-code-key"},
            "api":{"type":"openai-chat-completions","baseUrl":"https://opencode.ai/zen/go/v1"}}},
          {"providerId":"opencode-zen-chat","config":{
            "access":{"type":"api-key","apiKey":"sk-fake-zen-key"},
            "api":{"type":"openai-chat-completions","baseUrl":"https://opencode.ai/zen/v1"}}}
        ]}}}
    """.trimIndent()

    /** Fixed «now»: 3/11 12:00 UTC — the card's reset clocks become deterministic. */
    private val nowMs = Instant.parse("2026-11-03T12:00:00Z").toEpochMilli()

    // ── parse: the endpoint's shape ──

    @Test
    fun parserReadsTheThreeWindowsInTheProvidersOrder() {
        val usage = OpencodeQuota.parse(endpointJson)!!
        assertEquals(listOf("rolling", "weekly", "monthly"), usage.windows.map { it.name })
        val weekly = usage.windows[1]
        assertEquals(44.0, weekly.percent)
        assertEquals("ok", weekly.status)
        assertEquals(Instant.parse("2026-11-09T00:00:00Z").toEpochMilli(), weekly.resetMs)
    }

    @Test
    fun parserKeepsUnknownWindowsAfterTheKnownOnes() {
        val usage = OpencodeQuota.parse(
            """{"usage":{"daily":{"status":"ok","percent":7},"weekly":{"status":"ok","percent":1}}}""",
        )!!
        assertEquals(listOf("weekly", "daily"), usage.windows.map { it.name }, "the provider's ladder first")
    }

    @Test
    fun parserSurvivesMissingPercentAndBadInstants() {
        val usage = OpencodeQuota.parse(
            """{"usage":{"rolling":{"status":"exceeded"},"weekly":{"status":"ok","percent":44,"resetsAt":"not-a-date"}}}""",
        )!!
        assertNull(usage.windows[0].percent, "a status-only window keeps its honest word")
        assertEquals("exceeded", usage.windows[0].status)
        assertNull(usage.windows[1].resetMs, "an unparseable instant is NO instant — never a fake one")
    }

    @Test
    fun parserAnswersNullOnEveryUnusableShape() {
        assertNull(OpencodeQuota.parse("not json"))
        assertNull(OpencodeQuota.parse("""{"usage":{}}"""), "no windows → no data")
        assertNull(OpencodeQuota.parse("""{"usage":{"rolling":{}}}"""), "a window without percent/status is not a row")
        assertNull(OpencodeQuota.parse("""{"data":{}}"""), "no usage object")
    }

    // ── render: the card ──

    @Test
    fun renderSpeaksTheGoWindowsByteExact() {
        val text = OpencodeQuota.render(OpencodeQuota.parse(endpointJson)!!, nowMs = nowMs, zone = ZoneId.of("UTC"))
        assertEquals(
            """
            📊 <b>OpenCode Go</b>
            ⏱ Rolling: 0% usado · resets 15:35
            📅 Weekly: 44% usado · resets 09/11 00:00
            🗓 Monthly: 22% usado · resets 02/12 13:14
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun renderBoundaryAtSeventyFiveAndOneHundred() {
        fun line(pct: Double): String =
            OpencodeQuota.render(
                OpencodeQuota.Usage(listOf(OpencodeQuota.Window("weekly", pct, "ok", null))),
                nowMs = nowMs,
            )
        assertFalse("⚠️" in line(74.0), "74% stays calm")
        assertTrue("⚠️" in line(75.0), "75% carries the alert (the z.ai card's law)")
        assertTrue("⛔" in line(100.0), "100% is the hard stop")
        assertFalse("⚠️" in line(100.0), "⛔ replaces ⚠️ instead of stacking")
    }

    @Test
    fun renderSpeaksANonOkStatusVerbatimAndNeverInventsAPercent() {
        val text = OpencodeQuota.render(
            OpencodeQuota.Usage(listOf(OpencodeQuota.Window("rolling", null, "exceeded", null))),
            nowMs = nowMs,
        )
        assertTrue("⏱ Rolling: exceeded" in text, text)
        assertFalse("%" in text, "no percent reported → no percent rendered: $text")
    }

    @Test
    fun renderNamesAnUnknownWindowAndEscapesTheStatus() {
        val text = OpencodeQuota.render(
            OpencodeQuota.Usage(
                listOf(
                    OpencodeQuota.Window("daily", 5.5, "ok", null),
                    OpencodeQuota.Window("weekly", 10.0, "<b>bad</b>", null),
                ),
            ),
            nowMs = nowMs,
        )
        assertTrue("🔒 Daily: 5.5% usado" in text, "a fraction keeps its decimal (the z.ai law)")
        assertTrue("&lt;b&gt;bad&lt;/b&gt;" in text, "the provider's word is escaped — a raw tag poisons the outbox")
    }

    // ── the rule pick: provider → meter ──

    @Test
    fun theOpenCodeProviderIsRecognizedAndTheZaiOneIsNot() {
        assertTrue(OpencodeQuota.isOpenCodeProvider(providerJson, "opencode-go-chat"))
        assertTrue(OpencodeQuota.isOpenCodeProvider(providerJson, "opencode-zen-chat"))
        assertFalse(OpencodeQuota.isOpenCodeProvider(providerJson, "account:zai-start-plan"))
        assertFalse(OpencodeQuota.isOpenCodeProvider(providerJson, null), "no conversation → no guess")
        assertTrue(
            OpencodeQuota.isOpenCodeProvider(providerJson, "opencode-go-renamed"),
            "an opencode-named id without an exact rule falls back to the family — never to z.ai",
        )
    }

    @Test
    fun resolvePointsGoAtItsUsageEndpointWithItsOwnKey() {
        val target = OpencodeQuota.resolve(providerJson, "opencode-go-chat")!!
        assertEquals("https://opencode.ai/zen/go/v1/usage", target.url)
        assertEquals("sk-fake-open-code-key", target.key)
        assertEquals(OpencodeQuota.GO_LABEL, target.plan)
    }

    @Test
    fun resolveRefusesZenAndEveryNonOpenCodeProvider() {
        assertNull(
            OpencodeQuota.resolve(providerJson, "opencode-zen-chat"),
            "zen has no /usage endpoint (404 measured 3/11) — never a guessed URL",
        )
        assertNull(
            OpencodeQuota.resolve(providerJson, "account:zai-start-plan"),
            "a z.ai conversation must never be served the OpenCode meter",
        )
        assertNull(OpencodeQuota.resolve(providerJson, null))
        assertNull(
            OpencodeQuota.resolve(
                """{"config":{"providerConfigRules":{"providerRules":[{"providerId":"opencode-go-chat","config":{"access":{"type":"api-key"},"api":{"baseUrl":"https://opencode.ai/zen/go/v1"}}}]}}}""",
                "opencode-go-chat",
            ),
            "no key → no call",
        )
    }

    // ── report: the injected-effects composition ──

    @Test
    fun reportComposesRuleFetchParseAndRender() = runBlocking {
        var fetchUrl: String? = null
        var fetchToken: String? = null
        val text = OpencodeQuota.report(
            providerJson = { providerJson },
            providerId = "opencode-go-chat",
            fetch = { url, token ->
                fetchUrl = url
                fetchToken = token
                endpointJson
            },
            nowMs = nowMs,
        )!!
        assertTrue("📅 Weekly: 44% usado" in text)
        assertEquals("https://opencode.ai/zen/go/v1/usage", fetchUrl)
        assertEquals("sk-fake-open-code-key", fetchToken, "the key rides the fetch's argument only")
    }

    @Test
    fun reportWithoutAKeyOrProviderNeverTouchesTheNetwork() = runBlocking {
        var fetched = false
        val fetch: suspend (String, String) -> String = { _, _ -> fetched = true; "" }
        assertNull(OpencodeQuota.report(providerJson = { null }, providerId = "opencode-go-chat", fetch = fetch))
        assertNull(OpencodeQuota.report(providerJson = { providerJson }, providerId = null, fetch = fetch))
        assertNull(
            OpencodeQuota.report(providerJson = { providerJson }, providerId = "account:zai-start-plan", fetch = fetch),
            "a foreign provider never reaches the endpoint",
        )
        assertFalse(fetched)
    }

    @Test
    fun reportTurnsEveryFailureIntoAnHonestNull() = runBlocking {
        assertNull(
            OpencodeQuota.report(
                providerJson = { providerJson },
                providerId = "opencode-go-chat",
                fetch = { _, _ -> throw IOException("dead") },
            ),
        )
        assertNull(
            OpencodeQuota.report(
                providerJson = { providerJson },
                providerId = "opencode-go-chat",
                fetch = { _, _ -> "<html>cloudflare</html>" },
            ),
        )
    }

    // ── the /quota ladder: the provider's own meter, then honest words ──

    private class LadderOps : ChannelOps {
        val asked = mutableListOf<ChannelQuery>()
        var answers: Map<String, String> = emptyMap()

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            asked.add(query)
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

    /** A bound conversation consuming the OpenCode provider. */
    private fun opencodeOps(quotaJson: String) = LadderOps().apply {
        answers = mapOf(
            "botStatus" to
                """{"bot":{"id":"daemon-local","enabled":true},"context":{"workspacePath":"/ws","activeTaskId":"task-77","mode":"yolo"}}""",
            "modelSelection" to
                """{"selection":{"providerId":"opencode-go-chat","modelId":"deepseek-v4.1-flash"},"plans":[]}""",
            "quota" to quotaJson,
        )
    }

    private fun commands(o: LadderOps, direct: (suspend (String?) -> String?)?) = BotCommands(
        ops = o,
        hub = LiveHub(),
        daemonReset = { "" },
        quotaDirect = direct,
    )

    @Test
    fun theRungGetsTheConversationsProviderAndItsCardWins() = runBlocking {
        val o = opencodeOps("""{"generatedAt":1,"unavailableReason":"not_configured"}""")
        var seenProvider: String? = "<never>"
        val bc = commands(o) { provider ->
            seenProvider = provider
            "📊 <b>OpenCode Go</b>\n📅 Weekly: 44% usado"
        }
        val reply = bc.handle("/quota", 1L)!!.text
        assertEquals("opencode-go-chat", seenProvider, "the rung must be told WHICH provider the conversation consumes")
        assertTrue("44%" in reply, reply)
    }

    @Test
    fun anUnreadableOpenCodeMeterBlamesTheKeyNotTheFork() = runBlocking {
        val o = opencodeOps("""{"generatedAt":1,"unavailableReason":"not_configured"}""")
        val reply = commands(o) { _ -> null }.handle("/quota", 1L)!!.text
        assertEquals(BotCommands.QUOTA_OPENCODE_UNREADABLE, reply)
        assertFalse("not_configured" in reply, "the fork's not_configured would blame the wrong thing")
    }

    @Test
    fun aZaiConversationNeverGetsTheOpenCodeFallbackLine() = runBlocking {
        val o = LadderOps().apply {
            answers = mapOf(
                "botStatus" to
                    """{"bot":{"id":"daemon-local","enabled":true},"context":{"workspacePath":"/ws","activeTaskId":"task-77","mode":"yolo"}}""",
                "modelSelection" to
                    """{"selection":{"providerId":"account:zai-start-plan","modelId":"GLM-5.3"},"plans":[]}""",
                "quota" to """{"generatedAt":1,"unavailableReason":"not_configured"}""",
            )
        }
        val reply = commands(o) { _ -> null }.handle("/quota", 1L)!!.text
        assertTrue("not_configured" in reply, "the z.ai path keeps the channel's own word")
        assertFalse(reply == BotCommands.QUOTA_OPENCODE_UNREADABLE)
    }
}
