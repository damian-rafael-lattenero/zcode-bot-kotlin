package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A5's bot half: the coding plan's gifts on /quota — the read (both planes,
 * each degradable), the two CONFIRMED writes (a claim changes the account's
 * plan; a reset is spent forever) and the silence laws: no data → no line,
 * an old host → nothing, a degraded card → nothing on top.
 */
class GiftsCommandTest {

    private class FakeOps : ChannelOps {
        val queries = mutableListOf<ChannelQuery>()
        val answers = mutableMapOf<String, String>()
        var claimAnswer: QueryAnswer = QueryAnswer.Ok(
            """{"success":true,"code":0,"message":"","plan":{"planId":"global-build","status":"active","endsAt":1796000000}}""",
        )
        var resetAnswer: QueryAnswer = QueryAnswer.Ok("""{"used":true}""")
        var claimPlanId: String? = null
        var claimPlanIdCount = 0
        var resetQuery: ChannelQuery.UseCodingPlanReset? = null

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            queries.add(query)
            when (query) {
                is ChannelQuery.ClaimCodingPlan -> {
                    claimPlanId = query.planId
                    claimPlanIdCount += 1
                    return claimAnswer
                }
                is ChannelQuery.UseCodingPlanReset -> {
                    resetQuery = query
                    return resetAnswer
                }
                else -> Unit
            }
            return answers[queryKind(query)]?.let { QueryAnswer.Ok(it) }
                ?: QueryAnswer.Failed("internal", "no canned answer")
        }

        override suspend fun sendInbound(
            text: String,
            messageId: String,
            receivedAt: Long,
            attachments: List<InboundAttachment>,
        ) = InboundReceipt.FIRED

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
            bindTaskId: String?,
        ) = InboundAnswer.Answered(duplicate = false, replies = emptyList())

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long) = 0

        override suspend fun subscribe(taskId: String, workspacePath: String) {}
    }

    private class FakeHistory : TasksHistory {
        override suspend fun ctxTasks(limit: Int): List<TaskRow> =
            listOf(TaskRow(sid = "task-77", title = "zcode_full_plan", ws = "/ws"))

        override suspend fun modelSelection(sid: String): ModelSel? = null
        override suspend fun freshestTask(workspacePath: String): TaskRow? = null
        override suspend fun taskAlive(sid: String): Boolean = true
    }

    private val status =
        """{"bot":{"id":"daemon-local","enabled":true},"context":{"workspacePath":"/ws","activeTaskId":"task-77","mode":"task"}}"""

    /** The fork's answer: 2 fives + 1 week reset, one claimable plan with 300M tokens, one marketing offer. */
    private val giftsJson = """
        {"reset":{"fiveHour":[{"expireAt":1000000},{"expireAt":2000000}],"week":[{"expireAt":3000000}],
                  "latestFiveHourUsedAt":null,"latestWeekUsedAt":null,"hasUnreadHistory":false},
         "claimable":{"serverTime":1793700000000,"plans":[
            {"planId":"global-build","name":"ZCODE GLOBAL BUILD","description":"","priority":1,
             "entitlements":[{"entitlementId":"ent-1","showName":"GLM-5.3-Flash","meter":"token",
                              "unitType":"token","capabilities":["model:GLM-5.3-Flash"],
                              "grantUnits":300000000,"period":"one_time"}]}]},
         "offers":[{"planId":"global-build","label":"Claim","campaignId":"camp-1"}]}
    """.trimIndent()

    private val channelQuota =
        """{"generatedAt":1,"authenticated":true,"remaining":{"count":72,"isShow":true,"percentage":41,"nextResetTime":4700000}}"""

    private fun ops(
        gifts: String? = giftsJson,
        quota: String = channelQuota,
    ): FakeOps = FakeOps().apply {
        answers["botStatus"] = status
        answers["modelSelection"] =
            """{"selection":{"providerId":"account:zai-individual-coding-plan","modelId":"GLM-5.3-Flash"},"plans":[]}"""
        answers["quota"] = quota
        if (gifts != null) answers["codingPlanGifts"] = gifts
    }

    private fun commands(o: FakeOps): BotCommands = BotCommands(
        ops = o,
        hub = LiveHub(),
        daemonReset = { "" },
        clock = { 1_000L }, // the gifts' expireAt values (1e6/2e6/3e6) are all in the future
        history = FakeHistory(),
    )

    private fun rows(reply: BotReply): List<Pair<String, String>> = reply.markup!!
        .jsonObject["inline_keyboard"]!!.jsonArray
        .flatMap { row ->
            row.jsonArray.map { b ->
                b.jsonObject["text"]!!.jsonPrimitive.content to b.jsonObject["callback_data"]!!.jsonPrimitive.content
            }
        }

    // ── the wire ──

    @Test
    fun theThreeFramesRideTheForksWireShapes() {
        assertEquals(
            """{"type":"query","id":"q1","query":{"kind":"codingPlanGifts","taskId":"task-77"}}""",
            encodeClient(ClientFrame.Query("q1", ChannelQuery.CodingPlanGifts("task-77"))),
        )
        assertEquals(
            """{"type":"query","id":"q2","query":{"kind":"useCodingPlanReset","taskId":"task-77","resetType":"WEEK","idempotencyKey":"k-1"}}""",
            encodeClient(
                ClientFrame.Query("q2", ChannelQuery.UseCodingPlanReset("task-77", "WEEK", "k-1")),
            ),
        )
        assertEquals(
            """{"type":"query","id":"q3","query":{"kind":"claimCodingPlan","planId":"global-build"}}""",
            encodeClient(ClientFrame.Query("q3", ChannelQuery.ClaimCodingPlan("global-build"))),
        )
    }

    // ── the pure half ──

    @Test
    fun parseFiltersDeadResetsAndReadsTheOffer() {
        val gifts = GiftsMenu.parse(giftsJson, nowMs = 1_500_000L)
        assertEquals(1, gifts.resets.count { it.type == GiftsMenu.FIVE_HOUR }, "the expired 1e6 reset is gone")
        assertEquals(1, gifts.resets.count { it.type == GiftsMenu.WEEK })
        val offer = gifts.offers.single()
        assertEquals("ZCODE GLOBAL BUILD", offer.displayName)
        assertEquals(300_000_000.0, offer.tokens)
        assertEquals("GLM-5.3-Flash", offer.entitlement)
        assertEquals("único", offer.period)
    }

    @Test
    fun aMarketingOnlyOfferKeepsItsRealPlanId() {
        val gifts = GiftsMenu.parse(
            """{"offers":[{"planId":"flash-promo","label":"Claim","campaignId":"c1"}]}""",
            nowMs = 1L,
        )
        assertEquals("flash-promo", gifts.offers.single().displayName)
        assertNull(gifts.offers.single().tokens)
        // no billing-preview name → the line says WHAT it is + the real id,
        // never a title invented from the id (the 3/11 live shape: the
        // GLOBAL BUILD banner carries no text in the API answer)
        assertTrue(
            "🔴 <b>Oferta reclamable (flash-promo)</b>" in GiftsMenu.render(gifts, 1L).single(),
            GiftsMenu.render(gifts, 1L).single(),
        )
    }

    @Test
    fun theRenderSpeaksTheOwnerLanguageAndTheNumberLaw() {
        val gifts = GiftsMenu.parse(giftsJson, nowMs = 1_000L)
        val lines = GiftsMenu.render(gifts, nowMs = 1_000L)
        assertTrue("🎁 <b>2 resets</b> (5h)" in lines[0], lines[0])
        assertTrue("expira en 16m" in lines[0], "1000000-1000 ms → 16m: ${lines[0]}")
        assertTrue("🎁 <b>1 reset</b> (semanal)" in lines[1], lines[1])
        assertTrue("🔴 <b>Reclamable: ZCODE GLOBAL BUILD</b> — 300M tokens · GLM-5.3-Flash (único)" in lines[2], lines[2])
    }

    @Test
    fun unparseableAndEmptyAnswersAreSilence() {
        assertTrue(GiftsMenu.parse(null, 1L).isEmpty)
        assertTrue(GiftsMenu.parse("broken{", 1L).isEmpty)
        assertTrue(GiftsMenu.parse("""{"reset":{"fiveHour":[]},"claimable":{"plans":[]}}""", 1L).isEmpty)
        assertNull(GiftsMenu.keyboard(GiftsMenu.EMPTY))
    }

    // ── /quota's composition ──

    @Test
    fun aUsableCardCarriesTheGiftLinesAndTheirButtons() = runBlocking {
        val o = ops()
        val reply = commands(o).handle("/quota", 1L)!!
        assertTrue("41% remaining" in reply.text, reply.text)
        assertTrue("🎁 <b>2 resets</b>" in reply.text, reply.text)
        assertTrue("Reclamable: ZCODE GLOBAL BUILD" in reply.text, reply.text)
        val r = rows(reply)
        assertEquals(
            listOf("🎁 Reclamar" to "cl:global-build", "🎁 Usar reset 5h" to "gf:FIVE_HOUR", "🎁 Usar reset semanal" to "gf:WEEK"),
            r,
        )
        assertTrue(o.queries.any { it is ChannelQuery.CodingPlanGifts })
    }

    @Test
    fun aDegradedCardNeverCarriesGifts() = runBlocking {
        val o = ops(quota = """{"generatedAt":1,"unavailableReason":"not_configured"}""")
        o.answers.remove("botStatus")
        val reply = commands(o).handle("/quota", 1L)!!
        assertFalse("🎁" in reply.text, reply.text)
        assertFalse(o.queries.any { it is ChannelQuery.CodingPlanGifts }, "no card → no gift read")
    }

    @Test
    fun aHostWithoutTheFrameIsSilent() = runBlocking {
        val o = ops(gifts = null) // the fake answers unknown_query for the missing kind
        val reply = commands(o).handle("/quota", 1L)!!
        assertTrue("41% remaining" in reply.text)
        assertFalse("🎁" in reply.text)
        assertNull(reply.markup)
    }

    // ── the claim: two taps, the host's verdict ──

    @Test
    fun theFirstTapAsksAndSaysWhatTheClaimChanges() = runBlocking {
        val bc = commands(ops())
        bc.handle("/quota", 1L)
        val confirm = bc.onCallback("cl:global-build", 2L)!!
        assertTrue("¿Reclamar «ZCODE GLOBAL BUILD»?" in confirm.text, confirm.text)
        assertTrue("300M tokens · GLM-5.3-Flash (único)" in confirm.text, confirm.text)
        assertTrue("cambio de plan" in confirm.text)
        assertEquals(
            listOf("✅ Confirmar" to "cly:", "❌ Cancelar" to "gxn:"),
            rows(confirm),
        )
    }

    @Test
    fun theSecondTapClaimsAndSpeaksTheServersVerdict() = runBlocking {
        val o = ops()
        val bc = commands(o)
        bc.handle("/quota", 1L)
        bc.onCallback("cl:global-build", 2L)
        val done = bc.onCallback("cly:", 3L)!!
        assertEquals("global-build", o.claimPlanId)
        assertTrue("✅ <b>Reclamado: ZCODE GLOBAL BUILD</b>" in done.text, done.text)
        assertTrue("estado active" in done.text, done.text)
        assertTrue("/plan para usar el plan nuevo" in done.text)
    }

    @Test
    fun aRefusedClaimCarriesTheCodeAndTheServersWord() = runBlocking {
        val o = ops().apply {
            claimAnswer = QueryAnswer.Ok("""{"success":false,"code":1003,"message":"campaign ended"}""")
        }
        val bc = commands(o)
        bc.handle("/quota", 1L)
        bc.onCallback("cl:global-build", 2L)
        val done = bc.onCallback("cly:", 3L)!!
        assertTrue("El server rechazó el reclamo (1003): campaign ended" in done.text, done.text)
    }

    @Test
    fun aClaimWithoutAnArmedOfferIsTheHonestNudge() = runBlocking {
        val bc = commands(ops())
        assertEquals(BotCommands.GIFT_NOTHING_ARMED, bc.onCallback("cly:", 5L)!!.text)
    }

    @Test
    fun theServersCaptchaGateAnswersWithTheBrowserDoor() = runBlocking {
        // measured live 3/11: claimManualPlan throws «captcha verify failed»;
        // the fork's security edition is a stub, so no proof can exist here —
        // the answer is ONE line + a url button that opens the app's own page
        val o = ops().apply {
            claimAnswer = QueryAnswer.Failed("internal", "captcha verify failed")
        }
        val bc = commands(o)
        bc.handle("/quota", 1L)
        bc.onCallback("cl:global-build", 2L)
        val done = bc.onCallback("cly:", 3L)!!
        assertEquals(BotCommands.GIFT_CAPTCHA, done.text)
        val row = done.markup!!.jsonObject["inline_keyboard"]!!.jsonArray[0].jsonArray[0].jsonObject
        assertEquals("🌐 Abrir la web (planes)", row["text"]!!.jsonPrimitive.content)
        assertEquals(BotCommands.GIFT_CLAIM_URL, row["url"]!!.jsonPrimitive.content)
        assertEquals(
            "https://zcode.z.ai/en/rewards?embedded=app&theme=zai-dark",
            BotCommands.GIFT_CLAIM_URL,
            "the app's trusted-URL contract requires both params",
        )
        assertNull(row["callback_data"], "a url button opens the browser — no callback to answer")
        // learned: the SAME plan never burns another attempt — the next 🎁
        // tap goes straight to the browser door, no confirmation, no server hit
        val again = bc.onCallback("cl:global-build", 4L)!!
        assertEquals(BotCommands.GIFT_CAPTCHA, again.text)
        assertTrue(again.markup != null, "the door rides along")
        assertEquals(1, o.claimPlanIdCount, "exactly ONE claim hit the server")
    }

    @Test
    fun cancelTouchesNothing() = runBlocking {
        val o = ops()
        val bc = commands(o)
        bc.handle("/quota", 1L)
        bc.onCallback("cl:global-build", 2L)
        assertEquals("📬 Cancelado — no se tocó nada.", bc.onCallback("gxn:", 3L)!!.text)
        assertNull(o.claimPlanId)
    }

    @Test
    fun anOfferThatVanishedBeforeTheTapIsNamedAsSuch() = runBlocking {
        val o = ops()
        val bc = commands(o)
        assertEquals("🤷 Esa oferta ya no está — corré /quota de nuevo.", bc.onCallback("cl:gone", 1L)!!.text)
        assertNull(o.claimPlanId)
    }

    // ── the reset: two taps, one idempotency key ──

    @Test
    fun theResetAsksFirstAndThenFiresTheSameKey() = runBlocking {
        val o = ops()
        val bc = commands(o)
        bc.handle("/quota", 1L)
        val confirm = bc.onCallback("gf:FIVE_HOUR", 2L)!!
        assertTrue("¿Usar un reset de 5 horas?" in confirm.text, confirm.text)
        assertTrue("Te quedan <b>2</b>" in confirm.text, confirm.text)
        val done = bc.onCallback("gfy:", 3L)!!
        assertTrue("✅ Reset usado (5h)" in done.text, done.text)
        assertEquals("FIVE_HOUR", o.resetQuery!!.resetType)
        assertEquals("task-77", o.resetQuery!!.taskId)
        assertEquals(36, o.resetQuery!!.idempotencyKey.length, "a UUID rides as the server's idempotency key")
    }

    @Test
    fun aResetAgainstAnOldHostAsksForTheForkDeploy() = runBlocking {
        val o = ops().apply {
            resetAnswer = QueryAnswer.Failed("unknown_query", "unknown query kind: useCodingPlanReset")
        }
        val bc = commands(o)
        bc.handle("/quota", 1L)
        bc.onCallback("gf:WEEK", 2L)
        assertEquals(BotCommands.GIFT_OLD_FORK, bc.onCallback("gfy:", 3L)!!.text)
    }

    @Test
    fun aResetThatVanishedIsNamedAsSuch() = runBlocking {
        val o = ops(gifts = """{"reset":{"week":[{"expireAt":500}]}}""")
        val bc = commands(o)
        assertEquals("🤷 Ese reset ya no está — corré /quota de nuevo.", bc.onCallback("gf:WEEK", 1L)!!.text)
        assertNull(o.resetQuery)
    }

    @Test
    fun theCallbackDataRespectsTelegramsLaw() {
        val kb = GiftsMenu.keyboard(GiftsMenu.parse(giftsJson, 1_000L))!!
        val datas = kb.jsonObject["inline_keyboard"]!!.jsonArray.map {
            it.jsonArray[0].jsonObject["callback_data"]!!.jsonPrimitive.content
        }
        datas.forEach {
            assertTrue(it.length <= 64, "callback_data ≤ 64B: $it")
            assertFalse(it.any { c -> c.isWhitespace() }, "never whitespace: $it")
        }
        assertTrue(Json.parseToJsonElement(kb.toString()).jsonObject["inline_keyboard"] != null)
    }

    // ── the working card's prize line (the owner, 3/11) ──

    @Test
    fun theWorkingLineIsSilentWithoutPrizes() {
        assertNull(GiftsMenu.workingLine(GiftsMenu.EMPTY, 1_000L))
    }

    @Test
    fun theWorkingLineCountsBothWindowsAndCarriesTheEarliestExpiry() {
        val gifts = GiftsMenu.parse(
            """{"reset":{"fiveHour":[{"expireAt":1000000},{"expireAt":2000000}],
                       "week":[{"expireAt":3000000}]}}""",
            1_000L,
        )
        val line = GiftsMenu.workingLine(gifts, 1_000L)!!
        assertTrue("2 resets 5h" in line)
        assertTrue("1 reset semanal" in line)
        assertTrue("expira en" in line) // a REAL expireAt, never invented
        assertTrue("/quota" in line) // the detail's door
    }

    @Test
    fun theWorkingLineNamesTheOfferWithItsTokens() {
        val line = GiftsMenu.workingLine(GiftsMenu.parse(giftsJson, 1_000L), 1_000L)!!
        assertTrue("ZCODE GLOBAL BUILD" in line)
        assertTrue("300M tokens" in line)
        assertTrue("reclamable" in line)
    }

    @Test
    fun theWorkingLineWithoutANameDoesNotPoseThePlanIdAsATitle() {
        val gifts = GiftsMenu.parse(
            """{"offers":[{"planId":"zcode-v3-start-plan-1103","label":"","campaignId":"c"}]}""",
            1_000L,
        )
        val line = GiftsMenu.workingLine(gifts, 1_000L)!!
        assertTrue("oferta" in line)
        assertFalse("zcode-v3-start-plan-1103" in line)
    }
}
