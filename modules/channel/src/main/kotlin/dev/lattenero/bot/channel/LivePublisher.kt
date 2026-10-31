package dev.lattenero.bot.channel

import dev.lattenero.bot.transport.TgConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kap.Kap
import kap.Process
import kap.RestartPolicy
import kap.Schedule
import kap.process
import kap.repeat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * The turn's state between the channel and the phone: ONE current turn
 * (the subscribed task), folded by the PURE [LiveTurn.reduce] under a
 * mutex. This is the visible escape hatch of policy §7 — the state is a
 * value, the holder is the thinnest mutable box, and every transition is
 * the reducer's (tested) decision, never an in-place mutation.
 */
// the hub is the thinnest mutable box over the turn value — one method per
// honest verb (apply/pin/attach/retitle/resync…), none extractable
@Suppress("TooManyFunctions")
class LiveHub {
    private val mutex = Mutex()
    private var current: TurnLive? = null

    /** The lane scope (a sa: attach's fallback) — null watches the whole task's stream. */
    private var watchedLane: String? = null

    /**
     * A pinned card (a sa: attach's full form — the subagent's OWN session):
     * other tasks' events — the parent's among them — are DROPPED until the
     * pin lifts, or the single-task hub would flip the card back at the
     * parent's every event.
     */
    private var pinnedTaskId: String? = null

    /** One stream event folded in; answers the state after the fold. */
    suspend fun apply(
        frame: ServerFrame.StreamEvent,
        nowMs: Long,
        differ: LineDiffer = SimpleLineDiffer,
    ): TurnLive? = mutex.withLock {
        // the pinned view owns the hub: drop everyone else's frames whole
        if (pinnedTaskId != null && frame.taskId != pinnedTaskId) return@withLock current
        val fresh = when {
            // a new task's event replaces the watched turn (a /task pick or a
            // new subscribe): the old turn's status message stays sealed in the chat
            current != null && current!!.taskId != frame.taskId -> {
                watchedLane = null // a task switch is also a lane-switch: scope dies with the task
                null
            }
            else -> current
        }
        // a lane-scoped card folds ONLY its subagent's events plus the root
        // tool's own lifecycle (arrival/close — the honest end signal)
        if (watchedLane != null && fresh != null) {
            val ev = frame.event
            val passes = StreamEvents.parentToolUseId(ev) == watchedLane ||
                StreamEvents.toolId(ev) == watchedLane
            if (!passes) return@withLock current
        }
        LiveTurn.reduce(fresh, frame, nowMs, differ).also { current = it }
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
        mutex.withLock {
            watchedLane = laneId
            current = current?.copy(watchedLane = laneId)
        }
    }

    /** Back to the whole task's stream (a t: attach clears any lane scope). */
    suspend fun watchTask() {
        mutex.withLock {
            watchedLane = null
            current = current?.copy(watchedLane = null)
        }
    }

    /** The current lane scope — the sa: tap's resolution and tests. */
    suspend fun watchedLaneId(): String? = mutex.withLock { watchedLane }

    /**
     * The card's real title, resolved OUTSIDE the stream (the fork's
     * session_info_update never carries it for some sessions — the
     * history's tasks-index does): retitle the watched turn in place, the
     * next tick renders and seals with the honest name.
     */
    suspend fun retitle(taskId: String, title: String) {
        mutex.withLock {
            if (current?.taskId == taskId) current = current?.copy(title = title)
        }
    }

    /** A reconnect's resync: the lost events stay lost, the state re-baselines.
     *  The SEALED blocks survive (they are complete real text — a gap cuts
     *  the seam, never a sealed box — and the feed already spoke them);
     *  the live windows die (a tail/pending spanning the gap would render
     *  a continuity that no longer exists). */
    suspend fun resync(taskId: String) {
        mutex.withLock {
            if (current?.taskId == taskId) {
                current = current?.copy(
                    cotTail = "",
                    cotPending = "",
                    phase = TurnPhase.Idle,
                )
            }
        }
    }

    suspend fun peek(): TurnLive? = mutex.withLock { current }

    /** After the seal is published the turn is spent; /nuclear also lands here.
     *  A pin dies with the card: the parent's next event re-lights its own. */
    suspend fun clear(): Unit = mutex.withLock {
        current = null
        pinnedTaskId = null
    }
}

/**
 * THE VIEW THAT NEVER GOES QUIET (the owner's si-o-si): one Telegram
 * message, born at the turn's start, EDITED IN PLACE at a safe cadence —
 * spinner, phase counters with their timers, the CoT preview, the
 * visible heartbeat. Ticking is a Kap Process (`repeat(spaced(3s))`):
 * the spinner advances even when the model is quiet, which is exactly
 * the difference between "thinking" and "dead" the owner asked to SEE.
 *
 * At `task_complete` the seal replaces the status text and the FINAL
 * render travels as its own message: the turn's answer through the
 * injected snapshot reader (`taskSnapshot` query in the wiring).
 *
 * THE TRANSCRIPT FEED (the owner's 30/10 order: «quiero VER los archivos,
 * leer los commands de bash, leer los cots uno debajo del otro en
 * mensajes separados»): every tick drains [TranscriptFeed]'s diff — each
 * sealed CoT block and each tool CLOSE becomes its own message through
 * the render lane, and the status card SWAPS (delete → feed → re-birth)
 * so the thinking box is ALWAYS the last message of everything. The
 * level ([FeedVerbosity], read PER TICK from the injected setting —
 * policy §7: the publisher never reads files) gates: SUMMARY speaks
 * nothing, NORMAL blocks + closes, VERBOSE adds the tool OPEN
 * one-liners. Verbose is the DEFAULT.
 */
class LivePublisher(
    private val hub: LiveHub,
    private val telegram: LiveTelegram,
    private val config: () -> TgConfig,
    private val channelAlive: () -> Boolean,
    private val speak: suspend (text: String) -> Unit,
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
) {

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
     * One tick of the never-silence clock. MUTEXED: the scheduled ~3s
     * process AND the block tap's immediate nudge both land here — the
     * cells (statusMessageId, lastText, spin) are the publisher's only
     * mutable state and a tap must never race a seal.
     */
    suspend fun tick() = tickMutex.withLock { tickLocked() }

    // the tick ladder (boot seal → title → birth → replace → seal → edit)
    // IS the publisher: one guard per step, splitting it hides the order
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private suspend fun tickLocked() {
        val cfg = config()
        if (!cfg.configured) return
        sealOrphan(cfg) // the first tick's boot duty, before anything else
        var state = hub.peek() ?: return
        val now = clock()
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
            tryBirth(cfg, state)
        }
        // THE STREAM WATCHDOG: a live turn with a long silence is the fork's
        // dead bus, not a quiet model (measured 30/10 twice — burst on
        // subscribe, then silence on a LIVE turn). One resubscribe per
        // silence episode flushes a fresh burst and the card moves again; a
        // legitimately quiet stretch re-subscribes harmlessly (same task,
        // same stream); Done cards never bark.
        val silenceMs = now - (state.lastEventAtMs ?: 0)
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
            telegram.edit(
                cfg,
                mid,
                "⛓️ turno reemplazado — esta vista era de otra tarea; la nueva tiene su propio cartel.",
            )
            logger.info { "live view REPLACED: old taskId=$statusTaskId message_id=$mid -> new=${state.taskId}" }
            statusMessageId = null
            statusTaskId = null
            lastText = ""
            sealed = false
            titleResolvedFor = null // the new task's card resolves its own title
            feedSeen = null // the new turn's transcript starts clean — history is never replayed
            return
        }
        if (state.phase is TurnPhase.Done && !sealed) {
            seal(cfg, mid, state, now)
            return
        }
        drainFeed(cfg, state)
        val liveMid = statusMessageId ?: return // the swap's re-birth is throttled: the card returns next tick
        val text = LiveTurn.render(state, now, spin(), channelAlive(), verbs)
        if (text == lastText) return // nothing visible changed: no edit (the 429 budget is shared)
        if (telegram.edit(cfg, liveMid, text)) lastText = text
    }

    /**
     * The TRANSCRIPT FEED's drain: [TranscriptFeed]'s diff between the
     * seen pointer and this tick's state, spoken through the render lane.
     * When it speaks, THE CARD SWAPS — delete, the feed goes out, the
     * card is REBORN as the last message (the owner's rule: «el thinking
     * box siempre se tiene que redibujar como último mensaje de todo» — a
     * feed message may never bury the thinking box). Back-pressure: a
     * cooldown window skips the whole drain WITHOUT advancing the seen
     * pointer — the next tick re-speaks the same diff when it lifts; the
     * parked outbox and the re-born card never race for the chat's order.
     */
    // one guard per early exit (silent level, throttle window) — the drain's own shape
    @Suppress("ReturnCount")
    private suspend fun drainFeed(cfg: TgConfig, state: TurnLive) {
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
            feed.forEach { speak(it) } // no card yet: the feed speaks, the birth lands below it
        } else {
            telegram.delete(cfg, mid)
            statusMessageId = null
            lastText = ""
            feed.forEach { speak(it) }
            tryBirth(cfg, state) // the card re-born LAST — above nothing, below everything
        }
        logger.info {
            "transcript feed: ${feed.size} message(s) spoken " +
                "(card=${if (mid != null) "swapped" else "absent"})"
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
            feed.forEach { speak(it) }
            speak(LiveTurn.sealText(state, now))
        }
        val answer = finalAnswer(state.taskId)?.takeIf { it.isNotBlank() }
        if (answer != null) speak(answer)
        logger.info {
            "live view sealed: taskId=${state.taskId} reason=${(state.phase as TurnPhase.Done).stopReason} " +
                "thought=${state.thoughtChars} tools=${state.doneTools} answer=${answer?.length ?: 0} chars " +
                "feed=${feed.size}"
        }
        hub.clear()
        orphanStore?.clear()
        statusMessageId = null
        statusTaskId = null
        feedSeen = null
    }

    private fun spin(): Int {
        spinCount += 1
        return spinCount % SPIN_CYCLE
    }

    /**
     * A failed birth (cooldown window) must NOT retry every tick: the
     * 14:55 loop logged «born message_id=null» every 3s and would hammer
     * Telegram the moment the window lifted. Back off BIRTH_RETRY_TICKS
     * ticks between attempts, say it ONCE.
     */
    private suspend fun tryBirth(cfg: TgConfig, state: TurnLive) {
        if (birthCooldownTicks > 0) {
            birthCooldownTicks -= 1
            return
        }
        val born = telegram.birth(cfg, LiveTurn.birthText(state))
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
        lastText = ""
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

    private var spinCount = 0
    private var birthCooldownTicks = 0
    private var birthFailLogged = false
    private val tickMutex = Mutex()

    private companion object {
        val EDIT_CADENCE = 3.seconds
        const val SPIN_CYCLE = 4

        /** A blocked birth retries every ~30s (10 ticks × 3s), not every tick. */
        const val BIRTH_RETRY_TICKS = 10

        /** The silence that counts as the fork's dead bus, not a quiet model. */
        const val STREAM_WATCHDOG_SILENCE_MS = 60_000L
        const val MS_PER_SEC = 1000L
        const val TASK_ID_TAIL = 8
    }
}
