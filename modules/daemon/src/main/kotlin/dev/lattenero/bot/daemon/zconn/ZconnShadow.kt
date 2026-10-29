package dev.lattenero.bot.daemon.zconn

import dev.lattenero.bot.contracts.ShadowDecision
import dev.lattenero.bot.contracts.StateFiles
import dev.lattenero.bot.contracts.ZconnState
import dev.lattenero.bot.daemon.clilogs.CliEvent
import dev.lattenero.bot.daemon.clilogs.CliLogTailer
import dev.lattenero.bot.daemon.clilogs.LogCursor
import dev.lattenero.bot.daemon.shadow.ShadowLog
import dev.lattenero.bot.daemon.shadow.TimeIso
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

const val WATCHER = "zconn"

/** One watcher pass, in values (the `repeat` cadence sees exactly this). */
data class CycleOutcome(val events: Int, val decisions: List<ShadowDecision>)

/** The pure pass: state + the cycle's events -> next state + the decisions. */
sealed interface PassResult {

    /** First run ever: arm at NOW — the past's outages are not today's story. */
    data class Armed(val state: ZconnState) : PassResult

    data class Quiet(val state: ZconnState, val events: Int) : PassResult

    data class Spoke(val state: ZconnState, val events: Int, val decisions: List<ShadowDecision>) : PassResult
}

/**
 * `since` may only advance over REAL event timestamps (`last + 0.5`), never
 * into the future: the first version crept +0.5 per eventless cycle until
 * it sat ahead of the clock and the monitor went permanently deaf (F85c).
 */
const val SINCE_ADVANCE_S = 0.5

fun statusMessage(context: JsonObject?): String =
    (context?.get("statusMessage") as? JsonPrimitive)?.content ?: ""

fun ZconnNotice.toDecision(decisionTs: Double): ShadowDecision = when (this) {
    is ZconnNotice.Down -> ShadowDecision(
        ts = decisionTs, tsIso = TimeIso.format(decisionTs), watcher = WATCHER,
        decision = "down", text = ZconnText.downText(reason, pill), eventTs = ts,
    )
    is ZconnNotice.Up -> ShadowDecision(
        ts = decisionTs, tsIso = TimeIso.format(decisionTs), watcher = WATCHER,
        decision = "up", text = ZconnText.upText(firstDown, ts), eventTs = ts,
    )
}

/**
 * Pure reduction — the heart of the port (Python `zconn.listen` minus its
 * IO): fold the cycle's events through the episode machine, advance `since`
 * only over real timestamps clamped to now, render every notice.
 */
fun zconnPass(state: ZconnState?, events: List<CliEvent>, now: Double): PassResult {
    if (state == null) return PassResult.Armed(ZconnState(since = now))
    val (track, notices) = events.fold(
        ConnTrack(state.down, state.firstDown, state.lastUp, state.reason) to emptyList<ZconnNotice>(),
    ) { (t, acc), e ->
        val (next, spoken) =
            ConnTrackMachine.feed(t, e.ts, e.event, statusMessage(e.context), ZconnText.pillVocab(e.context))
        next to acc + spoken
    }
    val since = if (events.isEmpty()) state.since else minOf(events.maxOf { it.ts } + SINCE_ADVANCE_S, now)
    val next = ZconnState(track.down, track.firstDown, track.lastUp, track.reason, since)
    val decisions = notices.map { it.toDecision(now) }
    return if (decisions.isEmpty()) {
        PassResult.Quiet(next, events.size)
    } else {
        PassResult.Spoke(next, events.size, decisions)
    }
}

/**
 * The IO shell around [zconnPass], one pass per cycle: tail the CLI log,
 * feed the machine, persist the state, append the shadow decisions. Every
 * environmental failure degrades to emptiness (Python's silent-by-design
 * passes); only a bug throws, and the surrounding KAP `Process` restarts
 * those under its convergence law.
 */
class ZconnShadow(
    baseDir: File,
    private val stateDir: File,
    private val shadowLog: ShadowLog,
    private val now: () -> Double,
) {
    private val logger = KotlinLogging.logger {}
    private val tailer = CliLogTailer(baseDir)
    private val jsonWriter = Json { encodeDefaults = true }
    private val stateFile = File(stateDir, STATE_FILE)

    // escape hatch (policy §7): the tail cursor is this process's memory —
    // the persisted `since` floor is the cross-restart protection, so a
    // lost cursor can re-read but never re-announce.
    private var cursor: LogCursor? = null

    fun pass(): CycleOutcome {
        val tNow = now()
        val state = loadState()
        val read = tailer.read(cursor, ConnTrackMachine.NEEDLES, state?.since ?: NO_FILTER_SINCE)
        cursor = read.cursor
        val result = zconnPass(state, read.events, tNow)
        persist(result)
        return CycleOutcome(read.events.size, (result as? PassResult.Spoke)?.decisions.orEmpty())
    }

    /**
     * State first: a decision may repeat once if the shadow write then
     * fails — Python's order, Python's worst case.
     */
    private fun persist(result: PassResult) {
        when (result) {
            is PassResult.Armed -> saveState(result.state)
            is PassResult.Quiet ->
                // no events: no write. The state file's mtime is a clock; a
                // per-cycle rewrite would muddy it (the hb invariant family).
                if (result.events > 0) saveState(result.state)

            is PassResult.Spoke -> {
                saveState(result.state)
                result.decisions.forEach { d ->
                    shadowLog.append(d)
                    logger.info { "zconn ${d.decision}: ${d.text}" }
                }
            }
        }
    }

    private fun loadState(): ZconnState? =
        runCatching { stateFile.takeIf { it.exists() }?.readText() }.getOrNull()
            ?.let(StateFiles::parseObject)
            ?.let(ZconnState::fromJson)

    private fun saveState(state: ZconnState) {
        try {
            stateDir.mkdirs()
            val bytes = jsonWriter.encodeToString(ZconnState.serializer(), state).toByteArray()
            atomicWrite(stateFile, bytes)
        } catch (e: IOException) {
            logger.warn(e) { "zconn state save failed — worst case a notice repeats once" }
        }
    }

    /** tmp + fsync + rename — the cross-process write invariant (state/store.py), single-owner file or not. */
    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync()
        }
        if (!tmp.renameTo(target)) throw IOException("atomic rename failed: $target")
    }

    private companion object {
        const val STATE_FILE = "zconn.json"
        const val TMP_SUFFIX = ".tmp"
        const val NO_FILTER_SINCE = 0.0
    }
}
