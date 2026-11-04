package dev.lattenero.bot.daemon.notifications

import dev.lattenero.bot.channel.SealFacts
import dev.lattenero.bot.channel.ToolLive
import dev.lattenero.bot.daemon.feedBoxOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * U9 · V1 (daemon half) — the notifications and the transcript box are the
 * other two doors third-party text takes to the phone: a task title, an
 * outcome line and a tool's own output all come from the store or the
 * stream, never from the bot. They must arrive escaped, or a payload forges
 * markup / a raw `<` earns a 400 and the moment is never spoken.
 */
class NotifInjectionSecurityTest {

    private val payload = "<b>bold</b> & <a href=\"https://evil.example\">click</a>"

    private fun assertFullyEscaped(rendered: String, where: String) {
        assertFalse(rendered.contains("<b>bold"), "$where leaked a raw <b>: $rendered")
        assertFalse(rendered.contains("</a>"), "$where leaked a raw link: $rendered")
        assertTrue(rendered.contains("&lt;b&gt;"), "$where must escape the tag: $rendered")
        assertTrue(rendered.contains("&amp;"), "$where must escape the ampersand: $rendered")
    }

    @Test
    fun theSubagentBellEscapesTitleAndOutcome() {
        val rendered = NotifText.subagentDone(
            SubagentDone(sid = "sess_subagent_x", title = payload, outcome = payload, startedMs = 0, endedMs = 1),
        )
        assertFullyEscaped(rendered.substringAfter("<b>Subagente terminó</b>"), "the subagent bell")
    }

    @Test
    fun theSealBellEscapesTheTitleAndTheAnswer() {
        val seal = SealFacts(
            taskId = "ses_abc",
            title = payload,
            stopReason = "completed",
            startedAtMs = 0,
            sealedAtMs = 1_000,
            doneTools = 2,
            failedTools = 0,
            answer = payload,
            answerSpoken = false,
        )
        val rendered = assertNotNull(NotifText.turnSealed(seal))
        assertFullyEscaped(rendered, "the seal bell")
    }

    @Test
    fun theToolCloseBoxEscapesTheOutputTail() {
        val tool = ToolLive(
            toolId = "t1",
            name = "Bash",
            openedAtMs = 0,
            input = buildJsonObject { put("command", "ls") },
            status = "completed",
            output = payload,
        )
        val rendered = feedBoxOf(tool)
        // the LAST <pre> is the output tail (the bash box has its own <pre> for the command)
        val tail = rendered.substringAfterLast("<pre>").substringBefore("</pre>")
        assertFullyEscaped(tail, "the tool output tail")
        assertTrue(rendered.contains("<pre>") && rendered.contains("</pre>"), "the tail rides its own <pre>")
    }
}
