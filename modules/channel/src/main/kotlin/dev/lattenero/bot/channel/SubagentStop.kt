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
}
