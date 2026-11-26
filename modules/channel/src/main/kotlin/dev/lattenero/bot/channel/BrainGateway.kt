package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Locale

/**
 * agent-brain's HTTP answer as the commands see it. The transport is the
 * DAEMON's business (ktor lives there); the channel only classifies:
 * an answer with its status, a dead service, or a deadline.
 */
sealed interface BrainReply {
    /** Any HTTP answer — 2xx or not; the renderers decide what each status means. */
    data class Response(val status: Int, val body: String) : BrainReply

    /** Connection refused / DNS / socket closed: the service is not there. */
    data class Down(val detail: String? = null) : BrainReply

    /** The call hit its own deadline (~5 s). */
    data object Timeout : BrainReply
}

/**
 * The brain's doors the commands need (ADR-019/020), injected as suspend
 * lambdas: the daemon wires ktor, the tests wire fixtures. Retrieval only —
 * the brain never generates; /brain shows a reading list.
 */
class BrainGateway(
    /** The base URL, for the honest «down» line. */
    val url: String,
    val ragQuery: suspend (query: String, k: Int) -> BrainReply,
    val ragStats: suspend () -> BrainReply,
    val busStatus: suspend () -> BrainReply,
    val memorySearch: suspend (query: String, k: Int) -> BrainReply,
    val memoryRecent: suspend (limit: Int) -> BrainReply,
    val memoryStatus: suspend () -> BrainReply,
    /** GET /eval/latest (suite filter optional) — /learn's quality rows. */
    val evalLatest: suspend (suite: String?, limit: Int) -> BrainReply,
    /** GET /costs/today — /learn's cost line. */
    val costsToday: suspend () -> BrainReply,
)

/** One RAG hit — the citation is uri + heading, as agent-brain's QueryResponse has it. */
internal data class BrainHit(val uri: String, val title: String, val heading: String, val score: Double)

/** One memory row (search adds a score; recent leaves it null). */
internal data class BrainMemory(
    val id: Long?,
    val kind: String,
    val content: String,
    val createdAt: String?,
    val score: Double?,
)

internal data class BrainRagStats(val documents: Long, val chunks: Long, val bySource: Map<String, Long>)

internal data class BrainBusStatus(
    val transport: String,
    val file: String,
    val fileExists: Boolean,
    val fileBytes: Long,
    val offset: Long,
    val lagBytes: Long,
    val malformed: Long,
    val shrinks: Long,
    val seen: Long,
    val inserted: Long,
    val duplicates: Long,
    val dbEvents: Long,
    val lastTs: String?,
    val lastKind: String?,
)

internal data class BrainMemoryStatus(
    val pendingTurns: Long,
    val processedTurns: Long,
    val memories: Long,
    val byKind: Map<String, Long>,
    val quarantined: Long,
    val breaker: String,
    val chatModel: String,
    val embedModel: String,
)

/** One /eval/latest row (agent-brain's StoredRun, the fields /learn reads). */
internal data class BrainEvalRow(
    val suite: String,
    val model: String,
    val passed: Int,
    val total: Int,
    val gate: Double,
    val ok: Boolean,
    val ts: String?,
)

/** /costs/today's rollup. */
internal data class BrainCostsToday(val turns: Long, val usd: Double, val unpriced: Long)

/**
 * The JSON PARSERS — pure, fixture-pinned against agent-brain's own shapes
 * (`http/Server.kt` + `rag/RagService.kt` + `memory/MemoryWorker.kt`). Null
 * means "not that shape" (the command says ⚠️ unreadable); an empty list is
 * a real answer (the command says 📭 sin resultados).
 */
// one guard per shape — the parser ladder reads better flat (the house style)
@Suppress("ReturnCount")
internal object BrainJson {

    private val json = Json { ignoreUnknownKeys = true }

    private fun root(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    /** QueryResponse.hits — citations are the fallback when a server omits hits. */
    fun ragHits(body: String): List<BrainHit>? {
        val root = root(body) ?: return null
        val hits = root["hits"] as? JsonArray ?: root["citations"] as? JsonArray ?: return null
        return hits.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            BrainHit(
                uri = o.str("uri") ?: "",
                title = o.str("title") ?: "",
                heading = o.str("heading") ?: "",
                score = o.double("score") ?: 0.0,
            )
        }
    }

    /** MemorySearchResponse.memories. */
    fun memorySearch(body: String): List<BrainMemory>? {
        val root = root(body) ?: return null
        return (root["memories"] as? JsonArray)?.mapNotNull { memoryOf(it) }
    }

    /** GET /memory/recent's top-level array. */
    fun memoryRecent(body: String): List<BrainMemory>? {
        val array = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return null
        return array.mapNotNull { memoryOf(it) }
    }

    /** RagStats {documents, chunks, bySource}. */
    fun ragStats(body: String): BrainRagStats? {
        val root = root(body) ?: return null
        val documents = root.long("documents") ?: return null
        val chunks = root.long("chunks") ?: return null
        val bySource = (root["bySource"] as? JsonObject)?.mapNotNull { (source, value) ->
            (value as? JsonPrimitive)?.content?.toLongOrNull()?.let { source to it }
        }?.toMap() ?: emptyMap()
        return BrainRagStats(documents, chunks, bySource)
    }

    /** BusStatus (agent-brain's PgBusIngest shape; kafka fields ignored — additive). */
    fun busStatus(body: String): BrainBusStatus? {
        val root = root(body) ?: return null
        val transport = root.str("transport") ?: return null
        val file = root.str("file") ?: return null
        return BrainBusStatus(
            transport = transport,
            file = file,
            fileExists = root.bool("fileExists") ?: false,
            fileBytes = root.long("fileBytes") ?: 0,
            offset = root.long("offset") ?: 0,
            lagBytes = root.long("lagBytes") ?: 0,
            malformed = root.long("malformed") ?: 0,
            shrinks = root.long("shrinks") ?: 0,
            seen = root.long("seen") ?: 0,
            inserted = root.long("inserted") ?: 0,
            duplicates = root.long("duplicates") ?: 0,
            dbEvents = root.long("dbEvents") ?: 0,
            lastTs = root.str("lastTs"),
            lastKind = root.str("lastKind"),
        )
    }

    /** MemoryStatus {pendingTurns, processedTurns, memories, byKind, quarantined, breaker, …}. */
    fun memoryStatus(body: String): BrainMemoryStatus? {
        val root = root(body) ?: return null
        val memories = root.long("memories") ?: return null
        val byKind = (root["byKind"] as? JsonObject)?.mapNotNull { (kind, value) ->
            (value as? JsonPrimitive)?.content?.toLongOrNull()?.let { kind to it }
        }?.toMap() ?: emptyMap()
        return BrainMemoryStatus(
            pendingTurns = root.long("pendingTurns") ?: 0,
            processedTurns = root.long("processedTurns") ?: 0,
            memories = memories,
            byKind = byKind,
            quarantined = root.long("quarantined") ?: 0,
            breaker = root.str("breaker") ?: "?",
            chatModel = root.str("chatModel") ?: "?",
            embedModel = root.str("embedModel") ?: "?",
        )
    }

    /** The error hint of a non-2xx answer: agent-brain's {error: "…"} or the raw body head. */
    fun errorOf(body: String): String? {
        val root = root(body) ?: return body.takeIf { it.isNotBlank() }?.take(ERROR_HEAD)
        return root.str("error") ?: body.take(ERROR_HEAD).takeIf { it.isNotBlank() }
    }

    /** GET /eval/latest's top-level array (StoredRun rows, the fields /learn reads). */
    fun evalLatest(body: String): List<BrainEvalRow>? {
        val array = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return null
        return array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val suite = o.str("suite") ?: return@mapNotNull null
            BrainEvalRow(
                suite = suite,
                model = o.str("model") ?: "?",
                passed = o.long("passed")?.toInt() ?: 0,
                total = o.long("total")?.toInt() ?: 0,
                gate = o.double("gate") ?: 0.0,
                ok = o.bool("ok") ?: false,
                ts = o.str("ts"),
            )
        }
    }

    /** /costs/today: {turns, usd, unpriced} — the cost line /learn speaks. */
    fun costsToday(body: String): BrainCostsToday? {
        val root = root(body) ?: return null
        val turns = root.long("turns") ?: return null
        return BrainCostsToday(
            turns = turns,
            usd = root.double("usd") ?: 0.0,
            unpriced = root.long("unpriced") ?: 0,
        )
    }

    private fun memoryOf(element: JsonElement): BrainMemory? {
        val o = element as? JsonObject ?: return null
        val content = o.str("content") ?: return null
        return BrainMemory(
            id = o.long("id"),
            kind = o.str("kind") ?: "?",
            content = content,
            createdAt = o.str("createdAt"),
            score = o.double("score"),
        )
    }

    private const val ERROR_HEAD = 160
}

/** 2xx: agent-brain answers 200/201 (save) and never redirects. */
private const val HTTP_OK_MIN = 200
private const val HTTP_OK_MAX = 299
private val HTTP_SUCCESS = HTTP_OK_MIN..HTTP_OK_MAX

/** The owner's Spanish — every degradation said honestly, never a silent command. */
@Suppress("TooManyFunctions") // one renderer per command surface — the object IS the vocabulary
internal object BrainTexts {

    /** /memory recent's default and hard cap (agent-brain's MAX_K is 50). */
    const val RECENT_DEFAULT = 10
    const val RECENT_MAX = 50

    /** /memory search's k — Python's /memory default. */
    const val SEARCH_K = 5

    /** The answer's own 5 s deadline (the daemon's fetch uses the same). */
    const val TIMEOUT_S = 5

    private const val HITS_SHOWN = 8
    private const val ITEM_CHARS = 240
    private const val LEARN_ITEM_CHARS = 160

    private const val BYTES_PER_KB = 1024
    private const val BYTES_PER_MB = BYTES_PER_KB * BYTES_PER_KB

    const val BRAIN_USAGE =
        "🧠 /brain «question» — searches agent-brain's RAG and cites uri+heading.\n" +
            "/brain status — the RAG's documents/chunks + the bus pulse."

    const val MEMORY_USAGE =
        "🧠 /memory «text» — searches memories (semantic).\n" +
            "/memory recent [k] — the last k (default $RECENT_DEFAULT).\n" +
            "/memory status — turns, memories and breaker."

    const val LEARN_USAGE =
        "🧠 /learn — the last 7 days' learning card (memories, evals and costs). No arguments."

    const val UNWIRED =
        "⚠️ /brain and /memory are not wired in this daemon (no agent-brain HTTP client)."

    fun down(url: String): String =
        "📡 brain down — it does not answer at <code>${PyText.esc(url)}</code>" +
            " (is it running?)"

    fun timeout(seconds: Int): String = "⏱️ brain did not answer in ${seconds}s — try again."

    fun httpError(status: Int, detail: String?): String =
        "⚠️ brain answered HTTP $status" + (detail?.takeIf { it.isNotBlank() }?.let { ": ${PyText.esc(it)}" } ?: "")

    fun unreadable(status: Int): String =
        "⚠️ brain answered something unreadable (HTTP $status) — check the service."

    fun ragEmpty(query: String): String = "📭 No RAG results for «${PyText.esc(query)}»."

    fun memoryEmpty(query: String): String = "📭 No memories for «${PyText.esc(query)}»."

    const val MEMORY_RECENT_EMPTY =
        "📭 Memory is empty — no turns processed yet."

    fun ragAnswer(query: String, hits: List<BrainHit>): String {
        val shown = hits.take(HITS_SHOWN)
        return buildString {
            append("🧠 <b>brain</b> — «${PyText.esc(query)}» (")
            append("${hits.size} ${if (hits.size == 1) "result" else "results"})")
            append("\n")
            shown.forEachIndexed { i, hit ->
                append("${i + 1}. ${citation(hit)} (${score(hit.score)})")
                if (i < shown.lastIndex) append("\n")
            }
            if (hits.size > shown.size) append("\n… and ${hits.size - shown.size} more")
        }
    }

    fun memoryAnswer(query: String, memories: List<BrainMemory>): String {
        val shown = memories.take(HITS_SHOWN)
        return buildString {
            append("🧠 <b>memory</b> — «${PyText.esc(query)}» (")
            append("${memories.size} ${if (memories.size == 1) "memory" else "memories"})")
            append("\n")
            shown.forEachIndexed { i, memory ->
                append("${i + 1}. ${memoryLine(memory)}")
                memory.score?.let { append(" (${score(it)})") }
                if (i < shown.lastIndex) append("\n")
            }
            if (memories.size > shown.size) append("\n… and ${memories.size - shown.size} more")
        }
    }

    fun memoryRecent(memories: List<BrainMemory>): String {
        val shown = memories.take(HITS_SHOWN)
        return buildString {
            append("🧠 <b>recent memory</b> (${memories.size})")
            append("\n")
            shown.forEachIndexed { i, memory ->
                append("${i + 1}. ${memoryLine(memory)}")
                memory.createdAt?.takeIf { it.isNotBlank() }?.let { append(" · <code>${PyText.esc(it)}</code>") }
                if (i < shown.lastIndex) append("\n")
            }
            if (memories.size > shown.size) append("\n… and ${memories.size - shown.size} more")
        }
    }

    fun status(url: String, rag: BrainReply, bus: BrainReply): String = buildString {
        append("🧠 <b>agent-brain</b> · <code>${PyText.esc(url)}</code>")
        append("\n")
        append(ragLeg(rag))
        append("\n")
        append(busLeg(bus))
    }

    fun memoryStatus(status: BrainMemoryStatus): String = buildString {
        append("🧠 <b>memory</b> (agent-brain)")
        append("\n⏳ pending: ${status.pendingTurns} · ✅ processed: ${status.processedTurns}")
        append("\n📝 memories: ${status.memories}")
        if (status.byKind.isNotEmpty()) {
            append("\n   " + status.byKind.entries.joinToString(" · ") { (kind, n) -> "${PyText.esc(kind)} $n" })
        }
        append("\n🔒 quarantine: ${status.quarantined} · breaker: ${PyText.esc(status.breaker)}")
        append("\n🤖 chat: ${PyText.esc(status.chatModel)} · embed: ${PyText.esc(status.embedModel)}")
    }

    private fun ragLeg(reply: BrainReply): String = when (reply) {
        is BrainReply.Down -> "📚 RAG: 📡 down"
        is BrainReply.Timeout -> "📚 RAG: ⏱️ no answer"
        is BrainReply.Response -> when {
            reply.status !in HTTP_SUCCESS -> "📚 RAG: ⚠️ HTTP ${reply.status}"
            else -> BrainJson.ragStats(reply.body)?.let { stats ->
                buildString {
                    append("📚 RAG: ${stats.documents} documents · ${stats.chunks} chunks")
                    if (stats.bySource.isNotEmpty()) {
                        append("\n   by source: ")
                        append(stats.bySource.entries.joinToString(" · ") { "${PyText.esc(it.key)} ${it.value}" })
                    }
                }
            } ?: "📚 RAG: ⚠️ unreadable answer"
        }
    }

    private fun busLeg(reply: BrainReply): String = when (reply) {
        is BrainReply.Down -> "📡 Bus: down"
        is BrainReply.Timeout -> "📡 Bus: ⏱️ no answer"
        is BrainReply.Response -> when {
            reply.status !in HTTP_SUCCESS -> "📡 Bus: ⚠️ HTTP ${reply.status}"
            else -> BrainJson.busStatus(reply.body)?.let { bus ->
                buildString {
                    if (!bus.fileExists) {
                        append("📡 Bus: no file yet — <code>${PyText.esc(bus.file)}</code>")
                    } else {
                        append("📡 Bus: ${bytes(bus.fileBytes)} · offset ${bytes(bus.offset)}")
                        append(" · lag ${bytes(bus.lagBytes)}")
                        append("\n   events: ${bus.dbEvents} in DB · ${bus.inserted} inserted")
                        append(" · ${bus.duplicates} duplicates")
                        append(" · ${bus.malformed} malformed · ${bus.shrinks} shrinks")
                        if (bus.lastKind != null) {
                            append("\n   last: ${PyText.esc(bus.lastKind)}")
                            bus.lastTs?.let { append(" · <code>${PyText.esc(it)}</code>") }
                        }
                    }
                }
            } ?: "📡 Bus: ⚠️ unreadable answer"
        }
    }

    private fun citation(hit: BrainHit): String {
        val label = hit.heading.takeIf { it.isNotBlank() }
            ?: hit.title.takeIf { it.isNotBlank() }
            ?: hit.uri.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: hit.uri
        return "${PyText.esc(label)} — <code>${PyText.esc(hit.uri)}</code>"
    }

    private fun memoryLine(memory: BrainMemory): String {
        val original = memory.content.trim()
        val content = PyText.take(original, ITEM_CHARS)
        val trimmed = if (PyText.cpLength(content) < PyText.cpLength(original)) "$content…" else content
        val id = memory.id?.let { " #$it" } ?: ""
        return "[${PyText.esc(memory.kind)}$id] ${PyText.esc(trimmed)}"
    }

    private fun score(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    /**
     * /learn — the week's learning card (plan v2 T3): the memories the last
     * 7 days produced, the latest eval rows and today's cost. Legs degrade
     * honestly per leg (📡/⚠️), never a silent hole.
     */
    const val LEARN_WINDOW_DAYS = 7
    const val LEARN_PER_KIND = 4

    fun learn(
        memories: List<BrainMemory>,
        evals: List<BrainEvalRow>,
        costs: BrainCostsToday?,
        now: java.time.Instant,
    ): String {
        val cutoff = now.minus(java.time.Duration.ofDays(LEARN_WINDOW_DAYS.toLong()))
        val week = memories.filter { memory ->
            memory.createdAt?.let { created ->
                runCatching { java.time.Instant.parse(created).isAfter(cutoff) }.getOrDefault(false)
            } ?: false
        }
        val byKind = week.groupBy { it.kind }
        val memoryBlock = buildString {
            append("\n📝 facts ${byKind["fact"]?.size ?: 0} · 🎯 decisions ${byKind["decision"]?.size ?: 0}")
            append(" · ⭐ preferences ${byKind["preference"]?.size ?: 0}")
            for (kind in listOf("decision", "fact", "preference")) {
                val items = byKind[kind].orEmpty().take(LEARN_PER_KIND)
                if (items.isEmpty()) continue
                append("\n\n<b>$kind</b>")
                items.forEach { memory ->
                    append("\n• ${PyText.esc(PyText.take(memory.content.trim(), LEARN_ITEM_CHARS))}")
                }
            }
            if (week.isEmpty()) append("\n\n📭 no new memories this week")
        }
        val evalBlock = evalLeg(evals)
        val costLine = costs?.let { "today: ${it.turns} turns · $${String.format(Locale.ROOT, "%.2f", it.usd)}" }
            ?: "today: 📡 down"
        return buildString {
            append("🧠 <b>learning of the last $LEARN_WINDOW_DAYS days</b>")
            append(memoryBlock)
            append(evalBlock)
            append("\n\n💰 ")
            append(costLine)
        }
    }

    /** The eval leg: the newest run per suite, one honest row each. */
    private fun evalLeg(evals: List<BrainEvalRow>): String = if (evals.isEmpty()) {
        "\n\n📊 evals: 📭 no runs"
    } else {
        buildString {
            append("\n\n📊 <b>evals</b> (last run)")
            evals.forEach { row ->
                val mark = if (row.ok) "✅" else "❌"
                append("\n• ${PyText.esc(row.suite)} ${row.passed}/${row.total} $mark (gate ${row.gate})")
            }
        }
    }

    private fun bytes(value: Long): String = when {
        value < BYTES_PER_KB -> "$value B"
        value < BYTES_PER_MB -> String.format(Locale.ROOT, "%.1f KB", value / BYTES_PER_KB.toDouble())
        else -> String.format(Locale.ROOT, "%.1f MB", value / BYTES_PER_MB.toDouble())
    }
}

/**
 * /brain and /memory — the reading list from agent-brain (ADR-019). PURE
 * rendering over the injected [BrainGateway]; every branch of the ladder
 * (down / timeout / HTTP error / unreadable / empty) says what happened.
 */
class BrainCommands(
    private val brain: BrainGateway,
    /** The clock — at the edge, so `/learn`'s week is a value the tests pin. */
    private val now: () -> java.time.Instant = java.time.Instant::now,
) {

    /** `/brain [question | status]`. */
    suspend fun brain(rest: String): String {
        val arg = rest.trim()
        return when {
            arg.isEmpty() -> BrainTexts.BRAIN_USAGE
            arg.equals("status", ignoreCase = true) -> BrainTexts.status(brain.url, brain.ragStats(), brain.busStatus())
            else -> query(arg)
        }
    }

    private suspend fun query(arg: String): String = when (val reply = brain.ragQuery(arg, BrainTexts.SEARCH_K)) {
        is BrainReply.Down -> BrainTexts.down(brain.url)
        BrainReply.Timeout -> BrainTexts.timeout(BrainTexts.TIMEOUT_S)
        is BrainReply.Response -> when {
            reply.status !in HTTP_SUCCESS ->
                BrainTexts.httpError(reply.status, BrainJson.errorOf(reply.body))
            else -> BrainJson.ragHits(reply.body)?.let { hits ->
                if (hits.isEmpty()) BrainTexts.ragEmpty(arg) else BrainTexts.ragAnswer(arg, hits)
            } ?: BrainTexts.unreadable(reply.status)
        }
    }

    /** `/memory <text | recent [k] | status>`. */
    suspend fun memory(rest: String): String {
        val arg = rest.trim()
        return when {
            arg.isEmpty() -> BrainTexts.MEMORY_USAGE
            arg.equals("status", ignoreCase = true) -> memoryStatus()
            else -> memoryArg(arg)
        }
    }

    /**
     * `/learn` — the week's learning card (plan v2 T3): memories + evals +
     * today's cost, each leg honestly degraded. No args; everything else is
     * the usage line.
     */
    suspend fun learn(rest: String): String {
        if (rest.isNotBlank()) return BrainTexts.LEARN_USAGE
        val recent = brain.memoryRecent(60)
        val evals = brain.evalLatest(null, 12)
        val costs = brain.costsToday()
        // Each leg degrades alone; the card assembles whatever answered. All
        // three down = the one honest «down».
        val allDown = listOf(recent, evals, costs).all { it is BrainReply.Down }
        val memoryList: List<BrainMemory> = when (recent) {
            is BrainReply.Response ->
                if (recent.status in HTTP_SUCCESS) BrainJson.memoryRecent(recent.body).orEmpty() else emptyList()
            else -> emptyList()
        }
        val evalRows: List<BrainEvalRow> = when (evals) {
            is BrainReply.Response ->
                if (evals.status in HTTP_SUCCESS) BrainJson.evalLatest(evals.body).orEmpty() else emptyList()
            else -> emptyList()
        }
        val costsToday: BrainCostsToday? = (costs as? BrainReply.Response)
            ?.takeIf { it.status in HTTP_SUCCESS }
            ?.let { BrainJson.costsToday(it.body) }
        return when {
            allDown -> BrainTexts.down(brain.url)
            else -> BrainTexts.learn(memoryList, evalRows, costsToday, now())
        }
    }

    private suspend fun memoryArg(arg: String): String {
        val limit = recentLimit(arg)
        return if (limit != null) recent(limit) else search(arg)
    }

    private suspend fun search(query: String): String {
        val reply = brain.memorySearch(query, BrainTexts.SEARCH_K)
        return when (reply) {
            is BrainReply.Down -> BrainTexts.down(brain.url)
            BrainReply.Timeout -> BrainTexts.timeout(BrainTexts.TIMEOUT_S)
            is BrainReply.Response -> when {
                reply.status !in HTTP_SUCCESS ->
                    BrainTexts.httpError(reply.status, BrainJson.errorOf(reply.body))
                else -> BrainJson.memorySearch(reply.body)?.let { memories ->
                    if (memories.isEmpty()) BrainTexts.memoryEmpty(query) else BrainTexts.memoryAnswer(query, memories)
                } ?: BrainTexts.unreadable(reply.status)
            }
        }
    }

    private suspend fun memoryStatus(): String = when (val reply = brain.memoryStatus()) {
        is BrainReply.Down -> BrainTexts.down(brain.url)
        BrainReply.Timeout -> BrainTexts.timeout(BrainTexts.TIMEOUT_S)
        is BrainReply.Response -> memoryStatusReply(reply)
    }

    private suspend fun recent(limit: Int): String = when (val reply = brain.memoryRecent(limit)) {
        is BrainReply.Down -> BrainTexts.down(brain.url)
        BrainReply.Timeout -> BrainTexts.timeout(BrainTexts.TIMEOUT_S)
        is BrainReply.Response -> when {
            reply.status !in HTTP_SUCCESS -> BrainTexts.httpError(reply.status, BrainJson.errorOf(reply.body))
            else -> BrainJson.memoryRecent(reply.body)?.let { memories ->
                if (memories.isEmpty()) BrainTexts.MEMORY_RECENT_EMPTY else BrainTexts.memoryRecent(memories)
            } ?: BrainTexts.unreadable(reply.status)
        }
    }

    private fun memoryStatusReply(reply: BrainReply.Response): String = when {
        reply.status !in HTTP_SUCCESS -> BrainTexts.httpError(reply.status, BrainJson.errorOf(reply.body))
        else -> BrainJson.memoryStatus(reply.body)?.let(BrainTexts::memoryStatus)
            ?: BrainTexts.unreadable(reply.status)
    }

    /** "recent" or "recent 7" → the limit (absent = default, garbage = default, clamped to 1..50). */
    private fun recentLimit(arg: String): Int? {
        val parts = arg.split(WHITESPACE)
        if (!parts.first().equals("recent", ignoreCase = true)) return null
        val requested = parts.getOrNull(1)?.toIntOrNull() ?: BrainTexts.RECENT_DEFAULT
        return requested.coerceIn(1, BrainTexts.RECENT_MAX)
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}


// ---- the one reader ChannelFrames does not carry ---------------------------

internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()
