package dev.lattenero.bot.daemon.metrics

import dev.lattenero.bot.channel.ServerFrame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The turn tap (E2): the wiring hands it every stream frame the channel
 * delivers — the SAME real surfaces the bus producer reads (ADR-019's
 * lifecycle), this one only counts. The bus producer's own dedupe laws
 * port verbatim (each one tested):
 *
 * - a resubscribe burst re-emits `task_run_started` for the LIVE turn —
 *   the same fork turn or the same startedAt is the same turn, counted
 *   once;
 * - a burst replaying an ALREADY SEALED turn publishes nothing;
 * - a turn first WITNESSED at its own end (a subscription attached
 *   mid-flight) seals WITHOUT a duration — the counter counts it, the
 *   timer gets nothing (nothing invented).
 *
 * Built only when the facade measures; with the `metrics` knob off the
 * wiring never constructs it (the busTaps pattern — zero cost per frame).
 */
class TurnMetricsTap(
    private val metrics: Metrics,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * A turn's identity marks — what the bus's TurnState/SealMark carry,
     * metrics only: the start stamp and the fork's own turn id.
     */
    private data class TurnMark(val startedAtMs: Long, val forkTurn: String?)

    private val lock = Any()
    private val live = HashMap<String, TurnMark>()
    private val sealed = LinkedHashMap<String, TurnMark>()

    fun onFrame(frame: ServerFrame.StreamEvent) {
        when ((frame.event["type"] as? JsonPrimitive)?.content) {
            RUN_STARTED -> onRunStarted(frame)
            COMPLETE, ERROR -> onSealed(frame)
            else -> Unit // the rest of the union is not turn material
        }
    }

    private fun onRunStarted(frame: ServerFrame.StreamEvent) {
        val eventStart = long(frame.event, "startedAt")?.takeIf { it > 0 }
        val forkTurn = str(frame.event, "turnId")
        synchronized(lock) {
            val existing = live[frame.taskId]
            if (existing != null) {
                // a resubscribe burst re-emits the live turn's frames — the
                // same fork turn or the same startedAt is the same turn
                if (sameTurn(existing, eventStart, forkTurn)) return
            } else {
                // a burst replaying an ALREADY SEALED turn counts nothing
                val mark = sealed[frame.taskId]
                if (mark != null && sameTurn(mark, eventStart, forkTurn)) return
            }
            live[frame.taskId] = TurnMark(eventStart ?: frame.at.takeIf { it > 0 } ?: clockMs(), forkTurn)
            metrics.turnStarted()
        }
    }

    private fun onSealed(frame: ServerFrame.StreamEvent) {
        val outcome = if ((frame.event["type"] as? JsonPrimitive)?.content == COMPLETE) {
            TurnOutcome.COMPLETED
        } else {
            TurnOutcome.FAILED
        }
        val eventStart = long(frame.event, "startedAt")?.takeIf { it > 0 }
        val forkTurn = str(frame.event, "turnId")
        val startedAt: Long? = synchronized(lock) {
            val state = live.remove(frame.taskId)
            // a terminal frame with NO live turn on an already-sealed task is
            // a burst replay — silence (a second seal would lie twice)
            if (state == null && sealed.containsKey(frame.taskId)) return
            sealed[frame.taskId] = TurnMark(
                startedAtMs = state?.startedAtMs ?: eventStart ?: frame.at.takeIf { it > 0 } ?: clockMs(),
                forkTurn = forkTurn,
            )
            if (sealed.size > SEAL_CAP) sealed.remove(sealed.keys.first())
            state?.startedAtMs // null when the turn was witnessed only at its end
        }
        val duration = startedAt?.let { start ->
            val sealAt = frame.at.takeIf { it > 0 } ?: clockMs()
            (sealAt - start).coerceAtLeast(0)
        }
        metrics.turnSealed(outcome, duration)
    }

    /** The bus's own turn-identity rule: the fork turn when present, else the start stamp. */
    private fun sameTurn(mark: TurnMark, eventStart: Long?, forkTurn: String?): Boolean =
        (forkTurn != null && forkTurn == mark.forkTurn) ||
            (eventStart != null && eventStart == mark.startedAtMs)

    private fun str(event: JsonObject, key: String): String? = (event[key] as? JsonPrimitive)?.content

    private fun long(event: JsonObject, key: String): Long? =
        (event[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()

    private companion object {
        const val RUN_STARTED = "task_run_started"
        const val COMPLETE = "task_complete"
        const val ERROR = "task_error"

        /** The sealed-turn memory — pruned FIFO, never unbounded (the bus's own cap). */
        const val SEAL_CAP = 512
    }
}
