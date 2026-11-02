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
    fun tasksKeyboardIsGreenRowsWithFlatBlueSubagentLanes() {
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
            subagents = lanes,
        )!!
        val keyboard = kb.jsonObject["inline_keyboard"]!!.jsonArray
        assertEquals(4, keyboard.size) // task1, task2, then the FLAT lanes (the 2/11 law: always visible)
        val first = keyboard[0].jsonArray[0].jsonObject
        assertEquals("🟢 🎯 1 · arreglá el login", first["text"]!!.jsonPrimitive.content)
        assertEquals("t:ses_a", first["callback_data"]!!.jsonPrimitive.content)
        val second = keyboard[1].jsonArray[0].jsonObject
        assertEquals("🟢 🔄 2 · ses_b", second["text"]!!.jsonPrimitive.content)
        assertEquals("t:ses_b", second["callback_data"]!!.jsonPrimitive.content)
        val lane1 = keyboard[2].jsonArray[0].jsonObject
        assertEquals("🔵 Task ▶️", lane1["text"]!!.jsonPrimitive.content)
        assertTrue(lane1["callback_data"]!!.jsonPrimitive.content.startsWith("sa:"))
        // the ✍️ manito stays OUT of /tasks (the owner's 2/11: the unknown
        // manito is noise) — but the ⏹️ is an HONEST action: an open lane
        // carries its stop beside it (the same button /subagents has)
        assertEquals(2, keyboard[2].jsonArray.size)
        assertEquals("⏹️", keyboard[2].jsonArray[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(keyboard[2].jsonArray[1].jsonObject["callback_data"]!!.jsonPrimitive.content.startsWith("sst:"))
        val lane2 = keyboard[3].jsonArray[0].jsonObject
        assertEquals("🔵 Grep ⏹️", lane2["text"]!!.jsonPrimitive.content)
        // the CLOSED lane earns no stop — a stop over a finished child is decoration
        assertEquals(1, keyboard[3].jsonArray.size)
    }

    @Test
    fun tasksKeyboardCarriesTheStoreRunningSubagentsWithTheGhostMark() {
        val kb = Keyboards.tasksKeyboard(
            rows = emptyList(),
            running = emptySet(),
            activeSid = null,
            subagents = emptyList(),
            dbRunning = listOf(
                RunningSubagent(sid = "sess_fresh", title = "root-cause hunter", lastMs = 2_000_000L),
                RunningSubagent(sid = "sess_ghost", title = "killed box agent", lastMs = 2_000_000L - 20 * 60_000),
            ),
            nowMs = 2_000_000L,
        )!!
        val keyboard = kb.jsonObject["inline_keyboard"]!!.jsonArray
        assertEquals(2, keyboard.size)
        val fresh = keyboard[0].jsonArray[0].jsonObject
        assertEquals("🔵 root-cause hunter ▶️", fresh["text"]!!.jsonPrimitive.content)
        assertEquals("sa:sess_fresh", fresh["callback_data"]!!.jsonPrimitive.content)
        // the FRESH row carries its ⏹️ (sst: + a 16-char tail ≈ 20 bytes —
        // the 64-byte law never even breathes)
        assertEquals("⏹️", keyboard[0].jsonArray[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("sst:sess_fresh", keyboard[0].jsonArray[1].jsonObject["callback_data"]!!.jsonPrimitive.content)
        assertTrue(Keyboards.callbackDataValid("sst:sess_fresh"))
        // the GHOST earns no stop — a ⏸️ hace 20m row is the dead agent it renders as
        val ghost = keyboard[1].jsonArray[0].jsonObject
        assertEquals("🔵 killed box agent ⏸️ hace 20m", ghost["text"]!!.jsonPrimitive.content)
        assertEquals(1, keyboard[1].jsonArray.size, "the ghost row has no ⏹️")
    }

    @Test
    fun tasksKeyboardWithoutAnyRowIsNull() {
        assertNull(
            Keyboards.tasksKeyboard(rows = emptyList(), running = emptySet(), activeSid = null, subagents = emptyList()),
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
    fun theVerbosityPickerIsOneRowPerLevelWithTheCurrentMarked() {
        // the /verbosity picker: one row per level, the CURRENT one wearing
        // ✅ — tap = set (the vb: data is the level's canonical lowercase)
        val kb = Keyboards.verbosityPicker(FeedVerbosity.NORMAL)
        val rows = kb.jsonObject["inline_keyboard"]!!.jsonArray
        assertEquals(3, rows.size)
        assertEquals(
            "✅ 🔉 normal — bloques CoT + cierres de tools",
            rows[1].jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals("vb:normal", rows[1].jsonArray[0].jsonObject["callback_data"]!!.jsonPrimitive.content)
        assertEquals("vb:summary", rows[0].jsonArray[0].jsonObject["callback_data"]!!.jsonPrimitive.content)
        assertEquals("vb:verbose", rows[2].jsonArray[0].jsonObject["callback_data"]!!.jsonPrimitive.content)
        // the datas are tiny — the 64-byte law never breathes
        assertTrue(Keyboards.callbackDataValid("vb:summary"))
        assertTrue(Keyboards.callbackDataValid("vb:verbose"))
    }
}
