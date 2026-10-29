package dev.lattenero.bot.daemon.spectator

import dev.lattenero.bot.daemon.shadow.ShadowDecision
import dev.lattenero.bot.daemon.shadow.ShadowLog
import dev.lattenero.bot.daemon.shadow.TimeIso
import io.github.oshai.kotlinlogging.KotlinLogging
import kap.Kap
import kap.ProcessHandle
import kap.RestartPolicy
import kap.process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
 * the process: the turn is simply over.
 */
internal class SpectatorTurn(
    private val baseDir: File,
    private val stateDir: File,
    private val shadowLog: ShadowLog,
    private val now: () -> Double,
) {
    private val logger = KotlinLogging.logger {}

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
        val deadline = now() + CAP_S
        logger.info { "spectator: watching sid=...${sid.takeLast(SID_TAIL)} title=«${state.title}»" }
        // the two breaks are the two orchestration signals: the turn is over,
        // or a NEW turn took the marker over (its own spectator owns it)
        @Suppress("LoopWithTooManyJumpStatements")
        while (now() < deadline) {
            if (!SpectatorIo.markerPath(stateDir, sid).exists()) break // F32 delivered the real thing
            val tok = SpectatorIo.markerToken(stateDir, sid)
            if (token0.isNotEmpty() && tok.isNotEmpty() && tok != token0) break // a NEW turn owns it now
            if (token0.isEmpty() && tok.isNotEmpty()) token0 = tok
            val read = reader.read(pos)
            pos = read.first
            for (record in read.second) {
                val result = SpectatorCore.processRecord(record, state, cfg)
                state = result.first
                result.second.forEach { m ->
                    val mayPush = SpectatorIo.taskCurrentOk(baseDir, cfg, sid) &&
                        !SpectatorIo.viewAlive(stateDir, sid, now())
                    shadowLog.append(m.toDecision(sid, now(), mayPush))
                }
            }
            delay(POLL.inWholeMilliseconds)
        }
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
        var started = 0
        // one filter per candidate marker, in the hook's order
        @Suppress("LoopWithTooManyJumpStatements")
        for ((safe, file) in SpectatorIo.markers(stateDir)) {
            if (!file.exists() || turns.containsKey(safe)) continue
            val sid = index[safe] ?: continue
            if (sid in workers) continue // its own worker streams the turn
            val turn = SpectatorTurn(baseDir, stateDir, shadowLog, now)
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
