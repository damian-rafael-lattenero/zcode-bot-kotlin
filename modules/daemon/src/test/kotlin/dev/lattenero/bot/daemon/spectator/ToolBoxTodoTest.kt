package dev.lattenero.bot.daemon.spectator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * THE PLAN, READABLE (the owner, 22/11): a TodoWrite close renders the
 * plan line per line with its status colors — never the raw JSON blob.
 * The spectator path never sees TodoWrite (IDX_ONLY), so these pins own
 * the renderer: the feed's close box is the consumer.
 */
class ToolBoxTodoTest {

    private fun plan(vararg todos: String): JsonObject =
        Json.parseToJsonElement("""{"todos": [${todos.joinToString(",")}]}""") as JsonObject

    private fun boxOf(vararg todos: String): String = ToolBox.box("TodoWrite", plan(*todos))

    @Test
    fun aPlanRendersLinePerLineWithItsStatusColor() {
        val box = boxOf(
            """{"content": "write the sealer", "status": "completed", "priority": "medium"}""",
            """{"content": "run the suite", "status": "in_progress", "priority": "high"}""",
            """{"content": "deploy", "status": "pending"}""",
        )
        val lines = box.split("\n")
        assertEquals(4, lines.size, "head + one line per todo")
        assertTrue(lines[0].contains("<b>TodoWrite</b>") && lines[0].contains("1/3"), "the head speaks the progress: $box")
        assertTrue(lines[0].contains("🔄 1"), "the active count rides while one runs")
        assertTrue(lines[1].startsWith("✅ ") && "<s>write the sealer</s>" in lines[1], "done: struck through")
        assertTrue(lines[2].startsWith("🔄 ") && "🔺 " in lines[2] && "<b>run the suite</b>" in lines[2], "active: bold, high priority marked")
        assertTrue(lines[3].startsWith("▫️ ") && lines[3].contains("deploy"), "waiting: plain")
        assertFalse("<pre>" in box, "the lines live outside pre — inline colors are the point")
    }

    @Test
    fun theContentIsEscapedAndFlattenedToOneLine() {
        val box = boxOf("""{"content": "fix <b>the</b> bug\nsecond line", "status": "pending"}""")
        val lines = box.split("\n")
        assertEquals(2, lines.size, "one todo, ONE line — the content's own newline collapsed")
        assertTrue("&lt;b&gt;" in lines[1], "html stays escaped: ${lines[1]}")
        assertTrue("second line" in lines[1])
    }

    @Test
    fun malformedTodosFallBackToTheRawJsonBody() {
        val empty = boxOf()
        assertTrue("<pre>" in empty, "no todos: the honest raw JSON stays")
        val garbage = boxOf("""{"nosuch": 1}""", """42""", """{"content": "real one", "status": "pending"}""")
        val body = garbage.split("\n").drop(1).joinToString("\n")
        assertTrue("▫️ real one" in body, "non-todo entries are skipped, real ones render: $body")
        assertFalse("42" in body)
    }

    @Test
    fun thePlanCeilingCountsTheRestInsteadOfAWall() {
        val many = (1..50).map { n -> """{"content": "todo $n", "status": "pending"}""" }
        val box = boxOf(*many.toTypedArray())
        val lines = box.split("\n")
        assertEquals(ToolBox.TODO_LINES_MAX + 2, lines.size, "head + the cap + the counter")
        assertTrue(lines.last().contains("+10 more"), "the tail speaks what it hid")
    }

    @Test
    fun aLongContentLineIsPreviewsNotDumped() {
        val long = "x".repeat(400)
        val box = boxOf("""{"content": "$long", "status": "pending"}""")
        assertTrue("…" in box, "the line carries the ellipsis")
        assertFalse(long in box, "never the whole wall")
    }
}
