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
 * One block of the turn's voice — TYPED (audit wave 4, the no-opaque-types
 * law): the core builds values, [TurnDigest.wire] renders the wire's JSON at
 * the Telegram edge only.
 */
sealed interface VoiceBlock {
    /** The block's words, verbatim (a chunk that concatenates to the whole). */
    val text: String

    /** Prose — the pullquote look. */
    data class Prose(override val text: String) : VoiceBlock

    /** Fenced code — the `pre` look; [language] names the fence when it had one. */
    data class Code(override val text: String, val language: String?) : VoiceBlock
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

        stepsOf(message) ?: legacyContentOf(message)
    }.getOrNull()

    /**
     * The message's steps, walked from its `parts`: consecutive content
     * parts form ONE step; a tool-call part closes the current step and
     * names the next one's [TurnProse.afterTool]; thought parts stay silent
     * (the CoT has its own collapsed channel). Null when the message
     * carries no parts (the old shape) or yields no steps.
     */
    private fun stepsOf(message: JsonObject): TurnVoice? {
        val parts = message["parts"] as? JsonArray ?: return null
        val toolNames: List<String> = (message["tools"] as? JsonArray)?.mapNotNull { entry ->
            ((entry as? JsonObject)?.get("toolName") as? JsonPrimitive)?.content
        } ?: emptyList()
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
        return if (segments.isEmpty()) null else TurnVoice(segments)
    }

    /** THE OLD SHAPE'S FALLBACK: a message without `parts` rides its whole `content` as one step. */
    private fun legacyContentOf(message: JsonObject): TurnVoice? {
        val text = (message["content"] as? JsonPrimitive)?.content?.trim('\n') ?: return null
        return if (text.isBlank()) null else TurnVoice(listOf(TurnProse(text)))
    }

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
    fun pages(voice: TurnVoice): List<List<VoiceBlock>> {
        val blocks = mutableListOf<VoiceBlock>()
        val total = voice.segments.size
        voice.segments.forEachIndexed { index, segment ->
            val label = labelOf(index, total, segment.afterTool)
            val full = if (label == null) segment.text else "$label\n\n${segment.text}"
            for ((isCode, language, chunk) in RichVoice.splitFences(full)) {
                var rest = chunk.trim('\n')
                while (rest.isNotEmpty()) {
                    val piece = takeChunk(rest, preferLines = !isCode)
                    blocks += if (isCode) VoiceBlock.Code(piece, language) else VoiceBlock.Prose(piece)
                    rest = rest.substring(piece.length)
                }
            }
        }
        return pack(blocks)
    }

    /** The page's plain shape — the degraded speak leg's text (escaping happens at the leg). */
    fun pageText(page: List<VoiceBlock>): String = page.joinToString("\n\n") { it.text }

    /**
     * THE WIRE EDGE: one page → the rich message's block params (`pre` with
     * its language, `pullquote` for prose) — the ONLY place the JSON shape
     * exists in this file.
     */
    fun wire(page: List<VoiceBlock>): List<JsonObject> = page.map { block ->
        buildJsonObject {
            when (block) {
                is VoiceBlock.Prose -> put("type", "pullquote")
                is VoiceBlock.Code -> {
                    put("type", "pre")
                    block.language?.let { put("language", it) }
                }
            }
            put("text", block.text)
        }
    }

    /**
     * The step's label line: the trailing step is the CONCLUSION (✅ marks
     * it); the rest count from 1 and name the tool they followed. A
     * single-step turn earns no chrome — the voice speaks bare.
     */
    private fun labelOf(index: Int, total: Int, afterTool: String?): String? = when {
        total == 1 -> null
        index == total - 1 -> "✅ Respuesta"
        afterTool != null -> "Paso ${index + 1} · tras 🔧 $afterTool"
        else -> "Paso ${index + 1}"
    }

    /** One chunk of [RichVoice.BLOCK_CP], prose preferring its last line boundary under the cap. */
    private fun takeChunk(text: String, preferLines: Boolean): String {
        val piece = PyText.take(text, RichVoice.BLOCK_CP)
        if (!preferLines || PyText.cpLength(text) <= RichVoice.BLOCK_CP) return piece
        val cut = piece.lastIndexOf('\n')
        return if (cut > RichVoice.BLOCK_CP / 2) piece.substring(0, cut + 1) else piece
    }

    /** Greedy packing: blocks share a page while the page stays under [RichVoice.BLOCK_CP]. */
    private fun pack(blocks: List<VoiceBlock>): List<List<VoiceBlock>> {
        if (blocks.isEmpty()) return emptyList()
        val pages = mutableListOf<List<VoiceBlock>>()
        var page = mutableListOf<VoiceBlock>()
        var total = 0
        for (block in blocks) {
            val size = PyText.cpLength(block.text)
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
