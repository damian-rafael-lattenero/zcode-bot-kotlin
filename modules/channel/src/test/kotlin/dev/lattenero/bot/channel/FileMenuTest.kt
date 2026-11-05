package dev.lattenero.bot.channel

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * THE FILE VIEWER (the owner, 5/11): a write/edit close's «📄 archivo
 * completo» reads the file FROM DISK AT TAP TIME — his explicit choice —
 * and pages it in place with REAL line numbers (the line-number law).
 * Every page tap is a FRESH re-read: the viewer's truth is the disk's,
 * not the close's frozen copy.
 */
class FileMenuTest {

    private class RecordingMenu(
        var disk: String?,
        var clockMs: Long = 1_000_000L,
    ) {
        val sent = mutableListOf<Pair<String, JsonObject?>>()
        val edits = mutableListOf<Triple<Long, String, JsonObject?>>()
        var nextMid = 50L

        val menu = FileMenu(
            read = { disk },
            edit = { mid, text, markup ->
                edits += Triple(mid, text, markup)
                true
            },
            send = { text, markup ->
                sent += text to markup
                nextMid++
            },
            clock = { clockMs },
        )
    }

    @Test
    fun theButtonRegistersIdempotentlyAndSpeaksTheDoor() {
        val rec = RecordingMenu(disk = "una línea")
        val first = rec.menu.buttonFor("/w/Main.kt")
        val second = rec.menu.buttonFor("/w/Main.kt")
        assertTrue("\"📄 archivo completo\"" in first.toString())
        assertTrue("fvm:1" in first.toString(), "the id rides the callback: $first")
        assertEquals(first, second, "the same path is the same door — one viewer, not many")
    }

    @Test
    fun theFirstTapReadsFreshAndSpeaksNumberedPages() = runTest {
        val rec = RecordingMenu(disk = listOf("a", "b", "c").joinToString("\n"))
        val markup = rec.menu.buttonFor("/w/Main.kt")
        val id = "fvm:(\\d+)".toRegex().find(markup.toString())!!.groupValues[1].toInt()
        assertEquals(null, rec.menu.open(id), "the send IS the feedback")
        val (text, pager) = rec.sent.single()
        assertTrue("📄 <b>/w/Main.kt</b>" in text, "the path rides the header: $text")
        assertTrue("fresco al toque" in text, "the freshness law speaks in the header")
        assertTrue("   1│ a" in text, "the line-number law: $text")
        assertNotNull(pager)
        assertTrue("fvp:$id:0" in pager.toString(), "the pager's first page: $pager")
    }

    @Test
    fun everyPageTapIsAFreshReRead() = runTest {
        val rec = RecordingMenu(disk = "original")
        val markup = rec.menu.buttonFor("/w/Main.kt")
        val id = "fvm:(\\d+)".toRegex().find(markup.toString())!!.groupValues[1].toInt()
        rec.menu.open(id)
        // the file CHANGED after the viewer opened — the page tap sees the NEW disk
        rec.disk = "cambió en la Mac"
        assertEquals(null, rec.menu.page(id, 0))
        val (mid, text, _) = rec.edits.single()
        assertEquals(50L, mid, "the edit lands on the SAME viewer message")
        assertTrue("cambió en la Mac" in text, "fresh at tap time — his choice: $text")
        assertTrue("original" !in text)
    }

    @Test
    fun theUnknownIdAndTheVanishedFileAnswerHonestly() = runTest {
        val rec = RecordingMenu(disk = null)
        assertTrue("otro turno" in (rec.menu.open(99) ?: ""))
        rec.menu.buttonFor("/w/gone.kt")
        assertTrue("No pude leer" in (rec.menu.open(1) ?: ""), "a vanished file is not a lie about it")
    }

    @Test
    fun theViewerThatDiedReopensWhole() = runTest {
        val rec = RecordingMenu(disk = "x")
        rec.menu.buttonFor("/w/Main.kt")
        // a viewer whose message is gone (restart) re-opens as a NEW send
        assertEquals(null, rec.menu.page(1, 0))
        assertTrue(rec.sent.isNotEmpty(), "the page tap fell back to a fresh open")
    }
}
