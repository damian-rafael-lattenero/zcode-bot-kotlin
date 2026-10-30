package dev.lattenero.bot.daemon.spectator

import dev.lattenero.bot.contracts.ShadowDecision
import dev.lattenero.bot.daemon.send.DaemonSend
import dev.lattenero.bot.daemon.send.SendLeg
import dev.lattenero.bot.daemon.send.SendLegs
import dev.lattenero.bot.daemon.shadow.ShadowLog
import dev.lattenero.bot.daemon.shadow.TimeIso
import io.github.oshai.kotlinlogging.KotlinLogging
import kap.Kap
import kap.ProcessHandle
import kap.RestartPolicy
import kap.Schedule
import kap.evalGraph
import kap.process
import kap.repeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One Mac turn, start to finish — the port of spectator.watch as the body
 * of a per-turn `Process`: reactive in spirit (poll cadence), pins the
 * turn by marker token, steps down when a NEW prompt takes over, ends
 * when F32 consumes the marker, hard cap 35 min. Normal completion ends
 * the process: the turn is simply over. The poll loop is schedule
 * algebra (U0's no-manual-concurrency sweep): one tick is a Kap that
 * answers with a [Tick] signal, and `repeat` owns the cadence, the stop
 * conditions and the cap — no hand-rolled while.
 */
internal class SpectatorTurn(
    private val baseDir: File,
    private val stateDir: File,
    private val shadowLog: ShadowLog,
    private val sendLeg: SendLeg = SendLegs.NONE,
    private val now: () -> Double,
) {
    private val logger = KotlinLogging.logger {}

    /** One poll's answer: keep going, the turn ended, or a NEW turn owns the marker. */
    private sealed interface Tick {
        data object Keep : Tick
        data object MarkerGone : Tick // F32 delivered the real thing
        data object Takeover : Tick // a NEW turn took the marker over
    }

    suspend fun run(sid: String) = withContext(MDCContext(mapOf(COMPONENT to "spectator"))) {
        val cfg = SpectatorIo.cfg(baseDir)
        var state = SpectatorState(
            t0Ms = (now() - SPAWN_GRACE_S) * 1000.0,
            title = SpectatorIo.taskLabel(baseDir, sid),
        )
        val rollout = File(File(File(baseDir, "cli"), "rollout"), "model-io-$sid.jsonl")
        val reader = RolloutReader(rollout)
        var pos = reader.sizeAtStart()
        var token0 = SpectatorIo.markerToken(stateDir, sid)
        logger.info { "spectator: watching sid=...${sid.takeLast(SID_TAIL)} title=«${state.title}»" }
        Kap {
            if (!SpectatorIo.markerPath(stateDir, sid).exists()) return@Kap Tick.MarkerGone
            val tok = SpectatorIo.markerToken(stateDir, sid)
            if (token0.isNotEmpty() && tok.isNotEmpty() && tok != token0) return@Kap Tick.Takeover
            if (token0.isEmpty() && tok.isNotEmpty()) token0 = tok
            val read = reader.read(pos)
            pos = read.first
            for (record in read.second) {
                val result = SpectatorCore.processRecord(record, state, cfg)
                state = result.first
                result.second.forEach { m ->
                    val mayPush = SpectatorIo.taskCurrentOk(baseDir, cfg, sid) &&
                        !SpectatorIo.viewAlive(stateDir, sid, now())
                    val d = m.toDecision(sid, now(), mayPush)
                    shadowLog.append(d)
                    // Python's send_msg: the gate guards the SEND, the
                    // decision is recorded either way; loud=False (silent
                    // boxes — only zconn's ⚠️/✅ notify)
                    if (mayPush) sendLeg.deliver(d, loud = false)
                }
            }
            Tick.Keep
        }.repeat(
            // the two orchestration signals stop the loop; the cadence is
            // Python's 1 s poll; the cap is 35 min OF TICKS (2100 at 1 s —
            // the count is the `now() < deadline` check made denotational;
            // times(n-1) because repeat runs the body once BEFORE deciding)
            Schedule.doUntil<Tick> { it != Tick.Keep } and
                Schedule.spaced<Tick>(POLL) and
                Schedule.times<Tick>(CAP_TICKS - 1),
        ).evalGraph()
        logger.info { "spectator: turn over (steps=${state.step}) sid=...${sid.takeLast(SID_TAIL)}" }
    }

    private fun SpectatorMessage.toDecision(sid: String, decisionTs: Double, gated: Boolean) =
        ShadowDecision(
            ts = decisionTs,
            tsIso = TimeIso.format(decisionTs),
            watcher = "spectator",
            decision = "message",
            text = text,
            eventTs = eventTs,
            gated = gated,
            sid = sid,
        )

    private companion object {
        const val COMPONENT = "component"
        const val SID_TAIL = 8
        const val SPAWN_GRACE_S = 1.0
        const val CAP_S = 35 * 60.0
        const val CAP_TICKS = (CAP_S).toInt() // 2100 ticks at 1 s == the 35 min cap
        val POLL: Duration = 1000.milliseconds
    }
}

/**
 * The marker scanner — what the on_user_prompt hook does for Python, done
 * by polling here: a marker for a session with no bot worker starts a
 * per-turn spectator Process. Crashed turns retry once, then go mute
 * (Python's dead spectator stays dead); normal completion ends them.
 */
internal class SpectatorOrchestrator(
    private val baseDir: File,
    private val stateDir: File,
    private val shadowLog: ShadowLog,
    private val now: () -> Double,
    private val turnScope: CoroutineScope,
    private val send: DaemonSend = DaemonSend(),
) {
    private val logger = KotlinLogging.logger {}

    // escape hatch (policy §7): the live per-turn handles — keyed by the
    // marker's safe sid; entries reaped when the turn's process completes.
    private val turns = mutableMapOf<String, ProcessHandle<Unit>>()

    fun scan(): Int {
        turns.entries.removeIf { !it.value.isAlive }
        val rollouts = File(File(baseDir, "cli"), "rollout")
        val index = SpectatorIo.sidIndex(rollouts)
        val workers = SpectatorIo.workerSids(stateDir)
        val cfg = SpectatorIo.cfg(baseDir)
        var started = 0
        // the hook's spawn condition, in its order: no worker, the knob on,
        // and ONLY the current task (F15 — the comparator's first catch was
        // exactly this gate missing at spawn time). At the cutover the knob
        // flips meaning: mac_spectator=false silences PYTHON, and the owner
        // (send mode != shadow) ignores it — a muted phone is the incident
        // the owner's overlap rule exists to prevent.
        @Suppress("LoopWithTooManyJumpStatements")
        for ((safe, file) in SpectatorIo.markers(stateDir)) {
            if (!file.exists() || turns.containsKey(safe)) continue
            val sid = index[safe] ?: continue
            if (sid in workers) continue // its own worker streams the turn
            if ((!cfg.macSpectator && !send.ownsSpectator) ||
                !SpectatorIo.taskCurrentOk(baseDir, cfg, sid)
            ) {
                continue
            }
            // the RENDER lane: firehose when split, the leg itself when not
            val turn = SpectatorTurn(baseDir, stateDir, shadowLog, send.firehose, now)
            val handle = Kap { turn.run(sid) }
                .process(
                    name = "spectator-$safe",
                    restart = RestartPolicy.constant(1.seconds, maxRestarts = 1),
                    heartbeatEvery = Duration.ZERO,
                )
                .startIn(turnScope)
            turns[safe] = handle
            started++
        }
        return started
    }

    fun liveTurns(): Int = turns.values.count { it.isAlive }
}
