package dev.lattenero.bot.daemon.history

import dev.lattenero.bot.channel.LineDiffer
import dev.lattenero.bot.channel.ToolVerb
import dev.lattenero.bot.channel.ToolVerbifier
import dev.lattenero.bot.daemon.spectator.SequenceMatcher
import dev.lattenero.bot.daemon.spectator.ToolBox
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The live view's VERBS, backed by the SPECTATOR's own classification
 * (the owner, 29/10: «cada thinking debe ser distinto: el thinking
 * general, el thinking por read, por write, y por cada accion que tenga
 * zcode, mcps, shell etc») — REUSED, never duplicated: [ToolBox.familyOf]
 * is the SAME table the final boxes render by.
 */
val LIVE_VERBS: ToolVerbifier = ToolVerbifier { name, input, isMcp ->
    when {
        isMcp || name.startsWith("mcp__") ->
            ToolVerb("🔌", "mcp", name.substringAfter("mcp__", name))

        else -> when (ToolBox.familyOf(name)) {
            ToolBox.Family.READ -> ToolVerb("📖", "reading", input?.let(ToolBox::filePathOf).orEmpty())
            ToolBox.Family.WRITE -> ToolVerb("✏️", "writing", input?.let(ToolBox::filePathOf).orEmpty())
            ToolBox.Family.EDIT -> ToolVerb("✏️", "editing", input?.let(ToolBox::filePathOf).orEmpty())
            ToolBox.Family.SHELL -> ToolVerb("⚡", "shell", input?.let(::cmdOf).orEmpty())
            ToolBox.Family.SUBAGENT -> ToolVerb("🤖", "subagent")
            ToolBox.Family.SEARCH -> ToolVerb("🔎", "searching", input?.let(::patternOf).orEmpty())
            ToolBox.Family.TODO -> ToolVerb("📋", "todos")
            ToolBox.Family.OTHER -> ToolVerb("🔧", name, input?.let(ToolBox::filePathOf).orEmpty())
        }
    }
}

/** The command a shell run previews. */
private fun cmdOf(input: JsonObject): String? {
    val text = when (val cmd = input["command"]) {
        is JsonArray -> cmd.joinToString(" ") { (it as? JsonPrimitive)?.content ?: it.toString() }
        is JsonPrimitive -> cmd.content
        else -> return null
    }
    return text.takeIf { it.isNotBlank() }
}

private fun patternOf(input: JsonObject): String? =
    (input["pattern"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

/**
 * The edit's final +N/−M through the byte-exact SEQUENCE MATCHER port
 * (difflib's opcodes: INSERT+REPLACE's j-side is added, DELETE+REPLACE's
 * i-side is removed) — the same engine the final boxes render with.
 */
val SEQUENCE_LINE_DIFFER: LineDiffer = LineDiffer { oldText, newText ->
    val a = oldText.lines()
    val b = newText.lines()
    var added = 0
    var removed = 0
    for (op in SequenceMatcher(a, b).opcodes()) {
        when (op.tag) {
            SequenceMatcher.Tag.INSERT -> added += op.j2 - op.j1
            SequenceMatcher.Tag.DELETE -> removed += op.i2 - op.i1

            // REPLACE is both sides: the old lines died, the new ones were born
            SequenceMatcher.Tag.REPLACE -> {
                added += op.j2 - op.j1
                removed += op.i2 - op.i1
            }

            SequenceMatcher.Tag.EQUAL -> {}
        }
    }
    added to removed
}
