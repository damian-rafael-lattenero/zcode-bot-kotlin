package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.DeployCommand
import dev.lattenero.bot.channel.DeployCommand.Target
import dev.lattenero.bot.channel.DeployCommand.Verdict
import dev.lattenero.bot.channel.Keyboards
import dev.lattenero.bot.channel.Tables
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

private val logger = KotlinLogging.logger {}

/**
 * B6 — /deploy's wiring half: [DeployCommand]'s verdicts rendered into the
 * commands lane's own throat, with THE ANTI-SUICIDE DOOR in its hands.
 * The script it launches boots out THIS daemon mid-run, so on a confirmed
 * go the announce flies FIRST and must LAND (the send leg's non-null
 * message id is the receipt); only then does the detached spawn fire. A
 * deploy the phone never heard about is a deploy we do not launch — the
 * daemon's death would otherwise be the phone's silence.
 *
 * The flow speaks through the injected [say] for EVERY verdict — no
 * branch is silent (the honest-indicators law): the card, the in-flight
 * refusal, the refusals, the loud failures.
 *
 * [say]'s contract is the send leg's: never throws, answers the born
 * message's id (null = shadow/dry-run/parked/failed — nothing flew).
 * [edit]'s contract is the transport's editMessageText leg (nullable =
 * unwired): best-effort by law, a failure never blocks the door.
 * [spawn]'s contract is the edge's: fork-and-forget for the target's OWN
 * sibling launchd job(s), throws only when a child could not be born
 * (missing script, unwritable log) — a throw means THIS daemon stays up
 * and the phone hears it LOUD.
 *
 * 2026-11-04 — THE ONE-TAP LAW (the owner: «choose what to deploy and that
 * choice already starts the deploy… a single time… that button row cannot
 * be pressed again» [translated]): the bare /deploy offers 🤖 bot / 🍴 fork /
 * 📱 whatsapp / 🚀 all and REMEMBERS the card's born message id; the tap
 * (or the typed word) IS the confirmation — the owner gate passes and the
 * door fires immediately, and the card's BUTTON ROW DIES (the edit strips
 * the keyboard, marking the chosen row «▶️ in flight»). The confirm cards,
 * the go/no row, the expiry and the arm are all retired. After a launch
 * that lands, THAT target is refused («a deploy is already in flight») until
 * the rearm window — per-target and time-bounded (the owner's 10/11 fix:
 * the old global process-lifetime flag let ONE WhatsApp deploy lock every
 * target for 30+ minutes) — and the spawn edge's busy-guard is the durable
 * one. The fork deploy does NOT touch this daemon; ALL is fork FIRST,
 * because a sibling job survives the bootout (the spawn order is the
 * survival law, not a style choice).
 */
class DeployFlow(
    private val machine: DeployCommand,
    private val say: suspend (String, Boolean, JsonObject?) -> Long?,
    /**
     * THE RICH PAIR (24/11): the targets card's native table body plus the
     * honest plain twin, one ladder (rich → plain → park). Null = unwired
     * (shadow/dry-run): [say] speaks the plain card alone. When wired, the
     * card's birth is the pair's business — [say] is NOT retried behind a
     * parked plain leg, so a window can never double-post the card.
     */
    private val sayPair: (suspend (rich: String, plain: String, loud: Boolean, markup: JsonObject?) -> Long?)? = null,
    /**
     * The keyboard-strip edit: (messageId, text, markup) — the targets
     * card's row dies in place. Null = unwired (shadow/dry-run): the row
     * stays as decoration and the in-flight refusal answers its taps.
     */
    private val edit: (suspend (Long, String, JsonObject?) -> Boolean)? = null,
    private val spawn: suspend (Target) -> Unit,
    /** The config's owner, read PER USE (the config cadence); null = nobody fires. */
    private val ownerUserId: () -> Long?,
) {

    /** The targets card's born message id — the tap's edit target (the row dies). */
    private var targetsCardId: Long? = null

    /**
     * The message path: bare = the targets card, a TARGET WORD = the door
     * immediately, ANYTHING ELSE (the retired go/no included) = the card.
     */
    suspend fun onText(rest: String, fromId: Long?) {
        val word = rest.trim().lowercase()
        if (word.isEmpty()) {
            showTargetsCard()
            return
        }
        handle(word, fromId)
    }

    /** The tap path — the callback_data without its `dep:` prefix. */
    suspend fun onTap(action: String, fromId: Long?) = handle(action, fromId)

    private suspend fun handle(action: String, fromId: Long?) {
        val target = targetOf(action)
        if (target == null) {
            // an unknown word never spawns: the card again (the retired
            // go/no reads exactly like any other stale button)
            showTargetsCard()
            return
        }
        when (val verdict = machine.confirm(target, fromId, ownerUserId())) {
            is Verdict.Confirmed -> launch(verdict.target)
            Verdict.InFlight -> say(IN_FLIGHT, false, null)
            Verdict.NotOwner -> say(NOT_OWNER, true, null)
        }
    }

    private suspend fun showTargetsCard() {
        val markup = Keyboards.deployTargets()
        val pair = sayPair
        val born = if (pair != null) {
            pair(TARGETS_CARD_RICH, TARGETS_CARD, false, markup)
        } else {
            say(TARGETS_CARD, false, markup)
        }
        if (born != null) targetsCardId = born
    }

    /**
     * THE DOOR: the row dies → announce → receipt check → spawn. The edit
     * goes first (it is not the door's receipt — the row must be dead
     * before anything can kill this process), the announce is the door's
     * law: no receipt, no spawn.
     */
    @Suppress("TooGenericExceptionCaught") // the spawn edge: any failure speaks LOUD, never crashes the poller
    private suspend fun launch(target: Target) {
        // THE BUTTON ROW DIES: the card is edited in place — keyboard
        // stripped, the chosen row marked «▶️ in flight». Best-effort: a
        // failed edit leaves the row as decoration and the in-flight
        // refusal answers its taps (the spawn edge's busy-guard is the
        // durable backstop).
        targetsCardId?.let { cardId ->
            val died = edit?.invoke(cardId, flownCard(target), null) ?: false
            if (!died) {
                logger.warn {
                    "deploy($target): the card's row-die edit failed (message_id=$cardId) — " +
                        "the in-flight refusal + the spawn busy-guard are the backstops"
                }
            }
        }
        val receipt = say(announceOf(target), true, null)
        if (receipt == null) {
            logger.warn { "deploy($target): the announce did NOT land — spawn REFUSED (no blind restarts)" }
            say(ANNOUNCE_FAILED, true, null)
            return
        }
        try {
            // ALL splits HERE, in the door: fork FIRST — its job must be armed
            // before the daemon's deploy boots out this daemon; brain next for
            // the same reason (its restart must be armed before this daemon's
            // own bootout). The order is the survival law, so it lives where
            // the tests can pin it.
            when (target) {
                Target.ALL -> {
                    spawn(Target.FORK)
                    spawn(Target.BRAIN)
                    spawn(Target.BOT)
                    spawn(Target.WHATSAPP)
                }

                else -> spawn(target)
            }
            // THE ONE-SHOT: the launch landed — THIS target is refused until
            // the rearm window (per-target, time-bounded; honest, best-effort:
            // the script-side guard is the durable one). A failed spawn never
            // lands here: the shot stays free for the honest retry.
            machine.spent(target)
            logger.warn {
                "deploy($target): script(s) spawned detached (announce message_id=$receipt) — " +
                    "this daemon may die mid-build"
            }
            say(spawnedNote(target), false, null)
        } catch (e: CancellationException) {
            throw e // shutdown must stay honest: cancellation never announces SPAWN_FAILED
        } catch (e: Exception) {
            logger.error(e) { "deploy($target): the spawn FAILED — this daemon stays up, the shot stays free" }
            say(SPAWN_FAILED + (e.message ?: e::class.simpleName.orEmpty()), true, null)
        }
    }

    /** The card's flown shape — the header kept, the keyboard gone, the choice marked. */
    private fun flownCard(target: Target): String = "🚀 <b>Deploy</b>\n" + when (target) {
        Target.BOT -> FLOWN_BOT
        Target.FORK -> FLOWN_FORK
        Target.WHATSAPP -> FLOWN_WHATSAPP
        Target.BRAIN -> FLOWN_BRAIN
        Target.ALL -> FLOWN_ALL
    }

    private fun announceOf(target: Target): String = when (target) {
        Target.BOT -> THE_ANNOUNCE
        Target.FORK -> THE_ANNOUNCE_FORK
        Target.WHATSAPP -> THE_ANNOUNCE_WHATSAPP
        Target.BRAIN -> THE_ANNOUNCE_BRAIN
        Target.ALL -> THE_ANNOUNCE_ALL
    }

    private fun spawnedNote(target: Target): String = when (target) {
        Target.BOT -> SPAWNED
        Target.FORK -> SPAWNED_FORK
        Target.WHATSAPP -> SPAWNED_WHATSAPP
        Target.BRAIN -> SPAWNED_BRAIN
        Target.ALL -> SPAWNED_ALL
    }

    companion object {
        /** The target words — taps arrive as `dep:<word>`, texts as the bare rest. */
        const val TARGET_WORD_BOT = "bot"
        const val TARGET_WORD_FORK = "fork"
        const val TARGET_WORD_WHATSAPP = "whatsapp"
        const val TARGET_WORD_BRAIN = "brain"
        const val TARGET_WORD_ALL = "all"

        /** The pre-rename word (the «Both» era) — stale cards on the phone still carry it. */
        const val TARGET_WORD_BOTH_ALIAS = "both"

        fun targetOf(action: String): Target? = when (action) {
            TARGET_WORD_BOT -> Target.BOT
            TARGET_WORD_FORK -> Target.FORK
            TARGET_WORD_WHATSAPP -> Target.WHATSAPP
            TARGET_WORD_BRAIN -> Target.BRAIN
            TARGET_WORD_ALL -> Target.ALL
            // THE STALE-CARD ALIAS (the owner's rename, 10/11): cards still
            // living on the phone carry dep:both — they must keep answering.
            TARGET_WORD_BOTH_ALIAS -> Target.ALL
            else -> null
        }

        /**
         * The ONE-TAP card — WHAT can be deployed, one honest line per
         * pipeline: the tap IS the confirmation, so these lines carry the
         * old confirm cards' content collapsed (the real cost of each).
         */
        const val TARGETS_CARD = (
            "🚀 <b>Deploy</b>\nWhat do I deploy? Tapping IS launching — a single time per target, " +
                "the row dies on the tap:\n" +
                "🤖 <b>Bot Kotlin</b> — build (tests included) → install → the LaunchAgent restarts the daemon\n" +
                "🍴 <b>ZCode Fork</b> — build NOW (~15 min, phase notices, the app stays alive) → " +
                "closes ZCode on its own → swap → reopens (a few seconds of 📡 channel at the end)\n" +
                "📱 <b>WhatsApp (whatsdigest)</b> — build with tests → install → the groups bot's " +
                "LaunchAgent restarts and verifies itself (never touches THIS daemon)\n" +
                "🧠 <b>Brain (agent-brain)</b> — build (installDist + detekt) → install → the brain's " +
                "LaunchAgent restarts (brief /brain and memory outage; the plugin reconnects on its own)\n" +
                "🚀 <b>All</b> — fork FIRST, then brain, the daemon and the WhatsApp bot (sibling launchd jobs)"
            )

        /**
         * THE SAME CARD AS A NATIVE TABLE (24/11): target → what the tap
         * runs. The plain [TARGETS_CARD] stays the fallback byte for byte.
         */
        val TARGETS_CARD_RICH: String = (
            "🚀 <b>Deploy</b>\nWhat do I deploy? Tapping IS launching — a single time per target, " +
                "the row dies on the tap:\n\n" +
                Tables.html(
                    header = listOf("Target", "What it does"),
                    rows = listOf(
                        listOf(
                            "🤖 Bot Kotlin",
                            "build (tests included) → install → the LaunchAgent restarts the daemon",
                        ),
                        listOf(
                            "🍴 ZCode Fork",
                            "build NOW (~15 min, phase notices, the app stays alive) → closes ZCode on its own → " +
                                "swap → reopens (a few seconds of 📡 channel at the end)",
                        ),
                        listOf(
                            "📱 WhatsApp (whatsdigest)",
                            "build with tests → install → the groups bot's LaunchAgent restarts and " +
                                "verifies itself (never touches THIS daemon)",
                        ),
                        listOf(
                            "🧠 Brain (agent-brain)",
                            "build (installDist + detekt) → install → the brain's LaunchAgent restarts " +
                                "(brief /brain and memory outage; the plugin reconnects on its own)",
                        ),
                        listOf(
                            "🚀 All",
                            "fork FIRST, then brain, the daemon and the WhatsApp bot " +
                                "(sibling launchd jobs)",
                        ),
                    ),
                )
            )

        /** The card's flown rows — the edit's text: keyboard stripped, the choice marked. */
        const val FLOWN_BOT = "🤖 Bot Kotlin — ▶️ in flight"
        const val FLOWN_FORK = "🍴 ZCode Fork — ▶️ in flight"
        const val FLOWN_WHATSAPP = "📱 WhatsApp (whatsdigest) — ▶️ in flight"
        const val FLOWN_BRAIN = "🧠 Brain (agent-brain) — ▶️ in flight"
        const val FLOWN_ALL = "🚀 All — ▶️ in flight (fork first)"

        /** The in-flight refusal — THIS target flew; its own slot rearms itself. */
        const val IN_FLIGHT = (
            "🚀 A deploy of THAT target is already in flight — I will not launch another of the same. " +
                "If the job is still running, the script itself refuses; wait for it to end " +
                "(the ~20 min rearm the slot)."
            )

        /** The door's exact sentences — the phone hears them BEFORE any script can kill anything. */
        const val THE_ANNOUNCE = "🚀 Deploying the daemon — back in a moment"

        const val THE_ANNOUNCE_WHATSAPP = (
            "📱 Deploying the WhatsApp bot (whatsdigest) — build with tests, " +
                "install and its own verification; THIS daemon is not touched, I stay alive"
            )

        const val SPAWNED_WHATSAPP = (
            "🛠 whatsdigest script launched detached — build with tests, install " +
                "and the groups bot's LaunchAgent re-verifies itself. " +
                "Log: <code>~/.zcode/bot-kotlin/logs/deploy-whatsapp.log</code> — /file brings it."
            )

        const val THE_ANNOUNCE_FORK = (
            "🍴 Fork deploy armed — I build the fork (~15 min, I notify you by phases, " +
                "the app stays alive); at the end it CLOSES ZCode on its own, swaps and reopens it"
            )

        const val THE_ANNOUNCE_BRAIN = (
            "🧠 Deploying the brain (agent-brain) — build with detekt, install and the LaunchAgent " +
                "restarts with /ready verification; THIS daemon is not touched, I stay alive " +
                "(brief /brain and memory outage)"
            )

        const val SPAWNED_BRAIN = (
            "🛠 Brain script launched detached — installDist + detekt, agent-brain's LaunchAgent " +
                "restarts and verifies /ready on its own (the brain-mcp plugin reconnects on its own). " +
                "Log: <code>~/.zcode/bot-kotlin/logs/deploy-brain.log</code> — /file brings it."
            )

        const val THE_ANNOUNCE_ALL = (
            "🚀 Deploying ALL — first the fork, then brain, the daemon and the WhatsApp bot; back in a moment"
            )

        const val SPAWNED = (
            "🛠 Script launched detached — the build takes minutes and the " +
                "NEW daemon greets at boot (the script verifies heartbeat + armed + log tail). " +
                "Log: <code>~/.zcode/bot-kotlin/logs/deploy-daemon.log</code> — /file brings it."
            )

        const val SPAWNED_FORK = (
            "🛠 Fork script launched detached (the SAME one as <code>zcode-deploy</code>) — " +
                "the build takes ~15 min and talks to you by phases with the app alive; at the end " +
                "it CLOSES ZCode on its own, swaps and reopens it (Telegram notices). " +
                "Log: <code>~/.zcode/bot-kotlin/logs/deploy-fork.log</code> — /file brings it."
            )

        const val SPAWNED_ALL = (
            "🛠 The four scripts launched detached — fork, brain, daemon and WhatsApp build " +
                "in parallel and each verification greets on return. " +
                "Logs: <code>~/.zcode/bot-kotlin/logs/deploy-fork.log</code>, " +
                "<code>deploy-brain.log</code>, <code>deploy-daemon.log</code> and " +
                "<code>deploy-whatsapp.log</code> — /file brings them."
            )

        const val ANNOUNCE_FAILED = (
            "⚠️ I could not announce the deploy (the message did not go out) — I do NOT deploy blind: " +
                "the daemon would die and the phone would go silent. Try again when the send returns."
            )

        const val SPAWN_FAILED = "❌ deploy: could not launch the script — the daemon did NOT restart: "

        const val NOT_OWNER = "🚫 Only the owner launches the deploy."
    }
}
