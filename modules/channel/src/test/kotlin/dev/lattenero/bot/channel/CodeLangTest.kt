package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The one resolver: a path or a fence word → the slug the phone highlights. */
class CodeLangTest {

    @Test
    fun theFleetLanguagesResolveFromThePath() {
        assertEquals("kotlin", CodeLang.ofPath("/w/FleetCommands.kt"))
        assertEquals("nu", CodeLang.ofPath("/Users/lattenero/Scripts/scripts/fleet.nu"))
        assertEquals("bash", CodeLang.ofPath("/w/deploy.sh"))
        assertEquals("typescript", CodeLang.ofPath("/w/x.tsx"))
        assertEquals("json", CodeLang.ofPath("/w/config.json"))
        assertEquals("yaml", CodeLang.ofPath("/w/a.yml"))
        assertEquals("markdown", CodeLang.ofPath("/w/NOTES.md"))
    }

    @Test
    fun extensionLessNamesAreLanguagesToo() {
        assertEquals("docker", CodeLang.ofPath("/w/Dockerfile"))
        assertEquals("make", CodeLang.ofPath("/w/Makefile"))
    }

    @Test
    fun anUnknownExtensionAnswersNullNeverAWrongHighlighter() {
        assertNull(CodeLang.ofPath("/w/archive.xyz"))
        assertNull(CodeLang.ofPath("/w/no-extension"))
        assertNull(CodeLang.ofPath(null))
    }

    @Test
    fun fenceAliasesNormalizeAndCanonicalWordsPassThrough() {
        assertEquals("kotlin", CodeLang.normalize("kt"))
        assertEquals("bash", CodeLang.normalize("sh"))
        assertEquals("bash", CodeLang.normalize("SHELL"))
        assertEquals("yaml", CodeLang.normalize("yml"))
        assertEquals("kotlin", CodeLang.normalize("kotlin"))
        assertEquals("nu", CodeLang.normalize("nu"))
        assertNull(CodeLang.normalize("  "))
        assertNull(CodeLang.normalize(null))
    }

    // THE STREAMING TAIL: the LAST fence marker decides. An opener with a
    // word = the block still being written; a bare close = prose after it.
    @Test
    fun theStreamingTailReadsTheLastFenceMarker() {
        assertEquals("kotlin", CodeLang.ofStreamingTail("Listo:\n```kotlin\nfun fix() {"))
        assertEquals("bash", CodeLang.ofStreamingTail("```sh\nls -la"))
        assertNull(CodeLang.ofStreamingTail("```kotlin\nval x = 1\n```\nlisto"))
        assertNull(CodeLang.ofStreamingTail("```\ncode"))
        assertNull(CodeLang.ofStreamingTail("just some prose"))
        assertNull(CodeLang.ofStreamingTail(""))
    }
}
