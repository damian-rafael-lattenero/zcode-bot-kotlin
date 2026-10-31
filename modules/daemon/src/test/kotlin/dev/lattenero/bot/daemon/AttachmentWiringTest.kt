package dev.lattenero.bot.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ATTACHMENTS IN's wiring half (30/10): the honest gate (Telegram's 20 MB
 * bot law — the refusal NAMES it, absent size passes to fail honestly at
 * the download) and the filename's on-disk safety (no separators, capped).
 */
class AttachmentWiringTest {

    @Test
    fun theTwentyMbLawIsNamedInTheRefusal() {
        val refusal = attachmentRefusal(21L * 1024 * 1024)
        assertTrue("file is too big" in refusal!!, refusal)
        assertTrue("20 MB" in refusal)
    }

    @Test
    fun atOrUnderTheCapPasses() {
        assertNull(attachmentRefusal(20L * 1024 * 1024))
        assertNull(attachmentRefusal(1L))
    }

    @Test
    fun anUnknownSizePassesTheGate() {
        // Telegram omits file_size on some photos — the honest failure then
        // happens at the download itself, where the truth is
        assertNull(attachmentRefusal(null))
    }

    @Test
    fun theFilenameIsSafeOnDisk() {
        assertEquals("reporte_Q4.pdf", attachmentFilename("reporte Q4.pdf"))
        assertEquals(".._etc_passwd", attachmentFilename("../../etc/passwd"))
        assertEquals("attachment", attachmentFilename("///"))
        assertEquals("a".repeat(120), attachmentFilename("a".repeat(500)))
    }
}
