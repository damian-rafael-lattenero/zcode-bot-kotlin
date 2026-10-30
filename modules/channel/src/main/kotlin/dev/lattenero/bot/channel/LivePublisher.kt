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
class LiveHub {
    private val mutex = Mutex()
    private var current: TurnLive? = null

    /** One stream event folded in; answers the state after the fold. */
    suspend fun apply(
        frame: ServerFrame.StreamEvent,
        nowMs: Long,
        differ: LineDiffer = SimpleLineDiffer,
    ): TurnLive? = mutex.withLock {
        val fresh = when {
            // a new task's event replaces the watched turn (a /task pick or a
            // new subscribe): the old turn's status message stays sealed in the chat
            current != null && current!!.taskId != frame.taskId -> null
            else -> current
        }
        LiveTurn.reduce(fresh, frame, nowMs, differ).also { current = it }
    }

    /** A reconnect's resync: the lost events stay lost, the state re-baselines. */
    suspend fun resync(taskId: String) {
        mutex.withLock {
            if (current?.taskId == taskId) {
                current = current?.copy(cotTail = "", cotExpanded = false, phase = TurnPhase.Idle)
            }
        }
    }

    suspend fun peek(): TurnLive? = mutex.withLock { current }

    /** The cajita's taps: cot: opens, cotr: folds (the next tick re-renders). */
    suspend fun expandCot(expand: Boolean) {
        mutex.withLock { current = current?.copy(cotExpanded = expand) }
    }

    /** After the seal is published the turn is spent; /nuclear also lands here. */
    suspend fun clear(): Unit = mutex.withLock { current = null }
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
 * render travels as its own message: the turn's tool boxes through the
 * SPECTATOR's renderer (injected — `ToolBox.box` from the daemon side)
 * and the turn's answer through the injected snapshot reader
 * (`taskSnapshot` query in the wiring).
 */
class LivePublisher(
    private val hub: LiveHub,
    private val telegram: LiveTelegram,
    private val config: () -> TgConfig,
    private val channelAlive: () -> Boolean,
    private val speak: suspend (text: String) -> Unit,
    private val finalBoxes: suspend (List<ToolLive>) -> String,
    private val finalAnswer: suspend (taskId: String) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The per-action verbs — the wiring injects the spectator's classification map. */
    private val verbs: ToolVerbifier = ToolVerbs.MINIMAL,
    /** The durable message id — the boot-time ORPHAN SEAL reads it (LiveOrphanStore). */
    private val orphanStore: LiveOrphanStore? = null,
) {

    /** the message being edited — the publisher's only mutable cell (§7 escape hatch). */
    @Volatile
    private var statusMessageId: Long? = null
    private var statusTaskId: String? = null
    private var lastText: String = ""
    private var sealed = false
    private var orphanChecked = false

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

    /** One tick of the never-silence clock. */
    @Suppress("ReturnCount") // the tick ladder: no state, no message, no edit — one guard each
    suspend fun tick() {
        val cfg = config()
        if (!cfg.configured) return
        sealOrphan(cfg) // the first tick's boot duty, before anything else
        val state = hub.peek() ?: return
        val now = clock()
        if (statusMessageId == null && state.phase != TurnPhase.Idle) {
            // A failed birth (cooldown window) must NOT retry every tick:
            // the 14:55 loop logged «born message_id=null» every 3s and
            // would hammer Telegram the moment the window lifted. Back off
            // BIRTH_RETRY_TICKS ticks between attempts, say it ONCE.
            if (birthCooldownTicks > 0) {
                birthCooldownTicks -= 1
                return
            }
            val born = telegram.birth(cfg, LiveTurn.birthText(state))
            if (born == null) {
                if (!birthFailLogged) {
                    logger.warn { "live view birth BLOCKED (cooldown/config) — retrying every ~$BIRTH_RETRY_TICKS ticks" }
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
            return
        }
        if (state.phase is TurnPhase.Done && !sealed) {
            seal(cfg, mid, state, now)
            return
        }
        val text = LiveTurn.render(state, now, spin(), channelAlive(), verbs)
        if (text == lastText) return // nothing visible changed: no edit (the 429 budget is shared)
        if (telegram.edit(cfg, mid, text, LiveTurn.cotMarkup(state))) lastText = text
    }

    private suspend fun seal(cfg: TgConfig, mid: Long, state: TurnLive, now: Long) {
        sealed = true
        telegram.edit(cfg, mid, LiveTurn.sealText(state, now))
        // the final shower: the spectator renderer's boxes + the turn's answer
        val boxes = finalBoxes(state.tools)
        val answer = finalAnswer(state.taskId)?.takeIf { it.isNotBlank() }
        if (answer != null) speak(answer)
        if (boxes.isNotBlank()) speak(boxes)
        logger.info {
            "live view sealed: taskId=${state.taskId} reason=${(state.phase as TurnPhase.Done).stopReason} " +
                "thought=${state.thoughtChars} tools=${state.doneTools} answer=${answer?.length ?: 0} chars"
        }
        hub.clear()
        orphanStore?.clear()
        statusMessageId = null
        statusTaskId = null
    }

    private fun spin(): Int {
        spinCount += 1
        return spinCount % SPIN_CYCLE
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

    private companion object {
        val EDIT_CADENCE = 3.seconds
        const val SPIN_CYCLE = 4

        /** A blocked birth retries every ~30s (10 ticks × 3s), not every tick. */
        const val BIRTH_RETRY_TICKS = 10
    }
}
