package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * /retry — Python's F22 rescue, the command half: the honest «nada» when
 * nothing fired yet, the verbatim re-fire through the SAME router (the
 * plan resolves as if the owner typed it — busy PARKS, never a flat
 * refusal), the save ONLY on FIRED (a parked, refused or channel-down
 * text is not /retry's ammo) and the armed order's law: the one-shot is
 * the owner's TYPED words' privilege — a /retry never spends it (stash →
 * fire → restore, the order flies AFTER the retry).
 */
class RetryCommandTest {

    /** The fake ops: canned answers + the recorders (orders included — the ✍️ law's witness). */
    private class FakeOps(startStatus: String) : ChannelOps {
        var botStatusJson: String = startStatus
        val answers = mutableMapOf<String, String>()

        /** The plain inbound's own receipt (the channel-down case flips it). */
        var inboundReceipt: InboundReceipt = InboundReceipt.FIRED
        val inbound = mutableListOf<String>()
        val awaited = mutableListOf<Pair<String, String>>()
        val queued = mutableListOf<String>()
        val subscribed = mutableListOf<Pair<String, String>>()

        /** The ✍️ direct orders (text to taskId) — the arm's ONE-shot evidence. */
        val orders = mutableListOf<Pair<String, String>>()

        /** The awaited bind's canned replies (the taskRunning refusal rides here). */
        var awaitReplies: List<String> = emptyList()
        var bindCap = false

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            val key = queryKind(query)
            if (key == "botStatus") return QueryAnswer.Ok(botStatusJson)
            return answers[key]?.let { QueryAnswer.Ok(it) } ?: QueryAnswer.Failed("internal", "no canned answer")
        }

        override suspend fun sendInbound(
            text: String,
            messageId: String,
            receivedAt: Long,
            attachments: List<InboundAttachment>,
        ): InboundReceipt {
            inbound.add(text)
            return inboundReceipt
        }

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
            bindTaskId: String?,
        ): InboundAnswer {
            awaited.add(text to messageId)
            return InboundAnswer.Answered(duplicate = false, replies = awaitReplies)
        }

        override fun supportsBind(): Boolean = bindCap

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long): Int {
            queued.add(text)
            return queued.size
        }

        override suspend fun subscribe(taskId: String, workspacePath: String) {
            subscribed.add(taskId to workspacePath)
        }

        override suspend fun sendOrder(
            text: String,
            messageId: String,
            receivedAt: Long,
            taskId: String,
        ): InboundReceipt {
            orders.add(text to taskId)
            return InboundReceipt.FIRED
        }
    }

    private class FakeHistory(
        private val rows: List<TaskRow>,
        private val freshest: TaskRow? = rows.firstOrNull(),
    ) : TasksHistory {
        override suspend fun ctxTasks(limit: Int): List<TaskRow> = rows.take(limit)
        override suspend fun modelSelection(sid: String): ModelSel? = null
        override suspend fun freshestTask(workspacePath: String): TaskRow? = freshest
        override suspend fun taskAlive(sid: String): Boolean = true
        override suspend fun subagentSessionIds(parentSid: String): List<String> = emptyList()
        override suspend fun subagentsRunning(parentSid: String): List<RunningSubagent> = emptyList()
    }

    private val app = "/Users/damian/Projects/app"

    private val rows = listOf(
        TaskRow(sid = "task-77", title = "unificador", ws = app),
        TaskRow(sid = "task-02", title = "otra", ws = app),
    )

    /** Bound to task-77, idle: a free text FIRES there (and saves). */
    private val boundStatus = """
        {"bot":{"id":"daemon-local","enabled":true},
         "context":{"workspacePath":"$app","activeTaskId":"task-77","mode":"full_access"}}
    """.trimIndent()

    /** Draft context: the first text BINDS to the open conversation before it fires. */
    private val draftStatus = """
        {"bot":{"id":"daemon-local","enabled":true},
         "context":{"workspacePath":"$app","activeTaskId":null,"mode":"full_access"}}
    """.trimIndent()

    /** (bot, ops, temp dir) — dir null = unwired store, the honest «nada» case. */
    private fun commands(
        ops: FakeOps,
        wired: Boolean = true,
        history: TasksHistory = FakeHistory(rows),
    ): Triple<BotCommands, FakeOps, java.nio.file.Path?> {
        val dir = if (wired) Files.createTempDirectory("retry-cmd-test") else null
        val bc = BotCommands(
            ops = ops,
            hub = LiveHub(),
            daemonReset = { "fake ladder" },
            clock = { 1_000_000L },
            history = history,
            defaultWorkspacePath = "/Users/damian/.zcode/workspace/default",
            lastPrompt = dir?.let { LastPrompt(it.resolve("last-prompt.json").toFile()) },
        )
        return Triple(bc, ops, dir)
    }

    /** The dispatcher's String view — the markup has its own tests (KeyboardsTest). */
    private suspend fun BotCommands.reply(text: String, updateId: Long): String? = handle(text, updateId)?.text

    private fun cleanup(dir: java.nio.file.Path?) {
        dir?.toFile()?.deleteRecursively()
    }

    // ---- the honest «nada» — nothing fired yet, nothing to relaunch ----

    @Test
    fun retryWithoutAStoreAnswersTheHonestNada() = runBlocking {
        val (bc, _, dir) = commands(FakeOps(boundStatus), wired = false)
        assertEquals(BotCommands.RETRY_NOTHING, bc.reply("/retry", 1L))
        cleanup(dir)
    }

    @Test
    fun retryWithAnEmptyStoreAnswersTheHonestNada() = runBlocking {
        val (bc, _, dir) = commands(FakeOps(boundStatus), wired = true)
        assertEquals(BotCommands.RETRY_NOTHING, bc.reply("/retry", 1L), "no fire yet: the file does not even exist")
        assertTrue(!dir!!.resolve("last-prompt.json").toFile().exists(), "«nada» never invents a save")
        cleanup(dir)
    }

    // ---- the verbatim re-fire through the SAME router ----

    @Test
    fun retryRefiresTheSavedPromptVerbatimAndConfirms() = runBlocking {
        val (bc, o, dir) = commands(FakeOps(boundStatus))
        // the first text fires (idle bound task) and the FIRED path saves it
        assertNull(bc.reply("arreglá el login del bot", 1L), "a FIRED text speaks via the live view, no ack")
        assertEquals(listOf("arreglá el login del bot"), o.inbound)
        // /retry: the saved text rides the router AGAIN, verbatim
        val ack = bc.reply("/retry", 2L)!!
        assertTrue("Re-lanzado a" in ack, "got: $ack")
        assertTrue("unificador" in ack, "the fired target's own title: $ack")
        assertEquals(
            listOf("arreglá el login del bot", "arreglá el login del bot"),
            o.inbound,
            "the SAME plan router re-delivered the text verbatim",
        )
        cleanup(dir)
    }

    @Test
    fun retryOnABusyConversationParksTheSavedTextNeverFlatRefuses() = runBlocking {
        val (bc, o, dir) = commands(FakeOps(boundStatus))
        assertNull(bc.reply("arreglá el login del bot", 1L))
        // the turn went live: the retry's saved text must PARK (F17), not refuse
        o.answers["activeTaskIds"] = """["task-77"]"""
        val ack = bc.reply("/retry", 2L)!!
        assertTrue("The Mac is busy" in ack, "got: $ack")
        assertTrue("queue" in ack, "the push-or-enqueue offer rides along: $ack")
        assertEquals(1, o.queued.size, "the parked text is the SAVED one")
        assertEquals("arreglá el login del bot", o.queued.first())
        assertEquals(1, o.inbound.size, "no second delivery — it parked")
        cleanup(dir)
    }

    // ---- the save ONLY on FIRED — the hooks' law ----

    @Test
    fun aParkedTextNeverSavesThePrompt() = runBlocking {
        val (bc, o, dir) = commands(FakeOps(boundStatus).apply { answers["activeTaskIds"] = """["task-77"]""" })
        val ack = bc.reply("corré los tests", 1L)!!
        assertTrue("The Mac is busy" in ack, "got: $ack")
        assertEquals(1, o.queued.size)
        assertTrue(o.inbound.isEmpty(), "parked = never delivered")
        assertEquals(BotCommands.RETRY_NOTHING, bc.reply("/retry", 2L), "the park saved nothing")
        cleanup(dir)
    }

    @Test
    fun aChannelDownTextNeverSavesThePrompt() = runBlocking {
        val (bc, o, dir) = commands(FakeOps(boundStatus).apply { inboundReceipt = InboundReceipt.UNAVAILABLE })
        val ack = bc.reply("corré los tests", 1L)!!
        assertTrue("📡" in ack, "the honest down text: $ack")
        assertEquals(listOf("corré los tests"), o.inbound, "the attempt rode the wire")
        assertEquals(BotCommands.RETRY_NOTHING, bc.reply("/retry", 2L), "a text that never fired is not ammo")
        cleanup(dir)
    }

    @Test
    fun aRefusedBindNeverSavesThePrompt() = runBlocking {
        val (bc, o, dir) = commands(
            FakeOps(draftStatus).apply {
                bindCap = true
                awaitReplies = listOf("Task task-77 is still running — refuse the switch")
            },
        )
        // draft + open conversation: the text BINDS first — the host refuses (taskRunning)
        val ack = bc.reply("cambiale el título a la carta", 1L)!!
        assertTrue("did not land" in ack, "the bind refusal speaks: $ack")
        assertEquals(1, o.awaited.size, "the bind was attempted")
        assertTrue(o.inbound.isEmpty(), "refused = the text was never delivered")
        assertEquals(BotCommands.RETRY_NOTHING, bc.reply("/retry", 2L), "the refusal saved nothing")
        cleanup(dir)
    }

    // ---- THE ARMED ORDER IS NEVER /retry's AMMO (the 2/11 law) ----

    @Test
    fun retryNeverSpendsTheArmedOrderTheNextTypedTextFliesAsTheOrder() = runBlocking {
        val (bc, o, dir) = commands(FakeOps(boundStatus))
        // 1) a prompt fires and saves (the fire's own watchContext subscribes once)
        assertNull(bc.reply("arreglá el login del bot", 1L))
        assertEquals(listOf("task-77" to app), o.subscribed)
        // 2) the owner arms the ✍️ order on task-77 (tap → read → write the order)
        val arm = bc.onCallback("so:task-77", 8L)!!
        assertTrue("Armado" in arm.text, "got: $arm")
        assertEquals(2, o.subscribed.size, "the arm's attach rode along")
        // 3) /retry with the arm live: the ack is the RETRY's — the saved prompt
        //    must NOT ride as the order, the one-shot must NOT be consumed
        val ack = bc.reply("/retry", 9L)!!
        assertTrue("Re-lanzado a" in ack, "got: $ack")
        assertTrue("Orden entregada" !in ack, "the armed order was never spent by the retry")
        assertTrue(o.orders.isEmpty(), "ops.orders received NOTHING from the retry")
        assertEquals(2, o.inbound.size, "the retry re-fired via the plain inbound, not via sendOrder")
        assertEquals(3, o.subscribed.size, "the retry watches like ANY fired line (the SAME path)")
        // 4) the NEXT free text still flies as the order — AFTER the retry
        val orderAck = bc.reply("la orden directa", 10L)!!
        assertTrue("Orden entregada" in orderAck, "got: $orderAck")
        assertEquals(listOf("la orden directa" to "task-77"), o.orders, "the order survived for the typed line")
        assertEquals(3, o.subscribed.size, "the order flies DIRECT — no subscribe, no watch")
        // 5) the order's flight saved nothing: the retry's own prompt is still the last
        val store = LastPrompt(dir!!.resolve("last-prompt.json").toFile())
        val saved = store.load()
        assertEquals("arreglá el login del bot", saved?.text, "an armed order never overwrites the saved prompt")
        assertEquals("task-77", saved?.sid)
        cleanup(dir)
    }

    @Test
    fun aFailedRetryKeepsTheArmedOrderUntouched() = runBlocking {
        val (bc, o, dir) = commands(FakeOps(boundStatus))
        val arm = bc.onCallback("so:task-77", 8L)!!
        assertTrue("Armado" in arm.text)
        // nothing saved: the honest «nada» must not even touch the arm
        assertEquals(BotCommands.RETRY_NOTHING, bc.reply("/retry", 9L))
        val orderAck = bc.reply("la orden directa", 10L)!!
        assertTrue("Orden entregada" in orderAck, "the arm survived the empty retry: $orderAck")
        assertEquals(listOf("la orden directa" to "task-77"), o.orders)
        cleanup(dir)
    }
}
