package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * THE NAME-FIRST LAW (v2 §7) and THE FIRST-LINE LAW — the owner, 12/11:
 * «las rutas son las largas, nunca llego a ver el nombre del archivo».
 * The name leads; multiline never collapses into one blob.
 */
class PathNameTest {

    // ---- detail(): the name leads, ONE dir of context behind ----

    @Test
    fun theBasenameLeadsAndTheNearestDirRidesBehind() {
        assertEquals("LiveTurn.kt · …/c", PathName.detail("/a/b/c/LiveTurn.kt"))
        assertEquals(
            "deploy-daemon.nu · …/scripts",
            PathName.detail("/Users/x/Projects/zcode-bot-kotlin/scripts/deploy-daemon.nu"),
        )
    }

    @Test
    fun aPathWithoutAParentRidesAlone() {
        assertEquals("file.txt", PathName.detail("file.txt"), "a bare name: no context to add")
        assertEquals("x", PathName.detail("/x"), "a root file: the parent is the root, no context")
        assertEquals("x", PathName.detail("./x"), "a dot-parent adds nothing")
    }

    @Test
    fun aTooLongDetailDropsTheTailNeverTheName() {
        val name = "x".repeat(58) + ".kt" // 61 cp — the name alone fills the budget
        assertEquals(name, PathName.detail("/y/$name"), "the tail dies, the name never")
    }

    @Test
    fun aDegeneratePathFallsBackToTheFilenameLaw() {
        assertEquals("/dir/", PathName.detail("/dir/"), "empty basename: the old elision speaks, unchanged when it fits")
    }

    // ---- shaped(): paths name-first, multiline first-line-only ----

    @Test
    fun aPathShapedDetailGoesNameFirst() {
        assertEquals("LiveTurn.kt · …/c", PathName.shaped("/a/b/c/LiveTurn.kt", 60))
    }

    @Test
    fun aMultilineDetailSpeaksItsFirstNonBlankLineOnly() {
        val blob = "\n\n  primera línea de verdad\nsegunda\n tercera"
        assertEquals("primera línea de verdad", PathName.shaped(blob, 120), "no blank lead, no collapse")
    }

    @Test
    fun aLongFirstLineStillCapsWithTheEllipsis() {
        val line = "x".repeat(200)
        assertEquals("x".repeat(60) + "…", PathName.shaped(line, 60))
    }

    @Test
    fun anAllBlankDetailSpeaksNothing() {
        assertEquals("", PathName.shaped("\n \n\t\n", 60))
    }
}
