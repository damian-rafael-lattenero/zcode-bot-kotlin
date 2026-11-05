package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The bot's configured owner — the taps the suite sends come from him. */
private const val OWNER = 7L

/**
 * /run <prompt> — Python's _cmd_run (handlers_tasks.py:343), the B5
 * command: a FRESH conversation and the prompt in it, ONE shot. The
 * channel-side guarantee (no createTask cap): /new moves the context to a
 * draft, botStatus CONFIRMS it (the same poll the bind uses), the prompt
 * flies with the create intent armed so the router never freshBinds to
 * the open conversation. Pinned here: the draft fast-path, the bound
 * path's /new + confirm, the honest refusals (usage, degraded, no
 * context, gated /new, never-confirmed draft — NOTHING fired in every
 * one), the 2/11 clobber law (the open conversation is never touched),
 * the armed order's law (/run never steals the ✍️ shot) and the one-text
 * create intent (a following line routes as always — it BINDS, never
 * silently creates).
 */
class RunCommandTest {

    /** The fake ops: canned answers + the recorders (the awaited /new included). */
    private class FakeOps(startStatus: String) : ChannelOps {
        var botStatusJson: String = startStatus

        /** The botStatus query's own shape (the degraded case flips it). */
        var statusAnswer: QueryAnswer = QueryAnswer.Ok("")

        /**
         * When set, the FIRST awaited send flips the status — the /new
         * landing (the fork processes the command between the frames).
         */
        var statusAfterAwait: String? = null

        /** The awaited send's canned answer (replies / Unavailable). */
        var awaitAnswer: InboundAnswer = InboundAnswer.Answered(duplicate = false, replies = emptyList())
        val answers = mutableMapOf<String, String>()
        val inbound = mutableListOf<String>()
        val awaited = mutableListOf<String>()
        val queued = mutableListOf<String>()
        val subscribed = mutableListOf<Pair<String, String>>()
        val orders = mutableListOf<Pair<String, String>>()

        override suspend fun query(query: ChannelQuery): QueryAnswer {
            val key = queryKind(query)
            if (key == "botStatus") {
                if (statusAnswer !is QueryAnswer.Unavailable) statusAnswer = QueryAnswer.Ok(botStatusJson)
                return statusAnswer
            }
            return answers[key]?.let { QueryAnswer.Ok(it) } ?: QueryAnswer.Failed("internal", "no canned answer")
        }

        override suspend fun sendInbound(
            text: String,
            messageId: String,
            receivedAt: Long,
            attachments: List<InboundAttachment>,
        ): InboundReceipt {
            inbound.add(text)
            return InboundReceipt.FIRED
        }

        override suspend fun sendInboundAwait(
            text: String,
            messageId: String,
            receivedAt: Long,
            bindTaskId: String?,
        ): InboundAnswer {
            awaited.add(text)
            statusAfterAwait?.let { botStatusJson = it }
            // production's /task verdict carries the status that NAMES the
            // task — the bind's in-band read-back (the 5/11 confirmation law)
            if (text.startsWith("/task ")) {
                return InboundAnswer.Answered(
                    duplicate = false,
                    replies = listOf("Workspace: app\n------\nTask: x (${text.removePrefix("/task ")})\nState: idle"),
                )
            }
            return awaitAnswer
        }

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
        ): InboundAnswer {
            orders.add(text to taskId)
            return InboundAnswer.Answered(duplicate = false, replies = emptyList())
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

    /** Bound to task-77 — where a naked inbound would land (the CLOBBER risk /run must refuse to take). */
    private val boundStatus = """
        {"bot":{"id":"daemon-local","enabled":true},
         "context":{"workspacePath":"$app","activeTaskId":"task-77","mode":"full_access"}}
    """.trimIndent()

    /** Draft context: the next armed text CREATES — /run's confirmed target. */
    private val draftStatus = """
        {"bot":{"id":"daemon-local","enabled":true},
         "context":{"workspacePath":"$app","activeTaskId":null,"mode":"full_access"}}
    """.trimIndent()

    /** No context at all: the bot was never talked to (the honest /run refusal). */
    private val noContextStatus = """
        {"bot":{"id":"daemon-local","enabled":true}}
    """.trimIndent()

    /** (bot, ops, temp dir) — the store is ALWAYS wired here (F22 is /run's own contract). */
    private fun commands(ops: FakeOps): Triple<BotCommands, FakeOps, java.nio.file.Path> {
        val dir = Files.createTempDirectory("run-cmd-test")
        val bc = BotCommands(
            ops = ops,
            hub = LiveHub(),
            daemonReset = { "fake ladder" },
            ownerUserId = { OWNER },
            clock = { 1_000_000L },
            history = FakeHistory(rows),
            defaultWorkspacePath = "/Users/damian/.zcode/workspace/default",
            lastPrompt = LastPrompt(dir.resolve("last-prompt.json").toFile()),
        )
        return Triple(bc, ops, dir)
    }

    /** The dispatcher's String view — the markup has its own tests (KeyboardsTest). */
    private suspend fun BotCommands.reply(text: String, updateId: Long): String? = handle(text, updateId)?.text

    private fun cleanup(dir: java.nio.file.Path) {
        dir.toFile().deleteRecursively()
    }

    // ---- the usage refusal: no prompt, nothing flies ----

    @Test
    fun runWithoutPromptAnswersTheUsageAndFiresNothing() = runBlocking {
        val (bc, o, dir) = commands(FakeOps(boundStatus))
        assertEquals(BotCommands.RUN_USAGE, bc.reply("/run", 1L))
        assertEquals(BotCommands.RUN_USAGE, bc.reply("/run   ", 2L), "blank is the same honest usage")
        assertTrue(o.inbound.isEmpty())
        assertTrue(o.awaited.isEmpty())
        cleanup(dir)
    }

    @Test
    fun runOnADegradedChannelRefusesAndFiresNothing() = runBlocking {
        val o = FakeOps(boundStatus).apply { statusAnswer = QueryAnswer.Unavailable }
        val (bc, _, dir) = commands(o)
        assertEquals(BotCommands.DEGRADED, bc.reply("/run preparate el desayuno", 1L))
        assertTrue(o.inbound.isEmpty())
        assertTrue(o.awaited.isEmpty())
        cleanup(dir)
    }

    @Test
    fun runWithoutAnyContextRefusesHonestlyAndFiresNothing() = runBlocking {
        val (bc, o, dir) = commands(FakeOps(noContextStatus))
        assertEquals(BotCommands.RUN_NO_CONTEXT, bc.reply("/run preparate el desayuno", 1L))
        assertTrue(o.inbound.isEmpty(), "no context: the prompt never rides")
        assertTrue(o.awaited.isEmpty())
        cleanup(dir)
    }

    // ---- the draft fast-path: already fresh, the prompt CREATES without a /new ----

    @Test
    fun runWithADraftContextFiresIntoANewConversationWithoutNew() = runBlocking {
        val (bc, o, dir) = commands(FakeOps(draftStatus))
        val ack = bc.reply("/run preparate el desayuno", 1L)!!
        assertTrue(ack.startsWith("🚀"), "got: $ack")
        assertTrue("app" in ack, "the new conversation's project: $ack")
        assertEquals(listOf("preparate el desayuno"), o.inbound, "the prompt, ONCE, straight to the draft")
        assertTrue(o.awaited.isEmpty(), "already a draft: no /new needed")
        // F22: the fired prompt is /retry's ammo, sid = the draft target
        val saved = LastPrompt(dir.resolve("last-prompt.json").toFile()).load()
        assertEquals("preparate el desayuno", saved?.text)
        assertEquals(DRAFT_TARGET, saved?.sid)
        cleanup(dir)
    }

    @Test
    fun afterAFiredRunTheNextLineRoutesAsAlwaysItBindsNeverSilentlyCreates() = runBlocking {
        val (bc, o, dir) = commands(FakeOps(draftStatus))
        val ack = bc.reply("/run preparate el desayuno", 1L)!!
        assertTrue("🚀" in ack, "the run fired: $ack")
        // the ONE-text law: the create intent was spent — the NEXT line BINDS to the open conversation
        bc.reply("ahora esto", 2L)
        assertTrue("/task task-77" in o.awaited, "the next line BINDS to the open conversation (no create left)")
        assertEquals(listOf("preparate el desayuno", "ahora esto"), o.inbound)
        cleanup(dir)
    }

    // ---- the bound path: /new first, the prompt only after the CONFIRMED draft ----

    @Test
    fun runWithABoundConversationSendsNewThenFiresOnlyAfterTheDraftConfirms() = runBlocking {
        val o = FakeOps(boundStatus).apply { statusAfterAwait = draftStatus }
        val (bc, _, dir) = commands(o)
        val ack = bc.reply("/run preparate el desayuno", 1L)!!
        assertTrue("🚀" in ack, "got: $ack")
        assertEquals(listOf("/new"), o.awaited, "the fork's own /new moves the context to a draft")
        assertEquals(listOf("preparate el desayuno"), o.inbound, "the prompt ONCE — into the NEW conversation")
        cleanup(dir)
    }

    @Test
    fun runNeverClobbersTheOpenConversation() = runBlocking {
        val o = FakeOps(boundStatus).apply { statusAfterAwait = draftStatus }
        val (bc, _, dir) = commands(o)
        bc.reply("/run arreglá el login del bot", 1L)
        // the 2/11 law's witness: task-77 (the open conversation) received NOTHING —
        // the prompt is not in its stream, nothing was queued on it either
        assertEquals(1, o.inbound.size, "only the new conversation got the prompt")
        assertTrue(o.queued.isEmpty(), "nothing parked on the busy-or-open conversation")
        cleanup(dir)
    }

    @Test
    fun runRefusesWhenTheDraftNeverConfirmsAndNothingFires() = runBlocking {
        val o = FakeOps(boundStatus) // the /new is answered ok but botStatus NEVER shows a draft
        val (bc, _, dir) = commands(o)
        assertEquals(BotCommands.RUN_NOT_FRESH, bc.reply("/run preparate el desayuno", 1L))
        assertTrue(o.inbound.isEmpty(), "THE GUARANTEE: no confirmed draft, no prompt on any wire")
        assertEquals(listOf("/new"), o.awaited)
        // the create intent never leaks: the next line routes as ALWAYS (bind, not create)
        o.botStatusJson = draftStatus
        bc.reply("ahora esto", 2L)
        assertTrue("/task task-77" in o.awaited, "the next line BINDS — the create intent was spent")
        cleanup(dir)
    }

    @Test
    fun runRefusesWhenTheRunningTurnGatesTheNew() = runBlocking {
        val o = FakeOps(boundStatus).apply {
            awaitAnswer = InboundAnswer.Answered(
                duplicate = false,
                replies = listOf("Task task-77 is still running — refuse the switch"),
            )
        }
        val (bc, _, dir) = commands(o)
        assertEquals(BotCommands.RUN_BUSY, bc.reply("/run preparate el desayuno", 1L))
        assertTrue(o.inbound.isEmpty(), "the gated /new means NO prompt anywhere")
        assertEquals(listOf("/new"), o.awaited)
        assertTrue(o.queued.isEmpty(), "/run does not park the prompt on the running conversation")
        cleanup(dir)
    }

    @Test
    fun runWhenTheWireDiesAfterNewRefuses() = runBlocking {
        val o = FakeOps(boundStatus).apply { awaitAnswer = InboundAnswer.Unavailable }
        val (bc, _, dir) = commands(o)
        assertEquals(RouteTexts.CHANNEL_DOWN, bc.reply("/run preparate el desayuno", 1L))
        assertTrue(o.inbound.isEmpty())
        cleanup(dir)
    }

    // ---- the armed order's law: /run never steals the ✍️ shot ----

    @Test
    fun runNeverSpendsTheArmedOrderTheTypedLineStillFlies() = runBlocking {
        val (bc, o, dir) = commands(FakeOps(boundStatus))
        val arm = bc.onCallback("so:task-77", 8L, OWNER)!!
        assertTrue("Armado" in arm.text, "got: $arm")
        val refused = bc.reply("/run preparate el desayuno", 9L)!!
        assertTrue("✍️" in refused, "got: $refused")
        assertTrue("unificador" in refused, "the armed order's own title: $refused")
        assertTrue(o.orders.isEmpty(), "/run never fired the order")
        assertTrue(o.inbound.isEmpty() && o.awaited.isEmpty(), "and it fired nothing else either")
        // the arm SURVIVES: the next typed line is still the owner's ✍️ shot
        val orderAck = bc.reply("la orden directa", 10L)!!
        assertTrue("Orden entregada" in orderAck, "got: $orderAck")
        assertEquals(listOf("la orden directa" to "task-77"), o.orders)
        cleanup(dir)
    }
}
