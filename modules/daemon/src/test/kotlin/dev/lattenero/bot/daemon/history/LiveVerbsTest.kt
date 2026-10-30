package dev.lattenero.bot.daemon.history

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The REAL verbs — the spectator's own classification (ToolBox.familyOf)
 * mapped to the live view's vocabulary, and the byte-exact SequenceMatcher
 * line differ behind the edit's +N/−M stamp.
 */
class LiveVerbsTest {

    @Test
    fun theFamiliesCarryTheirOwnEmojiAndVerb() {
        // reading: the file rides the verb's detail
        val read = LIVE_VERBS.of("Read", buildJsonObject { put("file_path", "/w/Main.kt") }, isMcp = false)
        assertEquals("📖", read.emoji)
        assertEquals("reading", read.verb)
        assertEquals("/w/Main.kt", read.detail)

        val write = LIVE_VERBS.of("Write", buildJsonObject { put("file_path", "/w/New.kt") }, isMcp = false)
        assertEquals("✏️", write.emoji)
        assertEquals("writing", write.verb)

        val edit = LIVE_VERBS.of("Edit", buildJsonObject { put("file_path", "/w/Main.kt") }, isMcp = false)
        assertEquals("editing", edit.verb)

        val shell = LIVE_VERBS.of("Bash", buildJsonObject { put("command", "npm test") }, isMcp = false)
        assertEquals("⚡", shell.emoji)
        assertEquals("shell", shell.verb)
        assertEquals("npm test", shell.detail)

        val sub = LIVE_VERBS.of("Task", null, isMcp = false)
        assertEquals("🤖", sub.emoji)
        assertEquals("subagent", sub.verb)

        val mcp = LIVE_VERBS.of("anything", null, isMcp = true)
        assertEquals("🔌", mcp.emoji)
        assertEquals("mcp", mcp.verb)

        val other = LIVE_VERBS.of("WebFetch", null, isMcp = false)
        assertEquals("🔧", other.emoji)
        assertEquals("WebFetch", other.verb)
    }

    @Test
    fun theSequenceDifferIsDifflibExact() {
        // replace 1 + insert 1 = +2; delete of b = −1
        val (added, removed) = SEQUENCE_LINE_DIFFER.of("a\nb\nc", "a\nX\nc\nd")
        assertEquals(2, added)
        assertEquals(1, removed)
        // pure insert: y and z are born (x was already there)
        assertEquals(2 to 0, SEQUENCE_LINE_DIFFER.of("x", "x\ny\nz"))
        // pure delete
        assertEquals(0 to 2, SEQUENCE_LINE_DIFFER.of("x\ny\nz\nq\nr", "x\ny\nz"))
        // identical
        assertEquals(0 to 0, SEQUENCE_LINE_DIFFER.of("same\nlines", "same\nlines"))
    }
}
