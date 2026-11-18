package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE SEAL'S 👍/👎 (audit: the first preference data): the last digest page
 * carries the row, the tap hands (sid, signal) to the wiring — which appends
 * the bus's `turn.feedback` — and the digest's text is never touched. A
 * malformed tap records NOTHING (no invented signals).
 */
class FeedbackTapTest {

    private class FakeOps : ChannelOps {
        override suspend fun query(query: ChannelQuery): QueryAnswer = QueryAnswer.Failed("unused", "test")
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

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long): Int = 0
        override suspend fun subscribe(taskId: String, workspacePath: String) {}
    }

    private class FakeHistory(private val rows: List<TaskRow>) : TasksHistory {
        override suspend fun ctxTasks(limit: Int): List<TaskRow> = rows.take(limit)
        override suspend fun modelSelection(sid: String): ModelSel? = null
        override suspend fun freshestTask(workspacePath: String): TaskRow? = rows.firstOrNull()
        override suspend fun taskAlive(sid: String): Boolean = true
    }

    private fun commands(calls: MutableList<Pair<String, String>>): BotCommands = BotCommands(
        ops = FakeOps(),
        hub = LiveHub(),
        daemonReset = { "test" },
        ownerUserId = { 7L },
        clock = { 1_000_000L },
        history = FakeHistory(listOf(TaskRow(sid = "task-77", title = "t", ws = "/tmp"))),
        defaultWorkspacePath = "",
        onFeedback = { sid, signal -> calls += sid to signal },
    )

    @Test
    fun theTapHandsTheSignalToTheWiring() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val reply = commands(calls).onCallback("fb:up:sess_abc123", 1L, 7L)
        assertEquals(listOf("sess_abc123" to "up"), calls)
        assertNull(reply, "the tap toast is the whole answer — the digest's text is never touched")
    }

    @Test
    fun aDownSignalRidesTheSamePath() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        commands(calls).onCallback("fb:dn:sess_abc123", 2L, 7L)
        assertEquals(listOf("sess_abc123" to "down"), calls)
    }

    @Test
    fun aMalformedTapRecordsNothing() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val bc = commands(calls)
        assertNull(bc.onCallback("fb:meh:sess_x", 3L, 7L))
        assertNull(bc.onCallback("fb:up:", 4L, 7L))
        assertNull(bc.onCallback("fb:up", 5L, 7L))
        assertTrue(calls.isEmpty(), "no invented signals: $calls")
    }

    @Test
    fun theRowCarriesBothSignalsUnderTelegramsByteLimit() {
        val row = Keyboards.feedbackRow("sess_976b29d9-230d-4a44-bcf7-555f3fc31d23")
        val buttons = row["inline_keyboard"]!!.jsonArray.single().jsonArray
        val datas = buttons.map { it.jsonObject["callback_data"]!!.jsonPrimitive.content }
        assertEquals(listOf("fb:up:sess_976b29d9-230d-4a44-bcf7-555f3fc31d23", "fb:dn:sess_976b29d9-230d-4a44-bcf7-555f3fc31d23"), datas)
        assertTrue(datas.all(Keyboards::callbackDataValid), "callback_data must stay ≤64 bytes: $datas")
    }
}
