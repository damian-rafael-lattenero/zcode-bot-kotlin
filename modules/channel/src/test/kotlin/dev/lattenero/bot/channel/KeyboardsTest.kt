package dev.lattenero.bot.channel

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The buttons — Python's R5 vocabulary pinned: the callback table (the
 * exact texts Python's callbacks.py maps), the 64-byte law, and the
 * keyboard builders (🟢 tasks, 🔵 subagents with ▶️/⏹️, the ☢️ row
 * Python's handlers_system sends).
 */
class KeyboardsTest {

    @Test
    fun callbackTableIsPythonsR5() {
        assertEquals("/nuclear go", Keyboards.callbackText("nuc:go"))
        assertEquals("/nuclear no", Keyboards.callbackText("nuc:no"))
        assertEquals("/stop go", Keyboards.callbackText("stop:go"))
        assertEquals("/task @ses_abc123", Keyboards.callbackText("t:ses_abc123"))
        assertEquals("/task @task-77", Keyboards.callbackText("t:task-77"))
        assertEquals("/subagents", Keyboards.callbackText("sa:toolu_123"))
        assertEquals("planfix", Keyboards.callbackText("planfix")) // free text, the historical default
    }

    @Test
    fun callbackDataRespectsTelegrams64Bytes() {
        assertTrue(Keyboards.callbackDataValid("t:" + "session_0f84de0cf002474fe596446e627129dc"))
        assertTrue(!Keyboards.callbackDataValid("t:" + "x".repeat(64)))
        assertTrue(!Keyboards.callbackDataValid(""))
        assertTrue(!Keyboards.callbackDataValid("t:se s")) // whitespace never rides the wire
    }

    @Test
    fun tasksKeyboardIsGreenRowsWithBlueSubagentLanes() {
        val rows = listOf(
            TaskRow(sid = "ses_a", title = "arreglá el login", ws = "/w/app"),
            TaskRow(sid = "ses_b", title = "", ws = "/w/app"),
        )
        val lanes = listOf(
            ToolLive(toolId = "toolu_running", name = "Task", parentToolUseId = "p", openedAtMs = 1),
            ToolLive(toolId = "toolu_closed", name = "Grep", parentToolUseId = "p", openedAtMs = 1, status = "completed"),
        )
        val kb = Keyboards.tasksKeyboard(
            rows = rows,
            running = setOf("ses_b"),
            activeSid = "ses_a",
            watchedTaskId = "ses_a",
            subagents = lanes,
        )!!
        val keyboard = kb.jsonObject["inline_keyboard"]!!.jsonArray
        assertEquals(4, keyboard.size) // task1, its 2 nested lanes, then task2
        val first = keyboard[0].jsonArray[0].jsonObject
        assertEquals("🟢 🎯 1 · arreglá el login", first["text"]!!.jsonPrimitive.content)
        assertEquals("t:ses_a", first["callback_data"]!!.jsonPrimitive.content)
        val lane1 = keyboard[1].jsonArray[0].jsonObject
        assertEquals("🔵 Task ▶️", lane1["text"]!!.jsonPrimitive.content)
        assertTrue(lane1["callback_data"]!!.jsonPrimitive.content.startsWith("sa:"))
        val lane2 = keyboard[2].jsonArray[0].jsonObject
        assertEquals("🔵 Grep ⏹️", lane2["text"]!!.jsonPrimitive.content)
        val second = keyboard[3].jsonArray[0].jsonObject
        assertEquals("🟢 🔄 2 · ses_b", second["text"]!!.jsonPrimitive.content)
        assertEquals("t:ses_b", second["callback_data"]!!.jsonPrimitive.content)
    }

    @Test
    fun tasksKeyboardWithoutRowsIsNull() {
        assertNull(
            Keyboards.tasksKeyboard(rows = emptyList(), running = emptySet(), activeSid = null, watchedTaskId = null, subagents = emptyList()),
        )
    }

    @Test
    fun nuclearConfirmIsPythonsExactRow() {
        val row = Keyboards.nuclearConfirm().jsonObject["inline_keyboard"]!!.jsonArray[0].jsonArray
        assertEquals("☢️ Confirm", row[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("nuc:go", row[0].jsonObject["callback_data"]!!.jsonPrimitive.content)
        assertEquals("🙅 Cancel", row[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("nuc:no", row[1].jsonObject["callback_data"]!!.jsonPrimitive.content)
    }

    @Test
    fun stopConfirmIsTheSkullRow() {
        val row = Keyboards.stopConfirm().jsonObject["inline_keyboard"]!!.jsonArray[0].jsonArray
        assertEquals("💀 Stop EVERYTHING", row[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("stop:go", row[0].jsonObject["callback_data"]!!.jsonPrimitive.content)
    }

    @Test
    fun buttonIsPythonsShape() {
        val b: JsonObject = Keyboards.button("x", "y")
        assertEquals("x", b["text"]!!.jsonPrimitive.content)
        assertEquals("y", b["callback_data"]!!.jsonPrimitive.content)
    }

    @Test
    fun cotBlocksButtonsAreTheBlockRow() {
        // Python's box_buttons reborn as cb:: one «📖 CoT N» per sealed
        // block, the EXPANDED one wearing ⤴️ (the SAME tap folds it)
        val kb = Keyboards.cotBlocksButtons(count = 2, expanded = null)!!
        val keyboard = kb.jsonObject["inline_keyboard"]!!.jsonArray
        assertEquals(1, keyboard.size) // 2 blocks: one row of 2
        val b1 = keyboard[0].jsonArray[0].jsonObject
        assertEquals("📖 CoT 1", b1["text"]!!.jsonPrimitive.content)
        assertEquals("cb:1", b1["callback_data"]!!.jsonPrimitive.content)
        val expanded = Keyboards.cotBlocksButtons(count = 2, expanded = 2)!!
        val row = expanded.jsonObject["inline_keyboard"]!!.jsonArray[0].jsonArray
        assertEquals("📖 CoT 1", row[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("⤴️ CoT 2", row[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("cb:2", row[1].jsonObject["callback_data"]!!.jsonPrimitive.content)
    }

    @Test
    fun cotBlocksKeyboardShowsOnlyTheMostRecent8InRowsOf4() {
        val kb = Keyboards.cotBlocksButtons(count = 10, expanded = null)!!
        val keyboard = kb.jsonObject["inline_keyboard"]!!.jsonArray
        assertEquals(2, keyboard.size) // 8 buttons in rows of 4
        assertEquals(4, keyboard[0].jsonArray.size)
        assertEquals(4, keyboard[1].jsonArray.size)
        assertEquals("📖 CoT 3", keyboard[0].jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("cb:3", keyboard[0].jsonArray[0].jsonObject["callback_data"]!!.jsonPrimitive.content)
        assertEquals("cb:10", keyboard[1].jsonArray[3].jsonObject["callback_data"]!!.jsonPrimitive.content)
        // the datas are tiny — the 64-byte law never breathes
        assertTrue(Keyboards.callbackDataValid("cb:10"))
    }

    @Test
    fun cotBlocksKeyboardIsNullWhileNothingSealed() {
        assertNull(Keyboards.cotBlocksButtons(count = 0, expanded = null))
    }
}
