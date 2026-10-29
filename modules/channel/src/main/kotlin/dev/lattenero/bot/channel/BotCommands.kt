package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val logger = KotlinLogging.logger {}

/** The dispatcher's data source — the client's queries behind one interface (tests fake it). */
interface ChannelOps {
    suspend fun query(query: ChannelQuery): QueryAnswer

    suspend fun sendInbound(text: String, messageId: String, receivedAt: Long): InboundReceipt

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
@Suppress("TooManyFunctions") // the eight + their shared plumbing — one function per command
class BotCommands(
    private val ops: ChannelOps,
    private val hub: LiveHub,
    /** /nuclear's daemon half: stopAll is the channel's; resetting OUR state is the daemon's. */
    private val daemonReset: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** One incoming line. Null = not a command (the router sends it as inbound). */
    suspend fun handle(text: String, updateId: Long): String? {
        val (base, rest) = parseCommand(text) ?: return route(text, updateId)
        return when (base) {
            "tasks" -> tasks()
            "all_tasks", "alltasks" -> allTasks()
            "status" -> status()
            "stop" -> stop()
            "nuclear", "nuke" -> nuclear()
            "quota" -> quota()
            "help", "start" -> help()
            "subagents", "agents" -> subagents()
            else -> UNKNOWN
        }.let { reply ->
            logger.info { "command /$base (${rest.length} args) -> ${reply.length} chars" }
            reply
        }
    }

    /** Free text → the REAL conversation (the router's live leg). */
    private suspend fun route(text: String, updateId: Long): String? {
        val receipt = ops.sendInbound(text, messageId = "tg-$updateId", receivedAt = clock())
        return when (receipt) {
            InboundReceipt.FIRED -> {
                watchContext() // the turn this text starts is the one to stream
                null // the live view + the final render speak; no ack noise
            }

            InboundReceipt.UNAVAILABLE ->
                "📡 The channel to the app is down (the fork's host is not running?) — " +
                    "text did NOT reach a conversation. Try again after the app restarts."
        }
    }

    // ---- the eight ---------------------------------------------------------

    /** /tasks — the focused list (listTasks of the bot's active context). */
    private suspend fun tasks(): String =
        when (val ctx = contextAnswer()) {
            ContextAnswer.Degraded -> degraded("botStatus")
            ContextAnswer.None -> NO_CONTEXT
            is ContextAnswer.Ctx -> when (val answer = ops.query(ChannelQuery.ListTasks(ctx.value.workspacePath))) {
                is QueryAnswer.Ok ->
                    renderTaskArray(answer.resultJson, ctx.value.activeTaskId, focused = true, nowMs = clock())

                else -> degraded("listTasks")
            }
        }

    /** /all_tasks — every conversation the task list knows (timeline, all scopes the bot sees). */
    private suspend fun allTasks(): String =
        when (val ctx = contextAnswer()) {
            ContextAnswer.Degraded -> degraded("botStatus")
            ContextAnswer.None -> NO_CONTEXT
            is ContextAnswer.Ctx -> {
                val query = ChannelQuery.TaskList(
                    listKind = "timeline",
                    workspaceScopes = listOf(WorkspaceScope(ctx.value.workspacePath, ctx.value.workspaceIdentity)),
                    limit = LIST_CAP,
                )
                when (val answer = ops.query(query)) {
                    is QueryAnswer.Ok -> {
                        val items = (parse(answer.resultJson)?.obj("items") as? JsonArray)?.toString()
                            ?: answer.resultJson
                        renderTaskArray(items, ctx.value.activeTaskId, focused = false, nowMs = clock())
                    }

                    else -> degraded("taskList")
                }
            }
        }

    /** /status — what runs NOW (sessions-index) + the daemon's own live view. */
    private suspend fun status(): String {
        val lines = mutableListOf("🖥️ <b>bot status</b>")
        when (val ctx = contextAnswer()) {
            ContextAnswer.Degraded -> lines.add(DEGRADED)
            ContextAnswer.None ->
                lines.add("context: none (no conversation yet — text goes to the app's active task)")
            is ContextAnswer.Ctx -> {
                lines.add(
                    "context: ${PyText.esc(ctx.value.workspacePath.substringAfterLast('/'))}" +
                        " · ${PyText.esc(ctx.value.mode)}",
                )
                ctx.value.activeTaskId?.let { lines.add("active task: <code>${PyText.esc(it)}</code>") }
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

    /** /stop — the channel's stop (the SAME V4 stop as the UI's button). */
    private suspend fun stop(): String =
        when (val ctx = contextAnswer()) {
            ContextAnswer.Degraded -> DEGRADED
            ContextAnswer.None -> NO_CONTEXT
            is ContextAnswer.Ctx -> {
                val taskId = ctx.value.activeTaskId
                    ?: return "🤷 Nothing to stop — the bot's context has no active task."
                when (val answer = ops.query(ChannelQuery.Stop(taskId))) {
                    is QueryAnswer.Ok ->
                        "⏹️ Stop sent to <code>${PyText.esc(taskId.takeLast(SID_TAIL))}</code>" +
                            " — the turn ends on its own."

                    is QueryAnswer.Unavailable -> DEGRADED
                    is QueryAnswer.Failed -> "⚠️ Stop failed (${answer.code}): ${PyText.esc(answer.message ?: "?")}"
                }
            }
        }

    /** /nuclear — stopAll (the channel's half) + the daemon's reset (our half). */
    private suspend fun nuclear(): String {
        val workspace = (contextAnswer() as? ContextAnswer.Ctx)?.value?.workspacePath
        val stopped = when (val answer = ops.query(ChannelQuery.StopAll(workspace))) {
            is QueryAnswer.Ok -> parseStringsOf(answer.resultJson, "stopped")
            else -> emptyList()
        }
        daemonReset()
        return "☢️ Nuclear: stopped ${stopped.size} run(s)" +
            (stopped.take(NAMES_IN_NUCLEAR).joinToString("") {
                " · <code>${PyText.esc(it.takeLast(SID_TAIL))}</code>"
            }) +
            " — the daemon's live state was reset. ⌘Q of the app stays YOURS (the golden rule)."
    }

    /** /quota — the coding plan's pool (IUsageStatsService through the channel). */
    private suspend fun quota(): String {
        return when (val answer = ops.query(ChannelQuery.Quota())) {
            is QueryAnswer.Ok -> renderQuota(answer.resultJson, clock())
            is QueryAnswer.Unavailable -> DEGRADED
            is QueryAnswer.Failed -> "⚠️ Quota failed (${answer.code}): ${PyText.esc(answer.message ?: "?")}"
        }
    }

    /** /subagents — the live lanes grouped by parentToolUseId (the stream's own grouping). */
    private suspend fun subagents(): String {
        val turn = hub.peek() ?: return "📭 No turn is being watched — send text first."
        val lanes = turn.tools.filter { it.parentToolUseId != null }
        val thinkers = turn.subagentThought
        return when {
            lanes.isEmpty() && thinkers.isEmpty() ->
                "🤷 No subagents in «${PyText.esc(turn.title)}» — the main agent works alone."

            else -> buildString {
                append("🤖 <b>subagents of «${PyText.esc(turn.title)}»:</b>\n")
                lanes.forEach { lane ->
                    val running = " · running ${LiveTurn.fmtDur(clock() - lane.openedAtMs)}"
                    val status = lane.status?.let { " · $it" } ?: running
                    append("• <code>${PyText.esc(lane.toolId.takeLast(SID_TAIL))}</code>")
                    append(" ${PyText.esc(lane.name)}$status\n")
                }
                thinkers.forEach { (parent, chars) ->
                    append("• parent <code>${PyText.esc(parent.takeLast(SID_TAIL))}</code>")
                    append(" · 🧠 $chars chars thought\n")
                }
            }.trimEnd()
        }
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

    internal companion object {
        internal const val UNKNOWN =
            "🤷 Unknown command — /help lists the eight this bot speaks."
        internal const val DEGRADED =
            "📡 The channel to the app is down — the fork's host is not running? " +
            "Commands that need it wait for the app."
        internal const val NO_CONTEXT =
            "📭 No active conversation context — send text first (it opens/uses the app's real conversation)."
        internal const val SID_TAIL = 8
        internal const val LIST_CAP = 20
        internal const val NAMES_IN_NUCLEAR = 3

        internal val HELP = listOf(
            "📖 <b>The eight</b> (MVP — the rest is hot-fix territory):",
            "/tasks — conversations of the active project",
            "/all_tasks — every conversation (timeline)",
            "/status — what runs now + the live view",
            "/stop — stop the active task's turn",
            "/nuclear — stop EVERYTHING + reset the bot",
            "/quota — coding plan credits",
            "/subagents — the live task's subagents",
            "/help — this list",
            "",
            "Anything else is TEXT: it goes to the REAL conversation (live mode) " +
                "and the live view shows every thought, tool and line as it happens.",
        ).joinToString("\n")

        /** (base, rest) of a /command line, with @botname stripped — null when not a command. */
        @Suppress("ReturnCount") // one guard per non-command shape
        fun parseCommand(text: String): Pair<String, String>? {
            if (!text.startsWith("/")) return null
            val parts = text.substring(1).split(' ', limit = 2)
            if (parts[0].isEmpty()) return null
            val base = parts[0].substringBefore('@').lowercase()
            return base to (parts.getOrNull(1)?.trim() ?: "")
        }

        /** listTasks' bare array / taskList's items → the numbered list. */
        @Suppress("CyclomaticComplexMethod") // the list render: parse, sort, cap, mark
        fun renderTaskArray(json: String, activeTaskId: String?, focused: Boolean, nowMs: Long): String {
            val metas = runCatching {
                when (val el = Json.parseToJsonElement(json)) {
                    is JsonArray -> el
                    is JsonObject -> (el["items"] as? JsonArray) ?: JsonArray(emptyList())
                    else -> JsonArray(emptyList())
                }
            }.getOrNull() ?: JsonArray(emptyList())
            val tasks = metas.mapNotNull { it as? JsonObject }
                .mapNotNull { m ->
                    val id = m.str("taskId") ?: return@mapNotNull null
                    TaskBrief(id = id, title = m.str("title") ?: id, updatedAt = m.long("updatedAt"))
                }
                .sortedByDescending { it.updatedAt ?: 0 }
            if (tasks.isEmpty()) return "📭 No conversations${if (focused) " in the active project" else ""}."
            val head = if (focused) "💬 <b>conversations (project)</b>" else "💬 <b>all conversations</b>"
            return (listOf(head) + tasks.take(LIST_CAP).mapIndexed { i, t ->
                val mark = if (t.id == activeTaskId) " 🎯" else ""
                "  ${i + 1}. «${PyText.esc(t.title.take(TITLE_CAP))}»$mark" +
                    " · ${t.updatedAt?.let { ago(it, nowMs) } ?: "?"}"
            } + (if (tasks.size > LIST_CAP) listOf("  ··· ${tasks.size - LIST_CAP} more") else emptyList()))
                .joinToString("\n")
        }

        private fun ago(epochMs: Long, nowMs: Long): String {
            val s = (nowMs - epochMs) / 1000
            return when {
                s < SECS_MIN -> "${s}s ago"
                s < SECS_HOUR -> "${s / SECS_MIN}m ago"
                s < SECS_DAY -> "${s / SECS_HOUR}h ago"
                else -> "${s / SECS_DAY}d ago"
            }
        }

        private const val TITLE_CAP = 48
        private const val SECS_MIN = 60L
        private const val SECS_HOUR = 3600L
        private const val SECS_DAY = 86400L

        /** quota → the pool line (remaining.percentage / nextResetTime of the entitlement snapshot). */
        @Suppress("ReturnCount") // one guard per degrade of the entitlement snapshot
        fun renderQuota(json: String, nowMs: Long): String {
            val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
                ?: return "⚠️ Quota answer unparseable"
            val reason = root.str("unavailableReason")
            if (reason != null) return "📊 Quota unavailable: ${PyText.esc(reason)}"
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
    }

    internal data class Context(
        val workspacePath: String,
        val workspaceIdentity: String?,
        val activeTaskId: String?,
        val mode: String,
    )

    internal data class TaskBrief(val id: String, val title: String, val updatedAt: Long?)
}
