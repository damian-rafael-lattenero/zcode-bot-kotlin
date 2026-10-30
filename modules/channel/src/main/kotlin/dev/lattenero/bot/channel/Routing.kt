package dev.lattenero.bot.channel

/**
 * The free-text router's DECISION CORE — pure facts in, one plan out.
 *
 * The owner's doctrine (29/10, the cutover's first hot-fix): «probando
 * probando» opened a FRESH conversation instead of landing in the one
 * open in the app. The channel's inbound has NO target field — it rides
 * `BotState.activeTaskId`, and a bound-but-DELETED task tombstones into
 * a draft, which is exactly how a "new conversation" is born (measured:
 * the daemon-local bot's context pointed at `session_0f84de0c…`, deleted
 * an hour later, while the app's open conversation sat idle in front of
 * the owner — `activeTaskIds` never lists idle tasks, so the old
 * resolver could not even see it).
 *
 * The plan answers ONE question per line of [RoutePlan] — fire (where,
 * with or without a bind first), park on the busy turn (Python's F17),
 * refuse honestly, or tell the owner the re-aim is blocked by a running
 * task. Every input is gathered by [BotCommands] (queries + the
 * tasks-index direct read) and every outcome is LOGGED there — the
 * owner's standing rule: never radio silence, every routing decision
 * visible in daemon.jsonl.
 */
internal data class RouteFacts(
    /** The channel answered botStatus (false = degraded/down). */
    val contextReachable: Boolean,
    /** The bot has a context at all (false = gate closed / none yet). */
    val contextPresent: Boolean,
    val workspacePath: String?,
    /** The bot's bound task — where naked inbound would land today. */
    val activeTaskId: String?,
    /** The tasks-index says the bound task is alive (not deleted/archived). */
    val activeAlive: Boolean,
    /** sessions-index truth: the tasks with a live turn right now. */
    val running: Set<String>,
    /** The app's open conversation: freshest alive task of the bot's workspace. */
    val openSid: String?,
    val openTitle: String?,
) {
    companion object {
        val DEGRADED = RouteFacts(
            contextReachable = false,
            contextPresent = false,
            workspacePath = null,
            activeTaskId = null,
            activeAlive = false,
            running = emptySet(),
            openSid = null,
            openTitle = null,
        )
    }
}

/** What the router decided — exhaustive, one branch per behavior. */
internal sealed interface RoutePlan {
    /** Send the text to [sid]; [bind] first when the context aims elsewhere. */
    data class Fire(val sid: String, val bind: Boolean, val reason: String) : RoutePlan

    /** The target's turn is running: park the text (F17) — it fires on task_complete. */
    data class Enqueue(val sid: String, val reason: String) : RoutePlan

    /**
     * The re-aim is REFUSED because the previously-bound task runs
     * (botsService taskRunning): tell the owner (Python's
     * only_active_task fallback) and park the text on the running task.
     */
    data class BusyRebind(val active: String, val wantedSid: String, val wantedTitle: String?) : RoutePlan

    /** Nothing to talk to — the honest refusal, [reason] is the whole reply. */
    data class Refuse(val reason: String) : RoutePlan
}

/**
 * The decision table. Inputs are FROZEN facts (the caller gathers them
 * atomically enough for the decision; the bind's confirmation is a
 * separate effect). `newIntent` = the NEXT text CREATES a conversation
 * (Python's /task new) — no bind, straight into the draft.
 */
internal fun planRoute(facts: RouteFacts, newIntent: Boolean): RoutePlan = when {
    !facts.contextReachable -> RoutePlan.Refuse(RouteTexts.CHANNEL_DOWN)

    !facts.contextPresent -> RoutePlan.Refuse(RouteTexts.NO_CONTEXT)

    newIntent -> RoutePlan.Fire(
        sid = facts.activeTaskId ?: DRAFT_TARGET,
        bind = false,
        reason = "new-intent (/tasks new armed the draft)",
    )

    facts.activeTaskId != null && !facts.activeAlive -> tombstoneRoute(facts)

    facts.activeTaskId != null -> activeRoute(facts)

    else -> freshBindRoute(facts)
}

/** The bound task is deleted/archived: naked inbound would CREATE — re-aim or refuse. */
@Suppress("ReturnCount") // the ladder: running wins, then the open row, then the honest refusal
private fun tombstoneRoute(facts: RouteFacts): RoutePlan {
    val active = facts.activeTaskId!!
    // running AND deleted in the index: the index lags (a mid-turn task is
    // never deleted) — trust the sessions-index, park the text
    if (active in facts.running) return RoutePlan.Enqueue(active, "active deleted in index but running (index lag)")
    val open = facts.openSid ?: return RoutePlan.Refuse(RouteTexts.BOUND_GONE_NO_OPEN)
    return RoutePlan.Fire(open, bind = true, reason = "tombstone: bound $active is deleted — rebind to open")
}

/** A live bound task: the open conversation decides stay-vs-re-aim. */
private fun activeRoute(facts: RouteFacts): RoutePlan {
    val active = facts.activeTaskId!!
    val open = facts.openSid
    return when {
        open == null || open == active ->
            if (active in facts.running) {
                RoutePlan.Enqueue(active, "open conversation is mid-turn (F17)")
            } else {
                RoutePlan.Fire(active, bind = false, reason = "already aimed at the open conversation")
            }

        active in facts.running -> RoutePlan.BusyRebind(
            active = active,
            wantedSid = open,
            wantedTitle = facts.openTitle,
        )

        else -> RoutePlan.Fire(
            open,
            bind = true,
            reason = "open conversation moved to «${facts.openTitle ?: open}» — rebind",
        )
    }
}

/** No bound task (draft mode): the first text must bind or it CREATES by accident. */
private fun freshBindRoute(facts: RouteFacts): RoutePlan {
    val open = facts.openSid ?: return RoutePlan.Refuse(RouteTexts.NO_ACTIVE_CONVERSATION)
    return if (open in facts.running) {
        RoutePlan.Fire(open, bind = true, reason = "draft context — bind to the running open conversation")
    } else {
        RoutePlan.Fire(open, bind = true, reason = "draft context — bind to the open conversation before the text")
    }
}

/** The pseudo-target for /tasks new's armed text: the draft itself (no bind). */
internal const val DRAFT_TARGET = "(draft)"

/** The router's owner-facing texts — the owner's Spanish, one line each. */
internal object RouteTexts {
    const val CHANNEL_DOWN =
        "📡 The channel to the app is down (the fork's host is not running?) — " +
            "text did NOT reach a conversation. Try again after the app restarts."

    const val NO_CONTEXT =
        "📭 No active conversation context — send text first (it opens/uses the app's real conversation)."

    /** Python's handle_free_text refusal — free text NEVER opens a conversation by itself. */
    const val NO_ACTIVE_CONVERSATION =
        "📭 There is no active conversation yet — send /tasks and pick one with /tasks n.\n" +
            "After that, all text you send runs there (live mode)."

    /** The tombstone refusal: the bound conversation is gone and nothing is open to re-aim at. */
    const val BOUND_GONE_NO_OPEN =
        "🪦 The bot's conversation was deleted and no conversation is open in the app — " +
            "/tasks n picks one (or /tasks new opens a fresh one)."

    /**
     * The re-aim refusal (the owner's words, 29/10): the old task's turn
     * still runs and the botsService REFUSES the switch (taskRunning).
     */
    const val BUSY_REBIND =
        "🔄 task corriendo, no puedo re-apuntar — /stop primero o esperá"

    /** The bind did not land (race or vanished conversation). */
    const val BIND_FAILED =
        "⚠️ The switch did not land (a turn may have started mid-switch) — /tasks n again, or /stop first."
}
