package dev.lattenero.bot.daemon.spectator
import dev.lattenero.bot.contracts.PyText

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The tool call box renderer — a byte-exact port of messages._tool_box /
 * _tool_box_on / _tool_short_desc (F22bis, F50b, F55b): the COMPLETE
 * command, real colored diff for edits, numbered green lines for writes.
 * Pinned against spectator-golden.json generated from the real Python.
 */
// One function per Python branch — the count is the port's shape.
@Suppress("TooManyFunctions")
internal object ToolBox {

    const val CH_BOX = 3400

    private const val OUTPUT_CAP = 800
    private const val DESC_CAP = 90
    private const val HEAD_DESC_CAP = 120
    private const val READ_DESC_CAP = 160
    private const val PATTERN_CAP = 60
    private const val WRITE_PREVIEW_LINES = 40

    /** No permanent box: step index + live box only (tools_box_filter 'action', the default). */
    private val IDX_ONLY = setOf(
        "Read", "View", "Grep", "Glob", "LS", "TodoWrite", "TodoRead",
        "AskUserQuestion", "Skill", "ListModels", "ListMCPResources",
        "Task", "TaskOutput", "TaskStop",
    )

    /** 'minimal': bash + edits only. */
    private val MINIMAL = setOf("Bash", "Edit", "MultiEdit", "Replace", "Write", "Create", "New", "NotebookEdit")

    private val EDIT_TOOLS = setOf("Edit", "MultiEdit", "Replace")
    private val WRITE_TOOLS = setOf("Write", "Create", "New")
    private val READ_TOOLS = setOf("Read", "View")

    fun boxOn(name: String, filter: String): Boolean {
        val mode = filter.ifEmpty { "action" }
        return when (mode) {
            "all" -> true
            "minimal" -> name in MINIMAL
            else -> name !in IDX_ONLY
        }
    }

    /**
     * The LIVE VIEW's classification (29/10's per-action verbs): the
     * SAME tool families the boxes render by, one answer per name — the
     * channel's verbs ride this, never a second table.
     */
    enum class Family { READ, WRITE, EDIT, SHELL, SEARCH, SUBAGENT, TODO, OTHER }

    fun familyOf(name: String): Family = when {
        name == "Bash" || name == "Shell" -> Family.SHELL
        name in READ_TOOLS -> Family.READ
        name in WRITE_TOOLS -> Family.WRITE
        name in EDIT_TOOLS -> Family.EDIT
        name == "Task" -> Family.SUBAGENT
        name == "Grep" || name == "Glob" -> Family.SEARCH
        name == "TodoWrite" || name == "TodoRead" -> Family.TODO
        else -> Family.OTHER
    }

    /** The file a read/write/edit touches — shortDesc's own path chain (file_path, path). */
    fun filePathOf(inp: JsonObject): String? =
        listOf("file_path", "path").firstNotNullOfOrNull { k ->
            (inp[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        }

    /** One-line desc (step index, live box head). */
    // Python's descending key chain, one return per key (verbatim).
    @Suppress("ReturnCount")
    fun shortDesc(inp: JsonObject): String {
        val cmd = inp["command"]
        if (cmd is JsonArray) return cmd.joinToString(" ") { PyText.pyStr(it) }.let { PyText.take(it, DESC_CAP) }
        if (cmd is JsonPrimitive && cmd.isString) return PyText.take(cmd.content, DESC_CAP)
        for (k in listOf("file_path", "path")) {
            if (PyText.truthy(inp[k])) return PyText.pyStr(inp[k])
        }
        val pattern = inp["pattern"]
        if (PyText.truthy(pattern)) return "pattern " + PyText.take(PyText.pyStr(pattern), PATTERN_CAP)
        return ""
    }

    fun box(name: String, inp: JsonObject): String {
        val desc = shortDesc(inp)
        val head = "🔧 <b>${PyText.esc(name)}</b>" +
            if (desc.isNotEmpty()) " — <code>${PyText.esc(PyText.preview(desc, HEAD_DESC_CAP))}</code>" else ""
        val body = body(name, inp, desc)
        return head + if (body.isNotEmpty()) "\n$body" else ""
    }

    private fun body(name: String, inp: JsonObject, desc: String): String {
        val cmd = commandString(inp)
        return when {
            cmd != null && PyText.strip(cmd).isNotEmpty() -> bashBody(cmd, inp)
            hasEdits(inp) -> editsBody(inp)
            name in EDIT_TOOLS && inp.containsKey("old_string") && inp["old_string"] !is JsonNull ->
                editBody(inp)

            name in WRITE_TOOLS && PyText.truthy(inp["content"]) -> writeBody(inp)
            name in READ_TOOLS -> "<i>reading</i> <code>${PyText.esc(PyText.preview(desc, READ_DESC_CAP))}</code>"
            else -> "<pre>${PyText.esc(PyText.htmlClip(PyText.jsonDumps(inp), CH_BOX))}</pre>"
        }
    }

    /** The command, list items joined like the shell would (str(x) per item). */
    private fun commandString(inp: JsonObject): String? = when (val cmd = inp["command"]) {
        is JsonArray -> cmd.joinToString(" ") { PyText.pyStr(it) }
        is JsonPrimitive -> if (cmd.isString) cmd.content else null
        else -> null
    }

    private fun bashBody(cmd: String, inp: JsonObject): String {
        val out = listOf("output", "result")
            .firstNotNullOfOrNull { k -> inp[k].takeIf { PyText.truthy(it) } }
            ?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
        val b = StringBuilder("<pre>${PyText.esc(PyText.htmlClip(cmd, CH_BOX))}</pre>")
        if (out != null && PyText.strip(out).isNotEmpty()) {
            b.append("\n└ <pre>${PyText.esc(PyText.htmlClip(out, OUTPUT_CAP))}</pre>")
        }
        return b.toString()
    }

    private fun hasEdits(inp: JsonObject): Boolean =
        (inp["edits"] as? JsonArray)?.isNotEmpty() == true

    /** MultiEdit: ONE colored diff per edit (never the raw JSON blob). */
    private fun editsBody(inp: JsonObject): String {
        val edits = inp["edits"] as JsonArray
        val perEditCap = CH_BOX / maxOf(1, edits.size)
        val chunks = edits.mapIndexedNotNull { idx, ed ->
            if (ed !is JsonObject) {
                null
            } else {
                "· edit ${idx + 1}\n" + coloredDiff(
                    PyText.splitLines(PyText.pyStr(ed["old_string"])),
                    PyText.splitLines(PyText.pyStr(ed["new_string"])),
                    perEditCap,
                )
            }
        }
        return "<pre>${PyText.esc(PyText.htmlClip(chunks.joinToString("\n"), CH_BOX))}</pre>"
    }

    private fun editBody(inp: JsonObject): String {
        val diff = coloredDiff(
            PyText.splitLines(PyText.pyStr(inp["old_string"])),
            PyText.splitLines(PyText.pyStr(inp["new_string"])),
            CH_BOX,
        )
        return "<pre>${PyText.esc(PyText.htmlClip(diff, CH_BOX))}</pre>"
    }

    /** A Write is all new: green dots with each of the first 40 lines' REAL number, then the stamp. */
    private fun writeBody(inp: JsonObject): String {
        val lines = PyText.splitLines(PyText.pyStr(inp["content"]))
        val numbered = lines.take(WRITE_PREVIEW_LINES)
            .mapIndexed { i, ln -> "🟢 ${i + 1} $ln" }
            .joinToString("\n")
        val pre = "<pre>${PyText.esc(PyText.htmlClip(numbered, CH_BOX))}</pre>"
        val stamp = linesStamp(lines.size, 0, lines.size)
        return "$pre\n<i>$stamp</i>"
    }

    /** lines_stamp: 🟢 +N · 🔴 −M · K lines (the F50b stamp on every file shown). */
    fun linesStamp(added: Int, removed: Int, lines: Int? = null): String {
        val parts = mutableListOf<String>()
        if (added != 0) parts.add("🟢 +$added")
        if (removed != 0) parts.add("🔴 −$removed")
        lines?.let { parts.add("$it lines") }
        return parts.joinToString(" · ").ifEmpty { "—" }
    }
}
