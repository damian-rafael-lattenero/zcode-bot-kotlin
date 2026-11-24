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
}
