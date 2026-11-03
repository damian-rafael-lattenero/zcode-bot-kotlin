package dev.lattenero.bot.channel

/**
 * The ⏹️ tap's PURE half (FP §7): a subagent-stop tail (`sst:<16-char
 * tail>`) resolved against the facts the caller already holds — the live
 * dispatch lanes FIRST (the exact `sa:` attach order), then the store's
 * running rows. The Stop itself (the awaited `ChannelQuery.Stop`) is the
 * edge's business ([BotCommands.subagentStop]); this núcleo stays
 * testable with no channel, no store, no clock beyond the given nowMs.
 */
sealed interface SubagentStopTarget {
    /** The stop can fly: the child's FULL session id + the name the ack quotes. */
    data class Ready(val sid: String, val name: String) : SubagentStopTarget

    /**
     * An ALIVE lane the fork has not named yet — no launch ACK rode the
     * stream, so the child's session id is unknown. The honest refuso,
     * never a stop against a guessed session.
     */
    data class Unnamed(val name: String) : SubagentStopTarget

    /** Nothing alive answers the tail — a stop here would be decoration. */
    data object Gone : SubagentStopTarget
}

/**
 * The ORDER's pulse (the ✍️ gate, 2/11): the owner typed AT a subagent —
 * the bot owes them the truth about whether anyone is there to read it.
 * Alive flies; Ended bounces with the death notice; Unknown refuses to
 * guess (a stale row is silence, not proof — the child may be grinding a
 * long mission past the freshness window).
 */
sealed interface OrderPulse {
    data object Alive : OrderPulse
    data object Ended : OrderPulse
    data object Unknown : OrderPulse
}

internal object SubagentStop {

    /**
     * The tail's one meaning: a lane claims it (last-wins, [BotCommands.laneAttach]'s
     * own rule), else a store row does. A CLOSED lane's tail stops nothing —
     * the child already ended; a store row only answers while FRESH (the
     * same predicate that rendered its ⏹️): a row gone ghost between render
     * and tap is the dead agent it says it is.
     */
    @Suppress("ReturnCount") // one guard per honest resolution shape
    fun resolve(
        lanes: List<ToolLive>,
        dbRunning: List<RunningSubagent>,
        tail: String,
        nowMs: Long,
    ): SubagentStopTarget {
        val lane = lanes.lastOrNull { it.toolId.endsWith(tail) }
        if (lane != null) {
            if (lane.status != null) return SubagentStopTarget.Gone
            val name = Keyboards.subagentName(lane)
            val agentId = lane.subagentAgentId ?: return SubagentStopTarget.Unnamed(name)
            return SubagentStopTarget.Ready("${BotCommands.SUBAGENT_SESSION_PREFIX}$agentId", name)
        }
        val row = dbRunning.firstOrNull { it.sid.endsWith(tail) } ?: return SubagentStopTarget.Gone
        if (!Keyboards.dbRunningFresh(row, nowMs)) return SubagentStopTarget.Gone
        return SubagentStopTarget.Ready(row.sid, row.title.ifBlank { row.sid.takeLast(Keyboards.LANE_TAIL) })
    }

    /**
     * The order target's pulse, resolved from the SAME facts as resolve
     * (the dispatch lanes, the store's fresh rows). The LAW (the 2/11
     * swallow, proven live): a CLOSED lane is the death certificate —
     * but an OPEN lane never vouches for the inbox (the sa: pin freezes
     * the parent's fold: a lane can read open forever), so only the
     * STORE's fresh row speaks life; a stale row is silence, not proof.
     * Task conversations (no subagent prefix) always pulse Alive — a
     * task session resumes; a finished CHILD's inbox is read by nobody.
     */
    fun orderTarget(
        lanes: List<ToolLive>,
        dbRunning: List<RunningSubagent>,
        sid: String,
        nowMs: Long,
    ): OrderPulse {
        if (!sid.startsWith(BotCommands.SUBAGENT_SESSION_PREFIX)) return OrderPulse.Alive
        val agentId = sid.removePrefix(BotCommands.SUBAGENT_SESSION_PREFIX)
        val laneDeath = lanes.lastOrNull { it.subagentAgentId == agentId }?.let {
            if (it.status != null) OrderPulse.Ended else null
        }
        val rowPulse = dbRunning.firstOrNull { it.sid == sid }?.let {
            if (Keyboards.dbRunningFresh(it, nowMs)) OrderPulse.Alive else OrderPulse.Unknown
        }
        return laneDeath ?: rowPulse ?: OrderPulse.Unknown
    }
}
