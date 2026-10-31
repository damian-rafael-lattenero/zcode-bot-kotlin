package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three new commands against a fake channel: /last re-speaks the
 * snapshot's answer (the shower's own convention — raw body, ✂️ note at
 * the cap), /compact fires the fork's compact query and speaks its
 * refusals, /queue peeks the F17 store with per-row 🚀/🗑 taps. The
 * taps' honest ladders (drain-beat-me, channel-down-put-back) are
 * pinned here — a manual launch never LOSES a text.
 */
class LastCompactQueueTest {

    /** The fake ops: canned answers by kind + a REAL in-memory queue. */
    private class FakeOps : ChannelOps {
        val answers = mutableMapOf<String, String>()
        var available = true
        val inbound = mutableListOf<Pair<String, String>>()
        val parked = mutableListOf<QueuedText>()

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            if (!available) return QueryAnswer.Unavailable
            return answers[queryKind(query)]?.let { QueryAnswer.Ok(it) }
                ?: QueryAnswer.Failed("internal", "no canned answer")
        }

        override suspend fun sendInbound(
            text: String,
            messageId: String,
            receivedAt: Long,
            attachments: List<InboundAttachment>,
        ): InboundReceipt {
            if (!available) return InboundReceipt.UNAVAILABLE
            inbound.add(text to messageId)
            return InboundReceipt.FIRED
        }

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
        ): InboundAnswer = InboundAnswer.Answered(duplicate = false, replies = emptyList())

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long): Int {
            parked.add(QueuedText(text, messageId, receivedAt))
            return parked.size
        }

        override suspend fun queueEntries(): List<QueuedText> = parked.toList()

        override suspend fun queueTake(messageId: String): QueuedText? {
            val hit = parked.firstOrNull { it.messageId == messageId } ?: return null
            parked.remove(hit)
            return hit
        }

        override suspend fun subscribe(taskId: String, workspacePath: String) {
            // the launch's watch rides the real client; the fake needs nothing
        }
    }

    private class NoHistory : TasksHistory {
        override suspend fun ctxTasks(limit: Int): List<TaskRow> = emptyList()
        override suspend fun modelSelection(sid: String): ModelSel? = null
        override suspend fun freshestTask(workspacePath: String): TaskRow? = null
        override suspend fun taskAlive(sid: String): Boolean = true
    }

    private val status = """
        {"bot":{"id":"daemon-local","enabled":true},
         "context":{"workspacePath":"/ws","activeTaskId":"task-77","mode":"full_access"}}
    """.trimIndent()

    private fun snapshot(vararg messages: Pair<String, String>): String = buildString {
        append("""{"messages":[""")
        append(messages.joinToString(",") { (role, content) ->
            """{"role":"$role","content":"$content"}"""
        })
        append("]}")
    }

    private fun commands(ops: FakeOps): BotCommands = BotCommands(
        ops = ops,
        hub = LiveHub(),
        daemonReset = { "fake" },
        clock = { 1_000_000L },
        history = NoHistory(),
    )

    private fun ops(): FakeOps = FakeOps().apply {
        answers["botStatus"] = status
    }

    // ---- /last --------------------------------------------------------------

    @Test
    fun lastSendsTheSnapshotAnswerWithAnHonestHeader() = runBlocking {
        val o = ops()
        o.answers["taskSnapshot"] = snapshot("user" to "hacé X", "assistant" to "Listo: hice X.")
        val reply = commands(o).handle("/last", 1L)
        assertTrue(reply!!.text.startsWith("📬 <b>Last answer of «task-77»</b>"))
        assertTrue("Listo: hice X." in reply.text)
    }

    @Test
    fun lastAnswersHonestlyWhenThereIsNoAnswerYet() = runBlocking {
        val o = ops()
        o.answers["taskSnapshot"] = snapshot("user" to "hola")
        assertTrue("no answer yet" in commands(o).handle("/last", 1L)!!.text)
    }

    @Test
    fun lastNeedsAConversation() = runBlocking {
        val o = ops()
        o.answers["botStatus"] = """{"bot":{"enabled":true},"context":null}"""
        assertTrue("No active conversation" in commands(o).handle("/last", 1L)!!.text)
    }

    @Test
    fun lastDegradesAndFailsHonestly() = runBlocking {
        val o = ops()
        o.available = false
        assertTrue(commands(o).handle("/last", 1L)!!.text.startsWith("📡"))
        val o2 = ops() // taskSnapshot not canned -> Failed
        assertTrue("⚠️ /last failed (internal)" in commands(o2).handle("/last", 2L)!!.text)
    }

    @Test
    fun lastTruncatesWithTheScissorsNote() = runBlocking {
        val o = ops()
        val long = "x".repeat(4000)
        o.answers["taskSnapshot"] = snapshot("assistant" to long)
        val text = commands(o).handle("/last", 1L)!!.text
        assertTrue("✂️" in text)
        assertFalse(long in text)
    }

    // ---- /compact -----------------------------------------------------------

    @Test
    fun compactSendsTheQueryAndSpeaksTheTail() = runBlocking {
        val o = ops()
        o.answers["compact"] = """{"accepted":true}"""
        val text = commands(o).handle("/compact", 1L)!!.text
        assertTrue("🗜" in text)
        assertTrue("task-77" in text)
    }

    @Test
    fun compactFailsHonestlyOnAnOldHost() = runBlocking {
        val o = ops() // compact not canned -> Failed("internal", ...)
        assertTrue("⚠️ Compact failed (internal)" in commands(o).handle("/compact", 1L)!!.text)
        val o2 = ops()
        o2.available = false
        assertTrue(commands(o2).handle("/compact", 1L)!!.text.startsWith("📡"))
    }

    // ---- /queue -------------------------------------------------------------

    @Test
    fun queueEmptyIsHonest() = runBlocking {
        assertTrue("empty" in commands(ops()).handle("/queue", 1L)!!.text)
    }

    @Test
    fun queueListsParkedTextsWithLaunchAndDiscardButtons() = runBlocking {
        val o = ops()
        o.enqueueInbound("hola que tal", "tg-11", 100L)
        o.enqueueInbound("segunda línea\ncon salto", "tg-12", 200L)
        val reply = commands(o).handle("/queue", 1L)!!
        assertTrue("(2)" in reply.text)
        val rows = (reply.markup?.get("inline_keyboard") as? List<*>).orEmpty()
        assertEquals(2, rows.size)
        val first = (rows[0] as List<*>)[0]
        val firstData = (first as? Map<*, *>)?.get("callback_data")?.let { it as? JsonPrimitive }?.content
        assertTrue(firstData?.startsWith("ql:tg-11") == true)
        assertTrue(Keyboards.callbackDataValid(firstData ?: ""))
        // the preview is ONE line — the newline never rides a button
        val second = (rows[1] as List<*>)[0]
        val secondText = (second as? Map<*, *>)?.get("text")?.let { it as? JsonPrimitive }?.content.orEmpty()
        assertFalse(secondText.contains('\n'))
    }

    @Test
    fun launchFiresByHandAndEmptiesTheRow() = runBlocking {
        val o = ops()
        o.enqueueInbound("hola", "tg-11", 100L)
        val reply = commands(o).onCallback("ql:tg-11", 2L)
        assertTrue(reply!!.text.startsWith("🚀"))
        assertTrue(o.inbound.any { it.first == "hola" && it.second == "tg-11-manual" })
        assertTrue(o.parked.isEmpty())
    }

    @Test
    fun launchPutsTheTextBackWhenTheChannelIsDown() = runBlocking {
        val o = ops()
        o.enqueueInbound("hola", "tg-11", 100L)
        o.available = false
        val reply = commands(o).onCallback("ql:tg-11", 2L)
        assertTrue(reply!!.text.startsWith("📡"))
        assertEquals(1, o.parked.size) // a manual launch never LOSES the text
        assertEquals("hola", o.parked[0].text)
    }

    @Test
    fun launchAnswersHonestlyWhenTheDrainBeatTheTap() = runBlocking {
        assertTrue("Ya no está" in commands(ops()).onCallback("ql:tg-404", 1L)!!.text)
    }

    @Test
    fun discardDropsOneAndCountsTheRest() = runBlocking {
        val o = ops()
        o.enqueueInbound("hola", "tg-11", 100L)
        o.enqueueInbound("cha", "tg-12", 200L)
        val reply = commands(o).onCallback("qd:tg-11", 2L)
        assertTrue("🗑" in reply!!.text)
        assertTrue("1 left" in reply.text)
        assertEquals(listOf("cha"), o.parked.map { it.text })
    }

    // ---- snapshotLastAnswer (shared with the daemon's final shower) ----------

    @Test
    fun snapshotLastAnswerTakesTheLastAssistantOnly() {
        assertEquals("dos", BotCommands.snapshotLastAnswer(snapshot("assistant" to "uno", "user" to "vamos", "assistant" to "dos")))
        assertNull(BotCommands.snapshotLastAnswer(snapshot("user" to "hola")))
        assertNull(BotCommands.snapshotLastAnswer("""{"messages":"not a list"}"""))
        assertNull(BotCommands.snapshotLastAnswer("broken {"))
    }

    // ---- the menu contract ---------------------------------------------------

    @Test
    fun menuListsTheThirteenAndHelpSpeaksThem() {
        assertTrue(BotCommands.MENU.size >= 14) // steer landed — the count is MENU.size-driven now
        val names = BotCommands.MENU.map { it.first }
        assertTrue("last" in names && "compact" in names && "queue" in names)
        assertTrue("/last" in BotCommands.HELP && "/compact" in BotCommands.HELP && "/queue" in BotCommands.HELP)
        assertTrue("The ${BotCommands.MENU.size}" in BotCommands.HELP)
    }
}
