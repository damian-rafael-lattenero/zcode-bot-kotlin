package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The side-by-side's pure core: the bot's own diff format and the standard
 * one both parse, changes pair by index, and the changed middle is what the
 * drawer emphasises.
 */
class DiffShotTest {

    @Test
    fun `the bot's numbered format pairs a deletion with its replacement`() {
        val plan = DiffShot.rows("- 4 │ val old = 1\n+ 5 │ val new = 2\n  6 │ context")
        assertEquals(2, plan.rows.size, "the pair collapses into one row, the context stays")
        val pair = plan.rows[0]
        assertEquals(4, pair.oldNo)
        assertEquals("val old = 1", pair.old?.trim())
        assertEquals(5, pair.newNo)
        assertEquals("val new = 2", pair.new?.trim())
        assertTrue(plan.rows[1].same, "a context row carries both sides equal")
    }

    @Test
    fun `the standard unified format parses with no numbers`() {
        val plan = DiffShot.rows("-one\n+two\n three")
        val pair = plan.rows[0]
        assertNull(pair.oldNo, "a bare unified diff has no numbers to give")
        assertEquals("one", pair.old)
        assertEquals("two", pair.new)
    }

    @Test
    fun `an unpaired deletion keeps its own row`() {
        val plan = DiffShot.rows("-gone\n+kept\n+extra")
        assertEquals(2, plan.rows.size)
        assertNull(plan.rows[1].old, "the extra addition has no old side")
        assertEquals("extra", plan.rows[1].new)
    }

    @Test
    fun `hunk headers and blank lines never become rows`() {
        val plan = DiffShot.rows("@@ -1,2 +1,2 @@\n-a\n+b\n\\ No newline")
        assertEquals(1, plan.rows.size, plan.rows.toString())
    }

    @Test
    fun `file headers never become a red and green pair of paths`() {
        val plan = DiffShot.rows("--- a/Foo.kt\n+++ b/Foo.kt\n@@ -1 +1 @@\n-old\n+new")
        assertEquals(1, plan.rows.size, plan.rows.toString())
        assertEquals("old", plan.rows[0].old)
        assertEquals("new", plan.rows[0].new)
    }

    @Test
    fun `the budget cuts the plan and says so`() {
        val diff = (1..10).joinToString("\n") { "+ line $it" }
        val plan = DiffShot.rows(diff, maxRows = 4)
        assertEquals(4, plan.rows.size)
        assertTrue(plan.truncated, "the drawer must say the diff was cut")
        assertTrue(!DiffShot.rows("+one").truncated, "a short diff is whole")
    }

    @Test
    fun `the changed span is the middle two strings do not share`() {
        val (old, new) = DiffShot.changedSpan("val old = 1", "val new = 2")
        // the shared "val " stays cool; everything after it changed (the = 1
        // vs = 2 tail differs too, so the span runs to the end)
        assertEquals("old = 1", "val old = 1".substring(old.first, old.last + 1))
        assertEquals("new = 2", "val new = 2".substring(new.first, new.last + 1))
    }

    @Test
    fun `equal sides answer two empty spans`() {
        val (old, new) = DiffShot.changedSpan("same", "same")
        assertTrue(old.isEmpty() && new.isEmpty())
    }

    @Test
    fun `a pure insertion answers an empty old span`() {
        val (old, new) = DiffShot.changedSpan("ab", "abXY")
        assertTrue(old.isEmpty())
        assertEquals("XY", "abXY".substring(new.first, new.last + 1))
    }
}
