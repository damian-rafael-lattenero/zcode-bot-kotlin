package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE OUTPUT PAGER's tap world (the owner, 3/11): one button speaks the
 * collapsed output, a tap opens it IN PLACE with numbered pages, page
 * buttons flip it, the fold returns the header — a stale button answers
 * the honest miss.
 */
class BoxPagesTest {

    private val pages = ToolOutputPages.Pages(
        header = "⚡ <b>Bash</b> — <code>ls</code> · 2 líneas\n<i>📜 tocar para ver</i>",
        pages = listOf("   1│ uno\n   2│ dos", "   3│ tres"),
    )

    private class Rig {
        var sent: JsonObject? = null
        var editText: String? = null
        var editMarkup: JsonObject? = null
        var editOk = true
        val box = BoxPages { _, text, markup ->
            editText = text
            editMarkup = markup
            editOk
        }
    }

    private fun datas(markup: JsonObject?): List<String> = markup!!.jsonObject["inline_keyboard"]!!.jsonArray
        .flatMap { row -> row.jsonArray.map { it.jsonObject["callback_data"]!!.jsonPrimitive.content } }

    @Test
    fun speakRegistersAndTheTapOpensThePageInPlace() = runBlocking {
        val rig = Rig()
        rig.box.speakCollapsed(pages) { markup -> rig.sent = markup; 77L }
        assertEquals(listOf("bp:1:0"), datas(rig.sent))

        assertNull(rig.box.open(1, 1)) // null = the toast is silence
        assertTrue(rig.editText!!.contains("   3│ tres"))
        assertTrue(rig.editText!!.startsWith("⚡ <b>Bash</b>"))
        val pageDatas = datas(rig.editMarkup)
        assertTrue("bp:1:0" in pageDatas && "bp:1:1" in pageDatas)
        assertTrue("bf:1" in pageDatas)
    }

    @Test
    fun anOutOfRangePageClampsToTheLastOne() = runBlocking {
        val rig = Rig()
        rig.box.speakCollapsed(pages) { 77L }
        assertNull(rig.box.open(1, 99))
        assertTrue(rig.editText!!.contains("   3│ tres"))
    }

    @Test
    fun theFoldReturnsTheHeader() = runBlocking {
        val rig = Rig()
        rig.box.speakCollapsed(pages) { 77L }
        assertNull(rig.box.fold(1))
        assertEquals(pages.header, rig.editText)
        assertEquals(listOf("bp:1:0"), datas(rig.editMarkup))
    }

    @Test
    fun aStaleButtonAnswersTheHonestMiss() = runBlocking {
        val rig = Rig()
        assertEquals(BoxPages.MISS, rig.box.open(99, 0))
        assertEquals(BoxPages.MISS, rig.box.fold(99))
    }

    @Test
    fun aBlockedEditFlipsNothing() = runBlocking {
        val rig = Rig().apply { editOk = false }
        rig.box.speakCollapsed(pages) { 77L }
        assertNull(rig.box.open(1, 1)) // the toast stays silent, the message keeps its page
        assertTrue("   3│ tres" in rig.editText!!) // the edit was attempted, just refused
    }
}
