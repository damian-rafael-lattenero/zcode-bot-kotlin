package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
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

/**
 * A collapsed CoT block's tap (the `cb:<id>` buttons, 2/11): expand/fold
 * IN PLACE on the block's own message. The PUBLISHER owns the message
 * ids and the block texts (it spoke the collapsed messages and keeps the
 * registry) — the commands only route the tap. The answer is the toast's
 * text; null = the edit itself is the feedback (no new message lands in
 * the chat — the card's always-last law keeps its order).
 */
fun interface CoTTaps {
    suspend fun toggle(registryId: Int): String?
}

/**
 * A turn's LIVE subagent dispatch lanes — the ONE filter every reader
 * shares (/tasks's rows, /subagents's keyboard and groups, the ⏹️ stop's
 * resolution): an open top-level Agent/Task dispatch. The 30/10 bug's
 * law: a subagent row IS this dispatch lane, never the agents' inner
 * tools; four copies of this predicate once drifted — never again.
 */
internal fun dispatchLanes(turn: TurnLive?): List<ToolLive> =
    turn?.tools.orEmpty().filter {
        it.parentToolUseId == null && it.status == null && it.name in BotCommands.SUBAGENT_DISPATCH_NAMES
    }

/**
 * THE DESTRUCTIVE TAPS — the owner gate's vocabulary (a PURE predicate,
 * pinned by the suite). The poller's fail-closed owner filter is the
 * WORKING boundary of the wire; this is the second lock, the one that
 * still refuses when a future path bypasses dispatch (the /deploy
 * precedent, applied to every tap that spends something un-spendable):
 *
 * - `intr:` — ⚡ kills the busy turn mid-flight;
 * - `odp:` — ⚡ pushes by INTERRUPTING the busy child;
 * - `sst:` — ⏹️ stops a subagent's generation;
 * - `nuc:` — ☢️ stops every run, kills every CLI, restarts app and bot;
 * - `stop:` — 💀 hard-stops every active task's turn;
 * - `gfy:` — spends a coding-plan reset: finite and one-way.
 *
 * Everything else the router accepts (attach, view, pickers, local drafts,
 * arming an order) is reversible or inert: those taps keep their old law.
 *
 * THE FALLBACK'S OWN SPELLINGS count too: the router's `else` runs the
 * data as typed words ([Keyboards.callbackText]), so a payload that
 * spells `/nuclear` or `/stop` IS the same tap — the prefix list alone
 * would have let it through.
 */
private val DESTRUCTIVE_TAP_PREFIXES = listOf("intr:", "odp:", "sst:", "nuc:", "stop:", "gfy:")

/** The slash vocabulary the callback fallback can spell (the taps' typed names). */
private val DESTRUCTIVE_COMMANDS = setOf("nuclear", "nuke", "stop")

internal fun isDestructiveTap(data: String): Boolean = when {
    DESTRUCTIVE_TAP_PREFIXES.any(data::startsWith) -> true
    else -> {
        val typed = BotCommands.parseCommand(Keyboards.callbackText(data))?.first
        typed != null && typed in DESTRUCTIVE_COMMANDS
    }
}

/** The dispatcher's data source — the client's queries behind one interface (tests fake it). */
interface ChannelOps {
    suspend fun query(query: ChannelQuery): QueryAnswer

    suspend fun sendInbound(
        text: String,
        messageId: String,
        receivedAt: Long,
        attachments: List<InboundAttachment> = emptyList(),
    ): InboundReceipt

    /**
     * An ORDER direct to one session (the fork's inbound taskId — the ✍️
     * armed shot at an attached subagent), AWAITED: the answer is the
     * fork's own truth — [InboundAnswer.Failed] carries the v4 rejection
     * verbatim (the 2/11 lie: fire-and-forget said «entregada» while the
     * fork rejected 7ms later). The honest default for wiring that never
     * learned orders: UNAVAILABLE — an order must NEVER ride the ordinary
     * inbound and land in the wrong conversation.
     */
    suspend fun sendOrder(text: String, messageId: String, receivedAt: Long, taskId: String): InboundAnswer =
        InboundAnswer.Unavailable

    /**
     * STEER: redirigir el turno EN CURSO (la frame `steer` del canal —
     * delivery guide del CLI). Default UNAVAILABLE: un steer jamás viaja
     * como texto libre ni como orden — sin wiring que lo hable, el
     * comando dice la verdad y nada más.
     */
    suspend fun steer(taskId: String, text: String): SteerAnswer = SteerAnswer.Unavailable

    /**
     * LA RESPUESTA del dueño a una pregunta (el fork's respond frame —
     * 6993d7e8): awaited, la verdad del host. Default UNAVAILABLE: un tap
     * sin wiring deja la pregunta armada y lo dice — nunca un tap mudo.
     */
    suspend fun respondQuestion(frame: ClientFrame.Respond): RespondAnswer = RespondAnswer.Unavailable

    /**
     * The bind's send: the inbound's own answer (replies = the botsService's
     * words). [bindTaskId] (the fork's atomic bind, cap «bind»): el host
     * apunta el contexto con la ley del /task y entrega el texto en el MISMO
     * frame — el refusal viaja como única reply.
     */
    suspend fun sendInboundAwait(
        text: String,
        messageId: String,
        receivedAt: Long,
        bindTaskId: String? = null,
    ): InboundAnswer

    /**
     * Whether the live server advertised the atomic bind (cap «bind»).
     * Default false: an un-advertised frame MUST NOT be sent — an old host
     * would ignore the field and the text would land in the wrong
     * conversation (the two-step bind below stays the honest path).
     */
    fun supportsBind(): Boolean = false

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
    /**
     * The transcript feed's level (the /verbosity knob): WRITTEN here,
     * read PER TICK by the publisher — the daemon injects ONE shared
     * instance (the file-backed store), tests the in-memory default.
     */
    private val verbosity: VerbositySetting = VolatileVerbosity(),
    /** The questions' board (the answer round-trip) — null = unwired: the taps answer honestly. */
    private val board: QuestionBoard? = null,
    /**
     * /quota's DIRECT rung, PROVIDER-AWARE (owner's 3/11: «estoy usando la
     * api de open code… me gustaría que me muestre los límites disponibles
     * de opencode go» — the old z.ai-hardcoded leg answered the retired
     * coding plan): the argument is the ACTIVE conversation's providerId,
     * and the daemon's wiring serves that provider's own meter (z.ai's
     * monitor endpoint or OpenCode Go's /usage). A null answer (no key,
     * dead network, bad shape) or no wiring = the channel path answers as
     * always; a THROWN answer is caught here — the ladder never throws at
     * the user. Tests inject fakes.
     */
    private val quotaDirect: (suspend (String?) -> String?)? = null,
    /**
     * The collapsed CoT blocks' taps (the cb: buttons): the publisher's
     * registry — expand/fold IN PLACE on the block's own message. Null
     * (unwired) = a stale cb: tap answers the honest retired note.
     */
    private val blockTaps: CoTTaps? = null,
    /**
     * The collapsed TOOL OUTPUTS' taps (the `bp:`/`bf:` buttons): the
     * publisher's pager registry — open a numbered page in place, fold it
     * back. Null (unwired) = the honest miss.
     */
    private val boxTaps: BoxTaps? = null,
    /**
     * THE FILE VIEWER's taps (the `fvm:`/`fvp:` buttons, the owner's 5/11):
     * a write/edit close's «📄 archivo completo» reads the file FRESH AT
     * TAP TIME and pages it in place. Null (unwired) = the honest miss.
     */
    private val fileTaps: FileTaps? = null,
    /**
     * The last fired prompt's store (Python's F22): the SAVE rides the
     * free-text fire path's success branch, /retry reads it back. Null =
     * unwired (tests): /retry answers the honest «nada» and the save is
     * a no-op — never a crash.
     */
    private val lastPrompt: LastPrompt? = null,
    /**
     * /brain + /memory (ADR-019/020): the agent-brain reading list. Null
     * (the daemon has no brain client) = both commands answer honestly that
     * they are not wired — never a crash and never invented results.
     */
    private val brain: BrainCommands? = null,
    /**
     * THE OWNER'S ID — the SAME source /deploy reads (`owner_user_id` in
     * the bot's config), as a provider because the config is the edge's
     * file (DeployFlow's own shape: no duplicated parsing, one truth).
     * Null = fail closed: with no owner in the config NOBODY fires a
     * destructive tap — never «the first tap that arrives».
     */
    private val ownerUserId: () -> Long? = { null },
) {

    /** /nuclear's single-flight companion: the in-process half (the wiring adds the cross-restart mtime gate). */
    private val nuclearGate = java.util.concurrent.atomic.AtomicBoolean(false)

    /** /tasks new's pending intent: the NEXT free text skips the bind and CREATES (Python's /task new). */
    private var newIntent = false

    /**
     * THE OWNER'S SELECTION (the 5/11 exam): the /tasks tap's explicit
     * choice — the router's freshest-row heuristic can NEVER clobber it
     * (measured live 16:05: his text flew to «go_to_background» because
     * the index said «open conversation moved»). Armed by a CONFIRMED tap
     * bind, moved by the next tap, wiped by ☢️ and /tasks new; a selection
     * that dies in the index is cleared at facts time (the old laws
     * resume). It rides EVERY fire on the wire (bindTaskId) — the sid is
     * the aim, not a memory the next route may override.
     */
    private var selectedSid: String? = null
    private var selectedTitle: String? = null

    /** The question's free-text arm: the shortId whose question eats the NEXT line (the qf: tap). */
    private var pendingAnswerFor: String? = null

    /**
     * /plan_model_effort's DRAFT (owner, 3/11): the three choices live
     * LOCALLY until ✅ Aceptar — a partial tap never writes, and a draft
     * dies with [PLAN_DRAFT_TTL_MS] so a catalog change can never be
     * applied blind.
     */
    private var planDraft: PlanMenu.Draft? = null

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
            "quota" -> quota()
            "usage" -> BotReply(usage())
            "ctx" -> ctxList()
            "help", "start" -> BotReply(help())
            "subagents", "agents" -> subagents(rest)
            "plan", "plan_model_effort" -> plan() // the old name keeps muscle memory alive
            "mode" -> mode(rest)
            "plan_mode" -> planMode(rest)
            "last" -> BotReply(lastAnswer())
            "compact" -> compact()
            "context_window" -> contextWindow()
            "queue" -> queue()
            "retry" -> retry(updateId)
            "run" -> runCmd(rest, updateId)
            "steer" -> steer(rest)
            "verbosity" -> verbosityCmd(rest)
            "brain" -> BotReply(brain?.brain(rest) ?: BrainTexts.UNWIRED)
            "memory" -> BotReply(brain?.memory(rest) ?: BrainTexts.UNWIRED)
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
     *
     * THE SECOND LOCK ([fromId], U9's defense-in-depth): every
     * DESTRUCTIVE tap re-checks its sender against the config's owner
     * before routing — the same refusal `/deploy` speaks, and nothing
     * runs behind it. Non-destructive taps keep their old law (a watch
     * still needs no ownership); the poller's filter stays the working
     * boundary.
     */
    suspend fun onCallback(data: String, updateId: Long, fromId: Long?): BotReply? {
        if (isDestructiveTap(data) && !isOwner(fromId)) {
            logger.warn { "callback \"$data\" REFUSED — sender $fromId is not the owner; nothing executed" }
            return BotReply(NOT_OWNER)
        }
        return routeCallback(data, updateId)
    }

    /** The owner's second lock: a null sender or an unwired/blank owner never passes (fail closed). */
    private fun isOwner(fromId: Long?): Boolean {
        val owner = ownerUserId() ?: return false
        return fromId == owner
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // one branch per tap vocabulary — the dispatcher's own shape
    private suspend fun routeCallback(data: String, updateId: Long): BotReply? = when {
        data.startsWith("intr:") -> interruptNow(data.removePrefix("intr:"), updateId)

        data.startsWith("t:") -> taskAttach(data.removePrefix("t:"), updateId)

        data.startsWith("sa:") -> laneAttach(data.removePrefix("sa:"))

        data.startsWith("so:") -> orderArm(data.removePrefix("so:"))

        // the DETAIL CARD (sn:): the full uncapped name the row label
        // caps, one honest status line, the fresh action row — a dead
        // agent's card stays a pure memory (no buttons, no orders)
        data.startsWith("sn:") -> subagentDetail(data.removePrefix("sn:"))

        // the ⏹️ tap (sst:<tail>) — intercepted HERE, never left to the
        // callbackText fallback: a stray tail falling through would ride
        // to the agent as the owner's own words (the graver bug)
        // the ⚡/⏳ question's taps (2/11 23:12): the owner decides PER
        // MESSAGE whether a busy child gets pushed or queued
        data.startsWith("odp:") -> resolvePending(push = true)

        data.startsWith("odq:") -> resolvePending(push = false)

        data.startsWith("sst:") -> subagentStop(data.removePrefix("sst:"))

        // LA PREGUNTA's taps (the answer round-trip): qa:<sid>:<i> answers
        // the option; qf:<sid> arms the next line as the free-text answer.
        data.startsWith("qa:") -> questionTap(data.removePrefix("qa:"), updateId)

        data.startsWith("qf:") -> answerArm(data.removePrefix("qf:"))

        data.startsWith("qgo:") -> {
            logger.info { "callback ⏳ qgo: the parked text keeps its natural wait" }
            BotReply("📬 Queda encolado — entra cuando el turno termine.")
        }

        data.startsWith("ql:") -> queueLaunch(data.removePrefix("ql:"), updateId)

        data.startsWith("qd:") -> queueDiscard(data.removePrefix("qd:"), updateId)

        // /verbosity's picker tap: set the level (the write lands in the
        // injected setting — the publisher's very next tick obeys)
        data.startsWith("vb:") -> verbosityTap(data.removePrefix("vb:"))

        // /subagents's view switch: the ONE button each view offers (fixed
        // 4-byte datas — Telegram's 64B law never even breathes)
        data == "sao:" -> subagents("inactivos")

        data == "saa:" -> subagents("activos")

        data.startsWith("pl:") || data.startsWith("pm:") || data.startsWith("pe:") || data == "pd:" ||
            data == "pac:" || data == "pcl:" -> planTap(data)

        // /mode's picker tap (mo:<mode>): the SAME write the typed command
        // runs — one path, one vocabulary, one honest answer
        data.startsWith("mo:") -> mode(data.removePrefix("mo:"))

        // /plan_mode's toggle tap (pmd:on / pmd:off — the spec's pm: was
        // already the plan MODEL picker's): the SAME write the typed command
        data.startsWith("pmd:") -> planMode(data.removePrefix("pmd:"))

        // A5's gifts: the first tap ARMS (a confirmation card), the second
        // (cly:/gfy:) fires, gxn: cancels. The planId/type/key live in the
        // pending slot, never in the callback (Telegram's 64-byte law).
        data == "cly:" -> giftClaimGo()

        data == "gfy:" -> giftResetGo()

        data == "gxn:" -> giftCancel()

        data.startsWith("cl:") -> giftClaimConfirm(data.removePrefix("cl:"))

        data.startsWith("gf:") -> giftResetConfirm(data.removePrefix("gf:"))

        // LA EXPANSIÓN EN EL LUGAR (2/11): the collapsed CoT block's tap —
        // the tap toggles the block WHOLE on its OWN message (expand, then
        // fold). The registry lives in the publisher ([CoTTaps]); an
        // unwired cb: tap answers the honest note. The toast text (when
        // non-null) is the miss — a working tap edits silently.
        data.startsWith("cb:") -> {
            val id = data.removePrefix("cb:").toIntOrNull()
            if (id == null || blockTaps == null) {
                logger.info { "callback cb: UNWIRED — no CoT registry behind this button" }
                BotReply("📖 Ese botón quedó de otro turno — los bloques CoT se abren en sus propios mensajes.")
            } else {
                blockTaps.toggle(id)?.let { BotReply(it) }
            }
        }

        // THE OUTPUT PAGER (the owner, 3/11): bp:<id>:<page> opens/flips a
        // numbered page IN PLACE; bf:<id> folds the message back to its
        // header. The boxes' state lives in the fold (U5b) and the tap edge
        // is the publisher's; unwired or unknown ids answer the honest miss
        // (the toast), never silence.
        data.startsWith("bp:") -> {
            val parts = data.removePrefix("bp:").split(":")
            val id = parts.getOrNull(0)?.toIntOrNull()
            val page = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (id == null || boxTaps == null) {
                BotReply(BoxTaps.MISS)
            } else {
                boxTaps.open(id, page)?.let { BotReply(it) }
            }
        }

        data.startsWith("bf:") -> {
            val id = data.removePrefix("bf:").toIntOrNull()
            if (id == null || boxTaps == null) {
                BotReply(BoxTaps.MISS)
            } else {
                boxTaps.fold(id)?.let { BotReply(it) }
            }
        }

        // THE FILE VIEWER (the owner, 5/11): fvm:<id> opens the file FRESH
        // (read at tap time — later edits included), fvp:<id>:<page> turns
        // the numbered pages, each tap a fresh re-read. Unwired/unknown =
        // the honest miss, never silence.
        data.startsWith("fvm:") -> {
            val id = data.removePrefix("fvm:").toIntOrNull()
            if (id == null || fileTaps == null) {
                BotReply("🤷 Ese botón quedó de otro turno — el registro de archivos ya no lo tiene.")
            } else {
                fileTaps.open(id)?.let { BotReply(it) }
            }
        }

        data.startsWith("fvp:") -> {
            val parts = data.removePrefix("fvp:").split(":")
            val id = parts.getOrNull(0)?.toIntOrNull()
            val page = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (id == null || fileTaps == null) {
                BotReply("🤷 Ese botón quedó de otro turno — el registro de archivos ya no lo tiene.")
            } else {
                fileTaps.page(id, page)?.let { BotReply(it) }
            }
        }

        else -> {
            logger.info { "callback \"$data\" -> \"${Keyboards.callbackText(data)}\"" }
            handle(Keyboards.callbackText(data), updateId)
        }
    }

    /**
     * /plan_model_effort's taps — the DRAFT flow (owner, 3/11).
     *
     * pl:/pm:/pe: only move a LOCAL draft, one step at a time; each step
     * re-resolves its siblings against the catalog (a cross-plan pair can
     * never exist). Nothing is written until ✅ Aceptar (pac:) sends the
     * WHOLE valid triple in one frame. pcl: discards; pd: writes the draft
     * (or the selection in force) as the app's default.
     *
     * Why: the old flow wrote one field per tap and the fork merged it with
     * the stale sibling — plan opencode-go-chat × GLM-5.3-Flash was refused
     * («not selectable»), and every intermediate state was already a write.
     */
    private suspend fun planTap(data: String): BotReply {
        val ctx = (contextAnswer() as? ContextAnswer.Ctx)?.value
        val ws = ctx?.workspacePath
        val v = planViews(ws) ?: return BotReply(DEGRADED)
        val title = ctx?.activeTaskId?.let { id ->
            history.ctxTasks(SCOPE_FOR_PICK).firstOrNull { r -> r.sid == id }?.title
        }
        return when (data) {
            "pcl:" -> {
                planDraft = null
                BotReply(
                    "✖️ Borrador descartado — la selección vigente quedó intacta.",
                    markup = PlanMenu.keyboard(v),
                )
            }

            "pac:" -> acceptDraft(v, ws)
            "pd:" -> defaultFromDraft(v, ws)
            else -> draftTap(v, title, data)
        }
    }

    /** The three draft steps — each one re-resolves its siblings against the catalog. */
    @Suppress("ReturnCount") // the base draft + each step's honest refusal
    private fun draftTap(v: PlanMenu.Views, title: String?, data: String): BotReply {
        val base = liveDraft() ?: v.startDraft(clock()) ?: return BotReply(PLAN_UNKNOWN)
        return if (data.startsWith("pl:")) {
            draftStep(v, title, v.draftWithPlan(base, data.removePrefix("pl:")), PLAN_UNKNOWN)
        } else if (data.startsWith("pm:")) {
            draftStep(v, title, v.draftWithModel(base, data.removePrefix("pm:")), MODEL_UNKNOWN)
        } else {
            draftStep(
                v,
                title,
                v.draftWithEffort(base, data.removePrefix("pe:")),
                planEffortUnknown(v.effortVocabulary(base.plan, base.model).joinToString(" · ").ifEmpty { "—" }),
            )
        }
    }

    /** The draft's current value, dropping one built longer than the TTL (a catalog may have changed). */
    private fun liveDraft(): PlanMenu.Draft? =
        planDraft?.takeIf { clock() - it.atMs <= PLAN_DRAFT_TTL_MS }

    /**
     * The mirror's read of the OPEN PLAN DRAFT (the refresher's 🟡 lines):
     * the live draft inside its TTL, or null — validity against the fresh
     * catalog is the REFRESHER's check (it owns the fresh read).
     */
    fun openDraft(): PlanMenu.Draft? = liveDraft()

    /** One draft step's render — the status line ALWAYS says what would be applied. */
    private fun draftStep(
        v: PlanMenu.Views,
        title: String?,
        next: PlanMenu.Draft?,
        refusal: String,
    ): BotReply {
        if (next == null) return BotReply(refusal)
        planDraft = next
        return BotReply(PlanMenu.statusLine(v, title, next), markup = PlanMenu.keyboard(v, next))
    }

    /** ✅ Aceptar: the ONE write — the whole triple, only when the catalog still validates it. */
    @Suppress("ReturnCount") // no draft / not-applicable / the channel's three answers
    private suspend fun acceptDraft(v: PlanMenu.Views, ws: String?): BotReply {
        val d = liveDraft() ?: return BotReply(PLAN_NOTHING_TO_ACCEPT, markup = PlanMenu.keyboard(v))
        if (!v.draftValid(d)) {
            planDraft = null
            return BotReply(PLAN_DRAFT_STALE, markup = PlanMenu.keyboard(v))
        }
        val set = ops.query(
            ChannelQuery.SetModelSelection(
                scope = "conversation",
                plan = d.plan,
                model = d.model,
                effort = d.effort,
                workspacePath = ws,
            ),
        )
        return when (set) {
            is QueryAnswer.Ok -> {
                planDraft = null
                planReplyFrom(set.resultJson, "✅ aplicado")
            }

            // THE HONEST FAILURE (the owner's 4/11 timeout drill): the draft
            // SURVIVES every failure — the tap can always be retried — and
            // the copy says it, instead of the fork's opaque words.
            is QueryAnswer.Unavailable -> BotReply(
                "$DEGRADED\nEl draft sigue armado — tocá ✅ Aceptar cuando el canal vuelva.",
                markup = PlanMenu.keyboard(v, d),
            )

            is QueryAnswer.Failed -> applyFailure(set, v, d)
        }
    }

    /**
     * The apply's honest failure (4/11: the Aceptar tap landed while the
     * fork host was mid-hiccup — «(timeout): no answer in 15s» said
     * nothing the owner could ACT on). A timeout names the real suspect
     * (the channel dropped or the host is saturated) and the retry; any
     * other typed refusal keeps the fork's words AND the same promise:
     * the draft is still armed, Aceptar retries, Descartar discards.
     */
    private fun applyFailure(set: QueryAnswer.Failed, v: PlanMenu.Views, d: PlanMenu.Draft): BotReply {
        val head = when (set.code) {
            "timeout" -> "📡 El canal no respondió a tiempo (se cayó justo en ese momento o el host está saturado)."
            else -> "⚠️ No pude aplicar (${set.code}): ${PyText.esc(set.message ?: "?")}"
        }
        return BotReply(
            "$head\nEl draft sigue ARMADO — tocá ✅ Aceptar para reintentar, o ❌ Descartar para tirarlo.",
            markup = PlanMenu.keyboard(v, d),
        )
    }

    /** 📌 the app's default: the draft when there is one, else the selection in force. */
    @Suppress("ReturnCount") // the missing identity + the channel's three answers
    private suspend fun defaultFromDraft(v: PlanMenu.Views, ws: String?): BotReply {
        val draft = liveDraft()?.takeIf { v.draftValid(it) }
        val plan = draft?.plan ?: v.plan ?: return BotReply(PLAN_UNKNOWN)
        val model = draft?.model ?: v.model ?: return BotReply(MODEL_UNKNOWN)
        val set = ops.query(
            ChannelQuery.SetModelSelection(
                scope = "default",
                plan = plan,
                model = model,
                effort = draft?.effort ?: v.effort,
                workspacePath = ws,
            ),
        )
        return when (set) {
            is QueryAnswer.Ok -> planReplyFrom(set.resultJson, "📌 default de la app actualizado")
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

    /** /plan_model_effort — «elegir el plan, el modelo y el effort, y recién ahí aplicar». */
    @Suppress("ReturnCount") // one guard per honest state (no context, degraded, unreadable, menu)
    private suspend fun plan(): BotReply {
        val ctx = contextAnswer()
        // No conversation yet: the fork's modelSelection needs a bot context,
        // so querying it would fail with an internal error. Say the real
        // reason instead of blaming the deploy (the old fallback did).
        if (ctx is ContextAnswer.None) {
            return BotReply(
                "📭 No hay conversación activa — abrí una en ZCode o usá /tasks n. " +
                    "/plan_model_effort lee el plan, el modelo y el effort de la conversación activa.",
            )
        }
        val value = (ctx as? ContextAnswer.Ctx)?.value ?: return BotReply(DEGRADED)
        val title = value.activeTaskId?.let {
            history.ctxTasks(SCOPE_FOR_PICK).firstOrNull { r -> r.sid == it }?.title
        }
        val v = planViews(value.workspacePath) ?: return BotReply(
            "📡 No pude leer la selección — ¿el fork está deployado con los frames de /plan? " +
                "(commits 3d07cace+, falta el deploy del fork).",
        )
        val draft = liveDraft()?.takeIf { v.draftValid(it) }
        return BotReply(
            PlanMenu.statusLine(v, title, draft),
            markup = PlanMenu.keyboard(v, draft),
            // THE MIRROR MARK: the open menu self-refreshes on the fork's
            // config_option_update — keyed by the active task (no active
            // task = no stream events for it = no mirror, the honest nothing)
            mirror = value.activeTaskId?.let { MenuMirror.Mark(it, value.workspacePath, MenuMirror.Kind.PLAN) },
        )
    }

    private fun planReplyFrom(resultJson: String, note: String): BotReply {
        val v = PlanMenu.parse(resultJson) ?: return BotReply("⚠️ Respuesta de selección ilegible")
        return BotReply("$note\n${PlanMenu.statusLine(v, null)}", markup = PlanMenu.keyboard(v))
    }

    /**
     * /mode — la conversación y su modo de colaboración (Python's
     * `_cmd_mode`): bare shows the mode IN FORCE (read from the session's
     * own `meta.mode`, never remembered) with the five tap rows; typed
     * applies through the fork's `setMode` cap — the SAME switch the
     * desktop toolbar uses. An unknown word is refused with the real
     * vocabulary and NOTHING is sent (a typo must never downgrade a
     * conversation); a host without the frame says the fork deploy is the
     * fix; the channel down is the 📡.
     */
    @Suppress("ReturnCount") // one guard per honest tier (down, no context, bare, typed, unknown)
    private suspend fun mode(rest: String): BotReply {
        val ctx = when (val answer = contextAnswer()) {
            is ContextAnswer.Ctx -> answer.value
            ContextAnswer.None -> return BotReply(MODE_NO_CONTEXT)
            ContextAnswer.Degraded -> return BotReply(DEGRADED)
        }
        val taskId = ctx.activeTaskId ?: return BotReply(MODE_NO_CONTEXT)
        val title = history.ctxTasks(SCOPE_FOR_PICK).firstOrNull { it.sid == taskId }
            ?.title?.takeIf { it.isNotEmpty() } ?: taskId.takeLast(SID_TAIL)
        val requested = rest.trim().lowercase()
        if (requested.isEmpty()) {
            val current = currentMode(taskId, ctx.workspacePath)
            // THE MIRROR MARK: this menu self-refreshes on the fork's
            // config_option_update (the wiring registers the born message)
            return BotReply(
                ModeMenu.statusLine(title, current),
                markup = ModeMenu.keyboard(current),
                mirror = MenuMirror.Mark(taskId, ctx.workspacePath, MenuMirror.Kind.MODE),
            )
        }
        val mode = ModeMenu.byId(requested)
            ?: return BotReply(
                ModeMenu.usage(),
                markup = ModeMenu.keyboard(currentMode(taskId, ctx.workspacePath)),
            )
        return applyMode(taskId, title, mode)
    }

    /** The write: awaited, every outcome the fork's own word (Python's applied copy). */
    private suspend fun applyMode(taskId: String, title: String, mode: ModeMenu.Mode): BotReply =
        when (val answer = ops.query(ChannelQuery.SetMode(taskId, mode.id))) {
            is QueryAnswer.Ok -> BotReply(ModeMenu.applied(title, mode), markup = ModeMenu.keyboard(mode.id))
            is QueryAnswer.Unavailable -> BotReply(DEGRADED)
            is QueryAnswer.Failed -> if (answer.code == UNKNOWN_QUERY_CODE) {
                BotReply(MODE_OLD_FORK)
            } else {
                BotReply("⚠️ No pude aplicar el modo (${answer.code}): ${PyText.esc(answer.message ?: "?")}")
            }
        }

    /** The mode in force — the snapshot's `meta.mode`, one message requested (the read is cheap). */
    private suspend fun currentMode(taskId: String, ws: String?): String? =
        (ops.query(ChannelQuery.TaskSnapshot(taskId, ws, messageLimit = 1)) as? QueryAnswer.Ok)
            ?.let { ModeMenu.currentFrom(it.resultJson) }

    /**
     * /plan_mode — THE SEPARATE TOGGLE (4/11: plan left /mode's keyboard).
     * The state is READ from the fork's truth (the snapshot's `meta.mode
     * == "plan"`, never remembered); bare shows the card (the current
     * state marked), typed on/off toggles directly, an unknown word is
     * the card. ON writes `plan`; OFF writes the DEFAULT (Full access —
     * another mode is one /mode tap away). A mode switch is NOT
     * destructive: no owner-gate vocabulary here.
     */
    @Suppress("ReturnCount") // one guard per honest tier (down, no context, bare, typed, unknown)
    private suspend fun planMode(rest: String): BotReply {
        val ctx = when (val answer = contextAnswer()) {
            is ContextAnswer.Ctx -> answer.value
            ContextAnswer.None -> return BotReply(MODE_NO_CONTEXT)
            ContextAnswer.Degraded -> return BotReply(DEGRADED)
        }
        val taskId = ctx.activeTaskId ?: return BotReply(MODE_NO_CONTEXT)
        val title = history.ctxTasks(SCOPE_FOR_PICK).firstOrNull { it.sid == taskId }
            ?.title?.takeIf { it.isNotEmpty() } ?: taskId.takeLast(SID_TAIL)
        val current = currentMode(taskId, ctx.workspacePath)
        when (rest.trim().lowercase()) {
            "on" -> return planModeApply(taskId, title, on = true)
            "off" -> return planModeApply(taskId, title, on = false)
            // an unknown word never writes: the card again (bare included)
        }
        val on = PlanMode.isOn(current)
        return BotReply(PlanMode.statusLine(title, on), markup = PlanMode.keyboard(on))
    }

    /** The toggle's write: the same setMode switch, the 🧭 voice (OFF writes the default). */
    private suspend fun planModeApply(taskId: String, title: String, on: Boolean): BotReply {
        val mode = ModeMenu.byId(if (on) "plan" else ModeMenu.DEFAULT_MODE_ID)
            ?: return BotReply(DEGRADED) // unreachable with the built-in vocabulary — the honest tier, not a guess
        return when (val answer = ops.query(ChannelQuery.SetMode(taskId, mode.id))) {
            is QueryAnswer.Ok -> BotReply(PlanMode.applied(title, on), markup = PlanMode.keyboard(on))
            is QueryAnswer.Unavailable -> BotReply(DEGRADED)
            is QueryAnswer.Failed ->
                if (answer.code == UNKNOWN_QUERY_CODE) {
                    BotReply(MODE_OLD_FORK)
                } else {
                    BotReply("⚠️ No pude aplicar Plan mode (${answer.code}): ${PyText.esc(answer.message ?: "?")}")
                }
        }
    }

    // ── A5: the coding plan's gifts (the 🎁 read and its two confirmed writes) ──

    /**
     * The gifts read: a dead plane is EMPTY (silence), an old host is
     * silence too (no capability probe, no warning) — A5's degradation
     * contract: no data → no line.
     */
    private suspend fun giftsFor(taskId: String?): GiftsMenu.Gifts =
        when (val answer = ops.query(ChannelQuery.CodingPlanGifts(taskId))) {
            is QueryAnswer.Ok -> GiftsMenu.parse(answer.resultJson, clock())
            is QueryAnswer.Failed -> {
                if (answer.code != UNKNOWN_QUERY_CODE) {
                    logger.info { "gifts: ${answer.code} — no lines (${answer.message ?: "?"})" }
                }
                GiftsMenu.EMPTY
            }
            QueryAnswer.Unavailable -> GiftsMenu.EMPTY
        }

    /** The conversation in front (the gifts' provider hint; null is fine — the host resolves). */
    private suspend fun activeTaskId(): String? =
        (contextAnswer() as? ContextAnswer.Ctx)?.value?.activeTaskId

    /** `cl:<planId>` — re-read the offer (the truth may have moved) and ask the ✅. */
    @Suppress("ReturnCount") // one guard per honest tier (gone, already captcha-gated, armed)
    private suspend fun giftClaimConfirm(planId: String): BotReply {
        val offer = giftsFor(activeTaskId()).offers.firstOrNull { it.planId == planId }
            ?: return BotReply("🤷 Esa oferta ya no está — corré /quota de nuevo.")
        // already learned: this plan needs a captcha the build cannot make —
        // skip the doomed attempt and hand the browser door directly
        if (planId in captchaGatedPlans) return webDoor()
        pendingGift = PendingGift("claim", planId, null, null, offer.displayName, clock())
        logger.info { "gift: claim ARMED for ${offer.planId} — waiting the ✅" }
        return BotReply(GiftsMenu.claimConfirmation(offer), markup = GiftsMenu.confirmKeyboard("cly:"))
    }

    /** `cly:` — the claim itself: the host's verdict IS the answer. */
    private suspend fun giftClaimGo(): BotReply {
        val p = livePendingGift("claim")
            ?: return BotReply(GIFT_NOTHING_ARMED)
        pendingGift = null
        logger.info { "gift: CLAIM firing for ${p.planId}" }
        return when (val answer = ops.query(ChannelQuery.ClaimCodingPlan(p.planId.orEmpty()))) {
            is QueryAnswer.Ok -> BotReply(GiftsMenu.claimResult(answer.resultJson, p.name))
            is QueryAnswer.Unavailable -> BotReply(DEGRADED)
            is QueryAnswer.Failed -> {
                if (answer.message?.contains("captcha", ignoreCase = true) == true) {
                    p.planId?.let(captchaGatedPlans::add)
                }
                giftFailure("reclamar", answer)
            }
        }
    }

    /** The browser door: ONE line + a real url button (the only way a bot opens a browser). */
    private fun webDoor(): BotReply = BotReply(
        GIFT_CAPTCHA,
        markup = GiftsMenu.webDoorKeyboard("🌐 Abrir la web (planes)", GIFT_CLAIM_URL),
    )

    /** `gf:<type>` — how many are left and how soon the earliest dies; then the ✅. */
    private suspend fun giftResetConfirm(type: String): BotReply {
        val alive = giftsFor(activeTaskId()).resets.filter { it.type == type }
        if (alive.isEmpty()) return BotReply("🤷 Ese reset ya no está — corré /quota de nuevo.")
        pendingGift = PendingGift(
            kind = "reset",
            planId = null,
            resetType = type,
            idempotencyKey = java.util.UUID.randomUUID().toString(),
            name = "reset",
            atMs = clock(),
        )
        logger.info { "gift: reset ARMED ($type, ${alive.size} alive) — waiting the ✅" }
        return BotReply(
            GiftsMenu.resetConfirmation(type, alive.size, alive.first().expireAt, clock()),
            markup = GiftsMenu.confirmKeyboard("gfy:"),
        )
    }

    /** `gfy:` — spend the reset with the key generated at CONFIRM time (a double tap re-sends the same key). */
    private suspend fun giftResetGo(): BotReply {
        val p = livePendingGift("reset")
            ?: return BotReply(GIFT_NOTHING_ARMED)
        pendingGift = null
        logger.info { "gift: RESET firing (${p.resetType})" }
        return when (
            val answer = ops.query(
                ChannelQuery.UseCodingPlanReset(activeTaskId(), p.resetType.orEmpty(), p.idempotencyKey.orEmpty()),
            )
        ) {
            is QueryAnswer.Ok -> BotReply(
                "✅ Reset usado (${if (p.resetType == GiftsMenu.WEEK) "semanal" else "5h"}). " +
                    "Corré /quota en un momento para ver el pool nuevo.",
            )
            is QueryAnswer.Unavailable -> BotReply(DEGRADED)
            is QueryAnswer.Failed -> giftFailure("usar el reset", answer)
        }
    }

    /** `gxn:` — nothing was touched; say exactly that. */
    private fun giftCancel(): BotReply {
        pendingGift = null
        return BotReply("📬 Cancelado — no se tocó nada.")
    }

    private fun giftFailure(verb: String, answer: QueryAnswer.Failed): BotReply {
        val message = answer.message.orEmpty()
        // the server's captcha gate: this fork build has the security edition
        // STUBBED, so no proof can ever be produced here — answer with the
        // browser door (a url button), not a wall of text
        if (message.contains("captcha", ignoreCase = true)) {
            return webDoor()
        }
        return if (answer.code == UNKNOWN_QUERY_CODE) {
            BotReply(GIFT_OLD_FORK)
        } else {
            BotReply("⚠️ No pude $verb (${answer.code}): ${PyText.esc(message.ifBlank { "?" })}")
        }
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
     * The ⏹️ tap (`sst:<tail>` — beside the ▶️ of every live subagent):
     * resolve the tail to the child's FULL session id ([SubagentStop]'s
     * pure half — lanes first, then the store's fresh rows) and fly the
     * SAME awaited stop a /stop uses, now at subagent level (the fork's
     * stopGeneration is goal-pause — the ack says the generation stops,
     * never a promised kill). Every outcome is the honest one: the real
     * error verbatim on failure, the refuso when the fork has not named
     * the child's session yet, «ya no está vivo» when nothing answers.
     */
    private suspend fun subagentStop(tail: String): BotReply {
        val lanes = dispatchLanes(hub.peek())
        return when (val target = SubagentStop.resolve(lanes, runningRows(), tail, clock())) {
            is SubagentStopTarget.Gone -> {
                logger.info { "callback ⏹️ sst: tail=$tail resolves to nothing alive — honest gone" }
                BotReply("⏹️ Ese subagente ya no está vivo.")
            }

            is SubagentStopTarget.Unnamed -> {
                logger.info { "callback ⏹️ sst: «${target.name}» alive but unnamed (no launch ACK yet)" }
                BotReply("⏹️ «${PyText.esc(target.name)}» — todavía no sé su sesión — probá en un momento.")
            }

            is SubagentStopTarget.Ready -> {
                logger.info { "callback ⏹️ sst: STOP ${target.sid} («${target.name}»)" }
                when (val answer = ops.query(ChannelQuery.Stop(target.sid))) {
                    is QueryAnswer.Ok -> BotReply("⏹️ «${PyText.esc(target.name)}» — generación detenida.")
                    is QueryAnswer.Unavailable -> BotReply(DEGRADED)
                    is QueryAnswer.Failed -> BotReply(
                        "⚠️ No pude detener «${PyText.esc(target.name)}» (${answer.code}): " +
                            PyText.esc(answer.message ?: "?"),
                    )
                }
            }
        }
    }

    /**
     * The store's running rows for the open context — the SAME facts the
     * ⏹️ reads; the ORDER gates (arm + fire) and the detail card share
     * them so ONE freshness law answers for every subagent affordance.
     */
    private suspend fun runningRows(): List<RunningSubagent> {
        val parentSid = hub.peek()?.taskId
            ?: (contextAnswer() as? ContextAnswer.Ctx)?.value?.activeTaskId
        return if (parentSid != null) history.subagentsRunning(parentSid) else emptyList()
    }

    /**
     * THE OWNER'S «EMPUJAR» (2/11 23:00: «que detenga lo que está haciendo
     * y me lea el mensaje»). The fork's steer delivery is `guide` — the
     * text enters at the current tool's safe boundary, so a child inside a
     * nine-minute tool stays deaf for nine minutes. A busy child's turn is
     * therefore CANCELLED first (the ⏹️ door) and the delivery that
     * follows finds a TERMINAL task, which the runtime resumes in the
     * background WITH the text as its input. Answers true when a turn was
     * actually cut (so the ack can say what happened).
     */
    private suspend fun isBusyChild(sid: String): Boolean =
        sid.startsWith(SUBAGENT_SESSION_PREFIX) &&
            SubagentStop.orderTarget(
                hub.peek()?.tools.orEmpty(),
                runningRows(),
                sid,
                clock(),
            ) == OrderPulse.Alive

    private suspend fun interruptChild(sid: String): Boolean {
        val alive = sid.startsWith(SUBAGENT_SESSION_PREFIX) &&
            SubagentStop.orderTarget(
                hub.peek()?.tools.orEmpty(),
                runningRows(),
                sid,
                clock(),
            ) == OrderPulse.Alive
        val stopped = alive && ops.query(ChannelQuery.Stop(sid)) is QueryAnswer.Ok
        if (stopped) logger.info { "route: ⚡ child $sid INTERRUPTED before the delivery (empujar)" }
        return stopped
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
    private suspend fun route(text: String, updateId: Long): BotReply? {
        pendingAnswerFor?.let { shortId ->
            pendingAnswerFor = null
            val (consumed, reply) = answerWithFreeText(shortId, text, updateId)
            if (consumed) return reply
            // the question died while armed: the text rides the normal route
        }
        return routeCore(text, updateId)
    }

    /**
     * The fire flow's decision core — the body a TYPED line and /retry
     * share verbatim (the plan resolves as if the owner typed it). /retry
     * lands here with any armed order STASHED ([retry] restores it on the
     * way out: the one-shot is the owner's TYPED words' privilege, a
     * command never spends it) — a typed line still meets the armed order
     * first, below.
     */
    @Suppress("ReturnCount") // one branch per plan — the router's honest outcomes
    private suspend fun routeCore(text: String, updateId: Long): BotReply? {
        // LA ORDEN ARMADA PRECEDE AL ROUTER (2/11: el park de F17 se comía
        // sus textos y la orden nunca volaba): un tiro DIRECTO a una sesión
        // nombrada — el fork lo entrega en ESA sesión sin tocar la ocupada,
        // así que ni el busy-park ni los refusals le aplican.
        armedOrder?.let { return fireArmedOrder(it, text, updateId) }
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
     * La orden armada VUELA: directo a la sesión nombrada, el router ni se
     * entera. ONE SHOT: the arm is consumed HERE, before the wire — a
     * second text rides the normal route (the 2/11 law the test pins; an
     * arm that survives its shot would silently redirect the owner's next
     * line to a subagent). The flight ALSO re-asks the target's pulse
     * (the 2/11 swallow: an order into a finished child's inbox is read
     * by NOBODY, and silence is the worst lie) — the shot is spent either
     * way, the bounce says so.
     */
    @Suppress("ReturnCount") // gate, the ⚡/⏳ question, the flight — one exit each
    private suspend fun fireArmedOrder(order: ArmedOrder, text: String, updateId: Long): BotReply {
        armedOrder = null // ONE shot: se consume al volar — el siguiente texto vuelve al router
        val gate: BotReply? = when (
            SubagentStop.orderTarget(hub.peek()?.tools.orEmpty(), runningRows(), order.sessionId, clock())
        ) {
            OrderPulse.Alive -> null
            OrderPulse.Ended -> BotReply(
                "💀 «${PyText.esc(order.title)}» ya terminó — la orden NO voló a nadie (el ✍️ queda libre).",
            )

            OrderPulse.Unknown -> BotReply(
                "🤔 «${PyText.esc(order.title)}» no da señal viva ahora — la orden NO voló. " +
                    "Reabrí su carta y volvé a armar si sigue ahí.",
            )
        }
        if (gate != null) {
            logger.info { "route: ORDER to ${order.sessionId} HELD by the pulse gate" }
            return gate
        }
        logger.info {
            "route: ORDER to ${order.sessionId} («${order.title}»): ${text.take(ORDER_LOG_PREVIEW)}"
        }
        // THE OWNER'S CHOICE (2/11 23:12: «quiero que me pregunte empujar o
        // encolar»): a BUSY child never gets an automatic interrupt — the
        // question IS the door. An idle child is delivered at once (there
        // is nothing to cut and nothing to wait for).
        if (isBusyChild(order.sessionId)) {
            pendingDelivery = PendingDelivery(order.sessionId, order.title, text, steer = false, updateId, clock())
            logger.info { "route: ORDER to ${order.sessionId} HELD — asking empujar/encolar" }
            return BotReply(askPushOrQueue(order.title), markup = Keyboards.pushOrQueue())
        }
        return deliverOrder(order.sessionId, order.title, text, updateId, interrupted = false)
    }

    /** The order's real flight — shared by the idle path and the ⚡ tap. */
    private suspend fun deliverOrder(
        sid: String,
        title: String,
        text: String,
        updateId: Long,
        interrupted: Boolean,
    ): BotReply {
        return when (
            val answer = ops.sendOrder(
                text,
                messageId = "tg-$updateId-ord",
                receivedAt = clock(),
                taskId = sid,
            )
        ) {
            is InboundAnswer.Answered -> BotReply(
                if (interrupted) {
                    "⚡ Le corté el turno a «${PyText.esc(title)}» y le entregué la orden — la lee AHORA."
                } else {
                    "⚔️ Orden entregada a «${PyText.esc(title)}» — su respuesta llega en la carta attachada."
                },
            )

            is InboundAnswer.Failed -> BotReply(
                "⚠️ La orden NO entró («${PyText.esc(title)}»): ${PyText.esc(answer.code)} — " +
                    // the verdict sits at the message's TAIL (…sendText rejected
                    // (proto.sessionNotFound)) — the head is just the echo
                    PyText.esc((answer.message ?: "?").takeLast(ORDER_FAIL_TAIL)) +
                    "\nLa orden NO voló; el ✍️ queda libre.",
            )

            is InboundAnswer.TimedOut -> BotReply(
                "🤔 El fork no contestó en ${answer.waitedMs}ms — estado de la orden desconocido. " +
                    "Mirá la carta antes de reintentar.",
            )

            InboundAnswer.Unavailable -> BotReply(RouteTexts.CHANNEL_DOWN)
        }
    }

    /**
     * EL BIND ATÓMICO (the fork's bindTaskId, cap «bind»): una sola ida —
     * el host apunta el contexto con la ley completa del /task y entrega el
     * texto en el MISMO frame; el refusal viaja como única reply (el mismo
     * veredicto que leía el bind en dos pasos). Sin orden armada: una orden
     * vuela DIRECTO por su propio camino y el bind sería basura.
     */
    @Suppress("ReturnCount") // one guard per verdict
    private suspend fun fireAtomicBind(plan: RoutePlan.Fire, text: String, updateId: Long): BotReply? =
        when (val answer = ops.sendInboundAwait(text, "tg-$updateId", clock(), bindTaskId = plan.sid)) {
            is InboundAnswer.Answered -> {
                val refusal = answer.replies.firstOrNull {
                    TASK_RUNNING_MARKER in it || TASK_MISSING_MARKER in it
                }
                if (refusal != null) {
                    logger.warn { "route: atomic bind to ${plan.sid} REFUSED ($refusal) — text not delivered" }
                    BotReply(RouteTexts.BIND_FAILED)
                } else {
                    logger.info {
                        "route: atomic bind+fire at ${plan.sid} (${answer.replies.size} system replies)"
                    }
                    watchContext() // the turn this text starts is the one to stream
                    null // the live view + the final render speak; no ack noise
                }
            }

            is InboundAnswer.Unavailable -> {
                logger.warn { "route: atomic bind to ${plan.sid} — channel down" }
                BotReply(RouteTexts.CHANNEL_DOWN)
            }

            is InboundAnswer.TimedOut -> {
                // no verdict in the window: the frame MAY have flown —
                // never re-send (double delivery); speak the doubt
                logger.warn { "route: atomic bind to ${plan.sid} TIMED OUT after ${answer.waitedMs}ms" }
                BotReply(RouteTexts.BIND_FAILED)
            }

            is InboundAnswer.Failed -> {
                // the fork's own rejection, verbatim (sessionNotFound & friends)
                logger.warn { "route: atomic bind to ${plan.sid} REJECTED (${answer.code})" }
                BotReply(RouteTexts.BIND_FAILED)
            }
        }

    /**
     * Executes a Fire: bind first when the plan says so, then the text.
     * A bind that lands on a RUNNING conversation parks the text (the
     * server would only bounce it with taskRunning — honest and quiet).
     */
    /**
     * The two-step bind for forks WITHOUT the cap «bind»: the `/task`
     * command's verdict (awaited), then the mid-bind park (F17) — a turn
     * that started between the pre-check and the bind parks the text
     * instead of bouncing it. null = continue (the text flies below);
     * a BotReply = the terminal answer.
     */
    @Suppress("ReturnCount") // one exit per bind outcome
    private suspend fun fireLegacyBind(
        plan: RoutePlan.Fire,
        text: String,
        updateId: Long,
        facts: RouteFacts,
    ): BotReply? {
        when (val bind = bindTo(plan.sid, updateId)) {
            is BindOutcome.Confirmed -> logger.info { "route: bound to ${plan.sid} (${bind.how})" }

            is BindOutcome.ConfirmedUnverified ->
                logger.info { "route: bound to ${plan.sid} (${bind.how}) — the text still flies" }

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
        val ws = facts.workspacePath
        if (ws != null && isRunning(plan.sid, ws) && plan.sid != facts.activeTaskId) {
            logger.info { "route: ${plan.sid} started running mid-bind — text parked (F17)" }
            return enqueueOrRefuse(text, updateId, plan.sid)
        }
        return null
    }

    @Suppress("ReturnCount") // one exit per route outcome (atomic / legacy / order / plain)
    private suspend fun firePlan(
        plan: RoutePlan.Fire,
        text: String,
        updateId: Long,
        facts: RouteFacts,
    ): BotReply? {
        if (plan.bind && armedOrder == null && ops.supportsBind()) {
            val reply = fireAtomicBind(plan, text, updateId)
            if (reply == null) rememberPrompt(plan.sid, text) // the atomic-bind fire IS a fire (F22)
            return reply
        }
        if (plan.bind) {
            fireLegacyBind(plan, text, updateId, facts)?.let { return it }
        }
        newIntent = false
        // LA ORDEN ARMADA (the owner's loop: tap a subagent → read its stream →
        // write an order): one armed shot — the next free text goes DIRECT to
        // that session (the fork's inbound taskId), then the arm disarms. The
        // reply lands on the attached card's own stream.
        armedOrder?.let { return fireArmedOrder(it, text, updateId) }
        return when (ops.sendInbound(text, messageId = "tg-$updateId", receivedAt = clock())) {
            InboundReceipt.FIRED -> {
                rememberPrompt(plan.sid, text) // F22: the fired prompt is /retry's ammo
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
     * Python's F22 — the fire path's ONLY save: a prompt that FIRED (the
     * plain inbound or the atomic bind; a parked text, a refused bind, an
     * armed order and a dead channel never save) becomes /retry's ammo,
     * stored per the daemon's own state dir ([LastPrompt]).
     */
    private fun rememberPrompt(sid: String, text: String) {
        lastPrompt?.save(sid, text, clock())
    }

    /**
     * /retry — relaunches the SAVED prompt verbatim (Python's F22
     * `_cmd_retry`: the failure/crash/timeout rescue). The saved text runs
     * the SAME fire flow a typed line takes ([routeCore] — the plan
     * resolves as if the owner typed it), so a busy conversation PARKS
     * with the push-or-enqueue question — strictly better than Python's
     * flat refusal. Nothing saved (or the store unwired) → the honest
     * «nada para relanzar».
     *
     * THE ARMED ORDER IS NEVER /retry's AMMO (2/11): the one-shot is the
     * owner's TYPED words' privilege — the ✍️ loop (tap a subagent → read
     * its stream → write the order) must not lose its shot to a /retry,
     * and the saved prompt must never ride as the order. So the arm is
     * stashed for the fire and restored on the way out (try/finally): the
     * order survives for the NEXT free text, verbatim law intact.
     */
    private suspend fun retry(updateId: Long): BotReply {
        val saved = lastPrompt?.load()
        if (saved == null || saved.text.isBlank()) return BotReply(RETRY_NOTHING)
        logger.info { "retry: re-firing the prompt saved at ${saved.sid} (${fmtEndClock(saved.ts)})" }
        val armed = armedOrder
        armedOrder = null
        try {
            // null = FIRED (the live view + the final render speak): our confirm line closes it
            return routeCore(saved.text, updateId) ?: BotReply(retryFiredLine(saved.ts))
        } finally {
            armedOrder = armed
        }
    }

    /** /retry's confirm: the fired target's title + the save's own HH:MM stamp. */
    private suspend fun retryFiredLine(ts: Long): String {
        val active = (contextAnswer() as? ContextAnswer.Ctx)?.value?.activeTaskId
        val title = active?.let { sid -> history.ctxTasks(SCOPE_FOR_PICK).firstOrNull { it.sid == sid }?.title }
            ?.takeIf { it.isNotEmpty() }
            ?: active?.takeLast(SID_TAIL)?.takeIf { it.isNotEmpty() }
            ?: "?"
        return "🔁 Re-lanzado a «${PyText.esc(title)}» el prompt guardado ${fmtEndClock(ts)}."
    }

    /**
     * /run «prompt» — Python's _cmd_run (handlers_tasks.py:343): a FRESH
     * conversation and the prompt in it, ONE shot. The channel-side
     * guarantee (B5's make-or-break, closed with existing caps — no
     * createTask needed): /new (the fork's own bot-chat command, it rides
     * inbound) moves the bot's context to a draft, botStatus CONFIRMS the
     * draft (activeTaskId null — the same poll the bind uses, the server
     * does not serialize inbounds), and the prompt flies with the create
     * intent armed so the router NEVER freshBinds to the app's open
     * conversation. Every refusal leaves that open conversation UNTOUCHED
     * (the 2/11 clobber law); the timeout's word is «nothing fired».
     * Python spawned silently (None) — here the fired line names the new
     * conversation (the live view streams it) and F22 saves the prompt
     * for /retry, strictly better.
     */
    @Suppress("ReturnCount") // one guard per honest state: usage, armed order, the fresh-draft ladder
    private suspend fun runCmd(rest: String, updateId: Long): BotReply {
        val prompt = rest.trim()
        if (prompt.isEmpty()) return BotReply(RUN_USAGE)
        armedOrder?.let { order ->
            logger.info { "run: REFUSED — an armed order to ${order.sessionId} is live; the shot stays the owner's" }
            return BotReply(
                "✍️ Tenés una orden armada para «${PyText.esc(order.title)}» — mandala primero; " +
                    "/run no se la roba y después lo corré de nuevo.",
            )
        }
        return when (val fresh = prepareFreshDraft(updateId)) {
            is FreshDraft.Refused -> BotReply(fresh.reason)

            is FreshDraft.Ready -> {
                newIntent = true // ONE text: this prompt CREATES, the next line routes as always
                try {
                    routeCore(prompt, updateId) ?: BotReply(runFiredLine(fresh.workspacePath))
                } finally {
                    newIntent = false // spent (or lost with a refusal) — never leaks to the next line
                }
            }
        }
    }

    /** /run's fresh-draft ladder: READY (a CONFIRMED draft) or the honest refusal — never a maybe. */
    private sealed interface FreshDraft {
        /** The context IS a draft (activeTaskId null): the next text CREATES — the guarantee holds. */
        data class Ready(val workspacePath: String) : FreshDraft

        /** The refusal's whole text — the open conversation was never touched. */
        data class Refused(val reason: String) : FreshDraft
    }

    /**
     * THE NEWNESS GUARANTEE, step by step: a draft context is confirmed
     * BEFORE the prompt exists on any wire. Already a draft → ready (its
     * first text creates). Bound → the AWAITED /new (the fork's own
     * command; the taskRunning refusal is the busy verdict) and then the
     * botStatus poll ([BIND_CONFIRM_TRIES]' window) — a draft the server
     * never admitted is a refusal, never a prompt into the old
     * conversation.
     */
    @Suppress("ReturnCount") // one guard per honest state on the way to the confirmed draft
    private suspend fun prepareFreshDraft(updateId: Long): FreshDraft {
        val ctx = contextAnswer()
        if (ctx is ContextAnswer.Degraded) return FreshDraft.Refused(DEGRADED)
        if (ctx !is ContextAnswer.Ctx) return FreshDraft.Refused(RUN_NO_CONTEXT)
        val ws = ctx.value.workspacePath
        if (ctx.value.activeTaskId == null) return FreshDraft.Ready(ws)
        logger.info { "run: bound to ${ctx.value.activeTaskId} — /new first, the prompt waits for the confirmed draft" }
        val sent = ops.sendInboundAwait("/new", "tg-$updateId-new", clock())
        if (sent is InboundAnswer.Unavailable) return FreshDraft.Refused(RouteTexts.CHANNEL_DOWN)
        val replies = (sent as? InboundAnswer.Answered)?.replies.orEmpty()
        replies.firstOrNull { TASK_RUNNING_MARKER in it }?.let {
            logger.warn { "run: /new REFUSED (taskRunning) — the open conversation stays untouched" }
            return FreshDraft.Refused(RUN_BUSY)
        }
        repeat(BIND_CONFIRM_TRIES) {
            delay(BIND_CONFIRM_EVERY_MS)
            if ((contextAnswer() as? ContextAnswer.Ctx)?.value?.activeTaskId == null) {
                logger.info { "run: draft CONFIRMED (botStatus poll) — the prompt may fly" }
                return FreshDraft.Ready(ws)
            }
        }
        logger.warn {
            "run: /new never confirmed a draft in ${BIND_CONFIRM_TRIES * BIND_CONFIRM_EVERY_MS}ms — " +
                "refusing, NOTHING fired"
        }
        return FreshDraft.Refused(RUN_NOT_FRESH)
    }

    /** /run's confirm: the new conversation's project + the untouched-open law. */
    private fun runFiredLine(workspacePath: String): String =
        "🚀 /run: conversación NUEVA en «${PyText.esc(workspacePath.substringAfterLast('/'))}» — " +
            "el prompt ya entró ahí (la que tenías abierta no se tocó)."

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
            // the selection dies with its task: a vanished sid falls back to
            // the standing laws instead of firing at a ghost
            val selection = selectedSid
                ?.takeIf { runCatching { history.taskAlive(it) }.getOrDefault(false) }
            if (selection == null) {
                selectedSid = null
                selectedTitle = null
            }
            // the chosen task may run in ANOTHER workspace: its own running
            // check rides the facts (an atomic Fire into a mid-turn task in a
            // foreign workspace would die on taskRunning and LOSE the text —
            // the park-on-the-chosen law needs its turn to be visible)
            val selectedRunning = selection?.let { chosen ->
                history.ctxTasks(SCOPE_FOR_PICK).firstOrNull { it.sid == chosen }?.ws
                    ?.takeIf { it != ws }
                    ?.let { foreign -> runningIds(foreign) }
            }.orEmpty()
            RouteFacts(
                contextReachable = true,
                contextPresent = true,
                workspacePath = ws,
                activeTaskId = active,
                activeAlive = activeAlive,
                running = running + selectedRunning,
                openSid = open?.id,
                openTitle = open?.title?.takeIf { it.isNotEmpty() },
                selectedSid = selection,
                selectedTitle = selectedTitle,
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
     * The bind's four outcomes. [Confirmed] carries HOW (the reply's own
     * read-back or the botStatus poll — both count); [ConfirmedUnverified]
     * is the host's bare word when the read-back couldn't run at all (the
     * reply must never claim «verificado» then); [Refused] is the
     * server's own word (taskRunning/taskMissing — locale texts matched
     * by their stable markers); [Failed] is transport-level or a
     * read-back that contradicts the command.
     */
    internal sealed interface BindOutcome {
        data class Confirmed(val how: String) : BindOutcome

        data class ConfirmedUnverified(val how: String) : BindOutcome

        data class Refused(val reply: String, val fallback: String) : BindOutcome

        data class Failed(val reason: String) : BindOutcome
    }

    /**
     * Fires the botsService's own `/task <id>` — AWAITED (the replies are
     * the verdict) — and CONFIRMS by READ-BACK (the owner's 5/11 law: the
     * next message after a tap must be a 100%-sure confirmation, so a
     * bare «the command didn't complain» is never enough). Two proofs,
     * in order: the status reply itself names the task (`Task: title
     * (sess_…)` — formatStatusTaskLine), then a botStatus poll that must
     * say `activeTaskId == taskId`. Only when the channel answers the
     * command but botStatus stays unanswerable the whole window does the
     * host's bare verdict stand (rare, degraded — and the reply says so).
     */
    @Suppress("ReturnCount") // one exit per transport shape, then the verdict helpers
    private suspend fun bindTo(taskId: String, updateId: Long): BindOutcome {
        val sent = ops.sendInboundAwait("/task $taskId", "tg-$updateId-bind", clock())
        val replies = when (sent) {
            is InboundAnswer.Answered -> sent.replies
            is InboundAnswer.Unavailable -> return BindOutcome.Failed("channel down")
            is InboundAnswer.Failed -> return BindOutcome.Failed(sent.code)
            is InboundAnswer.TimedOut -> {
                logger.warn { "bind: /task $taskId answered nothing in ${sent.waitedMs}ms — polling botStatus" }
                emptyList()
            }
        }
        val firstReply = replies.firstOrNull()
        logger.info {
            "bind: /task $taskId replied ${replies.size} line(s)${firstReply?.let { ": $it" } ?: ""}"
        }
        bindRefusal(replies)?.let { return it }
        return if (sent is InboundAnswer.Answered) {
            answeredVerdict(taskId, replies)
        } else {
            when (val poll = pollActiveTask(taskId)) {
                PollVerdict.Confirmed -> BindOutcome.Confirmed(how = "botStatus poll")
                is PollVerdict.Contradicted, PollVerdict.Unanswerable ->
                    BindOutcome.Failed("botStatus never said activeTaskId=$taskId")
            }
        }
    }

    /** The refusal markers, matched once — taskRunning (the live verdict) first. */
    private fun bindRefusal(replies: List<String>): BindOutcome.Refused? =
        replies.firstOrNull { TASK_RUNNING_MARKER in it }?.let {
            BindOutcome.Refused(reply = it, fallback = "taskRunning — the old task still runs")
        }
            ?: replies.firstOrNull { TASK_MISSING_MARKER in it }?.let {
                BindOutcome.Refused(reply = it, fallback = "taskMissing — not in the bot's workspace/project")
            }

    /** The answered command's read-back: the reply's own task name, then the poll. */
    private suspend fun answeredVerdict(taskId: String, replies: List<String>): BindOutcome =
        if (replies.any { "($taskId)" in it }) {
            BindOutcome.Confirmed(how = "readback in reply")
        } else {
            when (val poll = pollActiveTask(taskId)) {
                PollVerdict.Confirmed -> BindOutcome.Confirmed(how = "botStatus poll")
                is PollVerdict.Contradicted -> {
                    logger.warn {
                        "bind: /task $taskId awaited ok but the read-back says ${poll.active ?: "nothing"}"
                    }
                    BindOutcome.Failed("read-back never agreed: active=${poll.active ?: "?"}")
                }

                // unanswerable the whole window — the host's word is all there is
                PollVerdict.Unanswerable -> BindOutcome.ConfirmedUnverified("awaited verdict, read-back silent")
            }
        }

    /** The poll's three truths: the id agreed, another id held the ground, or the read-back stayed silent. */
    private sealed interface PollVerdict {
        data object Confirmed : PollVerdict

        data class Contradicted(val active: String?) : PollVerdict

        data object Unanswerable : PollVerdict
    }

    /** botStatus polled until it names [taskId] — bounded by [BIND_CONFIRM_TRIES] rounds. */
    @Suppress("ReturnCount") // each truth exits once
    private suspend fun pollActiveTask(taskId: String): PollVerdict {
        var sawOther: String? = null
        repeat(BIND_CONFIRM_TRIES) {
            delay(BIND_CONFIRM_EVERY_MS)
            when (val ctx = contextAnswer()) {
                is ContextAnswer.Ctx ->
                    if (ctx.value.activeTaskId == taskId) {
                        return PollVerdict.Confirmed
                    } else {
                        sawOther = ctx.value.activeTaskId
                    }

                ContextAnswer.None, ContextAnswer.Degraded -> return PollVerdict.Unanswerable
            }
        }
        return PollVerdict.Contradicted(sawOther)
    }

    /**
     * The tap's cross-project ladder, rung one: `/project <ws>` — AWAITED,
     * same verdict discipline as [bindTo] (the workspace path matches by
     * value in the fork's resolveWorkspaceByValue, and the daemon bot is
     * allowed every workspace). Refused = the fork's own word (still
     * running / no such workspace) — never guessed.
     */
    private suspend fun bindWorkspace(ws: String, updateId: Long): BindOutcome =
        when (val sent = ops.sendInboundAwait("/project $ws", "tg-$updateId-ws", clock())) {
            is InboundAnswer.Answered -> workspaceVerdict(ws, sent.replies)
            is InboundAnswer.Unavailable -> BindOutcome.Failed("channel down")
            is InboundAnswer.Failed -> BindOutcome.Failed(sent.code)
            is InboundAnswer.TimedOut -> BindOutcome.Failed("/project timed out")
        }

    /** The switch's verdict, matched by the same stable markers the bind reads. */
    private fun workspaceVerdict(ws: String, replies: List<String>): BindOutcome {
        logger.info { "bind workspace: /project $ws replied ${replies.size} line(s)" }
        return when {
            replies.any { TASK_RUNNING_MARKER in it } ->
                BindOutcome.Refused(reply = TASK_RUNNING_MARKER, fallback = "taskRunning blocks the workspace switch")

            replies.any { WS_MISSING_MARKER in it } ->
                BindOutcome.Refused(reply = WS_MISSING_MARKER, fallback = "workspaceMissing")

            else -> BindOutcome.Confirmed(how = "workspace switched")
        }
    }

    // ---- the commands -------------------------------------------------------

    /**
     * /tasks — Python's `/task`: the projects-scope history (usage-backed
     * tasks, loose DEFAULT-workspace ones excluded), NOW with the owner's
     * 2/11 law: NO numeric args — the answer is always the TAPPABLE list
     * (tasks + subagents); `new` arms the next text to CREATE, `@sid`
     * stays for the taps' internal resolution.
     */
    private suspend fun tasks(rest: String, updateId: Long): BotReply = when {
        rest.startsWith("@") -> BotReply(pickBySid(rest.substring(1), updateId))
        rest.substringBefore(' ') == "new" -> BotReply(newConversation(rest.removePrefix("new").trim()))
        else -> taskList(all = false, hint = if (rest.isNotEmpty()) NO_NUMBERS_HINT else null)
    }

    /** /all_tasks — Python's `/all_tasks`: every conversation, loose ones included — buttons only, no numbers. */
    @Suppress("UnusedParameter") // the dispatcher's uniform (rest, updateId) shape; numbers are retired
    private suspend fun allTasks(rest: String, updateId: Long): BotReply =
        taskList(all = true, hint = if (rest.isNotEmpty()) NO_NUMBERS_HINT else null)

    /**
     * The list itself — Python's task_list_report(scope): rows + stamps +
     * footer, and the KEYBOARD in every branch that has rows (the owner's
     * 2/11 incident: the keyboard used to live ONLY in the Ctx branch, so
     * every channel bounce answered a buttonless list — the buttons ride
     * on LOCAL data: rows from the session store, subagents from the hub's
     * lanes + the store's running sessions; a tap on a dead channel fails
     * honestly at the tap, never buttonless at the list).
     */
    private suspend fun taskList(all: Boolean, hint: String? = null): BotReply {
        val rows = scopeRows(history.ctxTasks(if (all) ALL_CAP else TASKS_CAP), all, defaultWorkspacePath)
        val watched = hub.peek()
        val lanes = dispatchLanes(watched)
        return when (val ctx = contextAnswer()) {
            ContextAnswer.Degraded -> BotReply(degraded("botStatus"))
            ContextAnswer.None -> taskListWithoutContext(rows, all, hint, watched, lanes)
            is ContextAnswer.Ctx -> taskListWithContext(rows, all, hint, ctx, watched, lanes)
        }
    }

    /** The None branch: the channel's context is silent — the LOCAL truth still carries the buttons. */
    private suspend fun taskListWithoutContext(
        rows: List<TaskRow>,
        all: Boolean,
        hint: String?,
        watched: TurnLive?,
        lanes: List<ToolLive>,
    ): BotReply {
        val running = watched?.taskId?.let { setOf(it) } ?: emptySet()
        val dbRunning = watched?.taskId?.let { history.subagentsRunning(it) }.orEmpty()
        return BotReply(
            text = renderTaskList(rows, running, watched?.taskId, null, all, watchedSid = watched?.taskId) +
                (hint ?: ""),
            markup = Keyboards.tasksKeyboard(rows, running, watched?.taskId, lanes, dbRunning, clock()),
        )
    }

    /** The Ctx branch: the channel answered — the running set and the 🎯 come from it. */
    private suspend fun taskListWithContext(
        rows: List<TaskRow>,
        all: Boolean,
        hint: String?,
        ctx: ContextAnswer.Ctx,
        watched: TurnLive?,
        lanes: List<ToolLive>,
    ): BotReply {
        val running = when (val answer = ops.query(ChannelQuery.ActiveTaskIds(ctx.value.workspacePath))) {
            is QueryAnswer.Ok -> parseStrings(answer.resultJson).toSet()
            else -> emptySet()
        }
        val dbRunning = (watched?.taskId ?: ctx.value.activeTaskId)
            ?.let { history.subagentsRunning(it) }
            ?: emptyList()
        return BotReply(
            text = renderTaskList(
                rows,
                running,
                ctx.value.activeTaskId,
                ctx.value.workspacePath,
                all,
                watchedSid = watched?.taskId,
            ) + (hint ?: ""),
            markup = Keyboards.tasksKeyboard(
                rows = rows,
                running = running,
                // the 🎯 follows THE PIPE THE OWNER IS WATCHING (30/10:
                // «me muestra siempre unificador» — the bind REFUSES to
                // move while that turn runs; his selection is the attach)
                activeSid = watched?.taskId ?: ctx.value.activeTaskId,
                // ONLY the live dispatch lanes (the owner's 30/10 bug: the
                // old parentToolUseId!=null filter showed the agents' inner
                // tools as «bash, bash, read, read» — a subagent row IS the
                // open Agent/Task dispatch lane, named as he named it)
                subagents = lanes,
                dbRunning = dbRunning,
                nowMs = clock(),
            ),
        )
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
        // THE ATTACH'S INSTANT SWITCH (4/11): an idle task never streams, so
        // without this the card AND the 🎯 stayed on the previous turn until
        // its next event — the owner's «no me la deja marcada».
        hub.watchTaskId(sid, clock())
        logger.info { "callback 🟢 t: pipe ATTACHED to $sid (running=$live) — bind attempt rides along" }
        val title = row?.title?.takeIf { it.isNotEmpty() } ?: sid.takeLast(SID_TAIL)
        val watchLine = if (live) {
            "👁 «${PyText.esc(title)}» en vivo — la tubería es tuya desde ahora; " +
                "lo anterior quedó atrás (📄 /last trae la respuesta final)."
        } else {
            "👁 «${PyText.esc(title)}» suscripto — la carta en vivo entra cuando arranque su próximo turno."
        }
        return BotReply("$watchLine\n${bindAttempt(sid, updateId, title, row?.ws)}")
    }

    /**
     * The tap's bind attempt WITHOUT the router's context pre-check (the
     * owner's 2/11 confusion: a context-less tap answered the free-text
     * boilerplate «send text first» instead of the truth). The bind needs
     * no bot-side context — the HOST's verdict is the truth: taskRunning,
     * Task not found, or confirmed BY READ-BACK (the owner's 5/11 law:
     * the tap's next message is a 100%-sure «this is the active one»).
     * The 5/11 ladder: «Task not found» used to be a dead end (five taps
     * on one row, five refusals — the task simply lived in another
     * workspace) — now the tap moves the fork's context to the task's
     * own project first (`/project <ws>`, the daemon bot is allowed every
     * workspace) and binds again; a refusal there is the fork's word,
     * reported as-is.
     */
    private suspend fun bindAttempt(sid: String, updateId: Long, title: String, rowWs: String?): String =
        when (val bind = bindTo(sid, updateId)) {
            is BindOutcome.Confirmed -> {
                selectedSid = sid // THE TAP IS INTENT (5/11): it survives until he chooses otherwise
                selectedTitle = title
                "✅ CONFIRMADO: «${PyText.esc(title)}» es la conversación activa " +
                    "(verificado leyendo el fork — ${PyText.esc(sid.takeLast(SID_TAIL))}). " +
                    "Todo tu texto va ahí — tu elección se mantiene hasta que elijas otra."
            }

            is BindOutcome.ConfirmedUnverified -> {
                selectedSid = sid
                selectedTitle = title
                "🎯 Tu texto va a «${PyText.esc(title)}» — la conversación activa es esa " +
                    "(el fork lo confirmó; el canal no dejó releer el estado)."
            }

            is BindOutcome.Refused ->
                if (TASK_MISSING_MARKER in bind.reply) {
                    crossProjectLadder(sid, updateId, title, rowWs)
                } else {
                    "⏳ ${PyText.esc(bind.reply)} — tu texto sigue yendo a la activa; el 👁 ya está puesto."
                }

            is BindOutcome.Failed ->
                "⚠️ El bind no pudo confirmarse: ${PyText.esc(bind.reason)} — " +
                    "el 👁 ya está puesto; probá de nuevo con /tasks."
        }

    /** The «Task not found» second chance: move the fork to the task's own project, bind again. */
    private suspend fun crossProjectLadder(sid: String, updateId: Long, title: String, rowWs: String?): String {
        if (rowWs == null) {
            return "🚧 «${PyText.esc(title)}» vive en otro proyecto/workspace — la leés en vivo (👁), " +
                "pero tu texto no puede apuntarla: la frontera de proyecto del fork es deliberada."
        }
        return when (val ws = bindWorkspace(rowWs, updateId)) {
            // ConfirmedUnverified cannot arise here (no read-back runs in the
            // switch) — merged so the exhaustiveness follows the shared type
            is BindOutcome.Confirmed, is BindOutcome.ConfirmedUnverified ->
                when (val rebind = bindTo(sid, updateId)) {
                    is BindOutcome.Confirmed ->
                        "✅ CONFIRMADO: «${PyText.esc(title)}» es la conversación activa — también moví " +
                            "el fork a su proyecto «${PyText.esc(rowWs.substringAfterLast('/'))}» " +
                            "(verificado leyendo el fork — ${PyText.esc(sid.takeLast(SID_TAIL))}). Todo tu texto va ahí."

                    is BindOutcome.ConfirmedUnverified ->
                        "🎯 «${PyText.esc(title)}» quedó activa tras mudar el fork a su proyecto " +
                            "(el fork lo confirmó; sin relectura). Tu texto va ahí."

                    is BindOutcome.Refused -> "⏳ ${PyText.esc(rebind.reply)} — el 👁 ya está puesto."
                    is BindOutcome.Failed -> RouteTexts.BIND_FAILED
                }

            is BindOutcome.Refused ->
                if (TASK_RUNNING_MARKER in ws.reply) {
                    "⏳ Para activar «${PyText.esc(title)}» tengo que mudar el fork a su proyecto " +
                        "(«${PyText.esc(rowWs.substringAfterLast('/'))}»), pero la task actual sigue corriendo — " +
                        "/stop primero o esperá a que termine. Nada cambió; el 👁 ya está puesto."
                } else {
                    "🚧 «${PyText.esc(title)}» vive en «${PyText.esc(rowWs.substringAfterLast('/'))}» y el fork " +
                        "no reconoce ese workspace — la leés en vivo (👁); el texto no puede apuntarla."
                }

            is BindOutcome.Failed -> RouteTexts.BIND_FAILED
        }
    }

    /** One armed order: the next free text flies to this session. The §7 escape hatch. */
    private data class ArmedOrder(val sessionId: String, val title: String)

    /**
     * LA RESPUESTA por botón (the answer round-trip's flight): the option's
     * respond frame flies AWAITED and the question message's edit tells the
     * truth — Resolved seals with the option's label, Failed seals with the
     * host's verbatim error (a stale question never lies ok), Unavailable
     * keeps the question armed (the owner retries). Null = the edit IS the
     * feedback (a second message would only bury it). A MULTI-question form
     * ([QuestionFlow.Advanced]) never flies here: the answer is recorded,
     * the SAME message is edited with the next question, and the ONE frame
     * waits for the last answer ([QuestionFlow.Completed]).
     */
    @Suppress(
        "ReturnCount",
        "CyclomaticComplexMethod",
        "LongMethod", // style debt of the multi-question unit in flight — its own commit refactors
    ) // one guard per outcome — the tap's honest shapes
    private suspend fun questionTap(arg: String, updateId: Long): BotReply? {
        val active = board ?: return null
        val shortId = arg.substringBeforeLast(':')
        val index = arg.substringAfterLast(':').toIntOrNull() ?: return null
        val q = active.byShortId(shortId) ?: return BotReply("⏳ Esa pregunta ya no está pendiente.")
        val option = q.options.getOrNull(index) ?: return null
        logger.info { "question tap: ${q.kind} «${option.label}» → ${q.requestId.take(REQUEST_ID_LOG)}…" }
        if (q.kind == "elicitation") {
            return when (val step = active.answer(shortId, option.optionId)) {
                is QuestionFlow.Advanced -> null // the edit IS the feedback — the next question is on screen

                is QuestionFlow.Completed -> {
                    val resolvedSeal = if (step.question.questionCount > 1) {
                        "✅ Respondidas las ${step.question.questionCount} preguntas"
                    } else {
                        "✅ Respondido: <b>${PyText.esc(option.label)}</b>"
                    }
                    flyRespond(
                        frame = elicitationFrame(step.question, "tg-$updateId-qa"),
                        shortId = shortId,
                        active = active,
                        resolvedSeal = resolvedSeal,
                    )
                }

                null -> BotReply("⏳ Esa pregunta ya no está pendiente.")
            }
        }
        val frame = when (q.kind) {
            "permission" -> ClientFrame.Respond(
                id = "",
                taskId = q.taskId,
                requestId = q.requestId,
                kind = "permission",
                optionId = option.optionId,
                response = option.response,
                messageId = "tg-$updateId-qa",
            )

            else -> ClientFrame.Respond(
                id = "",
                taskId = q.taskId,
                requestId = q.requestId,
                kind = "plan",
                action = if (option.isDeny || option.optionId == "decline") "decline" else "accept",
                messageId = "tg-$updateId-qa",
            )
        }
        return flyRespond(
            frame = frame,
            shortId = shortId,
            active = active,
            resolvedSeal = "✅ Respondido: <b>${PyText.esc(option.label)}</b>",
        )
    }

    /**
     * The tap's respond flight and its honest edit — AWAITED, one outcome:
     * Resolved seals with [resolvedSeal], Failed seals with the host's
     * verbatim error (a stale question never lies ok), Unavailable keeps
     * the question armed (the owner retries). Null = the edit IS the
     * feedback (a second message would only bury it).
     */
    private suspend fun flyRespond(
        frame: ClientFrame.Respond,
        shortId: String,
        active: QuestionBoard,
        resolvedSeal: String,
    ): BotReply? = when (val answer = ops.respondQuestion(frame)) {
        RespondAnswer.Resolved -> {
            active.resolve(shortId, resolvedSeal)
            null
        }

        RespondAnswer.Unavailable ->
            BotReply("📡 El canal está caído — la pregunta sigue esperando, probá de nuevo.")

        is RespondAnswer.Failed -> {
            val detail = answer.message?.takeIf { it.isNotBlank() } ?: answer.code
            val seal = "⚠️ El tap no entró (${PyText.esc(answer.code)}): " +
                PyText.esc(PyText.take(detail, ORDER_LOG_PREVIEW))
            active.resolve(shortId, seal)
            null
        }
    }

    /**
     * The elicitation's respond frame — the COMPLETE questionnaire's content
     * in the fork's exact shape (buildBotElicitationContent's: `answers`
     * keyed by each question's text, `answer_N` per index, the singular
     * `answer` only for a one-question form).
     */
    private fun elicitationFrame(q: PendingQuestion, messageId: String) = ClientFrame.Respond(
        id = "",
        taskId = q.taskId,
        requestId = q.requestId,
        kind = "elicitation",
        action = "accept",
        content = elicitationContentAll(q.answerEntries(), q.questionCount),
        messageId = messageId,
    )

    /** The ✍️ arm: the NEXT free text IS the elicitation's answer (one shot, ArmedOrder's mechanics). */
    private suspend fun answerArm(shortId: String): BotReply {
        val q = board?.byShortId(shortId) ?: return BotReply("⏳ Esa pregunta ya no está pendiente.")
        pendingAnswerFor = shortId
        return BotReply("✍️ Escribí la respuesta — tu próxima línea vuela como respuesta a «${PyText.esc(q.header)}».")
    }

    /**
     * The armed answer's flight: the elicitation content carries the owner's
     * words verbatim (buildBotElicitationContent's shape — the answers keyed
     * by the QUESTION text, the bots' own precedent for accepting a
     * questionnaire). On a MULTI-question form the text answers the question
     * ON SCREEN ([QuestionFlow.Advanced] — the message advances, the arm is
     * consumed; a further text question needs a fresh ✍️ tap) and the ONE
     * frame flies only with the LAST answer ([QuestionFlow.Completed]). The
     * pair's FIRST leg is the consumption law: true = the line WAS the
     * answer (whatever the outcome — the caller stops routing it), false =
     * the question died while armed (the line rides the normal route).
     * Unavailable RE-ARMS — the words are still the answer once the channel
     * returns.
     */
    @Suppress("ReturnCount") // one guard per outcome — the armed answer's shapes
    private suspend fun answerWithFreeText(shortId: String, text: String, updateId: Long): Pair<Boolean, BotReply?> {
        val active = board ?: return false to null
        val q = active.byShortId(shortId) ?: return false to null
        if (q.kind != "elicitation") return false to null // plans and permissions answer by button only
        return when (val step = active.answer(shortId, text)) {
            is QuestionFlow.Advanced -> true to null // the edit IS the feedback — the next question is on screen

            is QuestionFlow.Completed -> {
                logger.info {
                    "question answer (free text) → ${q.requestId.take(REQUEST_ID_LOG)}…: " +
                        text.take(ORDER_LOG_PREVIEW)
                }
                when (
                    val answer =
                        ops.respondQuestion(elicitationFrame(step.question, "tg-$updateId-qf"))
                ) {
                    RespondAnswer.Resolved -> {
                        active.resolve(shortId, "✅ Tu respuesta entró.")
                        true to null
                    }

                    RespondAnswer.Unavailable -> {
                        pendingAnswerFor = shortId
                        true to BotReply(
                            RouteTexts.CHANNEL_DOWN + "\nLa respuesta sigue armada — reintentá con tu próxima línea.",
                        )
                    }

                    is RespondAnswer.Failed -> {
                        val detail = answer.message?.takeIf { it.isNotBlank() } ?: answer.code
                        val seal = "⚠️ La respuesta no entró (${PyText.esc(answer.code)}): " +
                            PyText.esc(PyText.take(detail, ORDER_LOG_PREVIEW))
                        active.resolve(shortId, seal)
                        true to null
                    }
                }
            }

            null -> false to null // the question died while armed: the line rides the normal route
        }
    }

    @Volatile
    private var armedOrder: ArmedOrder? = null

    /**
     * A delivery waiting on the owner's choice (2/11 23:12: «quiero que me
     * pregunte empujar o encolar»): the text, its address and its kind —
     * ONE shot, like the arm. The buttons (`odp:` ⚡ / `odq:` ⏳) answer it.
     */
    private data class PendingDelivery(
        val sid: String,
        val title: String,
        val text: String,
        val steer: Boolean,
        val updateId: Long,
        val atMs: Long,
    )

    @Volatile
    private var pendingDelivery: PendingDelivery? = null

    /**
     * The 🎁 confirmation's one-shot state (A5): the owner tapped Reclamar
     * or Usar reset — the NEXT tap is the ✅/❌. The planId/type/key live
     * HERE, never in the callback (Telegram's 64-byte law), and the TTL
     * keeps a stale confirmation from firing hours later.
     */
    private data class PendingGift(
        val kind: String, // "claim" | "reset"
        val planId: String?,
        val resetType: String?,
        val idempotencyKey: String?,
        val name: String,
        val atMs: Long,
    )

    @Volatile
    private var pendingGift: PendingGift? = null

    /**
     * Plans whose claim the server refused with a CAPTCHA (measured 3/11):
     * this build can NEVER produce the proof, so the bot stops asking —
     * the next 🎁 tap goes straight to the browser door instead of burning
     * another attempt. Session-scoped: the truth is re-learned on restart.
     */
    private val captchaGatedPlans = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The pending gift inside its TTL, or null (and the slot cleared). */
    private fun livePendingGift(kind: String): PendingGift? {
        val p = pendingGift ?: return null
        val usable = p.kind == kind && clock() - p.atMs <= PENDING_GIFT_TTL_MS
        if (!usable) pendingGift = null
        return p.takeIf { usable }
    }

    /**
     * A subagent's ORDER tap (`so:<tail>` — the ✍️ next to the 🔵 attach):
     * ATTACHES the subagent's stream (the full form when the fork names the
     * child, lane-scope otherwise — [laneAttach]'s semantics) AND arms the
     * next free text as an order direct to that session. One tap, one shot:
     * the order flies, the arm disarms, the card keeps streaming.
     */
    /**
     * The ✍️'s PULSE GATE (the 2/11 swallow): the arm flies only to a
     * target answering RIGHT NOW — the detail card's own law (fresh
     * buttons or none) applied to the arming itself. Null flies; the
     * reply refuses, BEFORE any attach: a refused arm leaves no pipe.
     */
    @Suppress("ReturnCount") // one exit per honest shape: dead lane, unnamed, no-row, pulse
    private suspend fun orderGateRefusal(tail: String): BotReply? {
        // THE TAPPED ROW IS THE TARGET (measured 2/11 23:04: the gate read
        // the hub's PIN instead — the owner tapped ✍️ on the fresh dummy
        // while pinned to the PREVIOUS one, and the arm bounced «💀 ya
        // terminó» about an agent he never pointed at).
        val state = hub.peek()
        val rows = runningRows()
        val raw = state?.tools.orEmpty()
        val lane = raw.lastOrNull { it.toolId.endsWith(tail) }
        val name: String
        val sid: String
        if (lane != null) {
            name = Keyboards.subagentName(lane)
            if (lane.status != null) return BotReply(
                "💀 «${PyText.esc(name)}» ya terminó — el ✍️ no arma órdenes a subagentes muertos.",
            )
            sid = lane.subagentAgentId?.let { "$SUBAGENT_SESSION_PREFIX$it" }
                ?: return BotReply(
                    "🤔 «${PyText.esc(name)}» todavía no da una sesión (falta su ACK) — el ✍️ no arma a ciegas.",
                )
        } else {
            val row = rows.firstOrNull { it.sid.endsWith(tail) }
            if (row != null) {
                name = row.title.ifBlank { row.sid.takeLast(SID_TAIL) }
                sid = row.sid
            } else {
                // a STALE tail (an old keyboard): the hub's pin is the only
                // clue left — and a ghost pin still refuses (the 20:34 law)
                val pin = hub.peekPin() ?: return null
                name = pin.takeLast(SID_TAIL)
                sid = pin
            }
        }
        val pulse = SubagentStop.orderTarget(raw, rows, sid, clock())
        return when (pulse) {
            OrderPulse.Alive -> null
            OrderPulse.Ended -> BotReply(
                "💀 «${PyText.esc(name)}» ya terminó — el ✍️ no arma órdenes a subagentes muertos.",
            )

            OrderPulse.Unknown -> BotReply(
                "🤔 «${PyText.esc(name)}» no da señal viva — el ✍️ no arma a ciegas. " +
                    "Reabrí su carta y probá de nuevo.",
            )
        }
    }

    @Suppress("ReturnCount") // two attach paths, one guard each
    private suspend fun orderArm(tail: String): BotReply {
        orderGateRefusal(tail)?.let {
            logger.info { "callback ✍️ so: REFUSED by the pulse gate" }
            return it
        }
        val attachReply = laneAttach(tail)
        val lane = hub.peek()?.tools?.lastOrNull { it.toolId.endsWith(tail) }
        val pin = hub.peekPin() // the gate passed: this target answers
        // an order flies ONLY to a REAL session id (the fork's inbound
        // taskId) — the lane-scope fallback has none and is honestly refused
        val (sessionId, title) = when {
            // THE LANE'S OWN ID WINS over the hub's pin: the pin may belong
            // to a PREVIOUS attach (the same 23:04 drill: the arm pointed
            // at the old dummy while the owner tapped the fresh one)
            lane != null && lane.subagentAgentId != null ->
                "$SUBAGENT_SESSION_PREFIX${lane.subagentAgentId}" to Keyboards.subagentName(lane)

            lane != null && pin != null -> pin to Keyboards.subagentName(lane)
            lane != null -> return BotReply(
                "👁 «${PyText.esc(Keyboards.subagentName(lane))}» attachada por lane, pero el fork no nombra su " +
                    "sesión — la orden directa no puede ir (fork viejo).",
            )

            pin != null -> {
                val t = hub.peek()?.taskId
                    ?.let { history.subagentsRunning(it) }
                    ?.firstOrNull { it.sid == pin }?.title
                pin to (t?.takeIf { it.isNotBlank() } ?: pin.takeLast(SID_TAIL))
            }

            else -> {
                // a TASK's own sid (the /tasks ✍️): the bind is workspace-
                // scoped (the fork's project boundary), the ORDER is not —
                // attach the pipe + arm direct to that session
                val row = history.ctxTasks(SCOPE_FOR_PICK).firstOrNull { it.sid == tail }
                    ?: return attachReply // the honest miss — nothing armed
                val ws = (contextAnswer() as? ContextAnswer.Ctx)?.value?.workspacePath
                    ?: return attachReply
                ops.subscribe(row.sid, ws)
                hub.watchTask()
                val title = row.title.ifEmpty { row.sid.takeLast(SID_TAIL) }
                armedOrder = ArmedOrder(row.sid, title)
                logger.info { "callback ✍️ so: TASK ORDER ARMED for ${row.sid}" }
                return BotReply(
                    "👁 «${PyText.esc(title)}» en vivo — la tubería es tuya.\n" +
                        "✍️ Armado: tu PRÓXIMO texto va como orden directo a esa task (sin bind).",
                )
            }
        }
        armedOrder = ArmedOrder(sessionId, title)
        logger.info { "callback ✍️ so: ORDER ARMED for $sessionId («$title»)" }
        return BotReply(
            "👁 ${attachReply.text}\n" +
                "✍️ Armado: tu PRÓXIMO texto va como orden a «${PyText.esc(title)}».",
        )
    }

    /**
     * The DETAIL CARD (the sn: tap — the row label caps at TITLE_CAP and
     * the spawn descriptions are longísimos, the 2/11 complaint): the
     * FULL name, one honest status line, and — only while it lives — the
     * action row (👁 attach · ✍️ orden · ⏹️ stop) built FRESH at tap
     * time. A finished agent still shows its full name: reading what the
     * mission was survives the agent; ordering into its dead inbox never
     * happens again (the fire gate holds regardless).
     */
    private suspend fun subagentDetail(tail: String): BotReply {
        // the RAW tools list — a closed lane's card survives as the memory
        // it is (the full name + 💀), unlike the open-only dispatchLanes
        val lane = hub.peek()?.tools?.lastOrNull { it.toolId.endsWith(tail) }
        if (lane != null) {
            return BotReply(
                PyText.esc(Keyboards.subagentDetailText(lane, clock())),
                markup = Keyboards.subagentDetailKeyboard(lane.status == null, tail),
            )
        }
        val row = runningRows().firstOrNull { it.sid.endsWith(tail) }
        return when (row) {
            null -> BotReply(
                "🔍 Ese subagente no está — ni lane viva ni fila en el store. " +
                    "Refrescá la lista con /subagents.",
            )

            else -> BotReply(
                PyText.esc(Keyboards.dbRowDetailText(row, clock())),
                markup = Keyboards.subagentDetailKeyboard(Keyboards.dbRunningFresh(row, clock()), tail),
            )
        }
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
        if (lane == null) {
            // not a stream lane: a STORED running session (the ACTIVE view's
            // stream-independent rows) — its session id IS the attach target
            val parentSid = state?.taskId
                ?: (contextAnswer() as? ContextAnswer.Ctx)?.value?.activeTaskId
            val sess = parentSid?.let { parent ->
                history.subagentSessionIds(parent).firstOrNull { it.endsWith(tail) }
            }
            if (sess != null) {
                val ws = (contextAnswer() as? ContextAnswer.Ctx)?.value?.workspacePath
                if (ws != null) {
                    // THE GHOST LAW (measured 4/11: an attach to a KILLED
                    // agent's store row — the store never closes it — born a
                    // blank card no event would ever fill, and the owner read
                    // «queda en blanco»): a row frozen longer than the ghost
                    // mark is a DEAD agent, not a stream. Honest refusal
                    // instead of pinning silence.
                    val row = history.subagentsRunning(parentSid ?: "").firstOrNull { it.sid == sess }
                    if (row != null && clock() - row.lastMs >= Keyboards.SUB_STALE_MIN * MS_PER_MINUTE) {
                        logger.info { "callback 🔵 sa: $sess is a GHOST (store row frozen) — honest refusal" }
                        return BotReply(
                            "💀 «${PyText.esc(row.title.ifBlank { sess.takeLast(SID_TAIL) })}» murió sin cerrar — " +
                                "su fila del store lleva congelada más de ${Keyboards.SUB_STALE_MIN} min y " +
                                "no hay stream que mostrar. Su resultado: 📄 /last o /tasks.",
                        )
                    }
                    ops.subscribe(sess, ws)
                    hub.pin(sess)
                    // THE INSTANT SWITCH (4/11): the fold must BE the child
                    // NOW — a quiet child never streams first, and without
                    // this the card stayed the parent's until its next event.
                    hub.watchTaskId(sess, clock())
                    logger.info { "callback 🔵 sa: STORED session ATTACHED $sess — pinned" }
                    return BotReply("👁 Subagente attachado — su turno completo fluye acá; se sella cuando cierra.")
                }
            }
            return BotReply("🤷 Ese subagente ya no está en la tubería — /tasks para re-engancharte.")
        }
        return laneAttachLane(lane)
    }

    private suspend fun laneAttachLane(lane: ToolLive): BotReply {
        val agentId = lane.subagentAgentId
        val ws = (contextAnswer() as? ContextAnswer.Ctx)?.value?.workspacePath
        if (agentId != null && ws != null) {
            val sessId = "$SUBAGENT_SESSION_PREFIX$agentId"
            if (sessId in history.subagentSessionIds(hub.peek()?.taskId ?: "")) {
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
            return "⏳ The previous task's turn is still running — the switch did NOT happen. " +
                "/stop first, then pick again."
        }
        val row = history.ctxTasks(SCOPE_FOR_PICK).firstOrNull { it.sid == sid }
        val title = row?.title?.takeIf { it.isNotEmpty() } ?: sid.takeLast(SID_TAIL)
        // the SAME confirmed-text law the tap follows — one selection, one truth
        return bindAttempt(sid, updateId, title, row?.ws)
    }

    /** Python's `/task new [project]`: arms the next free text to CREATE (live: the botsService draft). */
    @Suppress("ReturnCount") // one guard per refusal shape
    private suspend fun newConversation(tag: String): String {
        if (tag.isNotEmpty()) {
            return "⚠️ /tasks new opens it in the ACTIVE project — switching projects from " +
                "the phone is a next hot-fix. No conversation was opened."
        }
        val ws = (contextAnswer() as? ContextAnswer.Ctx)?.value?.workspacePath
        if (ws == null || ops.sendInbound("/new", "tg-new-${clock()}", clock()) != InboundReceipt.FIRED) {
            return DEGRADED
        }
        newIntent = true
        selectedSid = null // a fresh conversation is a new world — the old tap dies
        selectedTitle = null
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
                // THE OWNER'S SELECTION (5/11): the choice that outranks the heuristics
                selectedSid?.let { chosen ->
                    lines.add(
                        "🎯 elegida: «${PyText.esc(selectedTitle ?: chosen.takeLast(SID_TAIL))}» — " +
                            "tu texto va ahí hasta que elijas otra",
                    )
                }
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
            else -> return "⚠️ Stop failed — the channel is down? Nothing was stopped."
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
            selectedSid = null // ☢️: everything dies — the selection with it
            selectedTitle = null
            return "☢️ Nuclear go — $summary"
        } finally {
            nuclearGate.set(false)
        }
    }

    /**
     * /quota — the ladder, CHANNEL FIRST (the owner's 2/11: «me aparece la
     * quota de z.ai no la de start plan» — the bot-provider.json key answers
     * the OLD coding plan's pools while the conversation actually consumes
     * whatever provider the app has active): 1) the channel's quota query
     * (the Start-Plan buckets through the fork's usage service — the active
     * conversation's taskId rides along for the provider classification),
     * 2) the DIRECT rung OF THE ACTIVE PROVIDER ([quotaDirect] gets the
     * providerId from the conversation's own model selection — the owner's
     * 3/11: OpenCode Go users must see their /usage windows, never the z.ai
     * plan), 3) the channel's own honest word last — and, for an OpenCode
     * provider whose meter could not be read, a line that names the real
     * suspect (key/red) instead of the fork's misleading not_configured.
     * ON TOP of a usable card, the 🎁 gifts (A5): granted resets and
     * claimable offers, each behind its own confirmation tap. Gifts never
     * ride a degraded card (a gift line over 📡 would imply the channel
     * answered).
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // one rung per branch — the ladder's own shape
    private suspend fun quota(): BotReply {
        val ctx = contextAnswer() as? ContextAnswer.Ctx
        val taskId = ctx?.value?.activeTaskId
        val providerId = planViews(ctx?.value?.workspacePath)?.let { it.plan ?: it.defaultPlan }
        val channel = when (val answer = ops.query(ChannelQuery.Quota(taskId = taskId))) {
            is QueryAnswer.Ok -> renderQuota(answer.resultJson, clock())
            is QueryAnswer.Unavailable -> DEGRADED
            is QueryAnswer.Failed -> "⚠️ Quota failed (${answer.code}): ${PyText.esc(answer.message ?: "?")}"
        }
        val usable = channelUsable(channel)
        val direct = if (usable) null else quotaDirect?.let { run -> runCatching { run(providerId) }.getOrNull() }
        val base = when {
            usable -> channel
            direct != null -> direct
            providerId != null && providerId.contains("opencode", ignoreCase = true) -> QUOTA_OPENCODE_UNREADABLE
            else -> channel
        }
        if (direct == null && !usable) return BotReply(base) // degraded: the gifts stay silent
        val gifts = giftsFor(taskId)
        val lines = GiftsMenu.render(gifts, clock())
        if (lines.isEmpty()) return BotReply(base)
        return BotReply(
            base + "\n" + lines.joinToString("\n"),
            markup = GiftsMenu.keyboard(gifts),
        )
    }

    /** The channel's word is final unless it is the honest not_configured / degraded / failed one. */
    private fun channelUsable(text: String): Boolean =
        !text.contains("unavailable") &&
            !text.contains("no pool info") &&
            text != DEGRADED &&
            !text.startsWith("⚠️ Quota failed")

    /**
     * /usage — el consumo REAL de la conversación activa (the fork's
     * taskUsage query: the agent usage db's own aggregate — the same source
     * the desktop's panel reads; in the channel since B3a, so no
     * fork-deploy excuse lives here). Python's /usage summed TODAY across
     * sessions; the channel's query has NO time filter, so the card says
     * «acumulado» and never invents a «hoy» — honest about what the number
     * is (the B4 verdict: real data, shape pinned in UsageCommandTest). No
     * bound conversation → the honest 📭; channel down → 📡.
     */
    @Suppress("ReturnCount") // one guard per query outcome — the commands' shared shape
    private suspend fun usage(): String {
        val ctx = contextAnswer() as? ContextAnswer.Ctx ?: return noConversationOrDown()
        val taskId = ctx.value.activeTaskId
            ?: return "📭 No active conversation — /tasks n picks one; its answers come back here."
        val title = history.ctxTasks(SCOPE_FOR_PICK).firstOrNull { it.sid == taskId }
            ?.title?.takeIf { it.isNotEmpty() } ?: taskId.takeLast(SID_TAIL)
        return when (val answer = ops.query(ChannelQuery.TaskUsage(taskId, ctx.value.workspacePath))) {
            is QueryAnswer.Ok -> renderUsage(answer.resultJson, title)
            is QueryAnswer.Unavailable -> DEGRADED
            is QueryAnswer.Failed -> "⚠️ Usage failed (${answer.code}): ${PyText.esc(answer.message ?: "?")}"
        }
    }

    /**
     * /ctx — el medidor de contexto por conversación, EN LISTA (Python's
     * `context_report`, one numbered line per usage-backed conversation of
     * the tasks-index, sidebar order). The SUPERIOR data: Python estimated
     * the window from a model→tokens map and could show a STALE pre-compact
     * number as current — every line here rides the fork's REAL contextWindow
     * meter (runtime projection + the model registry's window, 9cfb39a9),
     * the same source the desktop's meter restores. The 🧹 compacted flag
     * Python derived from scanner logs: the fork's payload carries NO such
     * field, so no flag is rendered — never a fake. A conversation that
     * reports nothing reads ❔ sin datos; an old host refusing the query BY
     * NAME degrades the whole card into the fork-deploy note.
     */
    private suspend fun ctxList(): BotReply {
        val ctx = contextAnswer()
        val rows = if (ctx == ContextAnswer.Degraded) {
            emptyList()
        } else {
            runCatching { history.ctxTasks(CTX_CAP) }.getOrDefault(emptyList())
        }
        val reply = when {
            ctx == ContextAnswer.Degraded -> BotReply(DEGRADED)
            rows.isEmpty() -> BotReply(CTX_NO_ROWS)
            else -> BotReply(ctxCard(rows, (ctx as? ContextAnswer.Ctx)?.value?.workspacePath))
        }
        return reply
    }

    /** The list's body: one query per row (cheap local IPC), the by-name refusal loud once. */
    private suspend fun ctxCard(rows: List<TaskRow>, activeWs: String?): String {
        val answers = rows.map { row -> ops.query(ChannelQuery.ContextWindow(row.sid, row.ws)) }
        val refused = answers.filterIsInstance<QueryAnswer.Failed>()
            .firstOrNull { it.code == UNKNOWN_QUERY_CODE }
        if (refused != null) return contextWindowFailure(refused.code, refused.message)
        val lines = answers.mapIndexed { i, answer -> ctxLine(i + 1, rows[i], answer, activeWs) }
        return (listOf(CTX_HEADER) + lines + CTX_FOOTER).joinToString("\n")
    }

    /** One numbered row: the meter's line, the 📁 tag when the row lives in another workspace. */
    private fun ctxLine(n: Int, row: TaskRow, answer: QueryAnswer, activeWs: String?): String {
        val tag = if (row.ws.isNotEmpty() && row.ws != activeWs) {
            " · 📁${PyText.esc(row.ws.substringAfterLast('/'))}"
        } else {
            ""
        }
        val title = PyText.esc(row.title.ifEmpty { row.sid.takeLast(SID_TAIL) })
        val body = when (answer) {
            is QueryAnswer.Ok -> ctxMeter(answer.resultJson)
            else -> CTX_NO_DATA
        }
        return "$n. $body$tag — $title"
    }

    /**
     * The meter's read (pure): used+window → the emoji/pct line (Python's
     * 🔴 ≥80 · 🟡 ≥50 · 🟢 law); window unknown → the used count with a
     * «ventana ?» and NO invented percent; nothing reported → the honest ❔.
     * Every token count through [tokenRaw] — the number law.
     */
    private fun ctxMeter(json: String): String {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
        val used = root?.long("usedTokens")?.takeIf { it >= 0 }
        val window = root?.long("windowTokens")?.takeIf { it > 0 }
        val body = when {
            used == null -> CTX_NO_DATA
            window == null -> "${tokenRaw(used.toDouble())} · ventana ?"
            else -> {
                val pct = ((used * CTX_PERCENT_SCALE + window / 2) / window).toInt()
                val emoji = if (pct >= CTX_HOT_PCT) "🔴" else if (pct >= CTX_WARM_PCT) "🟡" else "🟢"
                val approx = if (root?.str("windowSource") == "owner_default") " (~)" else ""
                "$emoji ${tokenRaw(used.toDouble())} ($pct%)$approx"
            }
        }
        return body
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
     * /steer <texto> — redirigí el turno que corre (STEER, la frame
     * aditiva del canal): el texto entra con delivery guide y el CLI lo
     * inyecta DENTRO del turno vivo en el próximo límite de herramienta
     * (turn.steerQueued → turn.steerDrained). Es OTRO intento que el
     * texto libre (ese se ESTACIONA en la F17 cuando hay turno). Honesto
     * por contrato: idle → el texto arranca un turno normal (la guía no
     * se pierde — y se lo decimos); fork viejo → el nombre del fix; canal
     * caído → degradado; nada inventado.
     */
    @Suppress("ReturnCount") // one guard per honest outcome
    private suspend fun steer(rest: String): BotReply {
        val text = rest.trim()
        if (text.isEmpty()) return BotReply(STEER_USAGE)
        val ctx = contextAnswer() as? ContextAnswer.Ctx ?: return BotReply(noConversationOrDown())
        // THE PIN SPEAKS (A4's delegating door): after 👁 attaching a
        // subagent, /steer steers THE CHILD — the active conversation's
        // turn is not the one in front of the owner.
        hub.peekPin()?.takeIf { it.startsWith(SUBAGENT_SESSION_PREFIX) }?.let { return steerSubagent(it, text) }
        val taskId = ctx.value.activeTaskId
            ?: return BotReply("📭 No active conversation — /tasks n picks one; its answers come back here.")
        val title = history.ctxTasks(SCOPE_FOR_PICK).firstOrNull { it.sid == taskId }
            ?.title?.takeIf { it.isNotEmpty() } ?: taskId.takeLast(SID_TAIL)
        val running = isRunning(taskId, ctx.value.workspacePath)
        return when (val answer = ops.steer(taskId, text)) {
            SteerAnswer.Delivered -> {
                logger.info { "steer: ${text.length} chars to $taskId (was running=$running)" }
                if (running) {
                    BotReply(
                        "🌀 Steer entregado a «${PyText.esc(title)}» — entra DENTRO del turno que corre " +
                            "(se inyecta en el próximo límite de herramienta).",
                    )
                } else {
                    BotReply(
                        "🌀 «${PyText.esc(title)}» no tenía turno corriendo — el texto arrancó un turno normal.",
                    )
                }
            }

            is SteerAnswer.Failed ->
                if (answer.code == UNKNOWN_REQUEST_CODE) {
                    BotReply(STEER_OLD_FORK)
                } else {
                    BotReply("⚠️ Steer falló (${answer.code}): ${PyText.esc(answer.message ?: "?")}")
                }

            SteerAnswer.Unavailable -> BotReply(DEGRADED)
        }
    }

    /**
     * /steer's subagent half — the pinned child is the target. A4's fork
     * routes `steer` carrying a `sess_subagent_<agentId>` taskId to the
     * owner's runtime (the child's own turn), so the text enters the
     * RUNNING child exactly like the ✍️ order does. The reply NAMES the
     * child: the owner must know which turn heard it.
     */
    private suspend fun steerSubagent(child: String, text: String): BotReply {
        val name = hub.peek()?.tools
            ?.lastOrNull { lane -> lane.subagentAgentId?.let { "$SUBAGENT_SESSION_PREFIX$it" } == child }
            ?.let { Keyboards.subagentName(it) }
            ?: runningRows().firstOrNull { it.sid == child }?.title?.takeIf { it.isNotBlank() }
            ?: child.takeLast(SID_TAIL)
        // THE OWNER'S CHOICE: a BUSY child is never cut on our own judgment
        if (isBusyChild(child)) {
            pendingDelivery = PendingDelivery(child, name, text, steer = true, updateId = 0L, atMs = clock())
            logger.info { "steer: to SUBAGENT $child HELD — asking empujar/encolar" }
            return BotReply(askPushOrQueue(name), markup = Keyboards.pushOrQueue())
        }
        return deliverSteer(child, name, text, interrupted = false)
    }

    /** The steer's real flight — shared by the idle path and the ⚡ tap. */
    private suspend fun deliverSteer(
        child: String,
        name: String,
        text: String,
        interrupted: Boolean,
    ): BotReply = when (val answer = ops.steer(child, text)) {
        SteerAnswer.Delivered -> {
            logger.info { "steer: ${text.length} chars to SUBAGENT $child (interrupted=$interrupted)" }
            BotReply(
                if (interrupted) {
                    "⚡ Le corté el turno a «${PyText.esc(name)}» y le pasé el steer — lo lee AHORA."
                } else {
                    "🌀 Steer entregado a «${PyText.esc(name)}» — entra DENTRO del turno del subagente " +
                        "(se inyecta en el próximo límite de herramienta)."
                },
            )
        }

        is SteerAnswer.Failed ->
            if (answer.code == UNKNOWN_REQUEST_CODE) {
                BotReply(STEER_OLD_FORK)
            } else {
                BotReply("⚠️ Steer al subagente falló (${answer.code}): ${PyText.esc(answer.message ?: "?")}")
            }

        SteerAnswer.Unavailable -> BotReply(DEGRADED)
    }

    /** The ⚡/⏳ question's words — the title rides escaped. */
    private fun askPushOrQueue(title: String): String =
        "🕹 «${PyText.esc(title)}» está trabajando. ¿Cómo le va el mensaje?\n" +
            "⚡ Empujar — le corto el turno y lo lee ya.\n" +
            "⏳ Encolar — lo lee cuando termine."

    /** The ⚡/⏳ tap: the pending delivery's ONE shot. */
    @Suppress("ReturnCount") // expired, stale, the flight — one exit each
    private suspend fun resolvePending(push: Boolean): BotReply {
        val p = pendingDelivery ?: return BotReply("⌛ Esa pregunta ya venció — mandá el mensaje de nuevo.")
        pendingDelivery = null
        if (clock() - p.atMs > PENDING_DELIVERY_TTL_MS) {
            return BotReply("⌛ Pasaron más de 5 minutos — mandá el mensaje de nuevo.")
        }
        val interrupted = push && interruptChild(p.sid)
        logger.info {
            "callback ${if (push) "⚡" else "⏳"} ${if (p.steer) "STEER" else "ORDER"} to ${p.sid} " +
                "(interrupted=$interrupted)"
        }
        return if (p.steer) {
            deliverSteer(p.sid, p.title, p.text, interrupted)
        } else {
            deliverOrder(p.sid, p.title, p.text, p.updateId, interrupted)
        }
    }

    /**
     * /verbosity — the transcript feed's knob (the owner's 30/10 order:
     * the feed VERBOSE by default, his choice persists): bare → the
     * picker (the 3 levels, the current marked); an arg →
     * [FeedVerbosity.of] (the Spanish aliases included), unknown → the
     * picker with the honest note. The write lands in the injected
     * [VerbositySetting] — the daemon's file-backed store — and the
     * publisher reads it PER TICK: the very next feed message obeys.
     */
    @Suppress("ReturnCount") // the command's ladder: bare picker, level set, unknown — one exit each
    private fun verbosityCmd(rest: String): BotReply {
        val arg = rest.trim()
        if (arg.isNotEmpty()) {
            val level = FeedVerbosity.of(arg)
            if (level != null) {
                verbosity.set(level)
                logger.info { "verbosity: ${level.name} set via /verbosity $arg" }
                return BotReply(verbosityLine(level) + "\n✅ Aplicado.")
            }
            return BotReply(
                "🤷 «${PyText.esc(arg)}» no es un nivel — elegilo de la lista:",
                markup = Keyboards.verbosityPicker(verbosity.current()),
            )
        }
        return BotReply(
            verbosityLine(verbosity.current()) + "\nElegí el nivel:",
            markup = Keyboards.verbosityPicker(verbosity.current()),
        )
    }

    /** The vb: tap: set the level — the toast's reply is the confirmation. */
    private fun verbosityTap(arg: String): BotReply {
        val level = FeedVerbosity.of(arg) ?: return BotReply("🤷 Ese nivel no existe — nada cambió. /verbosity.")
        verbosity.set(level)
        logger.info { "callback 📣 vb:$arg — level=${level.name}" }
        return BotReply(verbosityLine(level) + "\n✅ Aplicado — el próximo mensaje del feed ya obedece.")
    }

    /** The command's one line: the level's word + what it lets through. */
    private fun verbosityLine(level: FeedVerbosity): String {
        val word = when (level) {
            FeedVerbosity.SUMMARY -> "🔈 summary"
            FeedVerbosity.NORMAL -> "🔉 normal"
            FeedVerbosity.VERBOSE -> "🔊 verbose"
        }
        val meaning = when (level) {
            FeedVerbosity.SUMMARY -> "solo el cartel de estado y la respuesta final"
            FeedVerbosity.NORMAL -> "cada bloque CoT sellado y cada cierre de tool, en mensajes separados"
            FeedVerbosity.VERBOSE -> "todo lo anterior, más el aviso de cada tool al abrirse"
        }
        return "📣 Feed en vivo: <b>$word</b> — $meaning"
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
            // one row per live dispatch lane — the tap ATTACHES its stream —
            // PLUS the store's own running sessions (stream-independent: the
            // bus can die mid-run, the store cannot — a running subagent is
            // visible even when its lane never reached the watcher)
            val lanes = dispatchLanes(turn)
            val parentSid = turn?.taskId
                ?: (contextAnswer() as? ContextAnswer.Ctx)?.value?.activeTaskId
            val dbRunning = if (parentSid != null) history.subagentsRunning(parentSid) else emptyList()
            BotReply(
                text + hint,
                markup = Keyboards.subagentsKeyboard(lanes, dbRunning, inactiveView = false, nowMs = clock()),
            )
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
        val dispatches = dispatchLanes(turn)
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
        /** The ms-per-minute the ghost law computes with ([Keyboards.SUB_STALE_MIN] is in minutes). */
        private const val MS_PER_MINUTE = 60_000L

        /** Lazy: MENU.size can never drift from the dispatcher's real vocabulary. */
        internal val UNKNOWN: String
            get() = "🤷 Unknown command — /help lists the ${MENU.size} this bot speaks."
        internal const val DEGRADED =
            "📡 The channel to the app is down — the fork's host is not running? " +
                "Commands that need it wait for the app."

        /**
         * The destructive tap's refusal — /deploy's NotOwner vocabulary
         * (🚫, «solo el dueño», nothing touched), said in the taps' own
         * voice. The tap answered it and ran NOTHING behind it.
         */
        internal const val NOT_OWNER = "🚫 Solo el dueño toca ese botón — no se ejecutó nada."

        /** /retry's honest refusal — nothing fired yet, so nothing to relaunch (F22). */
        internal const val RETRY_NOTHING =
            "🤔 Nada para relanzar — no hay prompt guardado. " +
                "Todo texto que entra a una conversación queda guardado y /retry lo vuelve a lanzar."

        /** /run's usage (Python's line; the project tag honestly retired — /tasks new's law). */
        internal const val RUN_USAGE =
            "⚠️ /run «prompt» — abre una conversación NUEVA en el proyecto activo y manda el prompt. " +
                "(Cambiar de proyecto desde el phone es el próximo hot-fix.)"

        /** /run with no bot context at all: nothing to spawn from (the fork's inbound needs the context). */
        internal const val RUN_NO_CONTEXT =
            "📭 No hay contexto del bot en la app — /run no puede abrir nada. " +
                "Mandá /tasks y tocá una fila primero."

        /** /run with the active turn running: the /new was gated — the open conversation is NOT touched. */
        internal const val RUN_BUSY =
            "🔄 Hay un turno corriendo y el /new no entró — /run no toca esa conversación. " +
                "/stop primero o esperá a que termine."

        /** /run when the draft never confirmed: the honest gap — nothing fired, try again. */
        internal const val RUN_NOT_FRESH =
            "⚠️ No pude garantizar una conversación nueva (el /new no aterrizó) — NADA voló. " +
                "La conversación abierta está intacta; probá de nuevo."

        /** The botsService's own refusal replies, matched by their stable markers (en-US). */
        const val TASK_RUNNING_MARKER = "still running"
        const val TASK_MISSING_MARKER = "Task not found"
        const val WS_MISSING_MARKER = "No available workspace found."

        /** The owner's 2/11 law: the lists are BUTTONS only — a typed number just re-shows them. */
        internal const val NO_NUMBERS_HINT =
            "\n⚠️ Sin números: tocá una fila para verla en vivo · /tasks new [project] crea"

        /** Python's status: «🎯 Active: none (/task n to pick one)» — buttons are the picker now. */
        internal const val ACTIVE_NONE = "🎯 Active: none — /tasks and tap a row"
        internal const val SID_TAIL = 8

        /** The requestId's log tail (the full wire id is noise in the daemon log). */
        private const val REQUEST_ID_LOG = 12

        /** The CLI session store's child-session convention — measured live 30/10 in the db rows. */
        internal const val SUBAGENT_SESSION_PREFIX = "sess_subagent_"

        /** The armed order's log preview cap. */
        private const val ORDER_LOG_PREVIEW = 60

        /** The ⚡/⏳ question's window: an answer older than this is stale. */
        internal const val PENDING_DELIVERY_TTL_MS = 5 * 60 * 1000L

        /** The failure message's shown tail — the v4 verdict lives at the end. */
        private const val ORDER_FAIL_TAIL = 140
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
            "plan_model_effort" to "plan, model and effort — choose the three, then ✅ Aceptar",
            "mode" to "the conversation's mode — yolo · build · edit · guarded",
            "plan_mode" to "Plan mode ON/OFF — the separate toggle (/mode no longer offers plan)",
            "last" to "re-sends the active conversation's last answer",
            "compact" to "shrinks the active conversation's context",
            "context_window" to "context window used — tokens, model window and percent",
            "queue" to "the parked texts — launch or discard",
            "run" to "opens a NEW conversation in the active project and sends the prompt — /run «prompt»",
            "steer" to "steers the RUNNING turn — the text enters the live turn",
            "stop" to "stops the active task's turn",
            "nuclear" to "emergency: stops EVERYTHING and resets the bot",
            "quota" to "the active plan's limits — z.ai pools or OpenCode Go windows",
            "usage" to "token usage of the active conversation — real, cumulative",
            "ctx" to "context % per conversation — the real meter list",
            "subagents" to "the active subagents · «inactivos» shows today's ended",
            "verbosity" to "cuánto habla el feed en vivo — summary / normal / verbose",
            "brain" to "pregunta al RAG de agent-brain — /brain status para el pulso",
            "memory" to "recuerdos del brain — /memory «texto» · recent [k] · status",
            "help" to "all commands",
        )

        internal val HELP = (listOf(
            "📖 <b>The ${MENU.size}</b> (the owner's MVP — the rest is hot-fix territory):",
        ) +
            MENU.map { (cmd, desc) -> "/$cmd — $desc" } +
            listOf(
                "",
                "Anything else is TEXT: it goes to the ACTIVE conversation (live mode) " +
                    "and the live view shows every thought, tool and line as it happens. " +
                    "Free text never opens a new one — /tasks new or /run «prompt» do.",
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
                "Modelo <b>${PyText.esc(model)}</b>: ${fmtTokens(used)} · ventana desconocida"
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
                "Modelo <b>${PyText.esc(model)}</b>: ${fmtTokens(used)} de ${fmtTokens(window)}$approx ($pct%)\n$bar"
            }
            return "$header\n$line"
        }

        /**
         * The meter's number ladder (owner's 3/11: «en vez de 1000k me
         * gustaría que diga 1M»): ≥1M renders as M (1M, 1.2M — one
         * decimal, trimmed when whole), ≥100K as whole K (123K), below as
         * one-decimal K (45.3K). The unit moves up only when the value
         * IS that magnitude — never a rounded lie at a lower rung.
         */
        private fun fmtTokens(tokens: Long): String = when {
            tokens >= CTX_M_DIVISOR -> {
                val tenths = (tokens * CTX_M_TENTHS + CTX_M_DIVISOR / CTX_M_TENTHS / 2) / CTX_M_DIVISOR
                if (tenths % CTX_M_TENTHS == 0L) {
                    "${tenths / CTX_M_TENTHS}M"
                } else {
                    String.format(Locale.ROOT, "%.1f", tenths.toDouble() / CTX_M_TENTHS) + "M"
                }
            }
            tokens >= CTX_K_DECIMAL_LIMIT -> "${(tokens + CTX_K_ROUND_HALF) / CTX_K_DIVISOR}K"
            else -> String.format(Locale.ROOT, "%.1f", tokens.toDouble() / CTX_K_DIVISOR) + "K"
        }

        /** The fork's refusal code for a query the connected host predates. */
        internal const val UNKNOWN_QUERY_CODE = "unknown_query"

        /**
         * The fork's refusal code for a REQUEST TYPE the connected host
         * predates (steer is a request, not a query — same additive law).
         */
        internal const val UNKNOWN_REQUEST_CODE = "unknown_request"
        internal const val STEER_USAGE =
            "🌀 /steer &lt;texto&gt; — redirigí el turno que corre: el texto entra DENTRO del turno vivo " +
                "(no se estaciona en la cola)."
        internal const val STEER_OLD_FORK =
            "⚠️ La app conectada no conoce el frame «steer» — falta deployar el fork actualizado " +
                "(ZCode damian, commit 7ae1a972+); el bot ya lo habla."

        /** /mode's by-name refusal from a host that predates the A4 cap. */
        internal const val MODE_OLD_FORK =
            "⚠️ La app conectada no conoce el frame «setMode» — falta deployar el fork actualizado " +
                "(ZCode damian, A4); el bot ya lo habla."

        /** /mode needs a conversation: the mode is PER conversation, not a global knob. */
        internal const val MODE_NO_CONTEXT =
            "📭 No hay conversación activa — /tasks n elige una: el modo es POR conversación."

        /**
         * A /plan tap whose plan (or model) the CURRENT catalog no longer
         * exposes — a keyboard from before the app changed providers. The
         * draft is NOT moved: an invented pair is exactly the bug being
         * fixed.
         */
        internal const val PLAN_UNKNOWN =
            "⚠️ Ese plan ya no está en el catálogo de la app — abrí /plan_model_effort de nuevo."
        internal const val MODEL_UNKNOWN =
            "⚠️ Ese modelo ya no está en el catálogo de la app — abrí /plan_model_effort de nuevo."

        /** An effort outside the shown model's vocabulary — the real list rides along. */
        internal fun planEffortUnknown(levels: String): String =
            "⚠️ Ese effort no existe para el modelo mostrado — válidos: $levels"

        /** ✅ with no draft open: nothing to apply, said plainly. */
        internal const val PLAN_NOTHING_TO_ACCEPT =
            "🤔 No hay ningún borrador abierto — tocá un plan, un modelo y un effort, y después ✅ Aceptar."

        /** The draft outlived its TTL (the catalog may have moved) — dropped, never applied blind. */
        internal const val PLAN_DRAFT_STALE =
            "⚠️ El borrador quedó viejo (el catálogo pudo cambiar) — lo descarté; armá la combinación de nuevo."

        /** A draft older than this is not trustworthy: the catalog can change under it. */
        internal const val PLAN_DRAFT_TTL_MS = 30L * 60L * 1000L

        /** No armed 🎁 (or its 5-minute window closed) — the honest nudge, not a silent no-op. */
        internal const val GIFT_NOTHING_ARMED =
            "🤔 No hay un 🎁 armado — corré /quota y tocá el botón de nuevo."

        /** /quota's gift rung against a host that predates A5. */
        internal const val GIFT_OLD_FORK =
            "⚠️ La app conectada no conoce el frame de regalos — falta deployar el fork actualizado " +
                "(ZCode damian, A5)."

        /**
         * The claim's captcha gate (measured live 3/11): the server refuses
         * without an anti-bot proof, and THIS fork build ships the security
         * edition as a stub (no captcha code exists in the tree) — so the
         * app's own Claim fails the same way. The answer is ONE line + the
         * browser door (the app's own trusted URL, params included).
         */
        internal const val GIFT_CAPTCHA =
            "🛡 Requiere CAPTCHA y este build no lo tiene — la vía segura es la app oficial. " +
                "La web, por si la oferta aparece ahí:"

        /** The app's OWN rewards URL (its trusted-URL contract requires both params). */
        internal const val GIFT_CLAIM_URL =
            "https://zcode.z.ai/en/rewards?embedded=app&theme=zai-dark"

        /** The confirmation's one-shot window (the pending slot's TTL). */
        internal const val PENDING_GIFT_TTL_MS = 5 * 60 * 1000L
        internal const val CTX_WINDOW_UNPARSEABLE =
            "⚠️ No pude leer la respuesta de la ventana de contexto"

        /** No usedTokens at all: the conversation never reported usage — ask, don't invent. */
        internal const val CTX_WINDOW_NO_DATA =
            "⚠️ La conversación aún no reporta uso de contexto — mandá un mensaje y volvé a probar."

        /** The taskUsage answer the daemon cannot parse — a broken host, said out loud. */
        internal const val USAGE_UNPARSEABLE =
            "⚠️ No pude leer la respuesta de uso (shape inesperado del host)"

        /**
         * An OpenCode provider whose own meter could not be read (3/11):
         * the fork's `not_configured` would blame the wrong thing — the
         * provider key or the network is the real suspect.
         */
        internal const val QUOTA_OPENCODE_UNREADABLE =
            "📊 <b>OpenCode</b>: no pude leer el uso — revisá la key del proveedor o la red, y reintentá."

        /** A zero TOTAL is a fresh conversation's truth — no tokens spent yet. */
        internal const val USAGE_EMPTY =
            "📭 Aún sin uso registrado — cuando la conversación consuma tokens, el total aparece acá."

        /** /ctx's card: Python's cap (8 rows), header, footer and the honest no-data word. */
        private const val CTX_CAP = 8
        internal const val CTX_HEADER = "🧠 <b>Contexto por conversación</b> (el medidor real del fork)"
        internal const val CTX_NO_ROWS = "🧠 No hay conversaciones con uso registrado."
        internal const val CTX_NO_DATA = "❔ sin datos"
        internal const val CTX_FOOTER =
            "🗜 /compact compacta la conversación activa · /context_window su medidor completo."
        private const val CTX_HOT_PCT = 80
        private const val CTX_WARM_PCT = 50
        private const val CTX_MODEL_UNKNOWN = "desconocido"
        private const val CTX_BAR_FULL = "▰"
        private const val CTX_BAR_EMPTY = "▱"

        /** The bar's fixed cell count (a ~10-cell meter reads at a glance). */
        private const val CTX_BAR_CELLS = 10
        private const val CTX_PERCENT_SCALE = 100L
        private const val CTX_PERCENT_HALF = 50L
        private const val CTX_K_DIVISOR = 1000L

        /** The M rung (3/11): ≥1M renders in millions, tenths-of-a-million rounded. */
        private const val CTX_M_DIVISOR = 1_000_000L
        private const val CTX_M_TENTHS = 10L

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
                "💬 <b>Conversations by project</b> — tap a row to watch it live"
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
         * cuántos tokens me quedan en las próximas 5 horas y en la semana» —
         * the 2/11 law v4: RAW token counts climb the ladder —
         * «300,000,000 → 300,000K → 300M»).
         * One line per REAL balance bucket: used/total through the owner's
         * ladder with the USED percent and its own reset — the fork's
         * builder carries the
         * numbers raw (usage/unit/remaining), never a bot-invented window.
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
                        "${tokenRaw(used)}/${tokenRaw(total)} usado " +
                            "${((used / total) * 100).toInt()}%"

                    else -> "${tokenRaw(used)} usados"
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

        /**
         * taskUsage → the usage card (pure — /usage's body). The fork's REAL
         * aggregate (the agent usage db — the desktop panel's own source),
         * rendered honest about WHAT it is: acumulado de la conversación,
         * never a «hoy» the query cannot know. Every token count climbs the
         * owner's ladder ([tokenRaw] — the number law: 166603400 → «166.6M»,
         * 850500 → «850.5K»); the request counts are NOT tokens — [fmtNum]'s
         * grouping only. A missing field renders «?», never an invented
         * zero; a zero TOTAL is a fresh conversation's truth (the 📭 line).
         */
        fun renderUsage(json: String, title: String): String {
            val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
            val total = root?.long("totalTokens")?.takeIf { it >= 0 }
            val lines = when {
                root == null || total == null -> listOf(USAGE_UNPARSEABLE)

                total == 0L -> listOf(
                    "📈 <b>Uso de «${PyText.esc(title)}»</b>",
                    USAGE_EMPTY,
                )

                else -> usageLines(root, total, title)
            }
            return lines.joinToString("\n")
        }

        /** The card's rows for a conversation WITH usage (the ladder's happy tier). */
        private fun usageLines(root: JsonObject, total: Long, title: String): List<String> = listOf(
            "📈 <b>Uso de «${PyText.esc(title)}»</b> — acumulado de la conversación",
            "• input ${tokenRaw(root.long("inputTokens")?.toDouble())} · " +
                "output ${tokenRaw(root.long("outputTokens")?.toDouble())} · " +
                "reasoning ${tokenRaw(root.long("reasoningTokens")?.toDouble())}",
            "• cache creación ${tokenRaw(root.long("cacheCreationTokens")?.toDouble())} · " +
                "lectura ${tokenRaw(root.long("cacheReadTokens")?.toDouble())}",
            usageTotalLine(root, total),
        )

        /** Python's bold total — requests through [fmtNum] (grouping only), tokens through [tokenRaw]. */
        private fun usageTotalLine(root: JsonObject, total: Long): String {
            val errors = root.long("modelErrorCount")?.takeIf { it > 0 }
                ?.let { " · ${fmtNum(it.toDouble())} errores" } ?: ""
            return "<b>Total: ${fmtNum(root.long("modelRequestCount")?.toDouble())} req · " +
                "${tokenRaw(total.toDouble())} tokens</b>$errors"
        }

        /** A JSON number as Double (ints and fractions alike — credits can be 31.5). */
        private fun JsonObject.num(key: String): Double? =
            (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

        /** Miles con coma (el dueño 2/11: «300,000.4» — ínfimo pero ayuda a leer). */
        private fun group(digits: String): String {
            val dot = digits.indexOf('.')
            val head = if (dot >= 0) digits.substring(0, dot) else digits
            val tail = if (dot >= 0) digits.substring(dot) else ""
            val grouped = head.reversed().chunked(3).joinToString(",").reversed()
            return grouped + tail
        }

        /** The owner's number law (2/11, over three iterations): RAW numbers
         *  wear a comma every 3 digits and nothing else — the unit never
         *  moves, Python's fmt_num K-conversion stays dead. The token pools
         *  have their own ladder: [tokenRaw]. */
        private fun fmtNum(n: Double?): String = when {
            n == null -> "?"
            else -> group(plain(n))
        }

        /** A RAW token count through the owner's ladder (2/11, v4 — the API
         *  numbers are RAW: «300,000,000 → 300,000K → 300M»): ≥ 1M reads as
         *  MILLIONS with 2 decimals, trailing zeros off («300M», «166.6M»,
         *  «4,000M»); ≥ 1K reads as K («850.5K»); below, the plain number.
         *  The v3 mistake: the input is NOT a K-count — dividing it by 1000
         *  again said a thousand times MORE. */
        internal fun tokenRaw(n: Double?): String = when {
            n == null -> "?"
            n >= RAW_PER_M -> group(scaled(n, RAW_PER_M)) + "M"
            n >= RAW_PER_K -> group(scaled(n, RAW_PER_K)) + "K"
            else -> group(plain(n))
        }

        /** The quotient through the owner's rounding: 2 decimals, trailing
         *  zeros off («300», «166.6», «550.68»). */
        private fun scaled(n: Double, divisor: Double): String =
            BigDecimal((n / divisor).toString())
                .setScale(M_DECIMALS, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()

        /** The Double as a short plain string — «300000», «166603.4» — never
         *  the scientific form Double.toString sprouts past 1e7. */
        private fun plain(n: Double): String =
            BigDecimal(n.toString()).stripTrailingZeros().toPlainString()

        private const val RAW_PER_M = 1_000_000.0
        private const val RAW_PER_K = 1_000.0
        private const val M_DECIMALS = 2

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
