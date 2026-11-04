package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        // the name tap opens the DETAIL CARD (the full uncapped name + actions)
        assertTrue(lane1["callback_data"]!!.jsonPrimitive.content.startsWith("sn:"))
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
    fun tasksKeyboardSkipsTheStoreGhostsAndCarriesTheFreshOnes() {
        // the owner's 3/11: two «MISSION:» ghosts of a killed batch rode
        // every /tasks as blue buttons — a stale store row is a DEAD agent
        // the CLI never closed; /subagents «inactivos» is its only home
        val kb = Keyboards.tasksKeyboard(
            rows = emptyList(),
            running = emptySet(),
            activeSid = null,
            subagents = emptyList(),
            dbRunning = listOf(
                RunningSubagent(sid = "sess_fresh", title = "root-cause hunter", lastMs = 2_000_000L),
                RunningSubagent(sid = "sess_ghost", title = "MISSION: killed box agent", lastMs = 2_000_000L - 20 * 60_000),
            ),
            nowMs = 2_000_000L,
        )!!
        val keyboard = kb.jsonObject["inline_keyboard"]!!.jsonArray
        assertEquals(1, keyboard.size, "the ghost earns NO row at all")
        val fresh = keyboard[0].jsonArray[0].jsonObject
        assertEquals("🔵 root-cause hunter ▶️", fresh["text"]!!.jsonPrimitive.content)
        assertEquals("sn:sess_fresh", fresh["callback_data"]!!.jsonPrimitive.content)
        // the FRESH row carries its ⏹️ (sst: + a 16-char tail ≈ 20 bytes —
        // the 64-byte law never even breathes)
        assertEquals("⏹️", keyboard[0].jsonArray[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("sst:sess_fresh", keyboard[0].jsonArray[1].jsonObject["callback_data"]!!.jsonPrimitive.content)
        assertTrue(Keyboards.callbackDataValid("sst:sess_fresh"))
    }

    @Test
    fun aKeyboardOfOnlyGhostsIsNoKeyboardAtAll() {
        val now = 10_000_000L
        assertNull(
            Keyboards.tasksKeyboard(
                rows = emptyList(),
                running = emptySet(),
                activeSid = null,
                subagents = emptyList(),
                dbRunning = listOf(
                    RunningSubagent("sess_ghost", "MISSION: one", lastMs = now - 60 * 60_000),
                    RunningSubagent("sess_ghost2", "MISSION: two", lastMs = now - 99 * 60_000),
                ),
                nowMs = now,
            ),
            "nothing alive and no rows → no keyboard (silence, not a ghost)",
        )
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
    fun deployTargetsIsTheOneTapRow() {
        // the ONE-TAP LAW (4/11): the target row IS the deploy — no go/no
        // confirm half exists anymore (the tap fires the door)
        val row = Keyboards.deployTargets().jsonObject["inline_keyboard"]!!.jsonArray[0].jsonArray
        assertEquals("🤖 Bot Kotlin", row[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("dep:bot", row[0].jsonObject["callback_data"]!!.jsonPrimitive.content)
        assertEquals("🍴 ZCode Fork", row[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("dep:fork", row[1].jsonObject["callback_data"]!!.jsonPrimitive.content)
        assertEquals("🚀 Ambos", row[2].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("dep:both", row[2].jsonObject["callback_data"]!!.jsonPrimitive.content)
        // the datas ride the 64-byte law with room to spare
        assertTrue(Keyboards.callbackDataValid("dep:bot"))
        assertTrue(Keyboards.callbackDataValid("dep:fork"))
        assertTrue(Keyboards.callbackDataValid("dep:both"))
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

    // ---- the guide's laws (docs/ui-style-guide.md) — pinned on the builders --

    /** (text, callback_data) of every button, row-flattened. */
    private fun buttons(kb: JsonObject): List<Pair<String, String>> =
        kb.jsonObject["inline_keyboard"]!!.jsonArray
            .flatMap { it.jsonArray }
            .map { b ->
                b.jsonObject["text"]!!.jsonPrimitive.content to
                    b.jsonObject["callback_data"]!!.jsonPrimitive.content
            }

    /** The keyboard's ROWS — the order law reads row by row, never flattened. */
    private fun rowsOf(kb: JsonObject): List<List<Pair<String, String>>> =
        kb.jsonObject["inline_keyboard"]!!.jsonArray
            .map { row -> row.jsonArray.map { b -> b.jsonObject["text"]!!.jsonPrimitive.content to b.jsonObject["callback_data"]!!.jsonPrimitive.content } }

    /** Every builder with LIVE rows, dead lanes and a ghost — the sweep's fixture. */
    private fun allKeyboards(): List<JsonObject> {
        val lanes = listOf(
            ToolLive(toolId = "toolu_open", name = "Task", parentToolUseId = null, openedAtMs = 1),
            ToolLive(toolId = "toolu_done", name = "Grep", parentToolUseId = null, openedAtMs = 1, status = "completed"),
        )
        val dbs = listOf(
            RunningSubagent(sid = "sess_fresh1", title = "hunter", lastMs = 1_880_000L),
            RunningSubagent(sid = "sess_ghost1", title = "ghost", lastMs = 2_000_000L - 20 * 60_000),
        )
        val rows = listOf(TaskRow(sid = "ses_a", title = "t", ws = "/w"))
        val queue = listOf(QueuedText(text = "hola", messageId = "tg-42", receivedAt = 1L))
        return listOfNotNull(
            Keyboards.tasksKeyboard(rows, setOf("ses_a"), "ses_a", lanes, dbs, 2_000_000L),
            Keyboards.subagentsKeyboard(lanes, dbs, inactiveView = false, nowMs = 2_000_000L),
            Keyboards.subagentsKeyboard(lanes, dbs, inactiveView = true, nowMs = 2_000_000L),
            Keyboards.subagentDetailKeyboard(alive = true, tail = "sess_fresh1"),
            Keyboards.queueKeyboard(queue),
            Keyboards.verbosityPicker(FeedVerbosity.NORMAL),
            Keyboards.cotToggle(7, expanded = false),
            Keyboards.cotToggle(7, expanded = true),
            Keyboards.interruptQuestion("ses_0f84de0cf002474fe596446e627129dc"),
            Keyboards.nuclearConfirm(),
            Keyboards.stopConfirm(),
            Keyboards.subagentsViewButtons(inactiveView = false),
            Keyboards.subagentsViewButtons(inactiveView = true),
        )
    }

    @Test
    fun actionRowsNeverLeadWithTheDestructiveEnd() {
        // the guide's order law: read → arm → run → stop; 🗑/💀/☢️ (and the
        // ⏹️ stop tap) LAST in their row — never first. The pinned exceptions
        // are the confirmation rows, which this sweep deliberately excludes.
        val actionKeyboards = allKeyboards().filter { kb ->
            val texts = buttons(kb).map { it.first }
            texts.any { it == "⏹️" || it == "🗑" || "✍️" in it }
        }
        assertTrue(actionKeyboards.size >= 4, "the action keyboards must be in the sweep")
        actionKeyboards.flatMap(::rowsOf).forEach { row ->
            val firstData = row.first().second
            assertTrue(
                !firstData.startsWith("sst:") && !firstData.startsWith("qd:"),
                "the destructive end led a row: $row",
            )
        }
    }

    @Test
    fun theDetailCardActionRowIsTheGuideSReferenceOrder() {
        // 👁 attach · ✍️ orden · ⏹️ stop — the reference row every other row aligns to
        val row = rowsOf(Keyboards.subagentDetailKeyboard(alive = true, tail = "abc")!!).single()
        assertEquals(
            listOf("👁 attach" to "sa:abc", "✍️ orden" to "so:abc", "⏹️" to "sst:abc"),
            row,
        )
    }

    @Test
    fun rowLabelsRespectTheCaps() {
        // the guide's cap law: the ROW caps the name at 34 — a longíssimo
        // name only ever fills the DETAIL CARD, never a button label
        val long = "título larguísimo que se pasa de cualquier fila ".repeat(4)
        val lanes = listOf(
            ToolLive(
                toolId = "toolu_open",
                name = "Task",
                parentToolUseId = null,
                openedAtMs = 1,
                input = buildJsonObject { put("description", JsonPrimitive(long)) },
            ),
        )
        val kb = Keyboards.tasksKeyboard(
            rows = listOf(TaskRow(sid = "ses_a", title = long, ws = "/w")),
            running = setOf("ses_a"),
            activeSid = "ses_a",
            subagents = lanes,
            dbRunning = listOf(RunningSubagent(sid = "sess_b", title = long, lastMs = 2_000_000L - 20 * 60_000)),
            nowMs = 2_000_000L,
        )!!
        val labels = buttons(kb).map { it.first }
        val task = labels.first { it.startsWith("🟢") }
        assertTrue(PyText.cpLength(task) <= 46, "task row over cap: ${PyText.cpLength(task)}")
        val lane = labels.first { it.startsWith("🔵") && it.endsWith("▶️") }
        assertTrue(PyText.cpLength(lane) <= 40, "lane row over cap: ${PyText.cpLength(lane)}")
        // the stale store row is a GHOST: it earns no label at all (3/11)
        assertTrue(labels.none { "⏸️" in it }, "a ghost never reaches a task list: $labels")
        val queue = buttons(Keyboards.queueKeyboard(listOf(QueuedText(long, "tg-1", 1L)))).first().first
        assertEquals(28, PyText.cpLength(queue), "queue preview over cap: $queue")
    }

    @Test
    fun theDestructiveEndsAndTheMarksKeepOneButtonShapeEach() {
        // the guide's one-meaning law, on the buttons: a bare glyph is THE
        // action it owns, and the destructive ends appear in exactly one shape
        allKeyboards().flatMap(::buttons).forEach { (text, data) ->
            if (text == "⏹️") assertTrue(data.startsWith("sst:"), "bare ⏹️ must be a stop tap: $data")
            if (text == "🗑") assertTrue(data.startsWith("qd:"), "bare 🗑 must be a discard tap: $data")
            if ("💀" in text) assertEquals("stop:go", data, "💀 owns /stop go's row: $data")
            if ("☢️" in text) assertTrue(data.startsWith("nuc:"), "☢️ owns the nuclear row: $data")
            if ("✍️" in text) assertTrue(data.startsWith("so:"), "✍️ arms an order, nothing else: $data")
            if ("👁" in text) assertTrue(data.startsWith("sa:"), "👁 attaches, nothing else: $data")
            if ("🚀" in text) assertTrue(data.startsWith("ql:"), "🚀 launches a parked text: $data")
            if (text.startsWith("⚡")) assertTrue(data.startsWith("intr:"), "⚡ interrupts: $data")
            if (text.startsWith("🟢")) assertTrue(data.startsWith("t:"), "🟢 rows attach tasks: $data")
            if (text.startsWith("🔵")) assertTrue(data.startsWith("sn:"), "🔵 rows open the detail card: $data")
        }
    }

    @Test
    fun everyBuilderDataRidesThe64ByteLaw() {
        allKeyboards().flatMap(::buttons).forEach { (_, data) ->
            assertTrue(Keyboards.callbackDataValid(data), "illegal callback_data: \"$data\"")
        }
    }
}
