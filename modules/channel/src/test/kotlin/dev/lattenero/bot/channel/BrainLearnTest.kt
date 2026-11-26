package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * /learn — the week's learning card (plan v2 T3): memories + evals + today's
 * cost, fixture-pinned against agent-brain's real /eval/latest (StoredRun[])
 * and /costs/today ({turns, usd, unpriced}) shapes. The honesty ladder per
 * leg: all three down = down; one leg down degrades THAT leg, the rest of
 * the card still speaks.
 */
class BrainLearnTest {

    /**
     * THE PINNED CLOCK (26/11): the fixtures are dated 18–19/11 and the card
     * counts a 7-day window. With the wall clock the suite went red a week
     * later, on its own — the window is a value now.
     */
    private val pinnedNow = java.time.Instant.parse("2026-11-20T12:00:00Z")

    private val url = "http://127.0.0.1:8765"

    private val recentJson = """
        [{"id":50,"kind":"decision","content":"The LoRA cutover waits for the gate, not intuition",
          "sessionId":"ses_9","turnId":"9","salience":0.9,
          "createdAt":"2026-11-19T01:00:00.000Z","score":null},
         {"id":51,"kind":"fact","content":"El judge shadow corre cada noche a las 04:00",
          "sessionId":"ses_9","turnId":"10","salience":0.6,
          "createdAt":"2026-11-18T20:00:00.000Z","score":null},
         {"id":12,"kind":"preference","content":"Commits chicos de 2020 — fuera de la ventana",
          "sessionId":"ses_1","turnId":"1","salience":0.7,
          "createdAt":"2020-01-01T10:00:00.000Z","score":null}]
    """.trimIndent()

    private val evalLatestJson = """
        [{"suite":"shadow","model":"deepseek-v4.1-flash","provider":"openai","passed":8,"total":8,
          "gate":0.7,"ok":true,"p50Ms":6249,"p95Ms":9000,"tokensPrompt":100,"tokensCompletion":50,
          "costUsd":0.0,"costKnown":false,"ts":"2026-11-19T04:00:00Z"},
         {"suite":"usage","model":"deepseek-v4.1-flash","provider":"openai","passed":8,"total":8,
          "gate":0.7,"ok":true,"p50Ms":300,"p95Ms":900,"tokensPrompt":10,"tokensCompletion":5,
          "costUsd":0.0,"costKnown":false,"ts":"2026-11-19T04:00:00Z"},
         {"suite":"recall-core","model":"nomic-embed-text","provider":"ollama","passed":33,"total":33,
          "gate":0.9,"ok":true,"p50Ms":22,"p95Ms":60,"tokensPrompt":263,"tokensCompletion":0,
          "costUsd":0.0,"costKnown":true,"ts":"2026-11-19T04:00:00Z"}]
    """.trimIndent()

    private val costsTodayJson =
        """{"turns":12,"usd":0.42,"unpriced":0}"""

    /** The brain's doors with canned answers (the /learn-relevant subset). */
    private class FakeBrain(val url: String = "http://127.0.0.1:8765") {
        var recent: BrainReply = BrainReply.Response(200, "[]")
        var evals: BrainReply = BrainReply.Response(200, "[]")
        var costs: BrainReply = BrainReply.Response(200, "{}")
        val calls = mutableListOf<String>()

        fun gateway(): BrainGateway = BrainGateway(
            url = url,
            ragQuery = { _, _ -> BrainReply.Response(200, """{"hits":[]}""") },
            ragStats = { BrainReply.Response(200, "{}") },
            busStatus = { BrainReply.Response(200, "{}") },
            memorySearch = { _, _ -> BrainReply.Response(200, """{"memories":[]}""") },
            memoryRecent = { limit -> calls += "recent:$limit"; recent },
            memoryStatus = { BrainReply.Response(200, "{}") },
            evalLatest = { suite, limit -> calls += "evals:$suite:$limit"; evals },
            costsToday = { calls += "costs"; costs },
        )
    }

    @Test
    fun learnRendersCountsTheWeeksMemoriesEvalsAndCost() = runBlocking {
        val fake = FakeBrain().apply {
            recent = BrainReply.Response(200, recentJson)
            evals = BrainReply.Response(200, evalLatestJson)
            costs = BrainReply.Response(200, costsTodayJson)
        }
        val text = BrainCommands(fake.gateway()) { pinnedNow }.learn("")
        assertTrue("learning of the last 7 days" in text)
        // The 2020 pair is OUTSIDE the window; the two fresh ones count.
        assertTrue("📝 facts 1" in text)
        assertTrue("🎯 decisions 1" in text)
        // Only decisions make their own block with content.
        assertTrue("The LoRA cutover waits for the gate" in text)
        // The eval rows speak suite + score + verdict.
        assertTrue("shadow 8/8 ✅" in text)
        assertTrue("recall-core 33/33 ✅" in text)
        // The cost line is today's real rollup.
        assertTrue("12 turns · \$0.42" in text)
        // The doors the card needed: recent(60), evals unfiltered, costs.
        assertTrue("recent:60" in fake.calls.joinToString())
        assertTrue("evals:null:12" in fake.calls.joinToString())
        assertTrue("costs" in fake.calls.joinToString())
    }

    @Test
    fun learnWithNothingFreshSaysTheHonestZero() = runBlocking {
        val fake = FakeBrain().apply {
            recent = BrainReply.Response(200, "[]")
            evals = BrainReply.Response(200, "[]")
            costs = BrainReply.Response(200, costsTodayJson)
        }
        val text = BrainCommands(fake.gateway()) { pinnedNow }.learn("")
        assertTrue("📭 no new memories this week" in text)
        assertTrue("📊 evals: 📭 no runs" in text)
        assertTrue("12 turns" in text)
    }

    @Test
    fun learnWithEverythingDownIsTheHonestCaido() = runBlocking {
        val fake = FakeBrain().apply {
            recent = BrainReply.Down(null)
            evals = BrainReply.Down(null)
            costs = BrainReply.Down(null)
        }
        val text = BrainCommands(fake.gateway()) { pinnedNow }.learn("")
        assertTrue("down" in text)
    }

    @Test
    fun learnDegradesTheEvalLegAlone() = runBlocking {
        val fake = FakeBrain().apply {
            recent = BrainReply.Response(200, recentJson)
            evals = BrainReply.Down(null)
            costs = BrainReply.Response(200, costsTodayJson)
        }
        val text = BrainCommands(fake.gateway()) { pinnedNow }.learn("")
        // The memories leg still speaks; the evals leg degrades alone.
        assertTrue("The LoRA cutover waits for the gate" in text)
        assertTrue("evals: 📭 no runs" in text)
        assertTrue("12 turns · \$0.42" in text)
    }

    @Test
    fun learnWithArgsIsTheUsageLine() = runBlocking {
        val text = BrainCommands(FakeBrain().gateway()) { pinnedNow }.learn("semana")
        assertTrue("/learn" in text)
    }
}
