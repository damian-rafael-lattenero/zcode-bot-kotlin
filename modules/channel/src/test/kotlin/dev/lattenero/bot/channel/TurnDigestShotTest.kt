package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The seal's 📸 recipe: the page becomes one shot; the language rides only
 * when the whole page is code (a mixed page would color prose with a code
 * grammar), and a blank page has nothing to draw.
 */
class TurnDigestShotTest {

    @Test
    fun `an all-code page keeps its language`() {
        val shot = TurnDigest.shotOf(listOf(VoiceBlock.Code("fun main() {}", "kotlin")))
        assertEquals("kotlin", shot?.language)
        assertEquals("fun main() {}", shot?.text)
    }

    @Test
    fun `a mixed page goes plain monospaced`() {
        val shot = TurnDigest.shotOf(
            listOf(VoiceBlock.Prose("Así quedó:"), VoiceBlock.Code("ls | first 3", "nu")),
        )
        assertNull(shot?.language, "prose never wears a code grammar")
        assertEquals("Así quedó:\n\nls | first 3", shot?.text)
    }

    @Test
    fun `two languages on one page mean no single grammar`() {
        val shot = TurnDigest.shotOf(
            listOf(VoiceBlock.Code("a", "nu"), VoiceBlock.Code("b", "kotlin")),
        )
        assertNull(shot?.language)
    }

    @Test
    fun `a code page without a fence language stays plain`() {
        val shot = TurnDigest.shotOf(listOf(VoiceBlock.Code("x", null)))
        assertNull(shot?.language)
        assertEquals("x", shot?.text)
    }

    @Test
    fun `a blank page has nothing to draw`() {
        assertNull(TurnDigest.shotOf(emptyList()))
        assertNull(TurnDigest.shotOf(listOf(VoiceBlock.Prose("   "))))
    }
}
