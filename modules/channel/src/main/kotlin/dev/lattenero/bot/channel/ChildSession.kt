package dev.lattenero.bot.channel

/**
 * THE CHILD SESSION'S ADDRESS (the fork's own convention) — its own file
 * so the wire contract's function budget stays untouched. The store's and
 * the fork's shared convention (`sess_subagent_<agentId>`,
 * zcode-task-types-core's `subagentAgentId` doc): the dispatch lane's
 * launch ACK names the child's agentId; THIS prefix turns it into the
 * subscribable session topic whose stream carries the child's real turn.
 * Single source — BotCommands, the hub's routing and the wiring's
 * auto-subscribe all speak through these helpers.
 */

/** The child session topic's prefix. */
const val SUBAGENT_SESSION_PREFIX = "sess_subagent_"

/** The child session's topic for an agentId (the launch ACK's additive field). */
fun subagentSessionId(agentId: String): String = "$SUBAGENT_SESSION_PREFIX$agentId"

/** True when the taskId names a child session's topic (not a main session). */
fun isSubagentSession(taskId: String): Boolean = taskId.startsWith(SUBAGENT_SESSION_PREFIX)

/** The agentId inside a child-session topic; null for any other taskId. */
fun subagentAgentIdOf(taskId: String): String? =
    taskId.takeIf { isSubagentSession(it) }?.removePrefix(SUBAGENT_SESSION_PREFIX)
