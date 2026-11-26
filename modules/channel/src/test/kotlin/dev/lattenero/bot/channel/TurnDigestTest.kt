package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The digest's pure laws: parse, label, pack — nothing dropped, ever. */
class TurnDigestTest {

    private fun snapshotOf(parts: String, tools: String = "[]"): String =
        """{"messages":[{"role":"assistant","tools":$tools,"parts":$parts}]}"""

    @Test
    fun theMergedTurnParsesIntoStepsLabeledByTheToolTheyFollowed() {
        val json = snapshotOf(
            parts = """[{"type":"content","content":"Voy a revisar"},{"type":"tool-call","toolIndex":0},""" +
                """{"type":"content","content":"Pasa, ahora el diff"},{"type":"tool-call","toolIndex":1},""" +
                """{"type":"content","content":"El diff quedó aplicado. Conclusión."}]""",
            tools = """[{"toolName":"Bash"},{"toolName":"Edit"}]""",
        )
        val voice = TurnDigest.ofSnapshot(json)!!
        assertEquals(3, voice.segments.size)
        assertNull(voice.segments[0].afterTool)
        assertEquals("Bash", voice.segments[1].afterTool)
        assertEquals("Edit", voice.segments[2].afterTool)
        assertEquals("El diff quedó aplicado. Conclusión.", voice.conclusion)
    }

    @Test
    fun consecutiveContentPartsMergeIntoOneStep() {
        val json = snapshotOf(
            """[{"type":"content","content":"misma "},{"type":"content","content":"frase"}]""",
        )
        val voice = TurnDigest.ofSnapshot(json)!!
        assertEquals(1, voice.segments.size)
        assertEquals("misma frase", voice.segments[0].text)
    }

    @Test
    fun thoughtPartsNeverSpeakInTheDigest() {
        val json = snapshotOf(
            """[{"type":"content","content":"a "},{"type":"thought","content":"(cot)"},""" +
                """{"type":"content","content":"b"}]""",
        )
        val voice = TurnDigest.ofSnapshot(json)!!
        assertEquals(1, voice.segments.size)
        assertEquals("a b", voice.segments[0].text)
    }

    @Test
    fun theOldShapeWithoutPartsFallsBackToTheWholeContent() {
        val json = """{"messages":[{"role":"assistant","content":"la respuesta entera"}]}"""
        val voice = TurnDigest.ofSnapshot(json)!!.segments
        assertEquals(1, voice.size)
        assertEquals("la respuesta entera", voice[0].text)
    }

    @Test
    fun noAssistantMessageOrBlankContentAnswersNull() {
        assertNull(TurnDigest.ofSnapshot("""{"messages":[{"role":"assistant","content":"   "}]}"""))
        assertNull(TurnDigest.ofSnapshot("not json"))
        assertNull(TurnDigest.ofSnapshot("""{"messages":[{"role":"user","content":"hola"}]}"""))
    }

    @Test
    fun theFoldProjectsSpentStepsAndTheSealCopyAsTheConclusion() {
        val state = TurnLive(
            taskId = "t",
            title = "T",
            proseSpent = listOf("seg uno", "seg dos"),
            answerAtSeal = "la conclusión del fold",
        )
        val voice = TurnDigest.ofFold(state)
        assertEquals(3, voice.segments.size)
        assertNull(voice.segments[0].afterTool)
        assertEquals("la conclusión del fold", voice.conclusion)
    }

    @Test
    fun anEmptyFoldProjectsAnEmptyVoice() {
        val voice = TurnDigest.ofFold(TurnLive(taskId = "t", title = "T"))
        assertTrue(voice.segments.isEmpty())
        assertNull(voice.conclusion)
    }

    @Test
    fun aSingleStepRendersAsAQuoteBehindTheDigestHeader() {
        val pages = TurnDigest.pages(TurnVoice(listOf(TurnProse("solo la respuesta"))))
        assertEquals(1, pages.size)
        assertEquals(1, pages[0].size)
        assertEquals(
            VoiceBlock.Prose("🧾 Digest:\n\nsolo la respuesta"),
            pages[0][0],
            "the pure core stays typed and the header rides the first block",
        )
        // the classic edge speaks the same block: the words quoted, so the
        // bot's voice wears the CoT's own highlight (the owner, 25/11)
        assertEquals(
            "<blockquote>🧾 Digest:\n\nsolo la respuesta</blockquote>",
            TurnDigest.htmlOf(pages[0]),
        )
    }

    @Test
    fun multiStepsCarryNumberedLabelsAndTheConclusionMark() {
        val voice = TurnVoice(
            listOf(
                TurnProse("hablo primero"),
                TurnProse("hablo tras la tool", afterTool = "Bash"),
                TurnProse("el final"),
            ),
        )
        val texts = TurnDigest.pages(voice).flatten().map { it.text }
        assertTrue(
            texts[0].startsWith("🧾 Digest:\n\nStep 1") && "hablo primero" in texts[0],
            "the digest opens with its header, then the numbered step: ${texts[0].take(60)}",
        )
        assertTrue(texts.any { it.startsWith("Step 2 · after 🔧 Bash") && "hablo tras la tool" in it })
        assertTrue(texts.last().startsWith("✅ Answer") && texts.last().endsWith("el final"))
    }

    @Test
    fun fencedCodeRidesAPreWithItsLanguageInsideTheDigest() {
        val pages = TurnDigest.pages(
            TurnVoice(listOf(TurnProse("mira:\n```kotlin\nval x = 1\n```\nfin"))),
        )
        val page = pages.single()
        assertEquals("kotlin", (page[1] as VoiceBlock.Code).language)
        val html = TurnDigest.htmlOf(page)
        assertTrue("<pre><code class=\"language-kotlin\">val x = 1</code></pre>" in html, html)
        assertTrue(html.startsWith("<blockquote>🧾 Digest:\n\nmira:</blockquote>"), html)
        assertTrue(html.endsWith("<blockquote>fin</blockquote>"), html)
    }

    @Test
    fun proseLinesSurviveAsRealBreaksAndInlineCodeAsRuns() {
        val html = TurnDigest.htmlOf(listOf(VoiceBlock.Prose("uno\nmirá `x`")))
        assertEquals("<blockquote>uno\nmirá <code>x</code></blockquote>", html)
    }

    @Test
    fun codeWithoutAFenceSpeaksTheShellLanguageAndEscapes() {
        val html = TurnDigest.htmlOf(listOf(VoiceBlock.Code("a < b", null)))
        assertEquals("<pre><code class=\"language-nu\">a &lt; b</code></pre>", html)
    }

    @Test
    fun anOversizedStepChunksAcrossBlocksAndNothingDrops() {
        val text = "palabra ".repeat(1500)
        val pages = TurnDigest.pages(TurnVoice(listOf(TurnProse(text))))
        val blocks = pages.flatten()
        assertTrue(blocks.all { PyText.cpLength(it.text) <= RichVoice.BLOCK_CP })
        // the header rides the first chunk: the continuation law still holds
        assertEquals("🧾 Digest:\n\n" + text.trim('\n'), blocks.joinToString("") { it.text })
    }

    @Test
    fun stepsShareAPageWhileTheBudgetHolds() {
        val pages = TurnDigest.pages(
            TurnVoice(listOf(TurnProse("primero"), TurnProse("segundo"), TurnProse("el final"))),
        )
        assertEquals(1, pages.size)
        assertEquals(3, pages[0].size)
    }
}
