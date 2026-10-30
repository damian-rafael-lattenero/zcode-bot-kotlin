package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val logger = KotlinLogging.logger {}

/**
 * One parked text of the F17 queue, as /queue shows it — the daemon's
 * own store mirrored here so the channel module never leans on it.
 */
data class QueuedText(val text: String, val messageId: String, val receivedAt: Long)

/** The dispatcher's data source — the client's queries behind one interface (tests fake it). */
interface ChannelOps {
    suspend fun query(query: ChannelQuery): QueryAnswer

    suspend fun sendInbound(text: String, messageId: String, receivedAt: Long): InboundReceipt

    /** The bind's send: the inbound's own answer (replies = the botsService's words). */
    suspend fun sendInboundAwait(text: String, messageId: String, receivedAt: Long): InboundAnswer

    /** Python's F17: park a text while its conversation is mid-turn — the queue size after, or -1. */
    suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long): Int

    /** The queue's current rows (/queue's peek) — empty when this wiring keeps no queue. */
    suspend fun queueEntries(): List<QueuedText> = emptyList()

    /** Removes ONE parked text — null when the drain already took it (the honest answer, not an error). */
    suspend fun queueTake(messageId: String): QueuedText? = null

    suspend fun subscribe(taskId: String, workspacePath: String)
}

/**
 * B3b's eight commands (the owner's locked MVP) + the live router. The
 * COMMANDS ride the channel's service queries (§3.4's table); the FREE
 * TEXT rides `inbound` — the composer's own pipeline, the conversation
 * REAL. The bot's own commands are NEVER forwaded as text (§3.2's rule).
 *
 * The rendering is pure `(query answers, now) -> HTML`; the queries are
 * the only effect, behind [ChannelOps] so the suite pins every reply
 * without a socket.
 */
@Suppress("TooManyFunctions", "LargeClass") // the nine + their shared plumbing — one function per command
class BotCommands(
    private val ops: ChannelOps,
    private val hub: LiveHub,
    /**
     * /nuclear's daemon half — the WHOLE ladder (owner's order, 29/10:
     * «el nuclear debe reiniciar todo a lo duro…»): stopAll, the CLI
     * kills, the app bootout+reopen, the hello wait, the self-kickstart.
     * Answers the summary (the ladder announces to Telegram itself; the
     * return is the log's paper trail).
     */
    private val daemonReset: suspend () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Python's ctx_tasks source (tasks-index + cli db, read-only) — the history /tasks lists. */
    private val history: TasksHistory = noHistory,
    /** Python's config.DEFAULT_WS — the loose-tasks workspace the /tasks scope excludes. */
    private val defaultWorkspacePath: String = "",
) {

    /** /nuclear's single-flight companion: the in-process half (the wiring adds the cross-restart mtime gate). */
    private val nuclearGate = java.util.concurrent.atomic.AtomicBoolean(false)

    /** /tasks new's pending intent: the NEXT free text skips the bind and CREATES (Python's /task new). */
    private var newIntent = false

    /** One incoming line. Null = not a command (the router sends it as inbound). */
    @Suppress("CyclomaticComplexMethod") // one branch per command — the dispatcher's own shape
    suspend fun handle(text: String, updateId: Long): BotReply? {
        val (base, rest) = parseCommand(text) ?: return route(text, updateId)
        val reply: BotReply = when (base) {
            "tasks", "task" -> tasks(rest, updateId) // "task" keeps Python's muscle memory alive
            "all_tasks", "alltasks" -> allTasks(rest, updateId)
            "status" -> BotReply(status())
            "stop" -> stop(rest)
            "nuclear", "nuke" -> nuclear(rest)
            "quota" -> BotReply(quota())
            "help", "start" -> BotReply(help())
            "subagents", "agents" -> subagents(rest)
            "plan" -> plan()
            "last" -> BotReply(lastAnswer())
            "compact" -> compact()
            "context_window" -> contextWindow()
            "queue" -> queue()
            else -> BotReply(UNKNOWN)
        }
        logger.info {
            "command /$base (${rest.length} args) -> ${reply.text.length} chars" +
                (if (reply.markup != null) " + keyboard" else "")
        }
        return reply
    }

    /**
     * A button tap (callback_query): translate by Python's R5 table and
     * run it as the owner's typed words. A `t:<sid>` tap ATTACHES the live
     * pipe to that task first — a watch needs no ownership and nothing
     * refuses it — and the bind attempt rides along (the 30/10 rule: he
     * selects ANY task and reads its stream from that moment, even
     * mid-turn, even while another task owns the conversation). The F17
     * pair is the owner's push-or-enqueue («que me pregunte interrumpir o
     * encolar»): ⚡ stops the busy turn (the queued text then fires on its
     * own), ⏳ confirms the natural wait. The tap's toast
     * (answerCallbackQuery) is the wiring's effect, not ours.
     */
    @Suppress("CyclomaticComplexMethod") // one branch per tap vocabulary — the dispatcher's own shape
    suspend fun onCallback(data: String, updateId: Long): BotReply? = when {
        data.startsWith("intr:") -> interruptNow(data.removePrefix("intr:"), updateId)

        data.startsWith("t:") -> taskAttach(data.removePrefix("t:"), updateId)

        data.startsWith("sa:") -> laneAttach(data.removePrefix("sa:"))

        data.startsWith("qgo:") -> {
            logger.info { "callback ⏳ qgo: the parked text keeps its natural wait" }
            BotReply("📬 Queda encolado — entra cuando el turno termine.")
        }

        data.startsWith("ql:") -> queueLaunch(data.removePrefix("ql:"), updateId)

        data.startsWith("qd:") -> queueDiscard(data.removePrefix("qd:"), updateId)

        // /subagents's view switch: the ONE button each view offers (fixed
        // 4-byte datas — Telegram's 64B law never even breathes)
        data == "sao:" -> subagents("inactivos")

        data == "saa:" -> subagents("activos")

        data.startsWith("pl:") || data.startsWith("pm:") || data.startsWith("pe:") || data == "pd:" ->
            planTap(data)

        // The CoT BLOCK tap (Python's ct:/cr: cycle, the cb: vocabulary): the
        // tap toggles that block's full text IN the card — expand, and the
        // SAME tap folds. Reply null ON PURPOSE: the expand is the card's own
        // in-place edit, never a new message; the toast is the wiring's and
        // the wiring nudges the publisher's tick so the edit is immediate.
        data.startsWith("cb:") -> {
            val n = data.removePrefix("cb:").toIntOrNull() ?: 0
            logger.info { "callback 📖 cb:$n — toggles the block in the card (no reply)" }
            hub.toggleCotBlock(n)
            null
        }

        else -> {
            logger.info { "callback \"$data\" -> \"${Keyboards.callbackText(data)}\"" }
            handle(Keyboards.callbackText(data), updateId)
        }
    }

    /**
     * A /plan tap: one field per tap (pl: plan, pm: model, pe: effort) or
     * pd: copying the conversation's selection to the app's default. The
     * set's OWN result re-renders the menu — the server's word is the truth.
     */
    private suspend fun planTap(data: String): BotReply {
        val scope = "conversation"
        val ws = (contextAnswer() as? ContextAnswer.Ctx)?.value?.workspacePath
        val set = when {
            data == "pd:" -> {
                val cur = planViews(ws) ?: return BotReply(DEGRADED)
                ops.query(
                    ChannelQuery.SetModelSelection(
                        scope = "default",
                        plan = cur.plan,
                        model = cur.model,
                        effort = cur.effort,
                        workspacePath = ws,
                    ),
                )
            }

            data.startsWith("pl:") -> ops.query(
                ChannelQuery.SetModelSelection(scope = scope, plan = data.removePrefix("pl:"), workspacePath = ws),
            )

            data.startsWith("pm:") -> ops.query(
                ChannelQuery.SetModelSelection(scope = scope, model = data.removePrefix("pm:"), workspacePath = ws),
            )

            else -> ops.query(
                ChannelQuery.SetModelSelection(scope = scope, effort = data.removePrefix("pe:"), workspacePath = ws),
            )
        }
        return when (set) {
            is QueryAnswer.Ok -> planReplyFrom(set.resultJson, "✅ aplicado")
            is QueryAnswer.Unavailable -> BotReply(DEGRADED)
            is QueryAnswer.Failed -> BotReply(
                "⚠️ No pude aplicar (${set.code}): ${PyText.esc(set.message ?: "?")}",
            )
        }
    }

    /** The channel's modelSelection answer, parsed — null when degraded/unparseable. */
    private suspend fun planViews(ws: String?): PlanMenu.Views? {
        val answer = ops.query(ChannelQuery.ModelSelection(workspacePath = ws))
        return (answer as? QueryAnswer.Ok)?.let { PlanMenu.parse(it.resultJson) }
    }

    /** /plan — «elegir el plan, el modelo, y la velocidad de effort». */
    private suspend fun plan(): BotReply {
        val ws = (contextAnswer() as? ContextAnswer.Ctx)?.value?.workspacePath
        val title = (contextAnswer() as? ContextAnswer.Ctx)?.value?.activeTaskId?.let {
            history.ctxTasks(SCOPE_FOR_PICK).firstOrNull { r -> r.sid == it }?.title
        }
        val v = planViews(ws) ?: return BotReply(
            "📡 No pude leer la selección — ¿el fork está deployado con los frames de /plan? " +
                "(commits 3d07cace+, falta el deploy del fork).",
        )
        return BotReply(PlanMenu.statusLine(v, title), markup = PlanMenu.keyboard(v))
    }

    private fun planReplyFrom(resultJson: String, note: String): BotReply {
        val v = PlanMenu.parse(resultJson) ?: return BotReply("⚠️ Respuesta de selección ilegible")
        return BotReply("$note\n${PlanMenu.statusLine(v, null)}", markup = PlanMenu.keyboard(v))
    }

    /**
     * ⚡ — the text is already in the F17 queue: stopping the turn IS the
     * push (task_complete's drain fires it immediately after). /stop go
     * stays the hard-all fallback when a single stop cannot land.
     */
    private suspend fun interruptNow(sid: String, updateId: Long): BotReply {
        logger.info { "callback ⚡ intr: stopping $sid — the parked text (update=$updateId) fires on turn end" }
        return when (val answer = ops.query(ChannelQuery.Stop(sid))) {
            is QueryAnswer.Ok -> BotReply("⚡ Interrumpido — tu mensaje entra ya.")
            is QueryAnswer.Unavailable -> BotReply(DEGRADED)
            is QueryAnswer.Failed -> BotReply(
                "⚠️ No pude interrumpir (${answer.code}): ${PyText.esc(answer.message ?: "?")}" +
                    " — /stop go es la vía dura.",
            )
        }
    }

    /**
     * Free text → the ACTIVE conversation, NEVER a new one (Python's
     * `only_active_task` doctrine — Damian, 29/10: «probando probando»
     * opened a fresh conversation instead of landing in the open one).
     *
     * The decision is [planRoute]'s (pure, frozen facts in — the test
     * table); THIS is the effectful half: gather the facts (each one
     * logged — the owner's standing rule: never radio silence, every
     * routing decision visible in daemon.jsonl), execute the plan, and
     * speak the honest refusals. The bind is the botsService's own
     * `/task <id>` command, AWAITED (its replies ARE the verdict:
     * taskRunning, taskMissing) and CONFIRMED via botStatus — the server
     * does not serialize inbounds.
     */
    @Suppress("ReturnCount") // one branch per plan — the router's honest outcomes
    private suspend fun route(text: String, updateId: Long): BotReply? {
        val facts = routeFacts()
        val plan = planRoute(facts, newIntent)
        logger.info {
            "route: update=$updateId ctxWs=${facts.workspacePath?.substringAfterLast('/')} " +
                "active=${facts.activeTaskId ?: "none"} alive=${facts.activeAlive} " +
                "running=${facts.running.size} open=${facts.openSid ?: "none"} " +
                "(${facts.openTitle ?: "?"}) -> ${plan::class.simpleName} ${plan.reasonForLog()}"
        }
        return when (plan) {
            is RoutePlan.Refuse -> BotReply(plan.reason)

            is RoutePlan.BusyRebind -> {
                logger.warn {
                    "route: re-aim to ${plan.wantedSid} REFUSED — ${plan.active} still runs " +
                        "(taskRunning); falling back to only_active_task (queue)"
                }
                val enq = enqueueOrRefuse(text, updateId, plan.active)
                BotReply(RouteTexts.BUSY_REBIND + "\n" + enq.text, markup = enq.markup)
            }

            is RoutePlan.Enqueue -> {
                logger.info { "route: ${plan.reason} — text parked (F17)" }
                enqueueOrRefuse(text, updateId, plan.sid)
            }

            is RoutePlan.Fire -> firePlan(plan, text, updateId, facts)
        }
    }

    /** The plan's one-line log suffix (the sealed shape has no common field). */
    private fun RoutePlan.reasonForLog(): String = when (this) {
        is RoutePlan.Fire -> "(${reason})"
        is RoutePlan.Enqueue -> "(${reason})"
        is RoutePlan.BusyRebind -> "(wanted «${wantedTitle ?: wantedSid}»)"
        is RoutePlan.Refuse -> ""
    }

    /**
     * Executes a Fire: bind first when the plan says so, then the text.
     * A bind that lands on a RUNNING conversation parks the text (the
     * server would only bounce it with taskRunning — honest and quiet).
     */
    @Suppress("ReturnCount") // one guard per bind outcome
    private suspend fun firePlan(
        plan: RoutePlan.Fire,
        text: String,
        updateId: Long,
        facts: RouteFacts,
    ): BotReply? {
        if (plan.bind) {
            when (val bind = bindTo(plan.sid, updateId)) {
                is BindOutcome.Confirmed -> logger.info { "route: bound to ${plan.sid} (${bind.how})" }

                is BindOutcome.Refused -> {
                    // the pre-check said idle but a turn started mid-switch — the
                    // server's own refusal text already reached the chat via the
                    // awaited replies; park the text on what actually runs
                    logger.warn { "route: bind to ${plan.sid} REFUSED (${bind.reply}) — ${bind.fallback}" }
                    return BotReply(RouteTexts.BIND_FAILED)
                }

                is BindOutcome.Failed -> {
                    logger.warn { "route: bind to ${plan.sid} FAILED: ${bind.reason}" }
                    return BotReply(RouteTexts.BIND_FAILED)
                }
            }
            // the bind may have landed on a conversation that just STARTED
            // (task_run_started races the text): park instead of bouncing
            val ws = facts.workspacePath
            if (ws != null && isRunning(plan.sid, ws) && plan.sid != facts.activeTaskId) {
                logger.info { "route: ${plan.sid} started running mid-bind — text parked (F17)" }
                return enqueueOrRefuse(text, updateId, plan.sid)
            }
        }
        newIntent = false
        return when (ops.sendInbound(text, messageId = "tg-$updateId", receivedAt = clock())) {
            InboundReceipt.FIRED -> {
                logger.info { "route: text FIRED at ${plan.sid} (${plan.reason})" }
                watchContext() // the turn this text starts is the one to stream
                null // the live view + the final render speak; no ack noise
            }

            InboundReceipt.UNAVAILABLE -> {
                logger.warn { "route: text NOT fired — the channel is down" }
                BotReply(RouteTexts.CHANNEL_DOWN)
            }
        }
    }

    /**
     * The router's frozen inputs, each one named in the log. The open
     * conversation comes from the tasks-index DIRECT READ (the freshest
     * alive task of the bot's workspace — `activeTaskIds` only lists
     * RUNNING tasks and cannot see an idle open conversation); the
     * channel's listTasks is the fallback when the index has nothing.
     */
    @Suppress("ReturnCount") // one branch per context answer
    private suspend fun routeFacts(): RouteFacts = when (val ctx = contextAnswer()) {
        ContextAnswer.Degraded -> RouteFacts.DEGRADED

        ContextAnswer.None -> RouteFacts(
            contextReachable = true,
            contextPresent = false,
            workspacePath = null,
            activeTaskId = null,
            activeAlive = false,
            running = emptySet(),
            openSid = null,
            openTitle = null,
        )

        is ContextAnswer.Ctx -> {
            val ws = ctx.value.workspacePath
            val running = runningIds(ws)
            val active = ctx.value.activeTaskId
            val activeAlive = active == null || runCatching { history.taskAlive(active) }.getOrDefault(true)
            val open = openConversation(ws)
            RouteFacts(
                contextReachable = true,
                contextPresent = true,
                workspacePath = ws,
                activeTaskId = active,
                activeAlive = activeAlive,
                running = running,
                openSid = open?.id,
                openTitle = open?.title?.takeIf { it.isNotEmpty() },
            )
        }
    }

    /** sessions-index truth: the tasks with a live turn (empty answer = degraded guess). */
    private suspend fun runningIds(workspacePath: String): Set<String> =
        when (val answer = ops.query(ChannelQuery.ActiveTaskIds(workspacePath))) {
            is QueryAnswer.Ok -> parseStrings(answer.resultJson).toSet()
            else -> emptySet()
        }

    /** The open conversation: tasks-index first (sees idle tasks), listTasks as fallback. */
    @Suppress("ReturnCount") // one guard per source: the index row, then each channel fallback
    private suspend fun openConversation(workspacePath: String): TaskBrief? {
        runCatching { history.freshestTask(workspacePath) }.getOrNull()?.let { row ->
            return TaskBrief(id = row.sid, title = row.title, updatedAt = row.whenMs)
        }
        // fallback: the channel's own view (running metas, most recently updated)
        val running = when (val answer = ops.query(ChannelQuery.ActiveTaskIds(workspacePath))) {
            is QueryAnswer.Ok -> parseStrings(answer.resultJson)
            else -> return null
        }
        if (running.isEmpty()) return null
        val metas = when (val answer = ops.query(ChannelQuery.ListTasks(workspacePath))) {
            is QueryAnswer.Ok -> parseTaskMetas(answer.resultJson)
            else -> return TaskBrief(running.first(), "", null)
        }
        return metas.filter { it.id in running }
            .maxByOrNull { it.updatedAt ?: 0 }
            ?.let { TaskBrief(it.id, it.title, it.updatedAt) }
            ?: TaskBrief(running.first(), "", null)
    }

    /** The active task has a live turn (sessions-index's non-terminal phases). */
    private suspend fun isRunning(taskId: String, workspacePath: String): Boolean =
        when (val answer = ops.query(ChannelQuery.ActiveTaskIds(workspacePath))) {
            is QueryAnswer.Ok -> taskId in parseStrings(answer.resultJson)
            else -> false // degraded guess: fire — the channel's own «task running» reply still speaks
        }

    /**
     * Python's F17 texts: the queue holds it; task_complete's drain fires
     * it. The park is NEVER silent (30/10's lesson): the answer carries
     * the push-or-enqueue question — an offer, never a gate (no tap and
     * the text still fires when the turn naturally ends).
     */
    private suspend fun enqueueOrRefuse(text: String, updateId: Long, active: String): BotReply {
        val title = history.ctxTasks(SCOPE_FOR_PICK).firstOrNull { it.sid == active }
            ?.title?.takeIf { it.isNotEmpty() } ?: active.takeLast(SID_TAIL)
        val n = ops.enqueueInbound(text, "tg-$updateId", clock())
        if (n < 0) {
            return BotReply("⚠️ The Mac is busy and I could NOT queue your message — retry in a moment.")
        }
        return BotReply(
            "⏳ The Mac is busy with «${PyText.esc(title)}» — your message stayed in the " +
                "queue ($n waiting). It runs when the turn finishes.",
            markup = Keyboards.interruptQuestion(active),
        )
    }

    /**
     * The bind's three outcomes. [Confirmed] carries HOW (the awaited
     * command's reply or the botStatus poll — both count); [Refused] is
     * the server's own word (taskRunning/taskMissing — locale texts
     * matched by their stable markers); [Failed] is transport-level.
     */
    internal sealed interface BindOutcome {
        data class Confirmed(val how: String) : BindOutcome

        data class Refused(val reply: String, val fallback: String) : BindOutcome

        data class Failed(val reason: String) : BindOutcome
    }

    /**
     * Fires the botsService's own `/task <id>` — AWAITED (the replies are
     * the verdict) — and CONFIRMS via botStatus (the server does not
     * serialize inbounds: the command's write and the poll are separate).
     */
    @Suppress("ReturnCount") // one guard per outcome (answered+confirmed, refused, gave up)
    private suspend fun bindTo(taskId: String, updateId: Long): BindOutcome {
        val sent = ops.sendInboundAwait("/task $taskId", "tg-$updateId-bind", clock())
        val replies = when (sent) {
            is InboundAnswer.Answered -> sent.replies
            is InboundAnswer.Unavailable -> return BindOutcome.Failed("channel down")
            is InboundAnswer.TimedOut -> {
                logger.warn { "bind: /task $taskId answered nothing in ${sent.waitedMs}ms — polling botStatus" }
                emptyList()
            }
        }
        val firstReply = replies.firstOrNull()
        logger.info {
            "bind: /task $taskId replied ${replies.size} line(s)${firstReply?.let { ": $it" } ?: ""}"
        }
        replies.firstOrNull { TASK_RUNNING_MARKER in it }?.let {
            return BindOutcome.Refused(reply = it, fallback = "taskRunning — the old task still runs")
        }
        replies.firstOrNull { TASK_MISSING_MARKER in it }?.let {
            return BindOutcome.Refused(reply = it, fallback = "taskMissing — not in the bot's workspace/project")
        }
        repeat(BIND_CONFIRM_TRIES) {
            delay(BIND_CONFIRM_EVERY_MS)
            if ((contextAnswer() as? ContextAnswer.Ctx)?.value?.activeTaskId == taskId) {
                return BindOutcome.Confirmed(
                    how = if (replies.isEmpty()) "botStatus poll" else "command reply + poll",
                )
            }
        }
        return BindOutcome.Failed("botStatus never said activeTaskId=$taskId")
    }

    // ---- the commands -------------------------------------------------------

    /**
     * /tasks — Python's `/task`: the projects-scope history (usage-backed
     * tasks, loose DEFAULT-workspace ones excluded). `n` picks (rebinds the
     * channel's context), `new` arms the next text to CREATE.
     */
    private suspend fun tasks(rest: String, updateId: Long): BotReply = when {
        rest.isEmpty() -> taskList(all = false)
        rest.startsWith("@") -> BotReply(pickBySid(rest.substring(1), updateId))
        rest.all { it.isDigit() } -> BotReply(pickScoped(rest.toInt(), all = false, updateId, "tasks"))
        rest.substringBefore(' ') == "new" -> BotReply(newConversation(rest.removePrefix("new").trim()))
        else -> BotReply(TASK_USAGE)
    }

    /** /all_tasks — Python's `/all_tasks`: every conversation, loose ones included. `n` picks. */
    private suspend fun allTasks(rest: String, updateId: Long): BotReply =
        if (rest.all { it.isDigit() } && rest.isNotEmpty()) {
            BotReply(pickScoped(rest.toInt(), all = true, updateId, "all_tasks"))
        } else {
            taskList(all = true)
        }

    /**
     * The list itself — Python's task_list_report(scope): rows + stamps +
     * footer, and now the KEYBOARD (the structural gap the interface map
     * named): one green 🟢 row per task (`t:<sid>` re-binds on tap), the
     * blue 🔵 lanes nested under the watched task only.
     */
    private suspend fun taskList(all: Boolean): BotReply {
        val rows = scopeRows(history.ctxTasks(if (all) ALL_CAP else TASKS_CAP), all, defaultWorkspacePath)
        return when (val ctx = contextAnswer()) {
            ContextAnswer.Degraded -> BotReply(degraded("botStatus"))
            ContextAnswer.None -> BotReply(renderTaskList(rows, emptySet(), null, null, all))
            is ContextAnswer.Ctx -> {
                val running = when (val answer = ops.query(ChannelQuery.ActiveTaskIds(ctx.value.workspacePath))) {
                    is QueryAnswer.Ok -> parseStrings(answer.resultJson).toSet()
                    else -> emptySet()
                }
                val watched = hub.peek()
                BotReply(
                    text = renderTaskList(
                        rows,
                        running,
                        ctx.value.activeTaskId,
                        ctx.value.workspacePath,
                        all,
                        watchedSid = watched?.taskId,
                    ),
                    markup = Keyboards.tasksKeyboard(
                        rows = rows,
                        running = running,
                        // the 🎯 follows THE PIPE THE OWNER IS WATCHING (30/10:
                        // «me muestra siempre unificador» — the bind REFUSES to
                        // move while that turn runs; his selection is the attach)
                        activeSid = watched?.taskId ?: ctx.value.activeTaskId,
                        watchedTaskId = watched?.taskId,
                        // ONLY the live dispatch lanes (the owner's 30/10 bug: the
                        // old parentToolUseId!=null filter showed the agents' inner
                        // tools as «bash, bash, read, read» — a subagent row IS the
                        // open Agent/Task dispatch lane, named as he named it)
                        subagents = watched?.tools.orEmpty().filter {
                            it.parentToolUseId == null && it.status == null && it.name in SUBAGENT_DISPATCH_NAMES
                        },
                    ),
                )
            }
        }
    }

    /** `/tasks n` — Python's task_select: the VISIBLE list's n binds the active conversation. */
    private suspend fun pickScoped(n: Int, all: Boolean, updateId: Long, word: String): String {
        val rows = scopeRows(history.ctxTasks(if (all) ALL_CAP else TASKS_CAP), all, defaultWorkspacePath)
        if (n < 1 || n > rows.size) return outOfList(word, rows.size)
        return pickBySid(rows[n - 1].sid, updateId)
    }

    /**
     * A /tasks row tap: ATTACH to that task's pipe FIRST — the channel's
     * stream is per-task and a watch is pure reading (no ownership, no
     * bind, nothing refuses it, even mid-turn of ANOTHER conversation) —
     * then the old bind semantics ride along via [pickBySid] and may
     * refuse honestly; the watch already happened. What ran before the
     * tap stays behind (the fork's continuous delivery has no replay);
     * /last carries the final answer when the turn ends.
     */
    @Suppress("ReturnCount") // one guard per honest miss
    private suspend fun taskAttach(sid: String, updateId: Long): BotReply {
        val ctx = contextAnswer() as? ContextAnswer.Ctx
        val row = history.ctxTasks(SCOPE_FOR_PICK).firstOrNull { it.sid == sid }
        val ws = row?.ws ?: ctx?.value?.workspacePath
        if (ws == null) {
            logger.info { "callback 🟢 t: attach refused: $sid is in no known index/context" }
            return BotReply("🤷 No encuentro esa task en el índice — /tasks para ver las que hay.")
        }
        val live = isRunning(sid, ws)
        ops.subscribe(sid, ws)
        hub.unpin() // a task attach is the WHOLE world — any pin or lane scope dies here
        hub.watchTask()
        logger.info { "callback 🟢 t: pipe ATTACHED to $sid (running=$live) — bind attempt rides along" }
        val title = row?.title?.takeIf { it.isNotEmpty() } ?: sid.takeLast(SID_TAIL)
        val watchLine = if (live) {
            "👁 «${PyText.esc(title)}» en vivo — la tubería es tuya desde ahora; " +
                "lo anterior quedó atrás (📄 /last trae la respuesta final)."
        } else {
            "👁 «${PyText.esc(title)}» suscripto — la carta en vivo entra cuando arranque su próximo turno."
        }
        return BotReply("$watchLine\n${pickBySid(sid, updateId)}")
    }

    /**
     * A subagent lane's tap (`sa:<tail>`): the SAME card as a task — the
     * owner's model, 30/10: «si yo hago attach a un subagent debo ver la ui
     * exactamente igual a la de una task». FULL FORM: the fork's additive
     * launch-ACK field names the child's session
     * (`sess_subagent_<agentId>`, the store's own convention) — constructed,
     * VERIFIED against the running store, then subscribed and pinned: the
     * card becomes the subagent's REAL turn (thoughts, tools, answer), kept
     * against the parent's own stream until it seals. FALLBACK (old fork /
     * ended child / degraded context): the parent's stream lane-scoped to
     * just that subagent (the honest, sparser view).
     */
    @Suppress("ReturnCount") // two attach paths, one guard each
    private suspend fun laneAttach(tail: String): BotReply {
        val state = hub.peek()
        val lane = state?.tools?.lastOrNull { it.toolId.endsWith(tail) }
            ?: return BotReply("🤷 Ese subagente ya no está en la tubería — /tasks para re-engancharte.")
        val agentId = lane.subagentAgentId
        val ws = (contextAnswer() as? ContextAnswer.Ctx)?.value?.workspacePath
        if (agentId != null && ws != null) {
            val sessId = "$SUBAGENT_SESSION_PREFIX$agentId"
            if (sessId in history.subagentSessionIds(state.taskId)) {
                ops.subscribe(sessId, ws)
                hub.pin(sessId)
                logger.info {
                    "callback 🔵 sa: SESSION ATTACHED $sessId (${lane.name}) — the card IS the subagent's turn"
                }
                return BotReply(
                    "👁 Subagente «${PyText.esc(lane.name)}» en vivo — su turno COMPLETO fluye acá " +
                        "(misma carta que una task); se sella cuando cierra.",
                )
            }
            logger.info { "callback 🔵 sa: $sessId not in the running store — honest lane-scope fallback" }
        }
        hub.attachLane(lane.toolId)
        logger.info { "callback 🔵 sa: lane ATTACHED ${lane.toolId} (${lane.name}) — the card speaks only its stream" }
        return BotReply(
            "👁 Subagente «${PyText.esc(lane.name)}» en vivo — la misma carta que una task, " +
                "mirando solo su stream; se sella cuando cierra.",
        )
    }

    /**
     * Python's _task_select_sid: bind + the 🎯 reply (the buttons' and
     * `/task n`'s shared end). The botsService REJECTS switches mid-turn
     * (getContextTaskBlockReason → taskRunning) — pre-checked here with
     * the channel's own activeTaskIds so the refusal is instant.
     */
    @Suppress("ReturnCount") // one guard per refusal, Python's three-error ladder
    private suspend fun pickBySid(sid: String, updateId: Long): String {
        val ctx = contextAnswer() as? ContextAnswer.Ctx
            ?: return RouteTexts.NO_CONTEXT
        val current = ctx.value.activeTaskId
        if (current != null && current != sid && isRunning(current, ctx.value.workspacePath)) {
            logger.warn { "pick $sid refused: ${current} still runs (taskRunning)" }
            return "⏳ The previous task's turn is still running — /stop first, then pick again."
        }
        val row = history.ctxTasks(SCOPE_FOR_PICK).firstOrNull { it.sid == sid }
        when (val bind = bindTo(sid, updateId)) {
            is BindOutcome.Confirmed -> logger.info { "pick $sid bound (${bind.how})" }

            is BindOutcome.Refused -> {
                logger.warn { "pick $sid refused: ${bind.reply} (${bind.fallback})" }
                return "⏳ ${PyText.esc(bind.reply)}"
            }

            is BindOutcome.Failed -> {
                logger.warn { "pick $sid failed: ${bind.reason}" }
                return RouteTexts.BIND_FAILED
            }
        }
        val title = row?.title?.takeIf { it.isNotEmpty() } ?: sid.takeLast(SID_TAIL)
        return "🎯 Active conversation: «${PyText.esc(title)}»\n" +
            "All text you send runs there — the live view streams it."
    }

    /** Python's `/task new [project]`: arms the next free text to CREATE (live: the botsService draft). */
    @Suppress("ReturnCount") // one guard per refusal shape
    private suspend fun newConversation(tag: String): String {
        if (tag.isNotEmpty()) {
            return "⚠️ /tasks new opens it in the ACTIVE project — switching projects from " +
                "the phone is a next hot-fix."
        }
        val ws = (contextAnswer() as? ContextAnswer.Ctx)?.value?.workspacePath
        if (ws == null || ops.sendInbound("/new", "tg-new-${clock()}", clock()) != InboundReceipt.FIRED) {
            return DEGRADED
        }
        newIntent = true
        return "🆕 New conversation in ${PyText.esc(ws.substringAfterLast('/'))} — " +
            "send the first message and it starts."
    }

    /** /status — what runs NOW (sessions-index) + the daemon's own live view. */
    private suspend fun status(): String {
        val lines = mutableListOf("📊 <b>Status</b>")
        when (val ctx = contextAnswer()) {
            ContextAnswer.Degraded -> lines.add(DEGRADED)
            ContextAnswer.None -> lines.add(ACTIVE_NONE)
            is ContextAnswer.Ctx -> {
                lines.add(
                    "context: ${PyText.esc(ctx.value.workspacePath.substringAfterLast('/'))}" +
                        " · ${PyText.esc(ctx.value.mode)}",
                )
                lines.add(activeLine(ctx.value.activeTaskId, ctx.value.workspacePath))
                modelLine(ctx.value.activeTaskId, ctx.value.mode)?.let(lines::add)
                when (val answer = ops.query(ChannelQuery.ActiveTaskIds(ctx.value.workspacePath))) {
                    is QueryAnswer.Ok -> {
                        val ids = parseStrings(answer.resultJson)
                        lines.add(
                            if (ids.isEmpty()) {
                                "running now: nothing"
                            } else {
                                "running now: ${ids.size} — " +
                                    ids.joinToString(", ") { "<code>${PyText.esc(it.takeLast(SID_TAIL))}</code>" }
                            },
                        )
                    }

                    else -> lines.add("running now: ? (query failed)")
                }
            }
        }
        hub.peek()?.let { turn ->
            val elapsed = turn.startedAtMs?.let { LiveTurn.fmtDur(clock() - it) } ?: "?"
            lines.add(
                "live view: «${PyText.esc(turn.title)}» · $elapsed · ${turn.phase::class.simpleName} · " +
                    "🧠 ${turn.thoughtChars} · 🔧 ${turn.doneTools + turn.tools.count { it.status == null }}",
            )
        }
        return lines.joinToString("\n")
    }

    /**
     * /stop — Python's two-step: `go` = HARD (everything, now); bare = the
     * SOFT stop of the active task's turn (the SAME V4 stop as the UI's
     * button) + the 💀 confirm button for the hard one. Python's exact
     * emoji contract.
     */
    @Suppress("ReturnCount") // one guard per degrade of the stop path
    private suspend fun stop(rest: String): BotReply {
        if (rest == "go") return BotReply(hardStop())
        return when (val ctx = contextAnswer()) {
            ContextAnswer.Degraded -> BotReply(DEGRADED)
            ContextAnswer.None -> BotReply(RouteTexts.NO_CONTEXT)
            is ContextAnswer.Ctx -> {
                val taskId = ctx.value.activeTaskId
                    ?: return BotReply("🤷 Nothing to stop — the bot's context has no active task.")
                when (val answer = ops.query(ChannelQuery.Stop(taskId))) {
                    is QueryAnswer.Ok -> BotReply(
                        "⏹️ Stop sent to <code>${PyText.esc(taskId.takeLast(SID_TAIL))}</code>" +
                            " — the turn ends on its own.",
                        markup = Keyboards.stopConfirm(),
                    )

                    is QueryAnswer.Unavailable -> BotReply(DEGRADED)
                    is QueryAnswer.Failed ->
                        BotReply("⚠️ Stop failed (${answer.code}): ${PyText.esc(answer.message ?: "?")}")
                }
            }
        }
    }

    /** `/stop go` — Python's hard half: every active task's turn, no confirmation. */
    private suspend fun hardStop(): String {
        val workspace = (contextAnswer() as? ContextAnswer.Ctx)?.value?.workspacePath
        val stopped = when (val answer = ops.query(ChannelQuery.StopAll(workspace))) {
            is QueryAnswer.Ok -> parseStringsOf(answer.resultJson, "stopped")
            else -> return "⚠️ Stop failed — the channel is down?"
        }
        return "💀 Hard stop: ${stopped.size} run(s) stopped — every active task's turn."
    }

    /**
     * /nuclear — Python's two-step (the owner, 29/10: «con go de una, sin
     * go soft y botón»): `go` = the FULL ladder (the owner's order:
     * «reiniciar todo a lo duro — el bot de kotlin y todo zcode habido y
     * por haber»); bare = the ☢️ card + Python's EXACT confirmation row
     * (☢️ Confirm / 🙅 Cancel, `nuc:go`/`nuc:no`); `no` = the exact
     * cancellation.
     */
    private suspend fun nuclear(rest: String): BotReply = when (rest) {
        "go" -> BotReply(nuclearGo())

        "no" -> BotReply("🙅 Cancelled — nothing was touched.")

        else -> BotReply(
            "☢️ <b>Nuclear</b> — emergency:\n" +
                "⏹️ stops ALL runs\n" +
                "💀 kills every zcode CLI on the Mac\n" +
                "🔄 restarts the app AND the bot, connects de cero\n\n" +
                "Confirm?",
            markup = Keyboards.nuclearConfirm(),
        )
    }

    /**
     * The `/nuclear go` half — the daemon's LADDER owns everything now
     * (stopAll included): it announces both ends itself and ends by
     * restarting this very daemon; the returned summary is the log's
     * paper trail (the phone already heard it from the ladder).
     */
    private suspend fun nuclearGo(): String {
        // SINGLE-FLIGHT (the 08:42 loop's lesson): every extra ☢️ tap while
        // one nuclear runs is another full restart of the Mac's stack — the
        // button stays on screen during the ~40s ladder, and impatience is
        // indistinguishable from a loop. One at a time, honestly refused.
        if (!nuclearGate.compareAndSet(false, true)) {
            logger.warn { "nuclear: already running — extra trigger REFUSED (single-flight)" }
            return "☢️ Ya hay un nuclear corriendo — esperá el resumen antes de lanzar otro."
        }
        try {
            val summary = daemonReset()
            return "☢️ Nuclear go — $summary"
        } finally {
            nuclearGate.set(false)
        }
    }

    /** /quota — the coding plan's pool (IUsageStatsService through the channel). */
    private suspend fun quota(): String {
        // the active conversation names the provider — the fork needs it to
        // classify the accountAccess hint (the Start Plan path's gate)
        val taskId = (contextAnswer() as? ContextAnswer.Ctx)?.value?.activeTaskId
        return when (val answer = ops.query(ChannelQuery.Quota(taskId = taskId))) {
            is QueryAnswer.Ok -> renderQuota(answer.resultJson, clock())
            is QueryAnswer.Unavailable -> DEGRADED
            is QueryAnswer.Failed -> "⚠️ Quota failed (${answer.code}): ${PyText.esc(answer.message ?: "?")}"
        }
    }

    /**
     * /last — re-sends the active conversation's LAST assistant answer
     * (the missed one: a restart, a flood window, a scroll past). The
     * same taskSnapshot the final shower uses; the body rides raw like
     * the shower's own speak — the pipe's htmlTruncate is the cap, and
     * MY take adds the honest ✂️ note before the pipe cuts silently.
     */
    @Suppress("ReturnCount") // one guard per query outcome — the commands' shared shape
    private suspend fun lastAnswer(): String {
        val ctx = contextAnswer() as? ContextAnswer.Ctx ?: return noConversationOrDown()
        val taskId = ctx.value.activeTaskId
            ?: return "📭 No active conversation — /tasks n picks one; its answers come back here."
        val title = history.ctxTasks(SCOPE_FOR_PICK).firstOrNull { it.sid == taskId }
            ?.title?.takeIf { it.isNotEmpty() } ?: taskId.takeLast(SID_TAIL)
        return when (val answer = ops.query(ChannelQuery.TaskSnapshot(taskId, ctx.value.workspacePath))) {
            is QueryAnswer.Ok -> renderLast(snapshotLastAnswer(answer.resultJson), title)
            is QueryAnswer.Unavailable -> DEGRADED
            is QueryAnswer.Failed -> "⚠️ /last failed (${answer.code}): ${PyText.esc(answer.message ?: "?")}"
        }
    }

    /** The context's two shapes speak differently: no conversation ≠ channel down. */
    private suspend fun noConversationOrDown(): String = when (contextAnswer()) {
        ContextAnswer.None -> "📭 No active conversation — /tasks n picks one; its answers come back here."
        else -> DEGRADED
    }

    /** The last answer's render: an honest header + the text, ✂️ when the cap eats the tail. */
    private fun renderLast(text: String?, title: String): String {
        if (text.isNullOrBlank()) {
            return "📭 «${PyText.esc(title)}» has no answer yet — the turn never completed one."
        }
        val body = if (PyText.cpLength(text) > LAST_BODY_CAP) {
            PyText.take(text, LAST_BODY_CAP) + "\n\n✂️ …the tail was cut — the full answer lives in the app."
        } else {
            text
        }
        return "📬 <b>Last answer of «${PyText.esc(title)}»</b>\n$body"
    }

    /**
     * /compact — the active conversation's context shrinks (the fork's
     * compact query; additive protocol: an old host refuses by name and
     * the refusal is spoken, never swallowed).
     */
    @Suppress("ReturnCount") // one guard per query outcome — the commands' shared shape
    private suspend fun compact(): BotReply {
        val ctx = contextAnswer() as? ContextAnswer.Ctx ?: return BotReply(noConversationOrDown())
        val taskId = ctx.value.activeTaskId
            ?: return BotReply("🤷 No active conversation to compact — /tasks n picks one.")
        return when (val answer = ops.query(ChannelQuery.Compact(taskId))) {
            is QueryAnswer.Ok -> BotReply(
                "🗜 Compact sent to <code>${PyText.esc(taskId.takeLast(SID_TAIL))}</code> — " +
                    "the conversation's context shrinks; the work stays.",
            )
            is QueryAnswer.Unavailable -> BotReply(DEGRADED)
            is QueryAnswer.Failed -> BotReply(
                "⚠️ Compact failed (${answer.code}): ${PyText.esc(answer.message ?: "?")}",
            )
        }
    }

    /**
     * /context_window — the active conversation's REAL context meter, the
     * owner's order: «que te marque eso, la ventana de tokens usada hasta
     * el momento por nK segun el modelo y el porcentaje que representa».
     * Every number rides the fork's contextWindow query (the desktop
     * meter's own source, 9cfb39a9) — NOTHING is estimated here. An old
     * host refuses the query BY NAME (unknown_query) and the refusal says
     * the fork update is the fix, /plan's degrade spirit.
     */
    @Suppress("ReturnCount") // one guard per query outcome — the commands' shared shape
    private suspend fun contextWindow(): BotReply {
        val ctx = contextAnswer() as? ContextAnswer.Ctx ?: return BotReply(noConversationOrDown())
        val taskId = ctx.value.activeTaskId
            ?: return BotReply("📭 No active conversation — /tasks n picks one; its answers come back here.")
        val title = history.ctxTasks(SCOPE_FOR_PICK).firstOrNull { it.sid == taskId }
            ?.title?.takeIf { it.isNotEmpty() } ?: taskId.takeLast(SID_TAIL)
        return when (val answer = ops.query(ChannelQuery.ContextWindow(taskId, ctx.value.workspacePath))) {
            is QueryAnswer.Ok -> BotReply(renderContextWindow(answer.resultJson, title))
            is QueryAnswer.Unavailable -> BotReply(DEGRADED)
            is QueryAnswer.Failed -> BotReply(contextWindowFailure(answer.code, answer.message))
        }
    }

    /** The honest failure: an old host refuses the query by name — the fork deploy is the fix. */
    private fun contextWindowFailure(code: String, message: String?): String =
        if (code == UNKNOWN_QUERY_CODE) {
            "⚠️ La app conectada no conoce la query «contextWindow» — falta deployar el fork " +
                "actualizado (ZCode damian, commit 9cfb39a9+); el bot ya la habla."
        } else {
            "⚠️ Context window failed ($code): ${PyText.esc(message ?: "?")}"
        }

    /** /queue — the F17 queue's honest peek: what is parked, 🚀 launch or 🗑 discard per row. */
    private suspend fun queue(): BotReply {
        val entries = ops.queueEntries()
        if (entries.isEmpty()) return BotReply("📭 The queue is empty — nothing parked.")
        return BotReply(
            "📬 <b>Parked texts</b> (${entries.size}) — 🚀 fires it now · 🗑 discards it:",
            markup = Keyboards.queueKeyboard(entries),
        )
    }

    /**
     * 🚀 ql: — one parked text fires NOW at the active conversation (the
     * drain's twin, by hand). A channel-down send goes back to the queue:
     * a manual launch never LOSES the text.
     */
    private suspend fun queueLaunch(messageId: String, updateId: Long): BotReply {
        val item = ops.queueTake(messageId)
            ?: return BotReply("🤷 Ya no está — el drenaje del turno se la llevó (o se descartó antes).")
        return when (ops.sendInbound(item.text, "$messageId-manual", clock())) {
            InboundReceipt.FIRED -> {
                logger.info { "queue: $messageId fired BY HAND (update=$updateId)" }
                watchContext() // the turn this text starts is the one to stream
                BotReply("🚀 Fired — the text runs in the active conversation.")
            }

            InboundReceipt.UNAVAILABLE -> {
                ops.enqueueInbound(item.text, item.messageId, item.receivedAt)
                BotReply("📡 The channel is down — the text went back to the queue.")
            }
        }
    }

    /** 🗑 qd: — one parked text is dropped, honestly counted. */
    private suspend fun queueDiscard(messageId: String, updateId: Long): BotReply {
        val dropped = ops.queueTake(messageId)
            ?: return BotReply("🤷 Ya no está — nada que descartar.")
        val left = ops.queueEntries().size
        logger.info { "queue: $messageId DISCARDED by hand (update=$updateId) — $left left" }
        return BotReply("🗑 Discarded («${PyText.esc(dropped.text.take(DISCARD_PREVIEW))}…») — $left left.")
    }

    /**
     * /subagents — TWO views, ONE button each (the owner's contract,
     * 30/10: «/subagents no muestra el subagente activo… y no mezclar los
     * inactivos»). DEFAULT: ONLY the ACTIVE subagent groups of the watched
     * turn. THE DIAGNOSED GAP: the live subagent IS the open Agent/Task
     * DISPATCH lane (parentToolUseId == null — the fork streams
     * tool_input_start → tool_call the instant the subagent spawns), which
     * the old `parentToolUseId != null` filter structurally EXCLUDED,
     * while the nested-lane events it looked for never ride the parent
     * stream at all (daemon.jsonl 30/10: four /subagents answered «no
     * subagents» at 15:48/16:48/16:49/16:58 with REAL subagents running —
     * sessions created 16:47:03 and 16:56:36). Completed lanes never mix
     * into this view. «inactivos» (the `sao:` tap or the inactivos/old
     * args) switches to TODAY's ENDED subagents: real rows the cli db's
     * own session table (parent_id + last_outcome) already holds — no
     * second journal — max 10, newest first; `activos`/`saa:` comes back.
     */
    private suspend fun subagents(rest: String): BotReply {
        val (inactive, hint) = subagentsView(rest)
        return if (inactive) {
            val parentSid = hub.peek()?.taskId
                ?: (contextAnswer() as? ContextAnswer.Ctx)?.value?.activeTaskId
            BotReply(
                renderSubagentsInactive(history.subagentsToday(parentSid, dayStartMs(clock()))) + hint,
                markup = Keyboards.subagentsViewButtons(inactiveView = true),
            )
        } else {
            val turn = hub.peek()
            val text = if (turn == null) {
                "📭 No turn is being watched — send text first."
            } else {
                renderSubagentsActive(turn, clock())
            }
            // one row per live dispatch lane — the tap ATTACHES its stream
            val lanes = turn?.tools.orEmpty().filter {
                it.parentToolUseId == null && it.status == null && it.name in SUBAGENT_DISPATCH_NAMES
            }
            BotReply(text + hint, markup = Keyboards.subagentsKeyboard(lanes, inactiveView = false))
        }
    }

    /** The arg half of /subagents: (inactive view?, usage hint) — the words the owner types. */
    internal fun subagentsView(rest: String): Pair<Boolean, String?> = when (rest.trim().lowercase()) {
        "", "now", "activos", "active" -> false to null
        "inactivos", "old", "inactive" -> true to null
        else -> false to "\n⚠️ Args: /subagents · /subagents inactivos (today's ended) · /subagents activos"
    }

    /** One ACTIVE subagent: its dispatch lane (when streamed) + open inner lanes + live thought chars. */
    private data class SubagentGroup(
        val parentId: String,
        val dispatch: ToolLive?,
        val openLanes: List<ToolLive>,
        val thoughtChars: Int,
    )

    /**
     * The ACTIVE groups of the watched turn, pure: an open Agent/Task
     * dispatch lane (parentToolUseId == null — THE live subagent), plus
     * the nested-transport paths (open inner lanes, live thought chars)
     * grouped by their parent, for transports that do stream them.
     * Completed lanes are NOT groups (the default view never mixes the
     * inactive ones in).
     */
    private fun subagentGroups(turn: TurnLive): List<SubagentGroup> {
        val dispatches = turn.tools
            .filter { it.parentToolUseId == null && it.status == null && it.name in SUBAGENT_DISPATCH_NAMES }
            .associateBy { it.toolId }
        val openInner = turn.tools
            .filter { it.parentToolUseId != null && it.status == null }
            .groupBy { it.parentToolUseId ?: "" }
        return (dispatches.keys + openInner.keys + turn.subagentThought.keys).distinct().map { id ->
            SubagentGroup(
                parentId = id,
                dispatch = dispatches[id],
                openLanes = openInner[id].orEmpty(),
                thoughtChars = turn.subagentThought[id] ?: 0,
            )
        }.sortedByDescending { it.dispatch?.openedAtMs ?: 0L }
    }

    /** The default view's render: active groups only, the live one first. */
    private fun renderSubagentsActive(turn: TurnLive, nowMs: Long): String {
        val groups = subagentGroups(turn)
        if (groups.isEmpty()) {
            return "🤖 No ACTIVE subagents in «${PyText.esc(turn.title)}» — the main agent works alone."
        }
        return buildString {
            append("🤖 <b>subagents of «${PyText.esc(turn.title)}»</b> — ${groups.size} active:\n")
            groups.forEach { g ->
                if (g.dispatch != null) {
                    append(
                        "• 🔵 <b>${PyText.esc(Keyboards.subagentName(g.dispatch))}</b> ▶️ · running " +
                            "${LiveTurn.fmtDur(nowMs - g.dispatch.openedAtMs)}",
                    )
                } else {
                    append("• 🔵 parent <code>${PyText.esc(g.parentId.takeLast(SID_TAIL))}</code>")
                }
                if (g.thoughtChars > 0) append(" · 🧠 ${LiveTurn.fmtK(g.thoughtChars)} chars thought")
                append('\n')
                g.openLanes.forEach { lane ->
                    append(
                        "   ⚡ ${PyText.esc(lane.name)} · ${lane.lines} líneas · " +
                            LiveTurn.fmtDur(nowMs - lane.openedAtMs) + "\n",
                    )
                }
            }
        }.trimEnd()
    }

    /** «inactivos»'s render: today's ENDED subagents, newest first, MAX 10 — real rows only. */
    private fun renderSubagentsInactive(rows: List<SubagentRow>): String {
        val capped = rows.sortedByDescending { it.endedMs }.take(SUBAGENTS_INACTIVE_CAP)
        if (capped.isEmpty()) return "📭 No subagents ended today."
        return buildString {
            append("⏹️ <b>subagents ended today</b> (${capped.size}):\n")
            capped.forEach { r ->
                append("${subagentStamp(r.outcome)} ${subagentTitle(r.title)}")
                val dur = LiveTurn.fmtDur((r.endedMs - r.startedMs).coerceAtLeast(0))
                append(" · ${fmtEndClock(r.endedMs)} · $dur\n")
            }
        }.trimEnd()
    }

    /** The outcome's honest stamp: the fork's own last_outcome word decides. */
    private fun subagentStamp(outcome: String): String = when (outcome) {
        "completed" -> "✅"
        "interrupted" -> "⛔"
        else -> "❔ ${PyText.esc(outcome.ifEmpty { "ended" })}"
    }

    /** The row's title: the subagent's real prompt head, one line, capped (preview's own …). */
    private fun subagentTitle(title: String): String =
        PyText.esc(PyText.preview(title, SUBAGENT_TITLE_CAP).ifEmpty { "subagent" })

    /** The end's clock stamp — HH:MM local (the row's when, today by construction). */
    private fun fmtEndClock(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(TIME_FMT)

    /** Today's local midnight — «today» is the daemon's own zone, never UTC's. */
    private fun dayStartMs(nowMs: Long): Long {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone)
            .toInstant().toEpochMilli()
    }

    /** /help — the MVP's own manual, curated by hand like Python's. */
    private fun help(): String = HELP

    // ---- shared plumbing ----------------------------------------------------

    /** The botStatus answer's three shapes — the honest difference between «none» and «down». */
    private sealed interface ContextAnswer {
        /** The channel is down/failed: commands that need it say so. */
        data object Degraded : ContextAnswer

        /** No context yet, or the bot's gate is closed — same honest apology. */
        data object None : ContextAnswer

        /** A live context: workspace + active task. */
        data class Ctx(val value: Context) : ContextAnswer
    }

    /** The bot's context: workspace + the active task (botStatus query). */
    private suspend fun contextAnswer(): ContextAnswer = when (val answer = ops.query(ChannelQuery.BotStatus)) {
        is QueryAnswer.Ok -> {
            val root = parse(answer.resultJson) ?: return ContextAnswer.Degraded
            val bot = root.obj("bot")
            val ctx = root.obj("context")
            when {
                ctx == null -> ContextAnswer.None // no conversation yet — the honest answer
                bot?.bool("enabled") == false -> ContextAnswer.None // the gate is CLOSED (Damian disabled the bot)
                else -> Context(
                    workspacePath = ctx.str("workspacePath") ?: return ContextAnswer.Degraded,
                    workspaceIdentity = ctx.str("workspaceIdentity"),
                    activeTaskId = ctx.str("activeTaskId"),
                    mode = ctx.str("mode") ?: "?",
                ).let(ContextAnswer::Ctx)
            }
        }

        QueryAnswer.Unavailable -> ContextAnswer.Degraded
        is QueryAnswer.Failed -> ContextAnswer.Degraded
    }

    /** After an inbound fires, watch the conversation it lands in (the stream's own subscription). */
    private suspend fun watchContext() {
        val ctx = contextAnswer() as? ContextAnswer.Ctx ?: return
        val taskId = ctx.value.activeTaskId ?: return
        ops.subscribe(taskId, ctx.value.workspacePath)
    }

    /** Python's status line: «🎯 Active: «title»» — the index first, taskMeta as fallback. */
    @Suppress("ReturnCount") // one guard per fallback of the title lookup
    private suspend fun activeLine(taskId: String?, workspacePath: String): String {
        if (taskId == null) return ACTIVE_NONE
        val fromIndex = history.ctxTasks(SCOPE_FOR_PICK).firstOrNull { it.sid == taskId }?.title
        val title = fromIndex?.takeIf { it.isNotEmpty() }
            ?: when (val answer = ops.query(ChannelQuery.TaskMeta(taskId, workspacePath))) {
                is QueryAnswer.Ok -> parse(answer.resultJson)?.str("title")?.takeIf { it.isNotEmpty() }
                else -> null
            }
            ?: return "🎯 Active: <code>${PyText.esc(taskId.takeLast(SID_TAIL))}</code>"
        return "🎯 Active: «${PyText.esc(title)}»"
    }

    /** Python's «🤖 model · 🧠 thinking · 🎛 mode» — model/thinking from db.sqlite, the mode from the context. */
    @Suppress("ReturnCount") // one guard per absent source (no task, no persisted selection)
    private suspend fun modelLine(taskId: String?, mode: String): String? {
        if (taskId == null) return null
        val sel = runCatching { history.modelSelection(taskId) }.getOrNull() ?: return null
        return "🤖 ${PyText.esc(sel.modelId)} · 🧠 ${PyText.esc(sel.reasoningLevel)} · 🎛 ${modeLabel(mode)}"
    }

    /** Python's MODES labels (plan/yolo vocabulary; the fork says full_access for yolo). */
    private fun modeLabel(mode: String): String = when (mode) {
        "plan" -> "🗺 Plan mode"
        "yolo", "full_access" -> "🔓 Full access"
        else -> mode
    }

    /** listTasks' bare array → (taskId, title, updatedAt) — the open-conversation resolver's metas. */
    private fun parseTaskMetas(json: String): List<TaskBrief> = runCatching {
        (Json.parseToJsonElement(json) as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { m ->
                val id = m.str("taskId") ?: return@mapNotNull null
                TaskBrief(id = id, title = m.str("title") ?: id, updatedAt = m.long("updatedAt"))
            }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun degraded(what: String): String =
        "📡 The channel is down ($what unavailable) — the fork's host is not running?"

    private fun parse(json: String): JsonObject? = runCatching {
        Json.parseToJsonElement(json) as? JsonObject
    }.getOrNull()

    private fun parseStrings(json: String): List<String> = runCatching {
        (Json.parseToJsonElement(json) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun parseStringsOf(json: String, key: String): List<String> =
        parse(json)?.let { root ->
            (root[key] as? JsonArray)
                ?.mapNotNull { el -> (el as? JsonPrimitive)?.content }
                .orEmpty()
        }.orEmpty()

    companion object {
        internal const val UNKNOWN =
            "🤷 Unknown command — /help lists the thirteen this bot speaks."
        internal const val DEGRADED =
            "📡 The channel to the app is down — the fork's host is not running? " +
                "Commands that need it wait for the app."

        /** The botsService's own refusal replies, matched by their stable markers (en-US). */
        const val TASK_RUNNING_MARKER = "still running"
        const val TASK_MISSING_MARKER = "Task not found"

        /** Python's /task usage hint, renamed to the owner's vocabulary. */
        internal const val TASK_USAGE =
            "⚠️ /tasks · /tasks &lt;n&gt; picks · /tasks new [project] · /all_tasks shows all"

        /** Python's status: «🎯 Active: none (/task n to pick one)». */
        internal const val ACTIVE_NONE = "🎯 Active: none (/tasks n to pick one)"
        internal const val SID_TAIL = 8

        /** The CLI session store's child-session convention — measured live 30/10 in the db rows. */
        internal const val SUBAGENT_SESSION_PREFIX = "sess_subagent_"
        internal const val NAMES_IN_NUCLEAR = 3

        /** Python's caps: /task lists 10, /all_tasks 20, picks resolve against 30. */
        private const val TASKS_CAP = 10
        private const val ALL_CAP = 20

        /**
         * The pick-resolution scope — PUBLIC because the daemon's live view
         * resolves the card's real title against the SAME window (one
         * constant, no drift): the 30 freshest rows are where a live task's
         * row lives.
         */
        const val SCOPE_FOR_PICK = 30

        /** The bind confirmation loop: the server does NOT serialize inbounds (§3.2). */
        internal const val BIND_CONFIRM_TRIES = 10
        internal const val BIND_CONFIRM_EVERY_MS = 200L

        /**
         * The eight — the SINGLE source of both the «/» menu (BotMenu)
         * and /help's list, so they can never drift apart again (the
         * owner, 29/10: «la lista de commands no corresponde con la lista
         * real»). Descriptions in Python's menu_desc tone.
         */
        val MENU = listOf(
            "tasks" to "conversations by project · /tasks n picks the active one",
            "all_tasks" to "all conversations, loose ones included",
            "status" to "what is running + the active conversation",
            "plan" to "plan, model and effort — tap to change",
            "last" to "re-sends the active conversation's last answer",
            "compact" to "shrinks the active conversation's context",
            "context_window" to "context window used — tokens, model window and percent",
            "queue" to "the parked texts — launch or discard",
            "stop" to "stops the active task's turn",
            "nuclear" to "emergency: stops EVERYTHING and resets the bot",
            "quota" to "coding plan credits",
            "subagents" to "the active subagents · «inactivos» shows today's ended",
            "help" to "all commands",
        )

        internal val HELP = (listOf("📖 <b>The thirteen</b> (the owner's MVP — the rest is hot-fix territory):") +
            MENU.map { (cmd, desc) -> "/$cmd — $desc" } +
            listOf(
                "",
                "Anything else is TEXT: it goes to the ACTIVE conversation (live mode) " +
                    "and the live view shows every thought, tool and line as it happens. " +
                    "Free text never opens a new one — /tasks new does.",
            )).joinToString("\n")

        /**
         * taskSnapshot → the last ASSISTANT message — /last's answer AND
         * the live view's final shower (the daemon's own parser is this
         * one, injected), so both renders can never drift.
         */
        fun snapshotLastAnswer(resultJson: String): String? = runCatching {
            val root = Json.parseToJsonElement(resultJson) as? JsonObject ?: return null
            val messages = root["messages"] as? JsonArray ?: return null
            messages.asSequence()
                .mapNotNull { it as? JsonObject }
                .filter { (it["role"] as? JsonPrimitive)?.content == "assistant" }
                .lastOrNull()
                ?.let { (it["content"] as? JsonPrimitive)?.content }
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

        /** /last's body cap: the header + the ✂️ note must fit inside the pipe's CH_REPLY. */
        internal const val LAST_BODY_CAP = 3600

        /**
         * The context meter's render (pure — /context_window's body): the
         * header, one model line, the ▰▱ bar. Honesty ladder: used+window →
         * the full meter; used only → «ventana desconocida» with NO percent
         * and NO bar (you cannot scale without the window); neither → the
         * honest ⚠️. Zero invented numbers, ever.
         */
        @Suppress("ReturnCount") // one guard per honesty tier of the meter
        fun renderContextWindow(json: String, title: String): String {
            val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
                ?: return CTX_WINDOW_UNPARSEABLE
            val model = root.str("model")?.takeIf { it.isNotEmpty() } ?: CTX_MODEL_UNKNOWN
            val used = root.long("usedTokens") ?: return CTX_WINDOW_NO_DATA
            if (used < 0) return CTX_WINDOW_NO_DATA
            val header = "🪟 <b>Ventana de contexto «${PyText.esc(title)}»</b>"
            val window = root.long("windowTokens")
            val windowSource = root.str("windowSource")
            val line = if (window == null || window <= 0) {
                "Modelo <b>${PyText.esc(model)}</b>: ${fmtK(used)} · ventana desconocida"
            } else {
                // Rounded to the nearest whole percent; >100% is honest (the window is gone).
                val pct = ((used * CTX_PERCENT_SCALE + window / 2) / window).toInt()
                val filled = ((pct.toLong() * CTX_BAR_CELLS + CTX_PERCENT_HALF) / CTX_PERCENT_SCALE)
                    .toInt()
                    .coerceIn(0, CTX_BAR_CELLS)
                val bar = CTX_BAR_FULL.repeat(filled) + CTX_BAR_EMPTY.repeat(CTX_BAR_CELLS - filled)
                // the owner's own 1M calibration when nothing exposes the
                // window — rendered approximate, never as a hard fact
                val approx = if (windowSource == "owner_default") " (~)" else ""
                "Modelo <b>${PyText.esc(model)}</b>: ${fmtK(used)} de ${fmtK(window)}$approx ($pct%)\n$bar"
            }
            return "$header\n$line"
        }

        /** nK for the meter: one decimal below 100K (45.3K), whole K at or above (123K). */
        private fun fmtK(tokens: Long): String = when {
            tokens >= CTX_K_DECIMAL_LIMIT -> "${(tokens + CTX_K_ROUND_HALF) / CTX_K_DIVISOR}K"
            else -> String.format(Locale.ROOT, "%.1f", tokens.toDouble() / CTX_K_DIVISOR) + "K"
        }

        /** The fork's refusal code for a query the connected host predates. */
        internal const val UNKNOWN_QUERY_CODE = "unknown_query"
        internal const val CTX_WINDOW_UNPARSEABLE =
            "⚠️ No pude leer la respuesta de la ventana de contexto"

        /** No usedTokens at all: the conversation never reported usage — ask, don't invent. */
        internal const val CTX_WINDOW_NO_DATA =
            "⚠️ La conversación aún no reporta uso de contexto — mandá un mensaje y volvé a probar."
        private const val CTX_MODEL_UNKNOWN = "desconocido"
        private const val CTX_BAR_FULL = "▰"
        private const val CTX_BAR_EMPTY = "▱"

        /** The bar's fixed cell count (a ~10-cell meter reads at a glance). */
        private const val CTX_BAR_CELLS = 10
        private const val CTX_PERCENT_SCALE = 100L
        private const val CTX_PERCENT_HALF = 50L
        private const val CTX_K_DIVISOR = 1000L

        /** The K rendering switch: one decimal below, whole K from here up. */
        private const val CTX_K_DECIMAL_LIMIT = 100_000L
        private const val CTX_K_ROUND_HALF = 500L

        /** The dispatch tool names the FORK itself uses (zcodeTaskServiceAdapter's isSubagentDispatchToolName). */
        internal val SUBAGENT_DISPATCH_NAMES = setOf("Task", "Agent")

        /** The inactive row's title cap — the prompt head, one phone line. */
        private const val SUBAGENT_TITLE_CAP = 64

        /** The discard's one-line preview of what was thrown away. */
        private const val DISCARD_PREVIEW = 40

        /** (base, rest) of a /command line, with @botname stripped — null when not a command. */
        @Suppress("ReturnCount") // one guard per non-command shape
        fun parseCommand(text: String): Pair<String, String>? {
            if (!text.startsWith("/")) return null
            val parts = text.substring(1).split(' ', limit = 2)
            if (parts[0].isEmpty()) return null
            val base = parts[0].substringBefore('@').lowercase()
            return base to (parts.getOrNull(1)?.trim() ?: "")
        }

        /** Python's args.out_of_list_error. */
        fun outOfList(word: String, n: Int): String =
            "⚠️ Number out of the list (1 to $n) — /$word."

        /**
         * Python's task_list_report — the text half of the tappable list:
         * header, `{stamp} {n}. {title}[ · 📁ws]` per row, footer. The stamp
         * carries BOTH marks when they co-occur (Damian, 26/10: «quiero los
         * dos emojis»); the buttons themselves are a next hot-fix (the
         * daemon's send path has no markup yet).
         */
        fun renderTaskList(
            rows: List<TaskRow>,
            running: Set<String>,
            activeSid: String?,
            activeWs: String?,
            all: Boolean,
            watchedSid: String? = null,
        ): String {
            if (rows.isEmpty()) {
                return if (all) "🧠 No tasks — /all_tasks shows all." else "🧠 No tasks project — /all_tasks shows all."
            }
            val header = if (all) {
                "💬 <b>All conversations</b> (loose ones included) — /tasks only projects"
            } else {
                "💬 <b>Conversations by project</b> — pick one with /tasks n"
            }
            val lines = mutableListOf(header)
            rows.forEachIndexed { i, t ->
                val stamp = buildString {
                    if (t.sid in running) append(STAMP_RUNNING)
                    if (t.sid == activeSid) append(STAMP_ACTIVE)
                }.ifEmpty { STAMP_IDLE }
                val tag = if (t.ws.isNotEmpty() && t.ws != activeWs) {
                    " · 📁${PyText.esc(t.ws.substringAfterLast('/'))}"
                } else {
                    ""
                }
                val title = t.title.takeIf { it.isNotEmpty() } ?: t.sid.takeLast(SID_TAIL)
                lines.add("$stamp ${i + 1}. ${PyText.esc(title)}$tag")
            }
            lines.add("")
            lines.add("Free text you send goes to the active conversation (🎯).")
            if (watchedSid != null && watchedSid != activeSid) {
                val watchedTitle = rows.firstOrNull { it.sid == watchedSid }
                    ?.title?.takeIf { it.isNotEmpty() } ?: watchedSid.takeLast(SID_TAIL)
                lines.add(
                    "👁 Viendo en vivo: «${PyText.esc(watchedTitle)}» — tu texto sigue yendo a la " +
                        "activa (el bind se niega mientras ese turno corre; el attach es solo lectura).",
                )
            }
            return lines.joinToString("\n")
        }

        /**
         * quota → Python's quota_report when the snapshot carries the limits
         * (⏱ 5 hours + 📅 Weekly, used/limit credits, resets, ⚠️ at ≥75%);
         * the remaining-only snapshot keeps the one-line fallback.
         */
        @Suppress("ReturnCount", "CyclomaticComplexMethod") // one guard per degrade / honesty tier
        fun renderQuota(json: String, nowMs: Long): String {
            val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
                ?: return "⚠️ Quota answer unparseable"
            val reason = root.str("unavailableReason")
            if (reason != null) return "📊 Quota unavailable: ${PyText.esc(reason)}"
            renderQuotaWindows(root)?.let { return it }
            renderStartPlanBuckets(root)?.let { return it }
            val remaining = root.obj("remaining")
                ?: return "📊 Quota: no pool info (the app is not authenticated?)"
            val pct = remaining.int("percentage")
            val count = remaining.int("count")
            val reset = remaining.long("nextResetTime")
            val pool = when {
                pct != null -> "$pct%"
                count != null -> "$count uses"
                else -> "?"
            }
            val resetText = reset?.let {
                val secs = (it - nowMs) / 1000
                when {
                    secs <= 0 -> "resets now-ish"
                    secs < SECS_HOUR -> "resets in ${secs / SECS_MIN}m"
                    else -> "resets in ${secs / SECS_HOUR}h"
                }
            } ?: ""
            return "📊 Coding plan: <b>$pool remaining</b>${if (resetText.isNotEmpty()) " · $resetText" else ""}"
        }

        /**
         * The Start Plan's buckets (30/10's owner order: «necesito saber
         * cuántos tokens me quedan en las próximas 5 horas y en la semana;
         * si el plan no muestra esos dos valores, la cantidad y el
         * porcentaje usado — 340K/500K usado x%»). One line per REAL
         * balance bucket: used/total in tokens with the USED percent and
         * its own reset — the fork's builder carries the numbers raw
         * (usage/unit/remaining), never a bot-invented window.
         */
        @Suppress("CyclomaticComplexMethod") // one guard per bucket-line field
        private fun renderStartPlanBuckets(root: JsonObject): String? {
            val limits = (root.obj("quota")?.get("limits") as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.filter { it.num("unit") != null || it.num("usage") != null }
                .orEmpty()
            if (limits.isEmpty()) return null
            val lines = mutableListOf("📊 <b>Start plan</b>")
            limits.forEach { lim ->
                val total = lim.num("unit")
                val used = lim.num("usage") ?: 0.0
                val label = lim.str("period") ?: lim.str("meter") ?: "cuota"
                val body = when {
                    total != null && total > 0 ->
                        "${fmtNum(used)}/${fmtNum(total)} usado " +
                            "${((used / total) * 100).toInt()}%"

                    else -> "${fmtNum(used)} usados"
                }
                val reset = lim.long("nextResetTime")?.let { " · resets ${fmtReset(it)}" } ?: ""
                val pctAlert = if (total != null && total > 0 && (used / total) >= 0.75) " ⚠️" else ""
                lines.add("• $label: $body$reset$pctAlert")
            }
            return lines.joinToString("\n")
        }

        /** Python's windows: only CREDIT_LIMIT rows, 5h first, the weekly after. */
        private fun renderQuotaWindows(root: JsonObject): String? {
            val limits = (root.obj("quota")?.get("limits") as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.filter { it.str("type") == "CREDIT_LIMIT" }
                .orEmpty()
            if (limits.isEmpty()) return null
            val lines = mutableListOf("📊 <b>Coding plan</b>")
            limits.sortedByDescending { it.int("number") ?: 0 }.forEach { lim ->
                val name = when (lim.int("number")) {
                    5 -> "⏱ 5 hours"
                    1 -> "📅 Weekly"
                    else -> "🔒 Limit"
                }
                val pct = lim.int("percentage")
                val pctText = pct?.let { " ($it%)" } ?: ""
                val alert = if ((pct ?: 0) >= 75) " ⚠️" else ""
                val reset = lim.long("nextResetTime")?.let { " · resets ${fmtReset(it)}" } ?: ""
                lines.add(
                    "$name: ${fmtNum(lim.num("currentValue"))}/${fmtNum(lim.num("usage"))}" +
                        " credits$pctText$reset$alert",
                )
            }
            return lines.joinToString("\n")
        }

        /** A JSON number as Double (ints and fractions alike — credits can be 31.5). */
        private fun JsonObject.num(key: String): Double? =
            (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

        /** Python's fmt_num: K only from 10K up, no decimals on small counts. */
        private fun fmtNum(n: Double?): String = when {
            n == null -> "?"
            n >= NUM_K_FROM -> {
                val text = String.format(Locale.ROOT, "%.1f", n / NUM_K_DIVISOR)
                "${text.replace(".0", "")}K"
            }

            else -> n.toInt().toString()
        }

        private const val NUM_K_FROM = 10_000.0
        private const val NUM_K_DIVISOR = 1_000.0

        /** Python's datetime_dt: HH:MM today, dd/MM HH:MM otherwise (local zone). */
        private fun fmtReset(epochMs: Long): String {
            val zone = ZoneId.systemDefault()
            val dt = Instant.ofEpochMilli(epochMs).atZone(zone)
            return if (dt.toLocalDate() == LocalDate.now(zone)) {
                dt.format(TIME_FMT)
            } else {
                dt.format(DATE_TIME_FMT)
            }
        }

        private const val STAMP_RUNNING = "🔄"
        private const val STAMP_ACTIVE = "🎯"
        private const val STAMP_IDLE = "·"
        private const val SECS_MIN = 60L
        private const val SECS_HOUR = 3600L
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
        private val DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm")
    }

    internal data class Context(
        val workspacePath: String,
        val workspaceIdentity: String?,
        val activeTaskId: String?,
        val mode: String,
    )

    internal data class TaskBrief(val id: String, val title: String, val updatedAt: Long?)
}
