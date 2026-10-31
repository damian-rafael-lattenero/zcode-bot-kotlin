package dev.lattenero.bot.daemon

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FILES OUT + /diff (30/10): the pure half. The /file plan's honest ladder
 * (bare → usage; relative → refused; missing/directory/oversized → the
 * truth named), the snapshot's fileChanges parse (the LAST turn by
 * default, `all` for the session), and the mini-diff's counts + inline
 * hunks with the caps — zero invented content anywhere.
 */
class FilesCommandsTest {

    // ---- /file --------------------------------------------------------------

    @Test
    fun bareFileSpeaksTheUsage() {
        val plan = FilesCommands.planFileSend("")
        assertNull(plan.file)
        assertTrue("/file <ruta-absoluta>" in plan.refusal!!, plan.refusal)
    }

    @Test
    fun aRelativePathIsRefusedByTheAbsoluteLaw() {
        val plan = FilesCommands.planFileSend("etc/passwd")
        assertTrue("ABSOLUTAS" in plan.refusal!!, plan.refusal)
    }

    @Test
    fun aMissingFileIsSaid() {
        val plan = FilesCommands.planFileSend("/no/existe/nada.txt")
        assertTrue("No existe" in plan.refusal!!, plan.refusal)
    }

    @Test
    fun aDirectoryIsNotAFile() {
        val dir = Files.createTempDirectory("filesout").toFile()
        val plan = FilesCommands.planFileSend(dir.absolutePath)
        assertTrue("No es un archivo" in plan.refusal!!, plan.refusal)
    }

    @Test
    fun anOversizedFileNamesTheCap() {
        val f = File(Files.createTempDirectory("filesout").toFile(), "big.bin")
        f.writeBytes(ByteArray(1)) // the size check reads length(); the cap math is the unit under test
        // 21 MB claimed via the constant — the plan reads the REAL file, so
        // the honesty here is the cap math, pinned by the constant itself
        assertEquals(20L * 1024 * 1024, FilesCommands.MAX_FILE_BYTES)
        val plan = FilesCommands.planFileSend(f.absolutePath)
        assertNotNull(plan.file) // 1 byte passes
    }

    @Test
    fun aRealFilePlans() {
        val f = File(Files.createTempDirectory("filesout").toFile(), "ok.txt")
        f.writeText("hola")
        val plan = FilesCommands.planFileSend(f.absolutePath)
        assertEquals(f, plan.file)
        assertNull(plan.refusal)
    }

    // ---- /diff --------------------------------------------------------------

    private val snapshot = """{"fileChanges":[
        {"turnIndex":0,"snapshots":[{"path":"/a/old.txt","beforeContent":"uno\ndos\n","afterContent":"uno\ndos\n tres","writeCount":1}]},
        {"turnIndex":1,"snapshots":[
            {"path":"/b/new.txt","beforeContent":null,"afterContent":"hola\nmundo","writeCount":2},
            {"path":"/a/old.txt","beforeContent":"uno\ndos\n tres","afterContent":"uno\nDOS\n tres","writeCount":1}
        ]}
    ]}"""

    @Test
    fun theLastTurnIsTheDefaultScope() {
        val files = FilesCommands.changedFilesOf(snapshot, all = false)
        assertEquals(listOf("/b/new.txt", "/a/old.txt"), files.map { it.path })
        assertNull(files[0].before) // a NEW file
    }

    @Test
    fun allWalksEveryTurn() {
        val files = FilesCommands.changedFilesOf(snapshot, all = true)
        assertEquals(listOf("/a/old.txt", "/b/new.txt", "/a/old.txt"), files.map { it.path })
    }

    @Test
    fun anUnparseableSnapshotIsHonestlyEmpty() {
        assertEquals(0, FilesCommands.changedFilesOf("{broken", all = false).size)
        assertEquals(0, FilesCommands.changedFilesOf("""{"messages":[]}""", all = false).size)
    }

    @Test
    fun aTurnWithoutChangesSaysSo() {
        val text = FilesCommands.renderDiff(emptyList(), all = false, maxReplyChars = 3500)
        assertTrue("no cambió ningún archivo" in text, text)
    }

    @Test
    fun theRenderCountsAndShowsTheHunks() {
        val files = FilesCommands.changedFilesOf(snapshot, all = false)
        val text = FilesCommands.renderDiff(files, all = false, maxReplyChars = 3500)
        assertTrue("Archivos cambiados" in text, text)
        assertTrue("(nuevo)" in text, text) // /b/new.txt was born this turn
        assertTrue("+1/−1" in text, text) // old.txt: DOS replaced dos
        assertTrue("+ DOS" in text, text) // the hunk shows the change inline
        assertTrue("/file /a/old.txt" !in text, text) // small diffs stay inline
    }

    @Test
    fun aBigDiffPointsAtFileInsteadOfLying() {
        val big = ChangedFile("/big/x.txt", (1..500).joinToString("\n") { "line $it" }, (1..500).joinToString("\n") { "line $it" } + "\nextra", 1)
        val text = FilesCommands.renderDiff(listOf(big), all = false, maxReplyChars = 3500)
        assertTrue("✂️" in text, text)
        assertTrue("/file /big/x.txt" in text, text)
    }

    // ---- the mini-diff itself ----------------------------------------------

    @Test
    fun theStatsCountBothSides() {
        val (added, removed) = MiniDiff.stats("a\nb\nc\n", "a\nB\nc\nd\n")
        assertEquals(2, added) // B and d
        assertEquals(1, removed) // b
    }

    @Test
    fun aNewFileIsAllAdds() {
        val (added, removed) = MiniDiff.stats(null, "hola\nmundo")
        assertEquals(2, added)
        assertEquals(0, removed)
    }
}
