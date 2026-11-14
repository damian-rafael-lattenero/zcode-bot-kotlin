package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.PendingQuestion
import dev.lattenero.bot.channel.QuestionItem
import dev.lattenero.bot.channel.QuestionOption
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The question board's persistence (the queue's and LastPrompt's exact
 * pattern): the registry survives the daemon's restart round-trip with
 * EVERY field, the absent/garbage/torn file degrades to an EMPTY board
 * (never a crash — the SendKnob law), and no tmp litter is left behind.
 */
class FileQuestionBoardStoreTest {

    @TempDir
    lateinit var dir: File

    private fun permissionQuestion(requestId: String, mid: Long = 5L): PendingQuestion = PendingQuestion(
        taskId = "t1",
        requestId = requestId,
        kind = "permission",
        header = "🔐 Permiso solicitado",
        title = "¿corro?",
        options = listOf(
            QuestionOption("allow", "Permitir", response = buildJsonObject { put("decision", "allow") }),
            QuestionOption("deny", "Denegar", isDeny = true),
        ),
        mid = mid,
    )

    @Test
    fun theRegistrySurvivesTheRestartRoundTripWithEveryField() {
        val file = File(dir, "question-board.json")
        val entries = linkedMapOf(
            "q1" to permissionQuestion("r1", mid = 7L),
            "q2" to PendingQuestion(
                taskId = "t2",
                requestId = "r2",
                kind = "elicitation",
                header = "❓ Pregunta",
                title = "¿qué base?",
                options = listOf(QuestionOption("main", "main")),
                plan = "1. el plan",
                questionCount = 2,
                questions = listOf(
                    QuestionItem("Elegí", "¿qué base?", listOf(QuestionOption("main", "main"))),
                    QuestionItem("Notas", "¿notas?", emptyList()),
                ),
                questionIndex = 1,
                answers = mapOf(0 to "main"),
                taskTitle = "kap — the detekt laws",
                originLabel = "general-purpose · audit the migrations",
            ),
        )
        val store = FileQuestionBoardStore(file)
        store.save(entries)

        // a NEW instance (the restart's boot): the file's truth comes back, ORDER included
        val loaded = FileQuestionBoardStore(file).load()
        assertEquals(entries.keys.toList(), loaded.keys.toList())
        assertEquals(entries["q1"], loaded["q1"])
        assertEquals(entries["q2"], loaded["q2"])
        assertTrue(!File(dir, "question-board.json.tmp").exists()) // no tmp litter
    }

    @Test
    fun anAbsentOrGarbageFileIsAnEmptyBoard() {
        assertTrue(FileQuestionBoardStore(File(dir, "absent.json")).load().isEmpty())
        val garbage = File(dir, "garbage.json")
        garbage.writeText("""[{"shortId":"q1", RUIDO""")
        assertTrue(FileQuestionBoardStore(garbage).load().isEmpty())
        // a torn ENTRY inside a valid array is skipped; the good ones survive
        val torn = File(dir, "torn.json")
        torn.writeText(
            """[{"shortId":"q0","title":"sin espina"},"cuerda",""" +
                """{"shortId":"q1","taskId":"t1","requestId":"r1","kind":"permission"}]""",
        )
        val loaded = FileQuestionBoardStore(torn).load()
        assertEquals(listOf("q1"), loaded.keys.toList())
    }

    @Test
    fun theWriteRefusesWithoutCrashingWhenTheFileIsADirectory() {
        val blocker = File(dir, "blocked")
        assertTrue(blocker.mkdirs(), "the 'file' is a directory — the write dies")
        val store = FileQuestionBoardStore(blocker)
        store.save(mapOf("q1" to permissionQuestion("r1"))) // degrade, never throw
        assertTrue(store.load().isEmpty()) // the 'file' is unreadable — the honest empty
    }
}
