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
     * A pinned card (a sa: attach's full form — the subagent's OWN session):
     * other tasks' events — the parent's among them — are DROPPED until the
     * pin lifts, or the single-task hub would flip the card back at the
     * parent's every event. The pin is the CARD'S IDENTITY (which message is
     * this), not render state: the fold's own state never carries it.
     */
    private var pinnedTaskId: String? = null

    /**
     * The fold's one step — the ONLY writer of the accumulator. `differ`
     * only matters for the frame events (an edit's +N/−M); the verbs fold
     * with the house default, which they never touch.
     */
    private fun fold(event: RenderEvent, differ: LineDiffer): TurnLive? {
        states.value = reduce(states.value, event, differ)
        return states.value
    }

    /** One stream event folded in; answers the state after the fold. */
    suspend fun apply(
        frame: ServerFrame.StreamEvent,
        nowMs: Long,
        differ: LineDiffer = SimpleLineDiffer,
    ): TurnLive? = mutex.withLock {
        // the pinned view owns the hub: drop everyone else's frames whole
        if (pinnedTaskId != null && frame.taskId != pinnedTaskId) return@withLock states.value
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
     *  a continuity that no longer exists). */
    suspend fun resync(taskId: String) {
        mutex.withLock { fold(RenderEvent.Resync(taskId), SimpleLineDiffer) }
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
     * flew — the born fact (id, header, pages, message id) folds.
     */
    suspend fun boxBorn(taskId: String, boxId: Int, header: String, pages: List<String>, messageId: Long) {
        mutex.withLock { fold(RenderEvent.BoxBorn(taskId, boxId, header, pages, messageId), SimpleLineDiffer) }
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
        fold(RenderEvent.Clear, SimpleLineDiffer)
        if (sealedTaskId == null || pinnedTaskId == sealedTaskId) pinnedTaskId = null
    }

    private companion object {
        /** The sealed archive's ceiling — the chat's own scrollback is the real limit. */
        const val SEALED_BOX_CAP = 120
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
        if (statusMessageId == null && state.phase != TurnPhase.Idle) {
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
        if (
            state.phase !is TurnPhase.Done &&
            silenceMs > STREAM_WATCHDOG_SILENCE_MS &&
            now - lastWatchdogAtMs > STREAM_WATCHDOG_SILENCE_MS
        ) {
            lastWatchdogAtMs = now
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
    }

    /**
     * THE CARD IS ALWAYS LAST: a changed render re-draws the card as a
     * NEW last message (the owner's law, 2/11 — «la quiero debajo de
     * todo SIEMPRE»), never an in-place edit something may have buried.
     * Only on change (the 429 budget is shared); under a 429 window the
     * redraw skips whole — the card stays where it is, stale but present,
     * and the next tick re-draws when the window lifts.
     */
    private suspend fun redrawCard(cfg: TgConfig, state: TurnLive, now: Long, liveMid: Long) {
        val text = LiveTurn.render(state, now, channelAlive(), verbs, giftsForCard(now))
        if (text == lastText) return // nothing visible changed: the card stays
        if (telegram.throttled()) return
        telegram.delete(cfg, liveMid)
        statusMessageId = null
        lastText = ""
        tryBirth(cfg, state, text)
    }

    /**
     * The TRANSCRIPT FEED's drain: [TranscriptFeed]'s diff between the
     * seen pointer and this tick's state, spoken through the render lane.
     * When it speaks, THE CARD SWAPS — delete, the feed goes out, the
     * card is REBORN as the last message with its CURRENT render (the
     * owner's rule: «el thinking box siempre se tiene que redibujar como
     * último mensaje de todo» — a feed message may never bury the card).
     * Back-pressure: a cooldown window skips the whole drain WITHOUT
     * advancing the seen pointer — the next tick re-speaks the same diff
     * when it lifts; the parked outbox and the re-born card never race
     * for the chat's order.
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
        if (mid == null) {
            speakFeed(feed, state) // no card yet: the feed speaks, the birth lands below it
        } else {
            telegram.delete(cfg, mid)
            statusMessageId = null
            lastText = ""
            speakFeed(feed, state)
            // the card re-born LAST with its CURRENT render — above
            // nothing, below everything
            tryBirth(cfg, state, LiveTurn.render(state, now, channelAlive(), verbs, giftsForCard(now)))
        }
        logger.info {
            "transcript feed: ${feed.size} message(s) spoken " +
                "(card=${if (mid != null) "swapped" else "absent"})"
        }
    }

    /**
     * Speaks the feed's diff and REGISTERS every collapsed message: a CoT
     * block via its registry, a tool output box BY FOLDING its birth (U5b
     * — the fold names the id, the send names the message). The collapsed
     * header is all the chat sees until the owner asks.
     */
    private suspend fun speakFeed(feed: List<FeedMessage>, state: TurnLive) {
        feed.forEach { msg ->
            // a COLLAPSED tool output: the header speaks with its one
            // button; the fold keeps the numbered pages for the taps
            msg.pages?.let { pages ->
                speakBox(state, pages) { markup -> speak(msg.text, markup) }
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
            cotBlocks.speakCollapsed(
                number = if (block == 0) null else block,
                text = text,
                cp = PyText.cpLength(text),
                send = { markup -> speak(msg.text, markup) },
            )
        }
    }

    /**
     * Speaks ONE collapsed output box and folds its birth (U5b): the fold
     * names the wire id — turn-unique by construction (the task's hash
     * base + the birth order, so an old turn's button can never alias a
     * new turn's box) — the send flies, and the born fact (the message id
     * included) folds. A send that never flew folds NOTHING: there is no
     * message to edit, the tap on it is an honest miss.
     */
    private suspend fun speakBox(
        state: TurnLive,
        pages: ToolOutputPages.Pages,
        send: suspend (markup: JsonObject?) -> Long?,
    ) {
        val base = state.taskId.hashCode() and BOX_ID_MASK
        val boxId = (hub.peek()?.boxes?.maxOfOrNull { it.boxId } ?: base) + 1
        val mid = send(Keyboards.boxOpen(boxId)) ?: return
        hub.boxBorn(state.taskId, boxId, pages.header, pages.pages, mid)
        logger.info { "box born: id=$boxId message_id=$mid pages=${pages.pages.size}" }
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
                ToolOutputPages.renderPage(box.header, box.pages, index),
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
        // the store's answer is RAW markdown/content — Telegram's HTML law
        // requires the escape (measured 31/10: a literal tag in the content
        // 400'd the send every tick until the breaker opened)
        if (answer != null) speak(PyText.esc(answer), null)
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

    private var birthCooldownTicks = 0
    private var birthFailLogged = false
    private var lastBirthAtMs: Long? = null
    private var lastBirthTaskId: String? = null
    private val tickMutex = Mutex()

    private companion object {
        val EDIT_CADENCE = 3.seconds

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
