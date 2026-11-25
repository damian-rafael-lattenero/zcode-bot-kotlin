package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The command shaper's laws: break ONLY at unquoted pipes, only when the
 * line exceeds the phone's budget, and never touch data lines.
 */
class CommandShapeTest {

    private val ownerCommand =
        "print (open --raw /tmp/probe-status.resp.json | from json | get result.rich_message | to json --raw)"

    @Test
    fun aLongPipelineBreaksAtItsUnquotedPipes() {
        assertEquals(
            "print (open --raw /tmp/probe-status.resp.json\n" +
                "  | from json | get result.rich_message\n" +
                "  | to json --raw)",
            CommandShape.shape(ownerCommand),
        )
    }

    @Test
    fun aShortLinePassesUntouched() {
        assertEquals("ls -la", CommandShape.shape("ls -la"))
    }

    @Test
    fun aLongLineWithoutPipesPassesUntouched() {
        val blob = "y".repeat(90)
        assertEquals(blob, CommandShape.shape(blob))
    }

    @Test
    fun aPipeInsideQuotesIsDataNotASplit() {
        val quoted = """rg -n "error|warn|fail|panic" /Users/lattenero/Projects/zcode-bot-kotlin/modules"""
        assertEquals(quoted, CommandShape.shape(quoted))
    }

    @Test
    fun aQuotedPipeNeverSplitsButTheRealOneDoes() {
        val command = """print ("a | b" | str upcase | str replace "A" "B" | str length | into string)"""
        val shaped = CommandShape.shape(command)
        assertEquals(2, shaped.lines().size, shaped)
        assertEquals(true, shaped.lines().first().contains("\"a | b\""), shaped)
        assertEquals(true, shaped.lines().first().endsWith("\"B\""), shaped)
        assertEquals(true, shaped.lines().last().startsWith("  | str length"), shaped)
    }

    @Test
    fun eachLineOfAMultiLineCommandIsShapedOnItsOwn() {
        val command = "ls\n" + ownerCommand + "\nprint 1"
        val lines = CommandShape.shape(command).lines()
        assertEquals("ls", lines.first())
        assertEquals("print 1", lines.last())
        assertEquals(5, lines.size, CommandShape.shape(command))
    }
}
