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

        fun board(): QuestionBoard = QuestionBoard(
            sendQuestion = { text, _ ->
                sent.add(text)
                nextMid
            },
            editQuestion = { mid, text, _ -> edits.add(mid to text) },
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
    fun resolveTaskSealsEveryPendingQuestionOfTheTask() = runBlocking {
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
    fun theRegistryCapEvictsTheOldest() = runBlocking {
        val seams = Seams()
        val board = seams.board()
        for (i in 1..QuestionBoard.REGISTRY_CAP + 1) {
            board.onStreamEvent(questionFrame("r$i"))
        }
        assertNull(board.byShortId("q1")) // the eldest evicted
        assertNotNull(board.byShortId("q${QuestionBoard.REGISTRY_CAP + 1}"))
    }
}
