package dev.lattenero.bot.channel

/**
 * B6 — /deploy's pure core, THE ONE-TAP LAW (the owner's order, 4/11:
 * «command deploy, elegir que deployar y esa eleccion ya empieza el
 * deploy… una única vez… no se puede volver a apretar esa botonera»).
 * The two-step machine DIED: no arm, no window, no confirm half, no
 * expiry — the tap IS the confirmation. What stays:
 *
 * - the OWNER gate — the poller's fail-closed filter already gates the
 *   wire (no non-owner update ever reaches dispatch); this second lock
 *   costs nothing and refuses the tap even if a future path ever bypasses
 *   the poller. A config that cannot say who owns it fails CLOSED: nobody
 *   fires on it, never «the first tap that arrives».
 * - the ONE-SHOT consumption — the first launch that lands spends the
 *   machine ([spent]); every further target reads [Verdict.InFlight]
 *   until process death (honest, best-effort — the spawn edge's own
 *   busy-guard is the durable one). A failed launch leaves the shot free:
 *   a retry after a failure is honest, a second deploy mid-flight is not.
 *
 * Single-threaded by contract: the poller dispatches serially, the
 * mutable shot needs no lock.
 */
class DeployCommand {

    /** What the tap deploys — chosen by the tap, fired by THE DOOR. */
    enum class Target {
        /** The Kotlin bot's own daemon: build with tests → install → LaunchAgent restart. */
        BOT,

        /** The ZCode fork: quit → build-install → reopen → socket/canal verification. */
        FORK,

        /**
         * The whatsdigest daemon (the family's WhatsApp-groups bot): build
         * with tests → install → its own LaunchAgent restarts. A sibling
         * ecosystem deploy — it never touches THIS daemon, so the
         * anti-suicide door is uniform but never actually at stake here.
         */
        WHATSAPP,

        /** Both, fork FIRST (a sibling job survives the daemon deploy's own bootout). */
        BOTH,
    }

    /** A target's verdict — the flow renders each one, no verdict is silent. */
    sealed interface Verdict {
        /** The door fires for THIS target: the row dies, the announce flies, then the spawn. */
        data class Confirmed(val target: Target) : Verdict

        /** The tap is not the owner's: refused, nothing touched. */
        data object NotOwner : Verdict

        /** A deploy already flew — the one-shot is spent until process death. */
        data object InFlight : Verdict
    }

    /** The one-shot's state: true from the first landed launch, forever after. */
    private var flying = false

    /** The owner gate — a null owner fails closed (the fail-closed law). */
    fun authorize(fromId: Long?, ownerUserId: Long?): Boolean =
        ownerUserId != null && fromId == ownerUserId

    /**
     * A target from the owner either FIRES or is refused. Confirming does
     * NOT itself spend the shot — the flow marks it ([spent]) only after
     * the spawn succeeded, so a failed announce or spawn can be retried.
     */
    fun confirm(target: Target, fromId: Long?, ownerUserId: Long?): Verdict = when {
        !authorize(fromId, ownerUserId) -> Verdict.NotOwner
        flying -> Verdict.InFlight
        else -> Verdict.Confirmed(target)
    }

    /** The flow's mark: the launch landed — every further target is [Verdict.InFlight]. */
    fun spent() {
        flying = true
    }
}
