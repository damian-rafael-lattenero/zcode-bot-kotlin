package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import dev.lattenero.bot.transport.TgConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kap.Kap
import kap.Process
import kap.RestartPolicy
import kap.Schedule
import kap.process
import kap.repeat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * The turn's state between the channel and the phone: ONE current turn
 * (the subscribed task), folded by the PURE [reduce] over the
 * [RenderEvent] spine under a mutex. U5a: THE HUB IS THE FOLD'S EDGE —
 * every transition (a frame, a tap's retitle, a reconnect's resync, a
 * heartbeat, the staleness tick) is a [RenderEvent] into the same pure
 * step; no `.copy` mutation lives outside [reduce] anymore. This is the
 * visible escape hatch of policy §7 — the state is a value, the holder is
 * the scan's materialized accumulator (a StateFlow: the fold's output,
 * held), and every transition is the reducer's (tested) decision.
 */
// the hub is the fold's edge: one method per honest verb (apply/pin/
// attach/retitle/resync…) that only ever EMITS the event — none extractable
@Suppress("TooManyFunctions")
class LiveHub {
    private val mutex = Mutex()

    /** THE SCAN'S ACCUMULATOR — the fold's state, held. Written ONLY by [fold]. */
    private val states = MutableStateFlow<TurnLive?>(null)

    /**
     * THE SEALED MEMORY (11/11): the turns that sealed recently — a sealed
     * turn's late frames are the BUS's reburst, never new output. The ghost
     * re-seal re-spoke the conclusion (measured: the same taskId sealed twice
     * 9s apart; the re-delivered task_complete re-seeded a fresh fold and
     * sealed again). Each entry carries the turn's own start stamp: a
     * task_run_started with a STRICTLY NEWER stamp (a genuinely new turn —
     * a reburst carries the same stamp it always had) lifts the memory;
     * everything else for a sealed task stays dropped. Bounded, newest first.
     */
    private var recentlySealed: List<Pair<String, Long>> = emptyList()

    /**
     * A pinned card (a sa: attach's full form — the subagent's OWN session):
     * other tasks' events — the parent's among them — are DROPPED until the
     * pin lifts, or the single-task hub would flip the card back at the
     * parent's every event. The pin is the CARD'S IDENTITY (which message is
     * this), not render state: the fold's own state never carries it.
     */
    private var pinnedTaskId: String? = null

    /**
     * THE WATCH'S HOLD — the owner's explicit choice (a SwitchTask attach),
     * HUB state on purpose: a seal clears the fold whole and a fold-borne
     * hold died with it (measured 16:09-16:10 — the flip loop returned the
     * moment the watched turn sealed). Only the owner's next attach moves
     * it; only ☢️ nuclear wipes it.
     */
    private var heldTaskId: String? = null

    /**
     * The fold's one step — the ONLY writer of the accumulator. `differ`
     * only matters for the frame events (an edit's +N/−M); the verbs fold
     * with the house default, which they never touch.
     */
    private fun fold(event: RenderEvent, differ: LineDiffer): TurnLive? {
        states.value = reduce(states.value, event, differ)
        return states.value
    }

    /**
     * One stream event folded in; answers the state after the fold.
     * The gates ARE the hub: pin → child-routing → hold → sealed memory →
     * lane scope → the fold, one guard each — the ladder's order is the law.
     */
    // one guard per gate — the ladder IS the shape
    @Suppress("CyclomaticComplexMethod")
    suspend fun apply(
        frame: ServerFrame.StreamEvent,
        nowMs: Long,
        differ: LineDiffer = SimpleLineDiffer,
    ): TurnLive? = mutex.withLock {
        // the pinned view owns the hub: drop everyone else's frames whole
        if (pinnedTaskId != null && frame.taskId != pinnedTaskId) return@withLock states.value
        // THE CHILD'S OWN STREAM (12/11): an auto-subscribed child session's
        // frames fold into the watched parent's child windows — NEVER a task
        // switch. A pinned or held child's own frames passed the gates above
        // (its card IS the child's turn; the attach skips this routing), and
        // every other child frame routes here: a thought/message chunk grows
        // the open dispatch lane's window, anything else (the child's own
        // tools, its seal, its errors) drops whole — the parent's Task close
        // tells that story, its report born open beneath the card.
        if (frame.taskId != pinnedTaskId && frame.taskId != heldTaskId && isSubagentSession(frame.taskId)) {
            return@withLock routeChild(frame, nowMs, differ)
        }
        // THE WATCH'S HOLD (5/11): an explicit attach is the owner's choice —
        // a previous task's still-subscribed running traffic never steals the
        // card back (the live flip-flop 15:13-16:10: born/REPLACED every few
        // seconds, «funciona horrible, no se desuscribe», «⛓️» en bucle). The
        // hold is HUB state — it SURVIVES the watched turn's seal (a seal
        // nulls the fold; a fold-borne hold died with it and the loop
        // returned at the next traffic). The implicit pre-attach world holds
        // nothing and keeps the traffic-following law.
        if (heldTaskId != null && frame.taskId != heldTaskId) return@withLock states.value
        // THE SEALED MEMORY'S GATE (11/11): a sealed turn's late frames are
        // the reburst, never new output — dropped whole. Only a task_run_started
        // carrying a STRICTLY NEWER start stamp (a genuinely new turn; the
        // reburst carries the stamp it always had) lifts the memory, and it
        // leaves the memory on the way in.
        val sealedStamp = recentlySealed.firstOrNull { it.first == frame.taskId }?.second
        if (sealedStamp != null) {
            val isNewTurn = StreamEvents.type(frame.event) == StreamEvents.RUN_STARTED &&
                (sealedStamp == 0L || StreamEvents.startedAt(frame.event) > sealedStamp)
            if (!isNewTurn) return@withLock states.value
            recentlySealed = recentlySealed.filterNot { it.first == frame.taskId }
        }
        // THE LANE SCOPE IS THE FOLD'S (U5c): the card's own watchedLane —
        // a task switch is also a lane-switch (a foreign task's state
        // carries no scope), so the scope dies with the task by
        // construction, not by a second var to clear.
        val scope = states.value?.takeIf { it.taskId == frame.taskId }?.watchedLane
        // a lane-scoped card folds ONLY its subagent's events plus the root
        // tool's own lifecycle (arrival/close — the honest end signal)
        if (scope != null) {
            val ev = frame.event
            val passes = StreamEvents.parentToolUseId(ev) == scope ||
                StreamEvents.toolId(ev) == scope
            if (!passes) return@withLock states.value
        }
        fold(classifyFrame(frame, nowMs), differ)
    }

    /** The sa: attach's full form: the card IS the subagent's own turn stream. */
    suspend fun pin(taskId: String) {
        mutex.withLock { pinnedTaskId = taskId }
    }

    /**
     * THE CHILD'S ROUTING (12/11), extracted from [apply]'s gate ladder:
     * the frame's child topic names its agentId; the CURRENT state's open
     * dispatch lane that owns the child receives the delta as a
     * [RenderEvent.ChildStream] — or the frame drops whole (the child's
     * own tools/seal/errors are the parent's Task close's business).
     * Caller holds [mutex].
     */
    private fun routeChild(frame: ServerFrame.StreamEvent, nowMs: Long, differ: LineDiffer): TurnLive? {
        val agentId = subagentAgentIdOf(frame.taskId)
        val lane = states.value?.tools
            ?.firstOrNull { it.status == null && it.subagentAgentId == agentId }
        val type = StreamEvents.type(frame.event)
        if (lane != null && (type == StreamEvents.THOUGHT || type == StreamEvents.MESSAGE)) {
            fold(
                RenderEvent.ChildStream(
                    laneId = lane.toolId,
                    thought = type == StreamEvents.THOUGHT,
                    delta = StreamEvents.content(frame.event),
                    nowMs = nowMs,
                ),
                differ,
            )
        }
        return states.value
    }

    /** Back to the whole-task world — a t: attach lifts any pin. */
    suspend fun unpin() {
        mutex.withLock { pinnedTaskId = null }
    }

    /** The pin's current target — tests and the tap's own bookkeeping. */
    suspend fun peekPin(): String? = mutex.withLock { pinnedTaskId }

    /** A sa: attach: the card speaks ONLY that subagent's stream (subagent implements task). */
    suspend fun attachLane(laneId: String) {
        mutex.withLock { fold(RenderEvent.ScopeLane(laneId), SimpleLineDiffer) }
    }

    /** Back to the whole task's stream (a t: attach clears any lane scope). */
    suspend fun watchTask() {
        mutex.withLock { fold(RenderEvent.WatchTask, SimpleLineDiffer) }
    }

    /**
     * THE ATTACH'S INSTANT SWITCH (4/11): the tapped task becomes the watched
     * turn NOW — an idle task never receives a frame, so without this the
     * card (and the 🎯 mark that reads the fold) stayed on the previous task
     * until its next event. The next tick's task-switch law does the rest:
     * the old card seals once, the tapped task's card is born. [nowMs]
     * stamps the seed — the attach IS a real event (the 5/11 blank card:
     * an unstamped seed's silence counted from the epoch).
     */
    suspend fun watchTaskId(taskId: String, nowMs: Long = 0L) {
        mutex.withLock {
            heldTaskId = taskId // the hold moves with the owner's newest tap
            fold(RenderEvent.SwitchTask(taskId, nowMs), SimpleLineDiffer)
        }
    }

    /** The current lane scope — read FROM the fold (the sa: tap's resolution and tests). */
    suspend fun watchedLaneId(): String? = mutex.withLock { states.value?.watchedLane }

    /**
     * The card's real title, resolved OUTSIDE the stream (the fork's
     * session_info_update never carries it for some sessions — the
     * history's tasks-index does): retitle the watched turn in place, the
     * next tick renders and seals with the honest name.
     */
    suspend fun retitle(taskId: String, title: String) {
        mutex.withLock { fold(RenderEvent.Retitle(taskId, title), SimpleLineDiffer) }
    }

    /** A reconnect's resync: the lost events stay lost, the state re-baselines.
     *  The SEALED blocks survive (they are complete real text — a gap cuts
     *  the seam, never a sealed box — and the feed already spoke them);
     *  the live windows die (a tail/pending spanning the gap would render
     *  a continuity that no longer exists — and the reconnect's catch-up
     *  burst would double onto them). */
    suspend fun resync(taskId: String) {
        mutex.withLock { fold(RenderEvent.Resync(taskId), SimpleLineDiffer) }
    }

    /**
     * THE WATCHDOG'S RE-ARM (10/11): a resubscribe on a LIVE turn re-baselines
     * the live windows — the answer's tail + pending prose AND the thinking's
     * tail + pending accumulator (one Thought delta writes both of the
     * thinking's: a re-stated delta doubles whichever survives). The fork's
     * subscribe burst re-states the in-flight streamed content, and windows
     * that survived it would double (the duplicated violet segments the owner
     * read). Fold BEFORE the resubscribe fires: the windows are empty when
     * the burst lands.
     */
    suspend fun rebase(taskId: String) {
        mutex.withLock { fold(RenderEvent.StreamRebase(taskId), SimpleLineDiffer) }
    }

    /**
     * The stream heartbeat's seq accounting — NOTHING else moves. The
     * keepalive consumes the connection's seq counter (the fork's own
     * law), so without this every quiet stretch reads as a lost-event gap
     * and the resync wipes the accumulating CoT (measured live 31/10: the
     * blocks never reached 3000). lastEventAtMs NEVER moves: the heartbeat
     * proves the BUS, not the model — a quiet model stays honestly quiet.
     */
    suspend fun keepalive(taskId: String, seq: Long) {
        mutex.withLock { fold(RenderEvent.Keepalive(taskId, seq), SimpleLineDiffer) }
    }

    /**
     * THE STALENESS TICK (U5a, the design's law 2): the publisher's
     * cadence folds a [RenderEvent.Tick] — the card's age becomes STATE
     * ([TurnLive.staleMs]); the watchdog reads the fold's staleness, never
     * computes its own.
     */
    suspend fun tick(nowMs: Long) {
        mutex.withLock { fold(RenderEvent.Tick(nowMs), SimpleLineDiffer) }
    }

    /**
     * A collapsed output box was born (U5b): the feed spoke it, the send
     * flew — the born fact (id, header, pages, message id, fence language,
     * and the page the message SHOWS — a born-open report's 0) folds.
     */
    suspend fun boxBorn(
        taskId: String,
        boxId: Int,
        header: String,
        pages: List<String>,
        messageId: Long,
        language: String? = null,
        page: Int = BoxLive.FOLDED,
    ) {
        mutex.withLock {
            fold(
                RenderEvent.BoxBorn(taskId, boxId, header, pages, messageId, language, page),
                SimpleLineDiffer,
            )
        }
    }

    /**
     * THE PAGER'S TAP (U5b): the box's message shows [pageIndex] now — the
     * edit landed; the fold mirrors it (the fact, not the request).
     */
    suspend fun page(boxId: Int, pageIndex: Int) {
        mutex.withLock { fold(RenderEvent.Page(boxId, pageIndex), SimpleLineDiffer) }
    }

    suspend fun peek(): TurnLive? = mutex.withLock { states.value }

    /**
     * THE SEALED BOXES' ARCHIVE: a box's MESSAGE outlives the turn that
     * spoke it — its buttons must keep opening pages after the seal (the
     * old registry survived it; the fold's Clear is the LIVE world's
     * reset, not the chat's). A box id is turn-unique by construction
     * (task hash + birth order), so merging seals never collides. /nuclear
     * (clear with no sealedTaskId) wipes it: nothing survives a ☢️.
     */
    private var sealedBoxes: List<BoxLive> = emptyList()

    /** A box by wire id — the LIVE turn's first, then the sealed archive. */
    suspend fun box(boxId: Int): BoxLive? = mutex.withLock {
        states.value?.boxes?.firstOrNull { it.boxId == boxId }
            ?: sealedBoxes.firstOrNull { it.boxId == boxId }
    }

    /**
     * After the seal is published the turn is spent; /nuclear also lands
     * here. A pin dies with ITS OWN card — [sealedTaskId] names the turn
     * that sealed. Measured live 2/11 22:48: the owner attached a child,
     * the PARENT's turn sealed six seconds later, and the blanket reset
     * stole the pin — /steer then flew to the parent he was not reading.
     * null keeps the nuclear semantics (everything dies).
     */
    suspend fun clear(sealedTaskId: String? = null): Unit = mutex.withLock {
        sealedBoxes = if (sealedTaskId == null) {
            emptyList()
        } else {
            // newest seal first; the archive is bounded like the live list
            (states.value?.boxes.orEmpty() + sealedBoxes).distinctBy { it.boxId }.take(SEALED_BOX_CAP)
        }
        if (sealedTaskId != null) {
            // THE SEALED MEMORY WRITES ITSELF (11/11): the turn that sealed
            // enters the reburst gate carrying its own start stamp — the
            // discriminator between the reburst's head and a genuinely new
            // turn's head. Bounded, newest first.
            val stamp = states.value?.startedAtMs ?: 0L
            recentlySealed = (listOf(sealedTaskId to stamp) + recentlySealed).take(SEALED_MEMORY_CAP)
        }
        fold(RenderEvent.Clear, SimpleLineDiffer)
        if (sealedTaskId == null || pinnedTaskId == sealedTaskId) pinnedTaskId = null
        if (sealedTaskId == null) heldTaskId = null // ☢️ nuclear: everything dies
        if (sealedTaskId == null) recentlySealed = emptyList() // ☢️ nuclear: every gate dies with it
    }

    private companion object {
        /** The sealed archive's ceiling — the chat's own scrollback is the real limit. */
        const val SEALED_BOX_CAP = 120

        /**
         * The sealed memory's ceiling (11/11): the last sealed task ids the
         * reburst gate remembers. A session's next REAL turn lifts its own
         * entry long before eviction would matter.
         */
        const val SEALED_MEMORY_CAP = 32
    }
}

/**
 * THE CARD IS THE PROCESS, THE FEED IS THE DATA (the owner's ley raíz,
 * 2/11: «separar bien el dato del proceso — el proceso debe estar siempre
 * visible, el dato debe mostrarse de forma ordenada»). THE CARD: one
 * Telegram message born at the turn's start carrying the WORKING header
 * (the elapsed since the real start) and the NOW line (verb + target +
 * that tool's own elapsed) — and it is RE-DRAWN AS THE CHAT'S LAST
 * MESSAGE after every event (the delete+redraw machinery, not only at
 * completions): nothing the bot says may bury the process. Honest about
 * lag: the redraw rides the 3s tick and the change law (a quiet render
 * moves nothing), and under a 429 window the card stays where it is —
 * stale but present, never missing. At `task_complete` the seal speaks
 * the one-line record and the turn's answer.
 *
 * THE TRANSCRIPT FEED (the owner's 30/10 order: «quiero VER los archivos,
 * leer los commands de bash…»): every tick drains [TranscriptFeed]'s
 * diff — each tool CLOSE becomes its own message with its real content,
 * and each sealed CoT block becomes a COLLAPSED message (header + button,
 * never the text) — the card SWAPS (delete → feed → re-birth last) in the
 * same tick. The tap on a collapsed message EXPANDS THE BLOCK IN PLACE
 * ([toggle], 2/11: «el lugar para que aparezca el texto ES EN EL BOTÓN»),
 * another tap re-collapses. The level ([FeedVerbosity], read PER TICK
 * from the injected setting — policy §7: the publisher never reads files)
 * gates: SUMMARY speaks nothing (the card STILL renders — the process is
 * never hidden), NORMAL collapsed blocks + closes, VERBOSE adds the tool
 * OPEN one-liners. Verbose is the DEFAULT.
 */
// The publisher's ladder: one function per duty (birth, feed, seal, watchdog,
// prize cache) — the split is the state machine's shape, not duplication.
@Suppress("TooManyFunctions", "LongParameterList")
class LivePublisher(
    private val hub: LiveHub,
    private val telegram: LiveTelegram,
    private val config: () -> TgConfig,
    private val channelAlive: () -> Boolean,
    private val speak: suspend (text: String, markup: JsonObject?) -> Long?,
    /**
     * The per-tool CLOSE renderer (injected — the daemon passes the
     * SPECTATOR's ToolBox, the same renderer the final transcript uses):
     * the feed's tool-close message's real content. A blank render (or
     * an input-less lane) falls back to the feed's honest one-liner.
     */
    private val toolBox: (ToolLive) -> String = { "" },
    private val finalAnswer: suspend (taskId: String) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The per-action verbs — the wiring injects the spectator's classification map. */
    private val verbs: ToolVerbifier = ToolVerbs.MINIMAL,
    /**
     * The REAL title's source (injected like [finalAnswer]): the history's
     * tasks-index — the stream's session_info_update title never arrives
     * for some sessions and the card wore the sid's tail. Null answer =
     * the honest fallback stays (nothing invented).
     */
    private val resolveTitle: suspend (taskId: String) -> String? = { null },
    /**
     * The stream watchdog's tooth (injected): re-issues the task's
     * subscription — the fork's continuous bus dies mid-run (measured 30/10
     * twice: burst on subscribe, then silence on a LIVE turn) and the
     * resubscribe flushes a fresh burst that re-lights the card.
     */
    private val resubscribe: suspend (taskId: String) -> Unit = {},
    /** The durable message id — the boot-time ORPHAN SEAL reads it (LiveOrphanStore). */
    private val orphanStore: LiveOrphanStore? = null,
    /**
     * The transcript feed's level — read PER TICK (the daemon injects the
     * file-backed store; tests and unwired daemons the in-memory default).
     */
    private val verbosity: VerbositySetting = VolatileVerbosity(),
    /**
     * THE PRIZE SOURCE (the owner, 3/11): a CACHED read of the coding
     * plan's gifts (the daemon injects the channel query). Refreshed every
     * [GIFTS_REFRESH_MS]; the card shows «Premios» while a turn runs and
     * the snapshot is younger than [GIFTS_STALE_MS]. Null/empty = silence.
     */
    private val gifts: suspend () -> GiftsMenu.Gifts? = { null },
    /**
     * THE SEAL TAP (the owner's 🔔 moments): the daemon's notifications
     * ride the SAME seal this card speaks — the facts are the seal's own
     * ([SealFacts]), nothing is re-queried, the turn is never re-read.
     * The tap NEVER breaks the seal: its failure is one log line and the
     * card's cleanup runs untouched.
     */
    private val onSeal: suspend (SealFacts) -> Unit = {},
    /**
     * THE FILE VIEWER (the owner, 5/11): write/edit closes earn the
     * «📄 archivo completo» button — the menu owns the path registry and
     * the fresh-at-tap read; the publisher only attaches the door. Null =
     * the closes stay buttonless (tests, unwired daemons).
     */
    private val fileMenu: FileMenu? = null,
    /**
     * THE VIOLET VOICE's knob (the owner, 5/11, daemon.json `rich_voice`):
     * the LLM's prose segments, the CoT blocks and the final answer travel
     * as rich messages — pullquote/pre/expandable blocks (Bot API 10.x),
     * each send falling back to the HTML blockquote on any rejection.
     * false = the pre-feature shapes (plain texts, the toggle buttons).
     */
    private val richVoice: Boolean = true,
) : CoTTaps, BoxTaps {

    /** the message being edited — the publisher's only mutable cell (§7 escape hatch). */
    @Volatile
    private var statusMessageId: Long? = null
    private var statusTaskId: String? = null
    private var lastText: String = ""
    private var sealed = false
    private var orphanChecked = false
    private var titleResolvedFor: String? = null
    /** The watchdog's last bark — one resubscribe per silence episode. */
    private var lastWatchdogAtMs: Long = 0
    /** The feed's seen pointer — the last turn snapshot the transcript already spoke. */
    private var feedSeen: TurnLive? = null
    /** The prize snapshot + its freshness stamps (see [gifts]). */
    private var giftsSnapshot: GiftsMenu.Gifts? = null
    private var giftsAttemptAtMs: Long = 0
    private var giftsOkAtMs: Long = 0

    /**
     * THE CoT REGISTRY (the collapsed blocks' tap world) — built lazily
     * on the publisher's telegram edit leg. All access rides [tickMutex]
     * (the taps land here too): the registry itself stays a plain holder.
     * (The TOOL OUTPUT boxes' world is NOT a registry anymore — U5b moved
     * it into the fold: [RenderState.boxes].)
     */
    private val cotBlocks = CotBlocks { messageId, text, markup ->
        telegram.edit(config(), messageId, text, markup)
    }

    /**
     * The ORPHAN SEAL — this process's FIRST tick after a restart edits
     * the frozen pre-restart status message once with the honest terminal
     * text (a restart must never leave a lying "thinking..." behind) and
     * burns the pointer. Runs before any birth: the seal and this boot's
     * own message are different messages.
     */
    private suspend fun sealOrphan(cfg: TgConfig) {
        if (orphanChecked) return
        orphanChecked = true
        val orphan = orphanStore?.readLast()
        if (orphan == null) {
            orphanStore?.clear() // a stale unreadable pointer dies with the boot
            return
        }
        orphanStore.clear()
        if (telegram.edit(
                cfg,
                orphan.messageId,
                "⛓️ turno perdido — el bot se reinició (nuclear/crash) y este cartel quedó huérfano. " +
                    "El estado real está en la app; un texto nuevo abre la vista en vivo de nuevo.",
            )
        ) {
            logger.info { "live view ORPHAN sealed: message_id=${orphan.messageId} taskId=${orphan.taskId}" }
        }
    }

    /**
     * The prize cache's refresh: ONE read per [GIFTS_REFRESH_MS]. A failed
     * read keeps the last snapshot only while it is younger than
     * [GIFTS_STALE_MS] — after that the card says nothing (honest silence,
     * never a stale claim).
     */
    private suspend fun refreshGifts(nowMs: Long) {
        if (nowMs - giftsAttemptAtMs < GIFTS_REFRESH_MS) return
        giftsAttemptAtMs = nowMs
        val snapshot = runCatching { gifts() }.getOrNull() ?: return
        giftsSnapshot = snapshot
        giftsOkAtMs = nowMs
    }

    /** The card's prize line — rendered per tick (live countdown), age-gated. */
    private fun giftsForCard(nowMs: Long): String? {
        val snapshot = giftsSnapshot
        return if (snapshot == null || nowMs - giftsOkAtMs > GIFTS_STALE_MS) {
            null
        } else {
            GiftsMenu.workingLine(snapshot, nowMs)
        }
    }

    /**
     * One tick of the never-silence clock. MUTEXED: the scheduled ~3s
     * process and (once the CoT collapse lands) the block taps all land
     * here — the cells (statusMessageId, lastText, the CoT registry) are
     * the publisher's only mutable state and a tap must never race a seal.
     */
    suspend fun tick() = tickMutex.withLock { tickLocked() }

    // the tick ladder (boot seal → title → birth → replace → seal → park → edit)
    // IS the publisher: one guard per step, splitting it hides the order
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private suspend fun tickLocked() {
        val cfg = config()
        if (!cfg.configured) return
        sealOrphan(cfg) // the first tick's boot duty, before anything else
        // THE STALENESS TICK (U5a, the design's law 2): the tick is a
        // RenderEvent — the fold reduces the card's age INTO the state; the
        // watchdog below reads the fold's staleness, never computes its own.
        val now = clock()
        hub.tick(now)
        val peeked = hub.peek()
        if (peeked == null) {
            // the hub went empty (a spent seal's cleanup, a ☢️ nuclear): the
            // fold's Clear already took the turn with the world
            return
        }
        var state: TurnLive = peeked
        refreshGifts(now)
        // THE ATTACH'S AWAIT (5/11): an explicitly-attached IDLE session
        // births its honest card («ningún turno corriendo ahora») — the old
        // law left the owner staring at NOTHING after every sa: attach to a
        // stored child whose turn was over
        if (statusMessageId == null && (state.phase != TurnPhase.Idle || state.attachAwaited)) {
            // THE REAL TITLE (the owner's 30/10 verdict: «644150daf013» in
            // his card's head): ONE resolution attempt per turn, exactly at
            // the birth tick — the birth message and every render/seal then
            // carry the honest name. Not before: an Idle turn (a stray
            // usage_update) must not burn the attempt before the card exists.
            if (titleResolvedFor != state.taskId) {
                titleResolvedFor = state.taskId
                if (LiveTurn.isFallbackTitle(state.taskId, state.title)) {
                    resolveTitle(state.taskId)?.takeIf { it.isNotBlank() }?.let { real ->
                        hub.retitle(state.taskId, real)
                        state = hub.peek() ?: return
                    }
                }
            }
            // the FIRST message is already the process card: the render,
            // not a placeholder
            tryBirth(cfg, state, LiveTurn.render(state, now, channelAlive(), verbs, giftsForCard(now)))
        }
        // THE STREAM WATCHDOG: a live turn with a long silence is the fork's
        // dead bus, not a quiet model (measured 30/10 twice — burst on
        // subscribe, then silence on a LIVE turn). One resubscribe per
        // silence episode flushes a fresh burst and the card moves again; a
        // legitimately quiet stretch re-subscribes harmlessly (same task,
        // same stream); Done cards never bark.
        // the fold's own staleness (the Tick event reduced it) — the same
        // clock the card renders with, held in state, not a side channel
        val silenceMs = state.staleMs ?: (now - (state.lastEventAtMs ?: 0))
        // an awaited attach (an idle session, no turn running) has no bus to
        // revive — its honest silence is the card's own words
        val barkable = state.phase !is TurnPhase.Done && !state.attachAwaited
        if (
            barkable &&
            silenceMs > STREAM_WATCHDOG_SILENCE_MS &&
            now - lastWatchdogAtMs > STREAM_WATCHDOG_SILENCE_MS
        ) {
            lastWatchdogAtMs = now
            hub.rebase(state.taskId) // the windows die FIRST — the burst must rebuild, not double
            resubscribe(state.taskId)
            val silenceSecs = silenceMs / MS_PER_SEC
            logger.info {
                "stream watchdog: ${silenceSecs}s of silence on a live turn — " +
                    "re-subscribed ${state.taskId.takeLast(TASK_ID_TAIL)}"
            }
        }
        val mid = statusMessageId ?: return
        // the hub replaces a watched turn with a new task's events: the old
        // status message would freeze with its last "thinking..." — a corpse
        // (measured 30/10: the owner watched a thinking tick while the app
        // was DOWN). Seal the old one honestly; the new turn births its own.
        if (statusTaskId != null && statusTaskId != state.taskId) {
            sealReplaced(cfg, mid)
            return
        }
        if (state.phase is TurnPhase.Done && !sealed) {
            seal(cfg, mid, state, now)
            return
        }
        drainFeed(cfg, state, now)
        val liveMid = statusMessageId ?: return // the swap's re-birth is throttled: the card returns next tick
        redrawCard(cfg, state, now, liveMid)
    }

    /** The task-switch corpse seal: the watched turn was replaced — the old card speaks once and dies. */
    private suspend fun sealReplaced(cfg: TgConfig, mid: Long) {
        telegram.edit(
            cfg,
            mid,
            "⛓️ turno reemplazado — esta vista era de otra tarea; la nueva tiene su propio cartel.",
        )
        logger.info { "live view REPLACED: old taskId=$statusTaskId message_id=$mid" }
        statusMessageId = null
        statusTaskId = null
        lastText = ""
        sealed = false
        titleResolvedFor = null // the new task's card resolves its own title
        feedSeen = null // the new turn's transcript starts clean — history is never replayed
        resetPacing() // the pings' counter dies with the replaced turn
    }

    /**
     * THE CARD EDITS IN PLACE, THE RECLAIM IS BOUNDED (the owner's 5/11
     * verdict: «no estoy viendo el cartel»): a changed render now EDITS the
     * message (Telegram's editMessageText — the seconds tick without a
     * single new message), and the delete+re-birth that keeps the card
     * «debajo de todo» fires at most ONCE per [BIRTH_SPACING_MS] window —
     * the 4/11 flicker died right here (the old redraw deleted first and
     * the spacing law then blocked the re-birth: the card went MISSING for
     * up to 30s at a stretch — six births in 2.5 minutes). Under a 429
     * window the whole redraw skips — the card stays where it is, stale
     * but present, and the next tick re-draws when the window lifts.
     */
    private suspend fun redrawCard(cfg: TgConfig, state: TurnLive, now: Long, liveMid: Long) {
        val text = LiveTurn.render(state, now, channelAlive(), verbs, giftsForCard(now))
        if (text == lastText) return // nothing visible changed: the card stays
        if (telegram.throttled()) return
        val reclaimDue = now - (lastBirthAtMs ?: 0L) >= BIRTH_SPACING_MS
        if (reclaimDue) {
            telegram.delete(cfg, liveMid)
            statusMessageId = null
            lastText = ""
            tryBirth(cfg, state, text)
        } else {
            if (telegram.edit(cfg, liveMid, text)) lastText = text
        }
    }

    /**
     * The TRANSCRIPT FEED's drain: [TranscriptFeed]'s diff between the
     * seen pointer and this tick's state, spoken through the render lane.
     * The feed speaks FIRST and the card follows it down (the owner's
     * rule: «el thinking box siempre se tiene que redibujar como último
     * mensaje de todo») — but the reclaim rides [redrawCard]'s bounded
     * window (the 5/11 law): when the spacing window is open the card
     * swaps last as before; when it is not, the feed speaks below the
     * card and the next window's tick reclaims the bottom — the card is
     * NEVER deleted without a re-birth guaranteed (the 4/11 orphan).
     * Back-pressure: a cooldown window skips the whole drain WITHOUT
     * advancing the seen pointer — the next tick re-speaks the same diff
     * when it lifts.
     */
    // one guard per early exit (silent level, throttle window) — the drain's own shape
    @Suppress("ReturnCount")
    private suspend fun drainFeed(cfg: TgConfig, state: TurnLive, now: Long) {
        val level = verbosity.current()
        if (level == FeedVerbosity.SUMMARY) {
            feedSeen = state // the level speaks nothing — but history stays history
            return
        }
        if (telegram.throttled()) return
        val feed = TranscriptFeed.messages(feedSeen, state, level, toolBox, verbs)
        feedSeen = state
        if (feed.isEmpty()) return
        val mid = statusMessageId
        speakFeed(feed, state)
        if (mid != null) {
            val reclaimDue = now - (lastBirthAtMs ?: 0L) >= BIRTH_SPACING_MS
            if (reclaimDue) {
                telegram.delete(cfg, mid)
                statusMessageId = null
                lastText = ""
                tryBirth(cfg, state, LiveTurn.render(state, now, channelAlive(), verbs, giftsForCard(now)))
            }
            // window closed: the card stays above the feed this round — the
            // next window's redrawCard reclaims the bottom (edit-in-place law)
        }
        logger.info {
            "transcript feed: ${feed.size} message(s) spoken " +
                "(card=${if (mid != null) "reclaim" else "absent"})"
        }
    }

    /**
     * Speaks the feed's diff and REGISTERS every collapsed message: a CoT
     * block via its registry, a tool output box BY FOLDING its birth (U5b
     * — the fold names the id, the send names the message). The collapsed
     * header is all the chat sees until the owner asks.
     *
     * THE VIOLET VOICE (5/11, knob-gated): the LLM's prose segments and
     * the CoT blocks travel as RICH messages — pullquotes for the talk,
     * the native expandable quote for the thinking (no custom button, the
     * client collapses/expands). richVoice=false keeps the old shapes.
     */
    private suspend fun speakFeed(feed: List<FeedMessage>, state: TurnLive) {
        feed.forEach { msg ->
            // THE VOICE: the model's own prose — one violet message
            msg.prose?.takeIf { richVoice }?.let { prose ->
                telegram.sendRich(config(), RichVoice.proseBlocks(prose))
                return@forEach
            }
            // a COLLAPSED tool output: the header speaks with its one
            // button; the fold keeps the numbered pages for the taps. THE
            // CHILD'S REPORT (12/11) is the exception: born OPEN on page 1
            // — the words arrive read, not behind one more tap — with the
            // pager's buttons intact for the flips.
            msg.pages?.let { pages ->
                val text = boxBody(msg, pages)
                speakBox(state, pages, msg.bornOpen) { markup -> speak(text, markup) }
                return@forEach
            }
            // A WRITE/EDIT CLOSE: the diff speaks WITH the file door —
            // «📄 archivo completo» reads the file fresh at tap time
            if (msg.editPath != null && fileMenu != null) {
                speak(msg.text, fileMenu.buttonFor(msg.editPath))
                return@forEach
            }
            val block = msg.block ?: run {
                speak(msg.text, null)
                return@forEach
            }
            val text = when (block) {
                0 -> state.cotPending.takeIf { it.isNotBlank() }
                else -> state.cotBlocks.getOrNull(block - 1)
            } ?: return@forEach
            // the thinking: NATIVE expand/collapse (10.3's expandable quote)
            // — the custom toggle button retires with the rich client
            if (richVoice && telegram.sendRich(config(), listOf(RichVoice.thinkingBlock(text))) != null) {
                return@forEach
            }
            cotBlocks.speakCollapsed(
                number = if (block == 0) null else block,
                text = text,
                cp = PyText.cpLength(text),
                send = { markup -> speak(msg.text, markup) },
            )
        }
    }

    /**
     * A box message's birth body — the born-open report SHOWS page 1 (the
     * words arrive read); every other box speaks its folded header.
     */
    private fun boxBody(msg: FeedMessage, pages: ToolOutputPages.Pages): String =
        if (msg.bornOpen) {
            ToolOutputPages.renderPage(pages.header, pages.pages, 0, pages.language)
        } else {
            msg.text
        }

    /**
     * Speaks ONE collapsed output box and folds its birth (U5b): the fold
     * names the wire id — turn-unique by construction (the task's hash
     * base + the birth order, so an old turn's button can never alias a
     * new turn's box) — the send flies, and the born fact (the message id
     * included) folds. A send that never flew folds NOTHING: there is no
     * message to edit, the tap on it is an honest miss. [bornOpen] births
     * the box SHOWING page 1 (the child's report's own law): the fold
     * carries the page so a tap on the born-open message flips from truth.
     */
    private suspend fun speakBox(
        state: TurnLive,
        pages: ToolOutputPages.Pages,
        bornOpen: Boolean,
        send: suspend (markup: JsonObject?) -> Long?,
    ) {
        val base = state.taskId.hashCode() and BOX_ID_MASK
        val boxId = (hub.peek()?.boxes?.maxOfOrNull { it.boxId } ?: base) + 1
        val markup = if (bornOpen) {
            Keyboards.boxPages(boxId, 0, pages.pages.size)
        } else {
            Keyboards.boxOpen(boxId)
        }
        val mid = send(markup) ?: return
        hub.boxBorn(
            state.taskId,
            boxId,
            pages.header,
            pages.pages,
            mid,
            pages.language,
            if (bornOpen) 0 else BoxLive.FOLDED,
        )
        logger.info {
            "box born: id=$boxId message_id=$mid pages=${pages.pages.size} " +
                "language=${pages.language}${if (bornOpen) " BORN OPEN" else ""}"
        }
    }

    /**
     * THE TAP (the owner's 2/11 refinement, verbatim): «el lugar para que
     * aparezca el texto ES EN EL BOTÓN del CoT» — the collapsed block's
     * message expands IN PLACE with the block WHOLE; another tap folds it
     * back to the one-line header. MUTEXED with the tick (the registry is
     * the publisher's mutable state). The configured check rides here:
     * the registry's edit leg is the same cooldown-gated telegram.
     */
    override suspend fun toggle(registryId: Int): String? = tickMutex.withLock {
        val cfg = config()
        if (!cfg.configured) {
            "📡 Sin Telegram configurado — no hay mensaje que abrir."
        } else {
            cotBlocks.toggle(registryId)
        }
    }

    /**
     * The pager's tap (U5b): render FROM the fold — the box's own header,
     * numbered pages and message id — edit the message in place, and fold
     * the [RenderEvent.Page] fact on success (a throttled edit flips
     * NOTHING: the message keeps its page, the fold keeps its truth). A
     * box the fold does not know (an old turn, a restart) answers the
     * honest miss; null = the toast is silence (the edit speaks).
     */
    override suspend fun open(registryId: Int, page: Int): String? = tickMutex.withLock {
        val cfg = config()
        if (!cfg.configured) {
            "📡 Sin Telegram configurado — no hay mensaje que abrir."
        } else {
            val box = hub.box(registryId) ?: return@withLock BoxTaps.MISS
            val index = page.coerceIn(0, box.pages.size - 1)
            val ok = telegram.edit(
                cfg,
                box.messageId,
                ToolOutputPages.renderPage(box.header, box.pages, index, box.language),
                Keyboards.boxPages(registryId, index, box.pages.size),
            )
            if (ok) hub.page(registryId, index) // the mirror: the message shows page `index` now
            logger.info {
                val verdict = if (ok) "open" else "edit BLOCKED (throttle)"
                "box tap: id=$registryId page=${index + 1}/${box.pages.size} -> $verdict"
            }
            null
        }
    }

    /**
     * The pager's way back: the message folds to its one-line header and
     * the fold mirrors it ([BoxLive.FOLDED]) — same fact law as [open].
     */
    override suspend fun fold(registryId: Int): String? = tickMutex.withLock {
        val cfg = config()
        if (!cfg.configured) {
            "📡 Sin Telegram configurado — no hay mensaje que abrir."
        } else {
            val box = hub.box(registryId) ?: return@withLock BoxTaps.MISS
            val ok = telegram.edit(cfg, box.messageId, box.header, Keyboards.boxOpen(registryId))
            if (ok) hub.page(registryId, BoxLive.FOLDED)
            logger.info { "box tap: id=$registryId -> ${if (ok) "folded" else "fold BLOCKED (throttle)"}" }
            null
        }
    }

    /**
     * The seal: the turn's record, the transcript's REMAINDER (closes and
     * blocks folded after the last drain — a late attach's history, the
     * events between two ticks), the unsealed thought tail, then the
     * answer. SUMMARY keeps the old shape: the record edits the card in
     * place and only the answer speaks. Otherwise the card dies and the
     * record is born as the LAST message (the owner's rule holds to the
     * end); the remainder rides the outbox — the order holds even if a
     * window opens mid-loop. Back-pressure: with a remainder pending and
     * a cooldown window alive the seal WAITS (unsealed, retried next
     * tick) — the transcript loses nothing, the chat never floods.
     */
    private suspend fun seal(cfg: TgConfig, mid: Long, state: TurnLive, now: Long) {
        val level = verbosity.current()
        val feed = TranscriptFeed.messages(feedSeen, state, level, toolBox, verbs) +
            if (level == FeedVerbosity.SUMMARY) emptyList() else TranscriptFeed.pendingFlush(state)
        if (feed.isNotEmpty() && telegram.throttled()) return
        sealed = true
        if (feed.isEmpty()) {
            telegram.edit(cfg, mid, LiveTurn.sealText(state, now))
        } else {
            telegram.delete(cfg, mid)
            speakFeed(feed, state)
            speak(LiveTurn.sealText(state, now), null)
        }
        val answer = finalAnswer(state.taskId)?.takeIf { it.isNotBlank() }
            ?: state.answerAtSeal?.takeIf { it.isNotBlank() }
        // THE VOICE'S LAST WORD (5/11): the final answer IS the LLM talking —
        // it seals as a violet rich message (pullquote prose + pre code).
        // NEVER LOST twice over (11/11): the snapshot's full text first; when
        // the snapshot cannot answer NOW, the fold's own [answerAtSeal] copy
        // speaks (the prose no longer travels the feed — the seal owns the
        // answer). A violet send that could not fly (a cooldown window
        // opened mid-seal) falls back to the parked speak lane — the answer
        // arrives, even degraded. richVoice=false keeps the escaped plain
        // text (the HTML law comment below still governs that leg).
        if (answer != null) {
            val violet = if (richVoice) telegram.sendRich(cfg, RichVoice.proseBlocks(answer)) else null
            if (violet == null) speak(PyText.esc(answer), null)
        }
        logger.info {
            "live view sealed: taskId=${state.taskId} reason=${(state.phase as TurnPhase.Done).stopReason} " +
                "thought=${state.thoughtChars} tools=${state.doneTools} answer=${answer?.length ?: 0} chars " +
                "feed=${feed.size}"
        }
        sealTap(state, answer, now, answerSpoken = answer != null)
        // THE REGISTRY SURVIVES THE SEAL (the collapsed buttons keep
        // working after the turn ends — the owner reads the CoT calmly);
        // only the entry cap prunes it. The PIN dies only if THIS turn is
        // its card: the parent sealing must not steal a child's pin.
        hub.clear(state.taskId)
        orphanStore?.clear()
        statusMessageId = null
        statusTaskId = null
        feedSeen = null
        resetPacing() // the turn is spent — the pings' counter dies with it
    }

    /**
     * THE SEAL TAP's edge: one value ([SealFacts]) carrying the seal's
     * own truth, handed to the injected callback. The notification never
     * breaks the seal — the callback's failure is one log line and the
     * seal's cleanup (below) always runs (cancellation still passes).
     */
    @Suppress("TooGenericExceptionCaught") // the bus's tap invariant: no failure reaches the seal
    private suspend fun sealTap(state: TurnLive, answer: String?, now: Long, answerSpoken: Boolean) {
        try {
            onSeal(
                SealFacts(
                    taskId = state.taskId,
                    title = state.title,
                    stopReason = (state.phase as TurnPhase.Done).stopReason,
                    startedAtMs = state.startedAtMs,
                    sealedAtMs = now,
                    doneTools = state.doneTools,
                    failedTools = state.failedTools,
                    answer = answer,
                    answerSpoken = answerSpoken,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "seal notifications tap failed (${e::class.simpleName}) — the seal is unaffected" }
        }
    }

    /**
     * A failed birth (cooldown window) must NOT retry every tick: the
     * 14:55 loop logged «born message_id=null» every 3s and would hammer
     * Telegram the moment the window lifted. Back off BIRTH_RETRY_TICKS
     * ticks between attempts, say it ONCE. Every birth (the first and
     * every re-draw) carries the card's REAL render — the process card,
     * from its very first message.
     */
    private suspend fun tryBirth(cfg: TgConfig, state: TurnLive, text: String) {
        // THE BIRTH SPACING (measured 4/11): a channel flap re-births the SAME
        // task's card on EVERY reconnect — 18 births in one minute is a
        // Telegram flood waiting for the next penalty. A re-birth of the SAME
        // task waits out the spacing (the card returns next tick); a genuine
        // task SWITCH never waits (that birth is the never-silence law). The
        // blocked-birth cooldown rides the same gate.
        val now = clock()
        val lastSameTaskBirth = lastBirthAtMs?.takeIf { lastBirthTaskId == state.taskId }
        val spaced = birthCooldownTicks > 0 ||
            (lastSameTaskBirth != null && now - lastSameTaskBirth < BIRTH_SPACING_MS)
        if (spaced) {
            if (birthCooldownTicks > 0) birthCooldownTicks -= 1
            return
        }
        val born = telegram.birth(cfg, text, null)
        if (born == null) {
            if (!birthFailLogged) {
                logger.warn {
                    "live view birth BLOCKED (cooldown/config) — retrying every ~$BIRTH_RETRY_TICKS ticks"
                }
                birthFailLogged = true
            }
            birthCooldownTicks = BIRTH_RETRY_TICKS
            return
        }
        statusMessageId = born
        birthFailLogged = false
        sealed = false
        statusTaskId = state.taskId
        lastText = text
        lastBirthAtMs = now
        lastBirthTaskId = state.taskId
        orphanStore?.save(born, state.taskId) // the boot seal's pointer
        logger.info { "live view born: taskId=${state.taskId} message_id=$statusMessageId" }
    }

    /** The never-silence Process — the edit cadence is schedule algebra. */
    fun asProcess(every: Duration = EDIT_CADENCE, onHeartbeat: () -> Unit = {}): Process<Unit> =
        Kap { tick() }
            .repeat(Schedule.spaced(every))
            .process(
                name = "live-view",
                restart = RestartPolicy.exponential(base = 1.seconds, max = 15.seconds),
                heartbeatEvery = 30.seconds,
                onHeartbeat = onHeartbeat,
                onRestart = { attempt, error, delay ->
                    logger.warn(error) { "live view tick failed — restart $attempt in $delay" }
                },
            )

    /**
     * THE PROGRESS PINGS' Process (v2 §4) — the 5s cadence is schedule
     * algebra, same idiom as [asProcess], started next to it in Main.
     * The pacing state lives HERE (the fold stays pure).
     */
    fun pingProcess(onHeartbeat: () -> Unit = {}): Process<Unit> =
        Kap { pingTick() }
            .repeat(Schedule.spaced(PING_CADENCE))
            .process(
                name = "live-view-pings",
                restart = RestartPolicy.exponential(base = 1.seconds, max = 15.seconds),
                heartbeatEvery = 30.seconds,
                onHeartbeat = onHeartbeat,
                onRestart = { attempt, error, delay ->
                    logger.warn(error) { "live view ping failed — restart $attempt in $delay" }
                },
            )

    // ---- the 5s pings (v2 §4) ---------------------------------------------------

    /** The pings' pacing state — PUBLISHER state, the fold stays pure. */
    private var pingCounter = 0
    private var pingActionKey: String? = null
    private var pingLastThoughtChars = -1
    private var pingLastEditKey: String? = null

    /** One pass of the 5s cadence. MUTEXED with the tick (shared cells). */
    suspend fun pingTick() = tickMutex.withLock { pingTickLocked() }

    // one guard per silence/skip verdict — the ping's own honesty ladder
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private suspend fun pingTickLocked() {
        val cfg = config()
        if (!cfg.configured) return
        val state = hub.peek() ?: return
        val now = clock()
        // THE RESETS (v2 §4): the action's counter dies on Done and on task
        // switch — a new action's pings count from 1 again
        if (state.phase is TurnPhase.Done || (statusTaskId != null && statusTaskId != state.taskId)) resetPacing()
        if (state.phase is TurnPhase.Done) return
        // THE LIVE LAW: pings speak only a genuinely live turn — the same
        // conditions the card's frozen clock reads, never a frozen turn
        if (!channelAlive() || state.typedError != null || state.apiRetry != null) return
        val silence = now - (state.lastEventAtMs ?: now)
        if (silence >= LiveTurn.STALE_SOFT_MS) return // frozen — silence is data
        // FLOOD SAFETY (the standing law): the ping rides the firehose; a
        // cooldown window would DELAY the send — a delayed ping is a queued
        // ping. DROP it: never queue, never burst, max one per 5s per turn.
        if (telegram.throttled()) return
        when (val phase = state.phase) {
            is TurnPhase.Thinking -> {
                // CoT streaming (fresh per §2): the deltas are flowing NOW.
                // No new chars since the last ping = silence is data — skip.
                val fresh = state.lastThoughtAtMs != null && now - state.lastThoughtAtMs < LiveTurn.COT_QUIET_MS
                if (!fresh || state.thoughtChars == pingLastThoughtChars) return
                if (pingActionKey != PING_THINKING) resetPacing(PING_THINKING)
                pingCounter += 1
                pingLastThoughtChars = state.thoughtChars
                val text = "🧠 thinking ${LiveTurn.fmtKDecimal(state.thoughtChars.toLong())}"
                speak(text, null)
                logger.info { "progress ping #$pingCounter: $text (taskId=${state.taskId.takeLast(TASK_ID_TAIL)})" }
            }

            is TurnPhase.Writing -> {
                val lane = phase.toolId?.let { id -> state.tools.firstOrNull { it.toolId == id } }
                // Edit streaming only (a live +A/−R): bash and the answer
                // stream NO pings — the card already carries them
                val diff = lane?.takeIf { it.name in WRITE_EDIT_TOOLS && (it.added != null || it.removed != null) }
                if (diff == null) return
                val basename = (diff.input?.str("file_path") ?: diff.input?.str("path") ?: diff.name)
                    .substringAfterLast('/')
                // THE FILE'S RUNNING TOTAL (the owner's 5/11 ladder: +21 →
                // +55 → +94 is the SAME file accumulating across its edits —
                // every lane this turn touched that file counts, open and
                // sealed alike; the single lane's diff alone is a rung, not
                // the ladder)
                val totals = state.tools
                    .filter { it.name in WRITE_EDIT_TOOLS }
                    .filter { toolBasename(it) == basename }
                    .fold(0 to 0) { acc, t -> acc.first + (t.added ?: 0) to acc.second + (t.removed ?: 0) }
                val editKey = "$basename +${totals.first} −${totals.second}"
                if (pingActionKey != diff.toolId) resetPacing(diff.toolId)
                if (editKey == pingLastEditKey) return // the counts did not change
                pingCounter += 1
                pingLastEditKey = editKey
                val text = "✏️ Editando archivo $basename +${totals.first} −${totals.second}"
                speak(text, null)
                logger.info { "progress ping #$pingCounter: $text (taskId=${state.taskId.takeLast(TASK_ID_TAIL)})" }
            }

            is TurnPhase.Done, TurnPhase.Idle -> return
        }
    }

    /** A write/edit lane's file basename — the ladder's grouping key. */
    private fun toolBasename(lane: ToolLive): String =
        (lane.input?.str("file_path") ?: lane.input?.str("path") ?: lane.name).substringAfterLast('/')

    /** The pacing's reset — the whole cell set when the action changes. */
    private fun resetPacing(actionKey: String? = null) {
        pingActionKey = actionKey
        pingCounter = 0
        pingLastThoughtChars = -1
        pingLastEditKey = null
    }

    private var birthCooldownTicks = 0
    private var birthFailLogged = false
    private var lastBirthAtMs: Long? = null
    private var lastBirthTaskId: String? = null
    private val tickMutex = Mutex()

    private companion object {
        val EDIT_CADENCE = 3.seconds

        /** The progress pings' cadence (v2 §4) — one ping per 5s per turn, max. */
        val PING_CADENCE = 5.seconds

        /** The Thinking phase's action identity (the pings' counter key). */
        private const val PING_THINKING = "thinking"

        /** Two births never ride closer than this (the flap's flood guard). */
        const val BIRTH_SPACING_MS = 30_000L

        /** A blocked birth retries every ~30s (10 ticks × 3s), not every tick. */
        const val BIRTH_RETRY_TICKS = 10

        /** The silence that counts as the fork's dead bus, not a quiet model. */
        const val STREAM_WATCHDOG_SILENCE_MS = 60_000L
        const val MS_PER_SEC = 1000L
        const val TASK_ID_TAIL = 8

        /** The prize cache: one read per 5 min; a line older than 15 min goes silent. */
        const val GIFTS_REFRESH_MS = 300_000L
        const val GIFTS_STALE_MS = 900_000L

        /** The box id's mask — the task's hash, kept non-negative for the wire. */
        private const val BOX_ID_MASK = 0x7FFFFFFF
    }
}
