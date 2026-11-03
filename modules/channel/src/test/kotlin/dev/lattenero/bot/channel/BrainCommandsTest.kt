package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * /brain and /memory: the pure JSON parsers and renderers pinned against
 * agent-brain's REAL shapes (`http/Server.kt`, `rag/RagService.kt`,
 * `memory/MemoryWorker.kt`), plus every rung of the honesty ladder —
 * caído, timeout, HTTP error, unreadable, empty.
 */
class BrainCommandsTest {

    private val url = "http://127.0.0.1:8765"

    private val ragQueryJson = """
        {"query":"bridge y memoria","embedModel":"bge-m3",
         "hits":[
           {"chunkId":11,"docId":3,"uri":"repo://zcode-bot-kotlin/docs/plan-brujula.md",
            "title":"plan brujula","heading":"U1b — Encender bus y memoria","ordinal":5,
            "content":"el daemon publica BusEvent","score":0.91,"vectorRank":1,"textRank":2},
           {"chunkId":12,"docId":3,"uri":"repo://zcode-bot-kotlin/README.md",
            "title":"README","heading":"","ordinal":1,
            "content":"the bot","score":0.44,"vectorRank":2,"textRank":null}
         ],
         "citations":[
           {"uri":"repo://zcode-bot-kotlin/docs/plan-brujula.md","title":"plan brujula",
            "heading":"U1b — Encender bus y memoria","ordinal":5,"score":0.91}
         ]}
    """.trimIndent()

    private val memorySearchJson = """
        {"query":"cómo despliego","embedModel":"bge-m3",
         "memories":[
           {"id":42,"kind":"preference","content":"El dueño prefiere commits chicos y verificables",
            "sessionId":"ses_1","turnId":"1761685200123","salience":0.7,
            "createdAt":"2026-11-02T10:00:00.000Z","score":0.83},
           {"id":41,"kind":"fact","content":"El daemon usa <code>launchd</code> con kickstart",
            "sessionId":"ses_1","turnId":"1761685200000","salience":0.5,
            "createdAt":"2026-11-01T09:00:00.000Z","score":0.61}
         ]}
    """.trimIndent()

    private val memoryRecentJson = """
        [{"id":42,"kind":"preference","content":"Commits chicos",
          "sessionId":"ses_1","turnId":"1","salience":0.7,
          "createdAt":"2026-11-02T10:00:00.000Z","score":null},
         {"id":41,"kind":"fact","content":"Kickstart del daemon",
          "sessionId":"ses_1","turnId":"2","salience":0.5,
          "createdAt":"2026-11-01T09:00:00.000Z","score":null}]
    """.trimIndent()

    private val ragStatsJson =
        """{"documents":12,"chunks":340,"bySource":{"repo":200,"docs":140}}"""

    private val busStatusJson = """
        {"transport":"file","file":"/Users/x/.zcode/agent-brain/bus-events.jsonl","fileExists":true,
         "fileBytes":42188,"offset":42188,"lagBytes":0,"polls":3,"linesRead":120,"malformed":0,
         "shrinks":0,"seen":120,"inserted":118,"duplicates":2,"dbEvents":120,
         "lastTs":"2026-11-03T10:00:00.000Z","lastKind":"turn.completed",
         "kafkaCommits":null,"kafkaOffsets":null}
    """.trimIndent()

    private val memoryStatusJson = """
        {"pendingTurns":2,"processedTurns":31,"memories":120,
         "byKind":{"preference":40,"fact":80},"quarantined":0,"breaker":"CLOSED",
         "chatModel":"glm-5.3","embedModel":"bge-m3"}
    """.trimIndent()

    /** The brain's doors with mutable canned answers + the recorder. */
    private class FakeBrain(val url: String = "http://127.0.0.1:8765") {
        var rag: BrainReply = BrainReply.Response(200, """{"hits":[]}""")
        var stats: BrainReply = BrainReply.Response(200, "{}")
        var bus: BrainReply = BrainReply.Response(200, "{}")
        var search: BrainReply = BrainReply.Response(200, """{"memories":[]}""")
        var recent: BrainReply = BrainReply.Response(200, "[]")
        var memory: BrainReply = BrainReply.Response(200, "{}")
        val calls = mutableListOf<String>()

        fun gateway(): BrainGateway = BrainGateway(
            url = url,
            ragQuery = { q, k -> calls += "rag:$q:$k"; rag },
            ragStats = { calls += "ragStats"; stats },
            busStatus = { calls += "busStatus"; bus },
            memorySearch = { q, k -> calls += "search:$q:$k"; search },
            memoryRecent = { limit -> calls += "recent:$limit"; recent },
            memoryStatus = { calls += "memoryStatus"; memory },
        )
    }

    private fun fake(): FakeBrain = FakeBrain(url).apply {
        rag = BrainReply.Response(200, ragQueryJson)
        stats = BrainReply.Response(200, ragStatsJson)
        bus = BrainReply.Response(200, busStatusJson)
        search = BrainReply.Response(200, memorySearchJson)
        recent = BrainReply.Response(200, memoryRecentJson)
        memory = BrainReply.Response(200, memoryStatusJson)
    }

    // ---- /brain -------------------------------------------------------------

    @Test
    fun brainQueryCitesUriAndHeading() = runBlocking {
        val text = BrainCommands(fake().gateway()).brain("bridge y memoria")
        assertTrue("2 resultados" in text)
        assertTrue("U1b — Encender bus y memoria" in text)
        assertTrue("repo://zcode-bot-kotlin/docs/plan-brujula.md" in text)
        assertTrue("0.91" in text)
        // a blank heading falls back to the title, never to an empty citation
        assertTrue("README" in text)
        assertTrue("repo://zcode-bot-kotlin/README.md" in text)
    }

    @Test
    fun brainQueryPassesTheSearchK() = runBlocking {
        val f = fake()
        BrainCommands(f.gateway()).brain("x")
        assertEquals("rag:x:5", f.calls.single())
    }

    @Test
    fun brainNoHitsSaysEmptyNotError() = runBlocking {
        val f = fake().apply { rag = BrainReply.Response(200, """{"query":"x","embedModel":"m","hits":[],"citations":[]}""") }
        val text = BrainCommands(f.gateway()).brain("nada")
        assertTrue(text.startsWith("📭"))
        assertTrue("nada" in text)
    }

    @Test
    fun brainUnreachableIsTheDownLineWithTheUrl() = runBlocking {
        val f = fake().apply { rag = BrainReply.Down("ConnectException") }
        val text = BrainCommands(f.gateway()).brain("x")
        assertTrue(text.startsWith("📡 brain caído"))
        assertTrue(url in text)
    }

    @Test
    fun brainTimeoutIsTheClockLine() = runBlocking {
        val f = fake().apply { rag = BrainReply.Timeout }
        assertTrue(BrainCommands(f.gateway()).brain("x").startsWith("⏱️ brain no respondió en 5s"))
    }

    @Test
    fun brainHttpErrorNamesTheStatusAndTheServiceError() = runBlocking {
        val f = fake().apply {
            rag = BrainReply.Response(400, """{"error":"query must not be empty"}""")
        }
        val text = BrainCommands(f.gateway()).brain("")
        // empty rest is usage, not a query — a real query with a 400:
        val errored = BrainCommands(
            fake().apply { rag = BrainReply.Response(400, """{"error":"k must be between 1 and 50"}""") }.gateway(),
        ).brain("algo")
        assertTrue(errored.startsWith("⚠️ brain respondió HTTP 400"))
        assertTrue("k must be between 1 and 50" in errored)
        assertEquals(BrainTexts.BRAIN_USAGE, text)
    }

    @Test
    fun brainUnreadableBodyIsNotSilentlyEmpty() = runBlocking {
        val f = fake().apply { rag = BrainReply.Response(200, "<html>proxy error</html>") }
        assertTrue(BrainCommands(f.gateway()).brain("x").startsWith("⚠️ brain respondió algo ilegible (HTTP 200)"))
    }

    @Test
    fun brainBareRestIsTheUsage() = runBlocking {
        val f = fake()
        assertEquals(BrainTexts.BRAIN_USAGE, BrainCommands(f.gateway()).brain("  "))
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun brainStatusReadsRagStatsAndTheBus() = runBlocking {
        val f = fake()
        val text = BrainCommands(f.gateway()).brain("status")
        assertTrue(text.contains("12 documentos"))
        assertTrue(text.contains("340 chunks"))
        assertTrue(text.contains("repo 200"))
        assertTrue(text.contains("120 en DB"))
        assertTrue(text.contains("118 insertadas"))
        assertTrue(text.contains("turn.completed"))
        assertEquals(listOf("ragStats", "busStatus"), f.calls)
    }

    @Test
    fun brainStatusDegradesPerLeg() = runBlocking {
        val f = fake().apply { stats = BrainReply.Down("refused") }
        val text = BrainCommands(f.gateway()).brain("status")
        assertTrue("📚 RAG: 📡 caído" in text)
        assertTrue("📡 Bus: 41.2 KB" in text, text) // the bus leg still answers
    }

    // ---- /memory ------------------------------------------------------------

    @Test
    fun memoryQueryRendersKindIdAndContentEscaped() = runBlocking {
        val text = BrainCommands(fake().gateway()).memory("cómo despliego")
        assertTrue("2 recuerdos" in text, text)
        assertTrue("preference #42" in text)
        assertTrue("commits chicos" in text)
        assertTrue("0.83" in text)
        // the fixture's HTML-ish content is escaped, never interpreted by Telegram
        assertTrue("&lt;code&gt;launchd&lt;/code&gt;" in text, text)
        assertFalse("<code>launchd</code>" in text)
    }

    @Test
    fun memorySearchPassesTheSearchK() = runBlocking {
        val f = fake()
        BrainCommands(f.gateway()).memory("x")
        assertEquals("search:x:5", f.calls.single())
    }

    @Test
    fun memoryEmptySaysEmpty() = runBlocking {
        val f = fake().apply {
            search = BrainReply.Response(200, """{"query":"x","embedModel":"m","memories":[]}""")
        }
        assertTrue(BrainCommands(f.gateway()).memory("nada").startsWith("📭 Sin recuerdos"))
    }

    @Test
    fun memoryRecentDefaultsToTenAndClampsTheAsk() = runBlocking {
        val f = fake()
        val commands = BrainCommands(f.gateway())
        commands.memory("recent")
        commands.memory("recent 999")
        commands.memory("recent abc")
        commands.memory("recent 3")
        assertEquals(listOf("recent:10", "recent:50", "recent:10", "recent:3"), f.calls)
    }

    @Test
    fun memoryRecentRendersTheArrayAndItsTimestamp() = runBlocking {
        val text = BrainCommands(fake().gateway()).memory("recent")
        assertTrue("memoria reciente" in text)
        assertTrue("preference #42" in text)
        assertTrue("2026-11-02T10:00:00.000Z" in text)
    }

    @Test
    fun memoryRecentEmptyIsTheHonestZero() = runBlocking {
        val f = fake().apply { recent = BrainReply.Response(200, "[]") }
        assertEquals(BrainTexts.MEMORY_RECENT_EMPTY, BrainCommands(f.gateway()).memory("recent"))
    }

    @Test
    fun memoryStatusRendersCountsByKindAndBreaker() = runBlocking {
        val text = BrainCommands(fake().gateway()).memory("status")
        assertTrue("pendientes: 2" in text)
        assertTrue("procesados: 31" in text)
        assertTrue("recuerdos: 120" in text)
        assertTrue("preference 40" in text)
        assertTrue("breaker: CLOSED" in text)
        assertTrue("bge-m3" in text)
    }

    @Test
    fun memoryDegradationsMirrorBrain() = runBlocking {
        val down = BrainCommands(fake().apply { search = BrainReply.Down(null) }.gateway()).memory("x")
        assertTrue(down.startsWith("📡"))
        val timeout = BrainCommands(fake().apply { memory = BrainReply.Timeout }.gateway()).memory("status")
        assertTrue(timeout.startsWith("⏱️"))
        val http = BrainCommands(fake().apply { recent = BrainReply.Response(500, "boom") }.gateway()).memory("recent")
        assertTrue(http.startsWith("⚠️ brain respondió HTTP 500"))
        assertTrue("boom" in http)
        val unreadable = BrainCommands(fake().apply { memory = BrainReply.Response(200, "nope") }.gateway()).memory("status")
        assertTrue(unreadable.startsWith("⚠️ brain respondió algo ilegible"))
    }

    @Test
    fun memoryBareRestIsTheUsage() = runBlocking {
        val f = fake()
        assertEquals(BrainTexts.MEMORY_USAGE, BrainCommands(f.gateway()).memory(""))
        assertTrue(f.calls.isEmpty())
    }

    // ---- the dispatcher -----------------------------------------------------

    private class MinimalOps : ChannelOps {
        override suspend fun query(query: ChannelQuery) = QueryAnswer.Failed("internal", "unused")
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

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long) = 0
        override suspend fun subscribe(taskId: String, workspacePath: String) = Unit
    }

    private fun commands(brain: BrainCommands?): BotCommands = BotCommands(
        ops = MinimalOps(),
        hub = LiveHub(),
        daemonReset = { "unused" },
        brain = brain,
    )

    @Test
    fun theDispatcherRoutesBrainAndMemory() = runBlocking {
        val bc = commands(BrainCommands(fake().gateway()))
        assertTrue(bc.handle("/brain bridge", 1L)!!.text.contains("U1b — Encender bus y memoria"))
        assertTrue(bc.handle("/memory despliego", 2L)!!.text.contains("preference #42"))
        assertTrue(bc.handle("/brain status", 3L)!!.text.contains("340 chunks"))
        assertTrue(bc.handle("/memory status", 4L)!!.text.contains("recuerdos: 120"))
    }

    @Test
    fun withoutAGatewayBothCommandsSayUnwired() = runBlocking {
        val bc = commands(null)
        assertEquals(BrainTexts.UNWIRED, bc.handle("/brain algo", 1L)!!.text)
        assertEquals(BrainTexts.UNWIRED, bc.handle("/memory recent", 2L)!!.text)
    }

    @Test
    fun bothCommandsRideTheMenuAndTheHelp() {
        val names = BotCommands.MENU.map { it.first }
        assertTrue("brain" in names)
        assertTrue("memory" in names)
        assertTrue("/brain — " in BotCommands.HELP)
        assertTrue("/memory — " in BotCommands.HELP)
    }
}
