package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The question's pure core + the board's lifecycle (the answer round-trip,
 * the owner's phone answers everything). The parse reads the fork's OWN
 * event shapes; the board pins the registry's laws: dedupe by requestId,
 * the short-id cap, the honest seals, the mid-less degrade.
 */
class QuestionsTest {

    private fun ev(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    // ---- parseQuestionEvent: the fork's three shapes ----

    @Test
    fun permissionParsesOptionsWithTheirResponsesAndDenyMark() {
        val q = parseQuestionEvent(
            ev(
                """{"type":"permission_request","taskId":"t1","requestId":"r1",
                   "description":"run rm -rf?","options":[
                     {"optionId":"allow","name":"Permitir","response":{"decision":"allow"}},
                     {"optionId":"deny","name":"Denegar","response":{"decision":"deny"}}]}""",
            ),
        )!!
        assertEquals("permission", q.kind)
        assertEquals("t1", q.taskId)
        assertEquals("run rm -rf?", q.title)
        assertEquals(2, q.options.size)
        assertEquals("Permitir", q.options[0].label)
        assertEquals(
            "deny",
            q.options[1].response?.get("decision")?.let { (it as JsonPrimitive).content },
        )
        assertTrue(q.options[1].isDeny)
        assertFalse(q.options[0].isDeny)
    }

    @Test
    fun permissionWithoutOptionsGrowsTheHonestSyntheticPair() {
        val q = parseQuestionEvent(
            ev("""{"type":"permission_request","taskId":"t1","requestId":"r1","title":"?"}"""),
        )!!
        assertEquals(listOf("allow", "deny"), q.options.map { it.optionId })
        assertTrue(q.options[1].isDeny)
    }

    @Test
    fun missingRequestIdIsNotAQuestion() {
        assertNull(parseQuestionEvent(ev("""{"type":"permission_request","taskId":"t1"}""")))
        assertNull(parseQuestionEvent(ev("""{"type":"tool_updated","taskId":"t1","requestId":"r1"}""")))
    }

    @Test
    fun elicitationReadsTheQuestionnaireShape() {
        val q = parseQuestionEvent(
            ev(
                """{"type":"elicitation_request","taskId":"t1","requestId":"r2","questions":[
                     {"question":"¿qué base?","header":"Elegí","options":[
                       {"value":"main","label":"main"},{"value":"dev","label":"dev"}]}]}""",
            ),
        )!!
        assertEquals("elicitation", q.kind)
        assertEquals("¿qué base?", q.title)
        assertEquals("Elegí", q.header)
        assertEquals(1, q.questionCount)
        assertEquals("main", q.options[0].optionId)
        assertEquals("dev", q.options[1].label)
    }

    @Test
    fun multiQuestionEventCarriesTheFullQuestionnaire() {
        val q = parseQuestionEvent(
            ev(
                """{"type":"elicitation_request","taskId":"t1","requestId":"r2","questions":[
                     {"question":"¿qué base?","header":"Elegí","options":[
                       {"value":"main","label":"main"},{"value":"dev","label":"dev"}]},
                     {"question":"¿qué runtime?","options":[{"value":"node","label":"node"}]},
                     {"question":"¿notas?"}]}""",
            ),
        )!!
        assertEquals(3, q.questionCount)
        assertEquals(3, q.questions.size)
        assertEquals(0, q.questionIndex)
        assertEquals("¿qué base?", q.title) // the question on screen is the FIRST
        assertEquals(listOf("main", "dev"), q.options.map { it.optionId })
        assertEquals("¿qué runtime?", q.questions[1].text)
        assertEquals(listOf("node"), q.questions[1].options.map { it.optionId })
        assertEquals("¿notas?", q.questions[2].text)
        assertTrue(q.questions[2].options.isEmpty()) // the ✍️ arm only
        // the keyboard shows the CURRENT question's options + the arm
        val rows = questionKeyboard("q9", q)["inline_keyboard"]!!.toString()
        assertTrue("qa:q9:0" in rows)
        assertFalse("qa:q9:2" in rows) // question 3 has no options — nothing to tap
        assertTrue("qf:q9" in rows)
    }

    @Test
    fun multiQuestionRenderSaysWhichQuestionIsOnScreen() {
        val q = parseQuestionEvent(
            ev(
                """{"type":"elicitation_request","taskId":"t1","requestId":"r2","questions":[
                     {"question":"uno"},{"question":"dos"}]}""",
            ),
        )!!
        assertTrue("pregunta 1 de 2" in questionText(q))
        val advanced = q.copy(questionIndex = 1, header = q.questions[1].header, title = q.questions[1].text)
        assertTrue("pregunta 2 de 2" in questionText(advanced))
    }

    @Test
    fun planApprovalRidesTheElicitationDoor() {
        val q = parseQuestionEvent(
            ev(
                """{"type":"elicitation_request","taskId":"t1","requestId":"r3",
                   "schema":{"interaction":"plan_approval","plan":"1. hacer cosas"},
                   "message":"¿Apruebo?"}""",
            ),
        )!!
        assertEquals("plan", q.kind)
        assertEquals("1. hacer cosas", q.plan)
        assertEquals(listOf("approve", "decline"), q.options.map { it.optionId })
    }

    // ---- the render: keyboard + content ----

    @Test
    fun keyboardTapsCarryTheShortIdAndTheFreeTextArmOnlyForElicitation() {
        val perm = PendingQuestion("t1", "r1", "permission", "🔐", "?", listOf(QuestionOption("allow", "Permitir")))
        val rows = questionKeyboard("q7", perm)["inline_keyboard"]!!.toString()
        assertTrue("qa:q7:0" in rows)
        assertFalse("qf:q7" in rows)

        val elRows = questionKeyboard("q7", perm.copy(kind = "elicitation"))["inline_keyboard"]!!.toString()
        assertTrue("qa:q7:0" in elRows)
        assertTrue("qf:q7" in elRows)
    }

    @Test
    fun elicitationContentCarriesTheAnswersShape() {
        val c = elicitationContent("¿qué base?", "main", 1)
        val answers = c["answers"] as JsonObject
        assertEquals("main", answers["¿qué base?"]?.let { (it as JsonPrimitive).content })
        assertEquals("main", c["answer_0"]?.let { (it as JsonPrimitive).content })
        assertEquals("main", c["answer"]?.let { (it as JsonPrimitive).content })
        assertNull(elicitationContent("q", "x", 2)["answer"]) // multi-question: no whole-answer key
    }

    @Test
    fun completeMultiAnswerContentPinsTheForksExactWireShape() {
        // The fork's buildBotElicitationContent (botsService.ts:4439) for a
        // 2-question form: answers keyed by each QUESTION text, answer_N per
        // index, NO singular `answer`. This fixture is the contract.
        val expected = Json.parseToJsonElement(
            """{"answers":{"¿qué base?":"main","¿qué runtime?":"node"},"answer_0":"main","answer_1":"node"}""",
        ) as JsonObject
        val entries = listOf("¿qué base?" to "main", "¿qué runtime?" to "node")
        assertEquals(expected, elicitationContentAll(entries, 2))
        // the singular `answer` rides ONLY for a one-question form
        val single = Json.parseToJsonElement(
            """{"answers":{"q":"x"},"answer_0":"x","answer":"x"}""",
        ) as JsonObject
        assertEquals(single, elicitationContentAll(listOf("q" to "x"), 1))
    }

    @Test
    fun answerEntriesMapsEveryQuestionIndexToItsAnswer() {
        val q = parseQuestionEvent(
            ev(
                """{"type":"elicitation_request","taskId":"t1","requestId":"r2","questions":[
                     {"question":"¿qué base?"},{"question":"¿qué runtime?"}]}""",
            ),
        )!!
        assertEquals(
            listOf("¿qué base?" to "", "¿qué runtime?" to ""),
            q.answerEntries(), // nothing answered yet — the honest blanks
        )
        val completed = q.copy(answers = mapOf(0 to "main", 1 to "node"))
        assertEquals(listOf("¿qué base?" to "main", "¿qué runtime?" to "node"), completed.answerEntries())
    }

    // ---- the board: registry laws ----

    private fun questionFrame(requestId: String, taskId: String = "t1"): ServerFrame.StreamEvent =
        ServerFrame.StreamEvent(
            taskId = taskId,
            seq = 1,
            at = 0,
            event = ev("""{"type":"permission_request","taskId":"$taskId","requestId":"$requestId","title":"?"}"""),
        )

    private class Seams {
        val sent = mutableListOf<String>()
        val edits = mutableListOf<Pair<Long, String>>()
        var nextMid: Long? = 5L

        fun board(store: QuestionBoardStore? = null): QuestionBoard = QuestionBoard(
            sendQuestion = { text, _ ->
                sent.add(text)
                nextMid
            },
            editQuestion = { mid, text, _ -> edits.add(mid to text) },
            store = store,
        )
    }

    @Test
    fun theBoardSendsOnceAndDedupesByRequestId() = runBlocking {
        val seams = Seams()
        val board = seams.board()
        assertNotNull(board.onStreamEvent(questionFrame("r1")))
        assertEquals(1, seams.sent.size)
        assertNull(board.onStreamEvent(questionFrame("r1"))) // the fork's replay: no second message
        assertEquals(1, seams.sent.size)
    }

    @Test
    fun resolveEditsTheRememberedMessageAndDropsTheEntry() = runBlocking {
        val seams = Seams()
        val board = seams.board()
        board.onStreamEvent(questionFrame("r1"))
        board.resolve("q1", "✅ Respondido: <b>Permitir</b>")
        assertEquals(listOf(5L to "✅ Respondido: <b>Permitir</b>"), seams.edits)
        assertNull(board.byShortId("q1"))
        board.resolve("q1", "otra vez") // a late resolve is a no-op — the entry is gone
        assertEquals(1, seams.edits.size)
    }

    @Test
    fun unsendMessageStillRegistersAndSealsQuietly() = runBlocking {
        val seams = Seams()
        seams.nextMid = null
        val board = seams.board()
        board.onStreamEvent(questionFrame("r1"))
        assertEquals(0L, board.byShortId("q1")?.mid)
        board.resolve("q1", "x") // mid=0 → no edit, no crash
        assertEquals(0, seams.edits.size)
    }

    @Test
    // `: Unit` is the LAW: kotlin.test's assertNotNull RETURNS the value, so a
    // bare last expression makes this test non-void and Jupiter silently skips it
    fun resolveTaskSealsEveryPendingQuestionOfTheTask(): Unit = runBlocking {
        val seams = Seams()
        val board = seams.board()
        board.onStreamEvent(questionFrame("r1"))
        board.onStreamEvent(questionFrame("r2", taskId = "t2"))
        board.resolveTask("t1", "⌛")
        assertNull(board.byShortId("q1"))
        assertNotNull(board.byShortId("q2"))
    }

    @Test
    fun resolveByRequestIdSealsThePhonesMessageToo() = runBlocking {
        val seams = Seams()
        val board = seams.board()
        board.onStreamEvent(questionFrame("r1"))
        board.resolveByRequestId("r1", "✅ Respondido desde la app.")
        assertEquals(1, seams.edits.size)
        assertNull(board.byShortId("q1"))
    }

    @Test
    // `: Unit` (the assertNotNull law): a non-void @Test never runs in Jupiter
    fun theRegistryCapEvictsTheOldest(): Unit = runBlocking {
        val seams = Seams()
        val board = seams.board()
        for (i in 1..QuestionBoard.REGISTRY_CAP + 1) {
            board.onStreamEvent(questionFrame("r$i"))
        }
        assertNull(board.byShortId("q1")) // the eldest evicted
        assertNotNull(board.byShortId("q${QuestionBoard.REGISTRY_CAP + 1}"))
    }

    @Test
    // `: Unit` (the assertNotNull law): a non-void @Test never runs in Jupiter
    fun theCapEvictionSealsTheEldestCardInsteadOfLeavingItArmed(): Unit = runBlocking {
        val seams = Seams()
        val board = seams.board()
        for (i in 1..QuestionBoard.REGISTRY_CAP + 1) {
            board.onStreamEvent(questionFrame("r$i"))
        }
        assertNull(board.byShortId("q1")) // the eldest evicted...
        // ...and its card DIED on screen (4/11: the armed dead button answered «ya no está pendiente»)
        assertEquals(listOf(5L to QuestionBoard.EVICTION_TEXT), seams.edits)
    }

    @Test
    fun theEvictionOfAnUnsentCardStaysQuiet(): Unit = runBlocking {
        val seams = Seams()
        seams.nextMid = null
        val board = seams.board()
        for (i in 1..QuestionBoard.REGISTRY_CAP + 1) {
            board.onStreamEvent(questionFrame("r$i"))
        }
        assertNull(board.byShortId("q1"))
        assertEquals(0, seams.edits.size) // mid=0 → no edit, no crash (resolve's own law)
    }

    // ---- the board's persistence: the restart's memory (4/11's dead cards) ----

    /** The seam's fake: the snapshot IS the file — one board's saves are the next board's load. */
    private class MemoryStore : QuestionBoardStore {
        var snapshot: Map<String, PendingQuestion> = emptyMap()
        override fun load(): Map<String, PendingQuestion> = snapshot
        override fun save(entries: Map<String, PendingQuestion>) {
            snapshot = entries
        }
    }

    @Test
    fun theBoardRoundTripsThroughTheStoreAndTheOldCardStaysLive(): Unit = runBlocking {
        val store = MemoryStore()
        val a = Seams().board(store)
        a.onStreamEvent(questionFrame("r1"))
        a.onStreamEvent(questionFrame("r2"))
        assertEquals(setOf("q1", "q2"), store.snapshot.keys)
        assertEquals(5L, store.snapshot["q1"]?.mid) // the send's mid is part of the persisted state

        // the RESTARTED board (same store, fresh seams): the old cards keep working
        val seamsB = Seams()
        val b = seamsB.board(store)
        assertNotNull(b.byShortId("q1"))
        // the fork's re-announcement DEDUPES into the old card — no second message, no re-issue
        assertNull(b.onStreamEvent(questionFrame("r1")))
        assertEquals(0, seamsB.sent.size)
        // a tap on the OLD shortId resolves through the EXISTING path, at the OLD message id
        b.resolve("q1", "✅")
        assertNull(b.byShortId("q1"))
        assertEquals(listOf(5L to "✅"), seamsB.edits)
        assertFalse("q1" in store.snapshot) // the removal persisted too
    }

    @Test
    fun theReloadedBoardNeverReissuesALoadedShortId(): Unit = runBlocking {
        val store = MemoryStore()
        val a = Seams().board(store)
        a.onStreamEvent(questionFrame("r1"))
        a.onStreamEvent(questionFrame("r2"))
        val seamsB = Seams()
        val b = seamsB.board(store)
        assertNotNull(b.onStreamEvent(questionFrame("r3")))
        assertEquals("q3", store.snapshot.keys.last()) // the NEW question took the NEXT id — q1/q2 keep theirs
        assertEquals(1, seamsB.sent.size) // and it is the ONLY new card
    }

    @Test
    fun theCapEvictionLeavesTheStoreToo(): Unit = runBlocking {
        val store = MemoryStore()
        val board = Seams().board(store)
        for (i in 1..QuestionBoard.REGISTRY_CAP + 1) {
            board.onStreamEvent(questionFrame("r$i"))
        }
        assertFalse("q1" in store.snapshot) // the file reflects the removal (the eviction's disk shadow)
        assertEquals(QuestionBoard.REGISTRY_CAP, store.snapshot.size)
    }

    @Test
    fun aReloadedMultiQuestionFormAdvancesInPlaceAtTheOldMessage(): Unit = runBlocking {
        val store = MemoryStore()
        val a = FlowSeams().board(store)
        a.onStreamEvent(twoQuestionEvent("rm"))
        assertTrue(store.snapshot["q1"]?.questions?.size == 2) // the FULL questionnaire persisted

        val seamsB = FlowSeams()
        val b = seamsB.board(store)
        assertTrue(b.answer("q1", "main") is QuestionFlow.Advanced)
        val (mid, text, _) = seamsB.edits.single()
        assertEquals(9L, mid) // the OLD card's message — the restart changed nothing
        assertTrue("pregunta 2 de 2" in text)
    }

    // ---- the multi-question flow: advance IN PLACE, ONE respond at the end ----

    private fun elicitationFrame(requestId: String, questions: String): ServerFrame.StreamEvent =
        ServerFrame.StreamEvent(
            taskId = "t1",
            seq = 1,
            at = 0,
            event = ev(
                """{"type":"elicitation_request","taskId":"t1","requestId":"$requestId","questions":[$questions]}""",
            ),
        )

    private class FlowSeams {
        val sent = mutableListOf<String>()
        val edits = mutableListOf<Triple<Long, String, JsonObject?>>()

        fun board(store: QuestionBoardStore? = null): QuestionBoard = QuestionBoard(
            sendQuestion = { text, _ ->
                sent.add(text)
                9L
            },
            editQuestion = { mid, text, markup -> edits.add(Triple(mid, text, markup)) },
            store = store,
        )
    }

    private fun twoQuestionEvent(requestId: String) = elicitationFrame(
        requestId,
        questions = """
            {"question":"¿qué base?","options":[{"value":"main","label":"main"},{"value":"dev","label":"dev"}]},
            {"question":"¿qué runtime?","options":[{"value":"node","label":"node"}]}
        """.trimIndent(),
    )

    @Test
    fun twoQuestionFlowAdvancesTheSameMessageThenCarriesBothAnswers() = runBlocking {
        val seams = FlowSeams()
        val board = seams.board()
        board.onStreamEvent(twoQuestionEvent("rm"))
        assertEquals(1, seams.sent.size) // ONE message for the whole questionnaire

        // q1 answered by tap → the SAME message edited with q2, nothing flies yet
        val step1 = board.answer("q1", "main")
        assertTrue(step1 is QuestionFlow.Advanced)
        assertEquals(1, seams.sent.size) // the rate law: never a second message
        val (mid, text, markup) = seams.edits.single()
        assertEquals(9L, mid)
        assertTrue("pregunta 2 de 2" in text)
        assertTrue("¿qué runtime?" in text)
        assertTrue("qa:q1:0" in markup.toString()) // q2's OWN option now on screen
        assertEquals("¿qué runtime?", board.byShortId("q1")?.title)

        // q2 answered → Completed; the content carries BOTH answers in the fork's shape
        val step2 = board.answer("q1", "node")
        assertTrue(step2 is QuestionFlow.Completed)
        val completed = (step2 as QuestionFlow.Completed).question
        // the flight owns the LAST answer: the stored entry keeps q1's answer but
        // not q2's — an Unavailable retry re-answers only the last question
        val stored = board.byShortId("q1")!!
        assertEquals(mapOf(0 to "main"), stored.answers)
        assertEquals(1, stored.questionIndex)
        assertEquals(
            Json.parseToJsonElement(
                """{"answers":{"¿qué base?":"main","¿qué runtime?":"node"},"answer_0":"main","answer_1":"node"}""",
            ) as JsonObject,
            elicitationContentAll(completed.answerEntries(), completed.questionCount),
        )
    }

    @Test
    fun optionlessSecondQuestionTakesTheFreeTextArmThenFlies() = runBlocking {
        val seams = FlowSeams()
        val board = seams.board()
        board.onStreamEvent(
            elicitationFrame(
                "rn",
                questions = """
                    {"question":"¿qué base?","options":[{"value":"main","label":"main"}]},
                    {"question":"¿notas?","header":"Notas"}
                """.trimIndent(),
            ),
        )
        assertTrue(board.answer("q1", "main") is QuestionFlow.Advanced)
        val (_, text, markup) = seams.edits.single()
        assertTrue("¿notas?" in text)
        assertTrue("qf:q1" in markup.toString()) // the ✍️ arm remains
        assertFalse("qa:q1:" in markup.toString()) // q2 has no options — nothing to tap

        val step = board.answer("q1", "una nota")
        assertTrue(step is QuestionFlow.Completed)
        val completed = (step as QuestionFlow.Completed).question
        val content = elicitationContentAll(completed.answerEntries(), completed.questionCount)
        val answers = content["answers"] as JsonObject
        assertEquals("main", answers["¿qué base?"]?.let { (it as JsonPrimitive).content })
        assertEquals("una nota", answers["¿notas?"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun resolveByRequestIdMidFlowSealsAndDiscardsThePartialState() = runBlocking {
        val seams = FlowSeams()
        val board = seams.board()
        board.onStreamEvent(elicitationFrame("rp", questions = """{"question":"uno"},{"question":"dos"}"""))
        assertTrue(board.answer("q1", "x") is QuestionFlow.Advanced)
        board.resolveByRequestId("rp", "✅ Respondido desde la app.")
        assertNull(board.byShortId("q1"))
        assertEquals(2, seams.edits.size) // the advance edit + the honest seal
        assertNull(board.answer("q1", "y")) // answered from the app: no orphan advance, no respond
        assertEquals(2, seams.edits.size) // nothing further edits the dead message
    }

    @Test
    fun completedQuestionnaireAnswersNoMore() = runBlocking {
        val seams = FlowSeams()
        val board = seams.board()
        board.onStreamEvent(elicitationFrame("rd", questions = """{"question":"uno"}"""))
        assertTrue(board.answer("q1", "x") is QuestionFlow.Completed)
        board.resolve("q1", "✅ Respondido.")
        assertNull(board.answer("q1", "y")) // the double tap: an honest miss
        assertEquals(1, seams.edits.size) // the seal was the last word
    }

    @Test
    fun singleQuestionFlowStaysTheSingularAnswerCase() = runBlocking {
        val seams = FlowSeams()
        val board = seams.board()
        board.onStreamEvent(
            elicitationFrame("rs", questions = """{"question":"¿qué base?","options":[{"value":"main","label":"main"}]}"""),
        )
        val step = board.answer("q1", "main")
        assertTrue(step is QuestionFlow.Completed)
        assertEquals(0, seams.edits.size) // nothing to advance — no edit flew
        val completed = (step as QuestionFlow.Completed).question
        assertEquals(1, completed.questionCount)
        val content = elicitationContentAll(completed.answerEntries(), completed.questionCount)
        assertEquals("main", content["answer"]?.let { (it as JsonPrimitive).content }) // the singular key rides
        assertEquals(elicitationContent("¿qué base?", "main", 1), content) // byte-identical to the shape always flown
    }

    @Test
    fun theFlowRefusesPermissionQuestions() = runBlocking {
        val seams = FlowSeams()
        val board = seams.board()
        board.onStreamEvent(questionFrame("rperm"))
        assertNull(board.answer("q1", "allow"))
        assertEquals(0, seams.edits.size)
    }

    // ---- the any-task law (14/11): WHICH task and WHICH subagent asked ----

    @Test
    fun aSubagentOriginTagRidesTheParsedQuestionAndTheCard() {
        val q = parseQuestionEvent(
            ev(
                """{"type":"elicitation_request","taskId":"t1","requestId":"r9",
                   "questions":[{"question":"¿sigo?","options":[{"value":"si","label":"sí"}]}],
                   "origin":{"kind":"subagent","agentId":"a1","agentType":"general-purpose",
                             "childSessionId":"sess_subagent_a1","parentSessionId":"t1",
                             "description":"audit the migrations"}}""",
            ),
        )!!
        assertEquals("general-purpose · audit the migrations", q.originLabel)
        val text = questionText(q)
        assertTrue("🤖 subagente: general-purpose · audit the migrations" in text)
    }

    @Test
    fun anUnknownOriginShapeIsSilence() {
        val q = parseQuestionEvent(
            ev(
                """{"type":"permission_request","taskId":"t1","requestId":"r10","title":"?",
                   "origin":{"kind":"somethingElse","agentType":"x"}}""",
            ),
        )!!
        assertNull(q.originLabel) // the additive law: a foreign origin renders nothing
    }

    @Test
    fun theTaskTitleRidesTheCardAndTheBoard() = runBlocking {
        val seams = Seams()
        val board = seams.board()
        board.onStreamEvent(questionFrame("rt"), taskTitle = "kap — the detekt laws")
        assertTrue("📋 kap — the detekt laws" in seams.sent.single())
        assertEquals("kap — the detekt laws", board.byShortId("q1")?.taskTitle)
    }

    @Test
    fun pendingTaskIdsListsEveryTaskWithALiveQuestion() = runBlocking {
        val board = Seams().board()
        board.onStreamEvent(questionFrame("ra", taskId = "t1"))
        board.onStreamEvent(questionFrame("rb", taskId = "t2"))
        assertEquals(setOf("t1", "t2"), board.pendingTaskIds())
        board.resolveByRequestId("ra", "✅ Respondido desde la app.")
        assertEquals(setOf("t2"), board.pendingTaskIds())
    }
}
