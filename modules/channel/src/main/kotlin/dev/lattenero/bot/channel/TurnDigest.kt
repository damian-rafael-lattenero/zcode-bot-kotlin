package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One prose step of the turn's voice, with the tool that preceded it. */
data class TurnProse(
    /**
     * The step's full text — never elided. [TurnDigest.pages] chunks an
     * oversized step across blocks and messages; the chunks CONCATENATE to
     * the whole, so nothing the model said is ever dropped.
     */
    val text: String,
    /** The preceding tool's name, when one ran before this step spoke. */
    val afterTool: String? = null,
)

/**
 * The turn's organized voice: every prose step in chronological order; the
 * LAST step is the turn's conclusion.
 */
data class TurnVoice(val segments: List<TurnProse>) {
    /** The turn's final words — what /last, the bus and the notification speak. */
    val conclusion: String? get() = segments.lastOrNull()?.text?.takeIf { it.isNotBlank() }
}

/**
 * THE TURN'S ORGANIZED DIGEST (the owner, 16/11: «si esto estuviera
 * organizado y con contexto valdría bastante, pero no lo está»): the seal
 * stops shipping the turn's raw concatenated prose and speaks STEPS instead
 * — each one labeled with the tool it followed, the conclusion marked ✅.
 *
 * Where the structure comes from: the fork merges every assistant step of a
 * turn into ONE message whose `content` is a plain concatenation
 * (zcodePersistedMessageMerge.ts) — that concatenation IS the raw wall the
 * owner rejected. The same message carries `parts`, the REAL interleaving
 * (content / thought / tool-call, the tool-call indexing `tools[].toolName`),
 * and the digest rebuilds the steps from it. No fork change.
 *
 * Pure: JSON or fold state in, wire blocks out; the send legs live in
 * [LivePublisher.seal].
 */
object TurnDigest {

    /**
     * The snapshot's projection: the last assistant message — the merged
     * turn — walked through its `parts`. Consecutive content parts form ONE
     * step (a step may interleave text around tools, and the fork persists
     * one content part per text part); a tool-call part closes the current
     * step and names the next one's `afterTool`; thought parts stay silent
     * (the CoT has its own collapsed channel). The trailing step is the
     * conclusion.
     *
     * THE OLD SHAPE'S FALLBACK: a message without `parts` (an old snapshot,
     * a non-fork daemon) projects its whole `content` as one step — today's
     * behavior, organized as a single- step voice.
     */
    fun ofSnapshot(resultJson: String): TurnVoice? = runCatching {
        val root = Json.parseToJsonElement(resultJson) as? JsonObject ?: return null
        val messages = root["messages"] as? JsonArray ?: return null
        val message = messages.asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { (it["role"] as? JsonPrimitive)?.content == "assistant" }
            .lastOrNull() ?: return null

        val toolNames: List<String> = (message["tools"] as? JsonArray)?.mapNotNull { entry ->
            ((entry as? JsonObject)?.get("toolName") as? JsonPrimitive)?.content
        } ?: emptyList()

        val parts = message["parts"] as? JsonArray
        if (parts == null) {
            val text = (message["content"] as? JsonPrimitive)?.content?.trim('\n') ?: return null
            return if (text.isBlank()) null else TurnVoice(listOf(TurnProse(text)))
        }

        val segments = mutableListOf<TurnProse>()
        val run = StringBuilder()
        var afterTool: String? = null
        fun closeRun() {
            val text = run.toString().trim('\n')
            if (text.isNotBlank()) segments += TurnProse(text, afterTool)
            run.clear()
        }
        for (element in parts) {
            val part = element as? JsonObject ?: continue
            when ((part["type"] as? JsonPrimitive)?.content) {
                "content" -> run.append((part["content"] as? JsonPrimitive)?.content ?: "")
                "tool-call" -> {
                    closeRun()
                    val index = (part["toolIndex"] as? JsonPrimitive)?.content?.toIntOrNull()
                    afterTool = index?.let { toolNames.getOrNull(it) }
                }
                // "thought" and unknown types: the CoT's own channel — the digest speaks prose only
            }
        }
        closeRun()
        if (segments.isEmpty()) null else TurnVoice(segments)
    }.getOrNull()

    /**
     * THE FOLD'S PROJECTION — the never-lost fallback for the seal moment
     * the snapshot cannot answer: the spent steps in order and the seal's
     * own copy as the trailing conclusion. The fold cannot name the tool
     * each step followed, so the labels degrade to bare numbers — honest,
     * not invented.
     */
    fun ofFold(state: TurnLive): TurnVoice = TurnVoice(
        state.proseSpent.filter { it.isNotBlank() }.map { TurnProse(it) } +
            listOfNotNull(
                state.answerAtSeal?.trim('\n')?.takeIf { it.isNotBlank() }?.let { TurnProse(it) },
            ),
    )

    /**
     * THE DIGEST'S PAGES: each step becomes its labeled violet blocks —
     * fenced code rides `pre` (the fence's language rides), prose rides
     * `pullquote` — packed into pages of [RichVoice.BLOCK_CP] total, ONE
     * rich message per page. THE CONTINUATION LAW (the owner, 16/11: «se
     * trunca y no sigue el texto en un mensaje nuevo»): an oversized step
     * CHUNKS across blocks and pages — prose prefers a line boundary, code
     * cuts hard like the long command's chunks — and the chunks concatenate
     * to the whole. Nothing is ever dropped.
     *
     * The label rides INSIDE the step's first prose block (the label line,
     * then the words); a step opening with a fence speaks its label as its
     * own tiny pullquote, the code follows clean. A single-step turn renders
     * bare — the voice alone, exactly today's shape.
     */
    fun pages(voice: TurnVoice): List<List<JsonObject>> {
        val blocks = mutableListOf<JsonObject>()
        val total = voice.segments.size
        voice.segments.forEachIndexed { index, segment ->
            val label = labelOf(index, total, segment.afterTool)
            val full = if (label == null) segment.text else "$label\n\n${segment.text}"
            for ((isCode, language, chunk) in RichVoice.splitFences(full)) {
                var rest = chunk.trim('\n')
                while (rest.isNotEmpty()) {
                    val piece = takeChunk(rest, preferLines = !isCode)
                    blocks += if (isCode) {
                        buildJsonObject {
                            put("type", "pre")
                            put("text", piece)
                            language?.let { put("language", it) }
                        }
                    } else {
                        buildJsonObject {
                            put("type", "pullquote")
                            put("text", piece)
                        }
                    }
                    rest = rest.substring(piece.length)
                }
            }
        }
        return pack(blocks)
    }

    /** The page's plain shape — the degraded speak leg's text (escaping happens at the leg). */
    fun pageText(page: List<JsonObject>): String = page.joinToString("\n\n") { block ->
        (block["text"] as? JsonPrimitive)?.content ?: ""
    }

    /**
     * The step's label line: the trailing step is the CONCLUSION (✅ marks
     * it); the rest count from 1 and name the tool they followed. A
     * single-step turn earns no chrome — the voice speaks bare.
     */
    private fun labelOf(index: Int, total: Int, afterTool: String?): String? {
        if (total == 1) return null
        if (index == total - 1) return "✅ Respuesta"
        val step = "Paso ${index + 1}"
        return afterTool?.let { "$step · tras 🔧 $it" } ?: step
    }

    /** One chunk of [RichVoice.BLOCK_CP], prose preferring its last line boundary under the cap. */
    private fun takeChunk(text: String, preferLines: Boolean): String {
        val piece = PyText.take(text, RichVoice.BLOCK_CP)
        if (!preferLines || PyText.cpLength(text) <= RichVoice.BLOCK_CP) return piece
        val cut = piece.lastIndexOf('\n')
        return if (cut > RichVoice.BLOCK_CP / 2) piece.substring(0, cut + 1) else piece
    }

    /** Greedy packing: blocks share a page while the page stays under [RichVoice.BLOCK_CP]. */
    private fun pack(blocks: List<JsonObject>): List<List<JsonObject>> {
        if (blocks.isEmpty()) return emptyList()
        val pages = mutableListOf<List<JsonObject>>()
        var page = mutableListOf<JsonObject>()
        var total = 0
        for (block in blocks) {
            val size = PyText.cpLength((block["text"] as? JsonPrimitive)?.content ?: "")
            if (page.isNotEmpty() && total + size > RichVoice.BLOCK_CP) {
                pages += page
                page = mutableListOf()
                total = 0
            }
            page += block
            total += size
        }
        if (page.isNotEmpty()) pages += page
        return pages
    }
}
