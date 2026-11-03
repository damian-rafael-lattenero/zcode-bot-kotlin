package dev.lattenero.bot.channel

/**
 * B6 — /deploy's pure core: the two-step confirmation state machine. The
 * 'go' can only be produced by the button's data (Python's
 * handlers_system._cmd_deploy law, kept byte-exact) with two superiorities
 * the retired bot never had:
 *
 * - the confirmation EXPIRES — Python's `_DEPLOY_TIMEOUT_S = 600` was the
 *   subprocess ceiling; here it is the window a 'go' stays trustworthy.
 *   A confirmation that lives forever fires stale restarts hours later.
 * - the OWNER's identity is part of the decision — the poller's
 *   fail-closed filter already gates the wire (no non-owner update ever
 *   reaches dispatch); this second lock costs nothing and refuses the
 *   tap even if a future path ever bypasses the poller.
 *
 * The arm is ALWAYS consumed by its verdict (confirmed, expired, or the
 * owner's 'no'): a confirmation that survives its shot would fire the
 * NEXT stray tap — /retry's measured lesson, applied to the heaviest
 * button this bot owns. Single-threaded by contract: the poller
 * dispatches serially, the mutable arm needs no lock.
 */
class DeployCommand(private val clock: () -> Long = System::currentTimeMillis) {

    /** The moment the card went out (the injected clock's own reading); null = nothing armed. */
    private var armedAtMs: Long? = null

    /** One confirm's honest verdict — the wiring renders each one, no verdict is silent. */
    sealed interface Verdict {
        /** The go lands: the caller announces FIRST, then spawns (the door's order). */
        data object Confirmed : Verdict

        /** 🙅 — the owner said no; nothing was touched. */
        data object Cancelled : Verdict

        /** The window closed before the tap; the arm is spent, /deploy re-arms. */
        data object Expired : Verdict

        /** Nothing armed — a stray tap, a double-tap after the go, or a spent 'no'. */
        data object NotArmed : Verdict

        /** The tap is not the owner's: refused, the OWNER's arm left untouched. */
        data object NotOwner : Verdict
    }

    /** Bare /deploy (or any rest that is not go/no): arms a FRESH window. */
    fun arm() {
        armedAtMs = clock()
    }

    /**
     * The confirm half: [action] is the owner's word (go/no), [fromId] the
     * Telegram sender, [ownerUserId] the config's owner — null fails
     * closed (nobody confirms on a config that cannot say who owns it).
     */
    fun confirm(action: String, fromId: Long?, ownerUserId: Long?): Verdict {
        if (ownerUserId == null || fromId != ownerUserId) return Verdict.NotOwner
        val armedAt = armedAtMs
        val verdict = when {
            action != ACTION_GO && action != ACTION_NO -> Verdict.NotArmed // a wrong word never spends the window
            armedAt == null -> Verdict.NotArmed
            clock() - armedAt >= WINDOW_MS -> Verdict.Expired
            action == ACTION_GO -> Verdict.Confirmed
            else -> Verdict.Cancelled
        }
        // the arm is ALWAYS consumed by a decisive verdict (confirmed,
        // cancelled, expired): a spent confirmation never fires twice
        if (verdict != Verdict.NotArmed) armedAtMs = null
        return verdict
    }

    companion object {
        /** Python's _DEPLOY_TIMEOUT_S (600 s): the subprocess ceiling, now the window's own. */
        const val WINDOW_MS = 600_000L

        const val ACTION_GO = "go"
        const val ACTION_NO = "no"
    }
}
