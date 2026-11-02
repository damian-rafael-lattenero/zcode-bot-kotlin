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
private val BOT_STATUS = """
    {"bot":{"id":"daemon-local","enabled":true},
     "context":{"workspacePath":"/tmp/ws","activeTaskId":null,"mode":"full_access"}}
""".trimIndent()

class QuestionsTapTest {

    private class FakeOps : ChannelOps {
        val responds = mutableListOf<ClientFrame.Respond>()
        var answer: RespondAnswer = RespondAnswer.Resolved
        val inbound = mutableListOf<String>()

        override suspend fun query(query: ChannelQuery): QueryAnswer =
            if (queryKind(query) == "botStatus") QueryAnswer.Ok(BOT_STATUS) else QueryAnswer.Failed("internal", "unused")

        override suspend fun sendInbound(
            text: String,
            messageId: String,
            receivedAt: Long,
            attachments: List<InboundAttachment>,
        ): InboundReceipt {
            inbound.add(text)
            return InboundReceipt.FIRED
        }

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
            bindTaskId: String?,
        ): InboundAnswer =
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

    private val multiElicitationEvent = """
        {"type":"elicitation_request","taskId":"task-q","requestId":"rm","questions":[
           {"question":"¿qué base?","options":[{"value":"main","label":"main"},{"value":"dev","label":"dev"}]},
           {"question":"¿qué runtime?","options":[{"value":"node","label":"node"}]}]}
    """.trimIndent()

    private val optionlessSecondEvent = """
        {"type":"elicitation_request","taskId":"task-q","requestId":"ro","questions":[
           {"question":"¿qué base?","options":[{"value":"main","label":"main"}]},
           {"question":"¿notas?","header":"Notas"}]}
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

    private suspend fun feed(board: QuestionBoard, json: String) {
        board.onStreamEvent(
            ServerFrame.StreamEvent("task-q", 1, 0, Json.parseToJsonElement(json).jsonObject),
        )
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
        // questionCount == 1: the singular `answer` rides — byte-identical to the shape always flown
        assertEquals(
            "la main",
            frame.content?.get("answer")?.let { it as kotlinx.serialization.json.JsonPrimitive }?.content,
        )
        assertNull(board.byShortId("q1")) // sealed

        cmds.handle("otra cosa", 12) // the arm is gone: the text rides the normal route (the router's own tests cover that path)
        assertEquals(
            "⏳ Esa pregunta ya no está pendiente.",
            cmds.onCallback("qf:q1", 13)?.text, // the board sealed: no re-arm, no second answer
        )
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
        assertTrue(reply?.text?.contains("armada") == true) // the honest retry copy (CHANNEL_DOWN is English)
        assertNotNull(board.byShortId("q1")) // still armed

        ops.answer = RespondAnswer.Resolved
        assertNull(cmds.handle("la main otra vez", 12)) // the retry lands
        assertEquals(2, ops.responds.size)
    }

    // ---- the MULTI-question round-trip: advance in place, ONE frame at the end ----

    @Test
    fun multiQuestionTapFlowEditsInPlaceAndFliesOneFrameWithBothAnswers() = runBlocking {
        val ops = FakeOps()
        val seams = Seams()
        val board = seams.board()
        val cmds = commands(ops, board)
        feed(board, multiElicitationEvent)
        assertEquals(1, seams.sent.size) // ONE message for the whole questionnaire

        assertNull(cmds.onCallback("qa:q1:0", 20)) // q1: main — the edit IS the feedback
        assertEquals(0, ops.responds.size) // no frame yet — the questionnaire is half done
        assertEquals(1, seams.edits.size)
        assertTrue("pregunta 2 de 2" in seams.edits[0].second)
        assertTrue("¿qué runtime?" in seams.edits[0].second)
        assertEquals("¿qué runtime?", board.byShortId("q1")?.title)

        assertNull(cmds.onCallback("qa:q1:0", 21)) // q2: node — the LAST answer
        assertEquals(1, ops.responds.size) // exactly ONE frame, at the end
        val frame = ops.responds[0]
        assertEquals("elicitation", frame.kind)
        assertEquals("accept", frame.action)
        assertEquals("tg-21-qa", frame.messageId)
        // the fork's exact shape (buildBotElicitationContent): both answers, no singular `answer`
        assertEquals(
            Json.parseToJsonElement(
                """{"answers":{"¿qué base?":"main","¿qué runtime?":"node"},"answer_0":"main","answer_1":"node"}""",
            ).jsonObject,
            frame.content,
        )
        assertNull(frame.content?.get("answer"))
        assertEquals("✅ Respondidas las 2 preguntas", seams.edits[1].second) // the honest seal
        assertNull(board.byShortId("q1"))

        assertEquals(
            "⏳ Esa pregunta ya no está pendiente.",
            cmds.onCallback("qa:q1:0", 22)?.text, // the double tap: honest failure, no second frame
        )
        assertEquals(1, ops.responds.size)
    }

    @Test
    fun multiQuestionFreeTextArmCollectsTheOptionlessSecondOnly() = runBlocking {
        val ops = FakeOps()
        val seams = Seams()
        val board = seams.board()
        val cmds = commands(ops, board)
        feed(board, optionlessSecondEvent)

        assertNull(cmds.onCallback("qa:q1:0", 30)) // q1 by tap → the message advances to q2
        assertEquals(0, ops.responds.size)

        val arm = cmds.onCallback("qf:q1", 31) // the ✍️ arm — NOW it names question 2
        assertTrue(arm?.text?.contains("Notas") == true)

        assertNull(cmds.handle("una nota larga", 32)) // q2 by text → the ONE frame flies
        assertEquals(1, ops.responds.size)
        val frame = ops.responds[0]
        assertEquals("tg-32-qf", frame.messageId)
        assertEquals(
            Json.parseToJsonElement(
                """{"answers":{"¿qué base?":"main","¿notas?":"una nota larga"},"answer_0":"main","answer_1":"una nota larga"}""",
            ).jsonObject,
            frame.content,
        )
        assertNull(board.byShortId("q1")) // sealed

        cmds.handle("otra cosa", 33) // the arm was consumed with q2: the line rides the normal route
        assertEquals(1, ops.responds.size)
    }

    @Test
    fun resolveByRequestIdMidFlowSealsAndNoOrphanRespondFlies() = runBlocking {
        val ops = FakeOps()
        val seams = Seams()
        val board = seams.board()
        val cmds = commands(ops, board)
        feed(board, multiElicitationEvent)

        cmds.onCallback("qa:q1:1", 40) // q1: dev — partial state only
        assertEquals(0, ops.responds.size)
        board.resolveByRequestId("rm", "✅ Respondido desde la app.")
        assertNull(board.byShortId("q1"))

        assertEquals(
            "⏳ Esa pregunta ya no está pendiente.",
            cmds.onCallback("qa:q1:0", 41)?.text, // the stale tap: honest miss
        )
        assertEquals(0, ops.responds.size) // NO orphan respond
        assertEquals(2, seams.edits.size) // the advance edit + the seal — nothing more
    }
}
