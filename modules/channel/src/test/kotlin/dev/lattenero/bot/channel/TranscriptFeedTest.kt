package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The TRANSCRIPT FEED's pure diff: what speaks between two turn
 * snapshots per level. THE LAW (the owner, 2/11): the feed is THE DATA —
 * each tool close its own ordered message with real content, and the CoT
 * NEVER travels whole: every sealed 3K block is a COLLAPSED message
 * (header + the tap affordance, `block` marks which message opens), the
 * owner decides whether to read it. What never happens: history
 * re-spoken, SUMMARY noise, invented content.
 */
class TranscriptFeedTest {

    private val verbs = ToolVerbs.MINIMAL

    private fun turn(
        taskId: String = "task-9",
        blocks: List<String> = emptyList(),
        pending: String = "",
        tools: List<ToolLive> = emptyList(),
    ): TurnLive = TurnLive(
        taskId = taskId,
        title = taskId,
        cotBlocks = blocks,
        cotPending = pending,
        tools = tools,
    )

    private fun closed(
        id: String,
        name: String = "Bash",
        status: String? = "completed",
        input: kotlinx.serialization.json.JsonObject? = null,
        output: String? = null,
        outputLines: Int? = null,
    ) = ToolLive(
        toolId = id,
        name = name,
        openedAtMs = 0,
        status = status,
        input = input,
        output = output,
        outputLines = outputLines,
    )

    @Test
    fun aNewlySealedBlockSpeaksCollapsedWithItsNumber() {
        // «yo decido si los leo o no una vez que se llenó el botón de los
        // 3k» — the header + the affordance, NEVER the text
        val prev = turn(blocks = listOf("uno"))
        val curr = turn(blocks = listOf("uno", "dos"))
        val msgs = TranscriptFeed.messages(prev, curr, FeedVerbosity.NORMAL, { "" }, verbs)
        assertEquals(1, msgs.size)
        val block = msgs[0]
        assertEquals(2, block.block, "the message marks WHICH block it opens")
        assertTrue("📖 <b>CoT 2</b>" in block.text)
        assertTrue("tocar para leer" in block.text)
        assertTrue("dos" !in block.text, "the block's text never rides the collapsed message")
    }

    @Test
    fun theTapExpansionRendersTheBlockWhole() {
        // the tap's expanded render: header + the WHOLE block, one message
        val text = "línea uno\nlínea dos"
        val expanded = TranscriptFeed.expandedBlock(3, PyText.cpLength(text), text)
        assertTrue("📖 <b>CoT 3</b>" in expanded)
        assertTrue("<blockquote>línea uno\nlínea dos</blockquote>" in expanded)
        // the unsealed tail's honest label, both shapes
        assertTrue("sin sellar" in TranscriptFeed.collapsedHeader(null, 1200))
        assertTrue("sin sellar" in TranscriptFeed.expandedBlock(null, 1200, "x"))
    }

    @Test
    fun historyIsNeverReSpokenAndATurnSwitchStartsClean() {
        val base = turn(blocks = listOf("uno", "dos"))
        val grew = turn(blocks = listOf("uno", "dos", "tres"))
        assertEquals(emptyList(), TranscriptFeed.messages(base, base, FeedVerbosity.VERBOSE, { "" }, verbs))
        assertEquals(1, TranscriptFeed.messages(base, grew, FeedVerbosity.NORMAL, { "" }, verbs).size)
        // a switch: the new turn's history stays sealed in the chat
        assertEquals(
            emptyList(),
            TranscriptFeed.messages(base, turn(taskId = "task-10", blocks = listOf("x")), FeedVerbosity.VERBOSE, { "" }, verbs),
        )
        // a first sight (prev null) speaks nothing — no retro replay
        assertEquals(emptyList(), TranscriptFeed.messages(null, grew, FeedVerbosity.VERBOSE, { "" }, verbs))
    }

    @Test
    fun aToolCloseSpeaksTheInjectedBoxOnce() {
        val prev = turn(tools = listOf(closed("t1", status = null, input = buildJsonObject { put("command", "ls") })))
        val curr = turn(
            tools = listOf(
                closed("t1", input = buildJsonObject { put("command", "ls") }, output = "out", outputLines = 1),
            ),
        )
        val msgs = TranscriptFeed.messages(prev, curr, FeedVerbosity.NORMAL, { "BOX(${it.name})" }, verbs)
        assertEquals(listOf("BOX(Bash)"), msgs.map { it.text })
        assertNull(msgs[0].block, "a close is plain data — no CoT registration")
    }

    @Test
    fun anInputlessCloseFallsBackToTheHonestLine() {
        val prev = turn(tools = listOf(closed("t1", name = "Read", status = null)))
        val curr = turn(tools = listOf(closed("t1", name = "Read", outputLines = 12)))
        val msgs = TranscriptFeed.messages(prev, curr, FeedVerbosity.NORMAL, { "" }, verbs)
        assertEquals(1, msgs.size)
        assertTrue("<b>Read</b>: 12 líneas de salida ✅" in msgs[0].text)
    }

    @Test
    fun verboseAddsTheOpenOneLinerNormalDoesNot() {
        val open = closed("t1", status = null, input = buildJsonObject { put("command", "git status") })
        val prev = turn()
        val curr = turn(tools = listOf(open))
        assertEquals(
            1,
            TranscriptFeed.messages(prev, curr, FeedVerbosity.VERBOSE, { "" }, verbs).size,
            "VERBOSE: the open speaks",
        )
        assertTrue(
            TranscriptFeed.messages(prev, curr, FeedVerbosity.VERBOSE, { "" }, verbs)[0].text.startsWith("▶️"),
        )
        assertEquals(
            emptyList(),
            TranscriptFeed.messages(prev, curr, FeedVerbosity.NORMAL, { "" }, verbs),
            "NORMAL: the open is quiet — the close will speak",
        )
        assertEquals(emptyList(), TranscriptFeed.messages(prev, curr, FeedVerbosity.SUMMARY, { "" }, verbs))
    }

    @Test
    fun aLaneOpenedAndClosedBetweenTicksSpeaksOnlyItsClose() {
        val curr = turn(
            tools = listOf(closed("t1", input = buildJsonObject { put("command", "ls") }, output = "f", outputLines = 1)),
        )
        val msgs = TranscriptFeed.messages(turn(), curr, FeedVerbosity.VERBOSE, { "BOX" }, verbs)
        assertEquals(listOf("BOX"), msgs.map { it.text }, "the close is the content carrier; the open would be noise")
    }

    @Test
    fun aFullBlockNeverChunksOneMessageByLaw() {
        // a block is capped at COT_BLOCK_CP (3000) — it ALWAYS fits one
        // message; the old chunk shower is gone
        val long = "x".repeat(8000) // over the cap: what the reducer could never produce, but the law holds
        val msgs = TranscriptFeed.messages(
            turn(blocks = listOf()),
            turn(blocks = listOf(long)),
            FeedVerbosity.NORMAL,
            { "" },
            verbs,
        )
        assertEquals(1, msgs.size, "one collapsed message — the text lives behind the tap, not in the chat")
        assertTrue("8K" in msgs[0].text)
    }

    @Test
    fun thePendingTailFlushesCollapsedUnderBlockZero() {
        assertEquals(emptyList(), TranscriptFeed.pendingFlush(turn(pending = "")))
        val msgs = TranscriptFeed.pendingFlush(turn(pending = "cola final"))
        assertEquals(1, msgs.size)
        assertEquals(0, msgs[0].block, "the tail is block 0 — the registry knows its own label")
        assertTrue("sin sellar" in msgs[0].text && "tocar para leer" in msgs[0].text)
        assertTrue("cola final" !in msgs[0].text, "the tail's text never rides the collapsed message")
    }
}
