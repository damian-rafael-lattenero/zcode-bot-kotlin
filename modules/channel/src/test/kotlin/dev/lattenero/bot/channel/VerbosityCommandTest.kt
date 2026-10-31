package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * /verbosity — the transcript feed's knob: bare → the picker (the current
 * marked), an arg → the Spanish aliases, unknown → the picker with the
 * honest note; the vb: tap sets; the WRITE lands in the injected
 * [VerbositySetting] and the publisher reads it PER TICK (the same
 * instance the test passes — the seam's whole point).
 */
class VerbosityCommandTest {

    private class FakeOps : ChannelOps {
        override suspend fun query(query: ChannelQuery) = QueryAnswer.Failed("internal", "not needed here")

        override suspend fun sendInbound(text: String, messageId: String, receivedAt: Long) = InboundReceipt.FIRED

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
        ) = InboundAnswer.Answered(duplicate = false, replies = emptyList())

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long) = 0

        override suspend fun subscribe(taskId: String, workspacePath: String) {}
    }

    private fun commands(verbosity: VerbositySetting): BotCommands = BotCommands(
        ops = FakeOps(),
        hub = LiveHub(),
        daemonReset = { "ladder ok" },
        clock = { 1_000_000L },
        verbosity = verbosity,
    )

    @Test
    fun bareAnswersThePickerWithTheCurrentMarked() = runBlocking {
        val setting = VolatileVerbosity().apply { set(FeedVerbosity.NORMAL) }
        val reply = commands(setting).handle("/verbosity", 1L)!!
        assertTrue("🔉 normal" in reply.text, "the line names the current level")
        val rows = reply.markup!!["inline_keyboard"]!!.jsonArray
        assertEquals(3, rows.size, "one row per level")
        val current = rows.map { it.jsonArray[0].jsonObject }
        assertTrue(current.any { (it["text"]!!.jsonPrimitive.content).startsWith("✅ 🔉 normal") })
        assertEquals(
            listOf("vb:summary", "vb:normal", "vb:verbose"),
            rows.map { it.jsonArray[0].jsonObject["callback_data"]!!.jsonPrimitive.content },
        )
        assertEquals(FeedVerbosity.NORMAL, setting.current(), "bare /verbosity changes nothing")
    }

    @Test
    fun anArgSetsItThroughTheSeamWithTheSpanishAliases() = runBlocking {
        val setting = VolatileVerbosity()
        val cmds = commands(setting)
        assertTrue("✅ Aplicado" in cmds.handle("/verbosity resumen", 1L)!!.text)
        assertEquals(FeedVerbosity.SUMMARY, setting.current())
        assertTrue("✅ Aplicado" in cmds.handle("/verbosity vv", 2L)!!.text)
        assertEquals(FeedVerbosity.VERBOSE, setting.current())
        assertTrue("✅ Aplicado" in cmds.handle("/verbosity vn", 3L)!!.text)
        assertEquals(FeedVerbosity.NORMAL, setting.current())
    }

    @Test
    fun anUnknownArgAnswersThePickerWithTheHonestNote() = runBlocking {
        val setting = VolatileVerbosity()
        val reply = commands(setting).handle("/verbosity ruidoso", 1L)!!
        assertTrue("no es un nivel" in reply.text, "the honest note, never a guess")
        assertEquals(3, reply.markup!!["inline_keyboard"]!!.jsonArray.size)
        assertEquals(FeedVerbosity.DEFAULT, setting.current(), "the unknown arg changed nothing")
    }

    @Test
    fun thePickerTapSetsTheLevelAndTheRefusalSetsNothing() = runBlocking {
        val setting = VolatileVerbosity()
        val reply = commands(setting).onCallback("vb:summary", 1L)!!
        assertEquals(FeedVerbosity.SUMMARY, setting.current())
        assertTrue("Aplicado" in reply.text)
        val refusal = commands(setting).onCallback("vb:gratis", 2L)!!
        assertTrue("no existe" in refusal.text, "the honest refusal")
        assertEquals(FeedVerbosity.SUMMARY, setting.current(), "an unknown tap data set nothing")
    }

    @Test
    fun theAliasesCoverTheThreeLevelsAndNothingElse() {
        assertEquals(FeedVerbosity.VERBOSE, FeedVerbosity.of("verbose"))
        assertEquals(FeedVerbosity.VERBOSE, FeedVerbosity.of("detallado"))
        assertEquals(FeedVerbosity.VERBOSE, FeedVerbosity.of(" VV "))
        assertEquals(FeedVerbosity.NORMAL, FeedVerbosity.of("normal"))
        assertEquals(FeedVerbosity.NORMAL, FeedVerbosity.of("vn"))
        assertEquals(FeedVerbosity.SUMMARY, FeedVerbosity.of("summary"))
        assertEquals(FeedVerbosity.SUMMARY, FeedVerbosity.of("resumen"))
        assertEquals(FeedVerbosity.SUMMARY, FeedVerbosity.of("VS"))
        assertNull(FeedVerbosity.of("ruidoso"))
        assertNull(FeedVerbosity.of(""))
    }

    @Test
    fun theCommandRidesTheMenuAndHelp() {
        assertTrue(BotCommands.MENU.any { it.first == "verbosity" }, "the 15th command is on the menu")
        assertTrue("/verbosity" in BotCommands.HELP)
    }
}
