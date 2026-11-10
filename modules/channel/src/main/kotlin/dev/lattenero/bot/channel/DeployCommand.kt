package dev.lattenero.bot.channel

/**
 * B6 — /deploy's pure core, THE ONE-TAP LAW (the owner's order, 4/11:
 * «command deploy, elegir que deployar y esa eleccion ya empieza el
 * deploy… una única vez… no se puede volver a apretar esa botonera»).
 * The two-step machine DIED: no arm, no window, no confirm half — the tap
 * IS the confirmation. What stays:
 *
 * - the OWNER gate — the poller's fail-closed filter already gates the
 *   wire (no non-owner update ever reaches dispatch); this second lock
 *   costs nothing and refuses the tap even if a future path ever bypasses
 *   the poller. A config that cannot say who owns it fails CLOSED: nobody
 *   fires on it, never «the first tap that arrives».
 * - the ONE-SHOT consumption — a landed launch spends ITS OWN target
 *   ([spent(target)]); further taps of THAT target read [Verdict.InFlight]
 *   until the rearm window ([REARM_AFTER_MS]) passes. THE OWNER'S LIVE FIX,
 *   10/11: the old flag was global and died only with the process — the
 *   📱 WhatsApp deploy never restarts THIS daemon, so ONE tap locked every
 *   target for 30+ minutes. The law is «no double-fire THE SAME deploy»:
 *   the scope is the target, the clock bounds the flight (the fork arc is
 *   ~15-16 min), and the spawn edge's busy-guard stays the durable
 *   concurrency gate. A failed launch never spends anything: a retry after
 *   a failure is honest, a second deploy mid-flight is not.
 *
 * Single-threaded by contract: the poller dispatches serially, the mutable
 * slots need no lock.
 */
class DeployCommand(
    /** Injectable wall clock (tests bend time; production reads the wall). */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

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

        /**
         * ALL THREE — fork FIRST, then this daemon, then WhatsApp (each is
         * its own launchd job, and siblings survive each other's bootouts;
         * the order is the survival law, not a style choice). The owner's
         * rename, 10/11: «no debería decir "ambos", sino "todos"».
         */
        ALL,
    }

    /** A target's verdict — the flow renders each one, no verdict is silent. */
    sealed interface Verdict {
        /** The door fires for THIS target: the row dies, the announce flies, then the spawn. */
        data class Confirmed(val target: Target) : Verdict

        /** The tap is not the owner's: refused, nothing touched. */
        data object NotOwner : Verdict

        /** THIS target's deploy already flew — its one-shot is spent until the rearm. */
        data object InFlight : Verdict
    }

    /**
     * The one-shot's state per target: the landed-launch timestamp. A target
     * without a slot has never flown (or its window already rearmed).
     */
    private val spentAtMs = mutableMapOf<Target, Long>()

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
        inFlight(target) -> Verdict.InFlight
        else -> Verdict.Confirmed(target)
    }

    /** THIS target flew within the rearm window — its one-shot is still spent. */
    private fun inFlight(target: Target): Boolean =
        spentAtMs[target]?.let { nowMs() - it < REARM_AFTER_MS } == true

    /**
     * The flow's mark: THE TARGET's launch landed — its own slot is spent
     * until the rearm; every OTHER target answers on its own slot.
     */
    fun spent(target: Target) {
        spentAtMs[target] = nowMs()
    }

    companion object {
        /**
         * How long a landed target stays spent. The longest honest arc is the
         * fork's (~15-16 min build + swap + reopen) — 20 min covers it with
         * margin and never blocks a target that already finished.
         */
        const val REARM_AFTER_MS = 20 * 60_000L
    }
}
