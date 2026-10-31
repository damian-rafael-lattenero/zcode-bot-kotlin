package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The taps' round-trip: qa:<sid>:<i> flies the respond frame and seals the
 * question honestly; qf:<sid> arms the NEXT line as the answer (one shot);
 * an Unavailable keeps everything armed for the retry. The fork's contract
 * (6993d7e8) is the frame's shape: kind + optionId/response echo for a
 * permission, action + content for the elicitation door.
 */
class QuestionsTapTest {

    private class FakeOps : ChannelOps {
        val responds = mutableListOf<ClientFrame.Respond>()
        var answer: RespondAnswer = RespondAnswer.Resolved
        val inbound = mutableListOf<String>()

        override suspend fun query(query: ChannelQuery): QueryAnswer =
            if (query == ChannelQuery.BotStatus) {
                QueryAnswer.Ok(
                    """{"bot":{"enabled":true},""" +
                        """"context":{"workspacePath":"/ws/test","activeTaskId":"task-77","mode":"task"}}""",
                )
            } else {
                QueryAnswer.Failed("internal", "unused")
            }

        override suspend fun sendInbound(
            text: String,
            messageId: String,
            receivedAt: Long,
            attachments: List<InboundAttachment>,
        ): InboundReceipt {
            inbound.add(text)
            return InboundReceipt.FIRED
        }

        override suspend fun sendInboundAwait(text: String, messageId: String, receivedAt: Long): InboundAnswer =
            InboundAnswer.Answered(duplicate = false, replies = emptyList())

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long): Int = 1

        override suspend fun subscribe(taskId: String, workspacePath: String) {}

        override suspend fun respondQuestion(frame: ClientFrame.Respond): RespondAnswer {
            responds.add(frame)
            return answer
        }
    }

    private class Seams {
        val sent = mutableListOf<String>()
        val edits = mutableListOf<Pair<Long, String>>()

        fun board(): QuestionBoard = QuestionBoard(
            sendQuestion = { text, _ ->
                sent.add(text)
                7L
            },
            editQuestion = { mid, text, _ -> edits.add(mid to text) },
        )
    }

    private val permEvent = """
        {"type":"permission_request","taskId":"task-q","requestId":"rq1","description":"¿corro?",
         "options":[{"optionId":"allow","name":"Permitir","response":{"decision":"allow"}},
                    {"optionId":"deny","name":"Denegar","response":{"decision":"deny"}}]}
    """.trimIndent()

    private val elicitationEvent = """
        {"type":"elicitation_request","taskId":"task-q","requestId":"rq2","questions":[
           {"question":"¿qué base?","options":[{"value":"main","label":"main"}]}]}
    """.trimIndent()

    private fun commands(ops: FakeOps, board: QuestionBoard): BotCommands =
        BotCommands(
            ops,
            LiveHub(),
            daemonReset = { "" },
            history = object : TasksHistory {
                override suspend fun ctxTasks(limit: Int) = emptyList<TaskRow>()
                override suspend fun modelSelection(sid: String): ModelSel? = null
                override suspend fun freshestTask(workspacePath: String): TaskRow? = null
                override suspend fun taskAlive(sid: String) = true
            },
            board = board,
        )

    private fun feed(board: QuestionBoard, json: String) {
        kotlinx.coroutines.runBlocking {
            board.onStreamEvent(
                ServerFrame.StreamEvent("task-q", 1, 0, Json.parseToJsonElement(json).jsonObject),
            )
        }
    }

    @Test
    fun permissionTapFliesOptionIdAndResponseEchoAndSeals() = runBlocking {
        val ops = FakeOps()
        val seams = Seams()
        val board = seams.board()
        val cmds = commands(ops, board)
        feed(board, permEvent)

        val reply = cmds.onCallback("qa:q1:1", 99)
        assertEquals(1, ops.responds.size)
        val frame = ops.responds[0]
        assertEquals("permission", frame.kind)
        assertEquals("task-q", frame.taskId)
        assertEquals("deny", frame.optionId)
        assertEquals("""{"decision":"deny"}""", frame.response.toString())
        assertEquals("tg-99-qa", frame.messageId)
        assertNull(reply) // the edit IS the feedback
        assertNull(board.byShortId("q1")) // sealed: a second tap misses honestly
        assertEquals(1, seams.sent.size) // the question reached the phone exactly once
    }

    @Test
    fun staleTapSaysSo() = runBlocking {
        val cmds = commands(FakeOps(), Seams().board())
        val reply = cmds.onCallback("qa:zz:0", 1)
        assertEquals("⏳ Esa pregunta ya no está pendiente.", reply?.text)
    }

    @Test
    fun failedTapSealsWithTheHostsVerbatimError() = runBlocking {
        val ops = FakeOps()
        val seams = Seams()
        val board = seams.board()
        val cmds = commands(ops, board)
        feed(board, permEvent)
        ops.answer = RespondAnswer.Failed("already_resolved", "interaction ya resuelta")

        assertNull(cmds.onCallback("qa:q1:0", 5))
        assertEquals(7L to "⚠️ El tap no entró (already_resolved): interaction ya resuelta", seams.edits.single())
        assertNull(board.byShortId("q1"))
    }

    @Test
    fun unavailableKeepsTheQuestionArmed() = runBlocking {
        val ops = FakeOps()
        val board = Seams().board()
        val cmds = commands(ops, board)
        feed(board, permEvent)
        ops.answer = RespondAnswer.Unavailable

        val reply = cmds.onCallback("qa:q1:0", 5)
        assertTrue(reply?.text?.contains("canal") == true)
        assertNotNull(board.byShortId("q1")) // armed: the owner retries
    }

    @Test
    fun planTapFliesTheElicitationDoorWithAcceptAndDecline() = runBlocking {
        val ops = FakeOps()
        val board = Seams().board()
        val cmds = commands(ops, board)
        feed(
            board,
            """{"type":"elicitation_request","taskId":"task-q","requestId":"rq3",
               "schema":{"interaction":"plan_approval","plan":"1"},"message":"?"}""",
        )

        cmds.onCallback("qa:q1:0", 7)
        assertEquals("plan", ops.responds[0].kind)
        assertEquals("accept", ops.responds[0].action)

        ops.responds.clear()
        feed(
            board,
            """{"type":"elicitation_request","taskId":"task-q","requestId":"rq4",
               "schema":{"interaction":"plan_approval","plan":"2"},"message":"?"}""",
        ) // q2 — a FRESH requestId so the dedupe lets the decline tap live
        cmds.onCallback("qa:q2:1", 8)
        assertEquals("plan", ops.responds[0].kind)
        assertEquals("decline", ops.responds[0].action)
    }

    @Test
    fun freeTextArmAnswersOnceThenDisarms() = runBlocking {
        val ops = FakeOps()
        val seams = Seams()
        val board = seams.board()
        val cmds = commands(ops, board)
        feed(board, elicitationEvent)

        cmds.onCallback("qf:q1", 10) // the ✍️ arm
        assertNull(cmds.handle("la main", 11)) // the answer: Resolved → the edit is the feedback

        assertEquals(1, ops.responds.size)
        val frame = ops.responds[0]
        assertEquals("elicitation", frame.kind)
        assertEquals("accept", frame.action)
        val answers = frame.content?.get("answers") as? JsonObject
        assertEquals(
            "la main",
            (answers?.get("¿qué base?") as? kotlinx.serialization.json.JsonPrimitive)?.content,
        )
        assertNull(board.byShortId("q1")) // sealed

        cmds.handle("otra cosa", 12) // the arm is gone: the text rides the normal route
        assertEquals("otra cosa", ops.inbound.single())
    }

    @Test
    fun unavailableFreeTextRearsTheArm() = runBlocking {
        val ops = FakeOps()
        val board = Seams().board()
        val cmds = commands(ops, board)
        feed(board, elicitationEvent)
        ops.answer = RespondAnswer.Unavailable

        cmds.onCallback("qf:q1", 10)
        val reply = cmds.handle("la main", 11)
        assertTrue(reply?.text?.contains("canal") == true)
        assertNotNull(board.byShortId("q1")) // still armed

        ops.answer = RespondAnswer.Resolved
        assertNull(cmds.handle("la main otra vez", 12)) // the retry lands
        assertEquals(2, ops.responds.size)
    }
}
