package dev.lattenero.bot.channel

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The TRANSCRIPT FEED's pure diff: what speaks between two turn
 * snapshots per level — the owner's «los cots uno debajo del otro en
 * mensajes separados» — and what never does (history re-spoken, SUMMARY
 * noise, invented content).
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
        status: String = "completed",
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
    fun aNewlySealedBlockSpeaksWholeWithItsNumber() {
        val prev = turn(blocks = listOf("uno"))
        val curr = turn(blocks = listOf("uno", "dos"))
        val msgs = TranscriptFeed.messages(prev, curr, FeedVerbosity.NORMAL, { "" }, verbs)
        assertEquals(1, msgs.size)
        assertTrue("<b>CoT 2</b>" in msgs[0], "the block's number travels")
        assertTrue("<blockquote>dos</blockquote>" in msgs[0])
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
        assertEquals(listOf("BOX(Bash)"), msgs)
    }

    @Test
    fun anInputlessCloseFallsBackToTheHonestLine() {
        val prev = turn(tools = listOf(closed("t1", name = "Read", status = null)))
        val curr = turn(tools = listOf(closed("t1", name = "Read", outputLines = 12)))
        val msgs = TranscriptFeed.messages(prev, curr, FeedVerbosity.NORMAL, { "" }, verbs)
        assertEquals(1, msgs.size)
        assertTrue("<b>Read</b>: 12 líneas de salida ✅" in msgs[0])
    }

    @Test
    fun verboseAddsTheOpenOneLinerNormalDoesNot() {
        val open = closed("t1", input = buildJsonObject { put("command", "git status") })
        val prev = turn()
        val curr = turn(tools = listOf(open))
        assertEquals(
            1,
            TranscriptFeed.messages(prev, curr, FeedVerbosity.VERBOSE, { "" }, verbs).size,
            "VERBOSE: the open speaks",
        )
        assertTrue(
            TranscriptFeed.messages(prev, curr, FeedVerbosity.VERBOSE, { "" }, verbs)[0].startsWith("▶️"),
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
        assertEquals(listOf("BOX"), msgs, "the close is the content carrier; the open would be noise")
    }

    @Test
    fun aLongBlockChunksUnderTheMessageLaw() {
        val long = "x".repeat(8000)
        val msgs = TranscriptFeed.messages(
            turn(blocks = listOf()),
            turn(blocks = listOf(long)),
            FeedVerbosity.NORMAL,
            { "" },
            verbs,
        )
        assertTrue(msgs.size > 1, "the block chunks")
        assertTrue("<b>CoT 1</b>" in msgs.first(), "the number rides the FIRST chunk only")
        assertTrue("<b>CoT 1</b>" !in msgs[1])
        assertTrue(
            msgs.all { PyText.cpLength(it) <= LiveTurn.COT_SHOWER_CHUNK_CP + 200 },
            "every chunk stays near the shower's budget",
        )
    }

    @Test
    fun thePendingTailFlushesHonestlyLabeled() {
        assertEquals(emptyList(), TranscriptFeed.pendingFlush(turn(pending = "")))
        val msgs = TranscriptFeed.pendingFlush(turn(pending = "cola final"))
        assertEquals(1, msgs.size)
        assertTrue("sin sellar" in msgs[0] && "<blockquote>cola final</blockquote>" in msgs[0])
    }
}
