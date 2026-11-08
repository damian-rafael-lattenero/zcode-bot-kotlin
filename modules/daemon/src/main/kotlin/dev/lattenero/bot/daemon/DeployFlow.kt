package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.DeployCommand
import dev.lattenero.bot.channel.DeployCommand.Target
import dev.lattenero.bot.channel.DeployCommand.Verdict
import dev.lattenero.bot.channel.Keyboards
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
 * 2026-11-04 — THE ONE-TAP LAW (the owner: «elegir que deployar y esa
 * eleccion ya empieza el deploy… una única vez… no se puede volver a
 * apretar esa botonera»): the bare /deploy offers 🤖 bot / 🍴 fork /
 * 🚀 both and REMEMBERS the card's born message id; the tap (or the typed
 * word) IS the confirmation — the owner gate passes and the door fires
 * immediately, and the card's BUTTON ROW DIES (the edit strips the
 * keyboard, marking the chosen row «▶️ en vuelo»). The confirm cards, the
 * go/no row, the expiry and the arm are all retired. After a launch that
 * lands, every further target is refused («ya hay un deploy en vuelo»)
 * until process death — the spawn edge's busy-guard is the durable one.
 * The fork deploy does NOT touch this daemon; BOTH is fork FIRST, because
 * a sibling job survives the bootout (the spawn order is the survival
 * law, not a style choice).
 */
class DeployFlow(
    private val machine: DeployCommand,
    private val say: suspend (String, Boolean, JsonObject?) -> Long?,
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
        val born = say(TARGETS_CARD, false, Keyboards.deployTargets())
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
        // stripped, the chosen row marked «▶️ en vuelo». Best-effort: a
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
            // BOTH splits HERE, in the door: fork FIRST — its job must be armed
            // before the daemon's deploy boots out this daemon. The order is
            // the survival law, so it lives where the tests can pin it.
            when (target) {
                Target.BOTH -> {
                    spawn(Target.FORK)
                    spawn(Target.BOT)
                }

                else -> spawn(target)
            }
            // THE ONE-SHOT: the launch landed — every further target is
            // refused until process death (honest, best-effort: the
            // script-side guard is the durable one). A failed spawn never
            // lands here: the shot stays free for the honest retry.
            machine.spent()
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
        Target.BOTH -> FLOWN_BOTH
    }

    private fun announceOf(target: Target): String = when (target) {
        Target.BOT -> THE_ANNOUNCE
        Target.FORK -> THE_ANNOUNCE_FORK
        Target.BOTH -> THE_ANNOUNCE_BOTH
    }

    private fun spawnedNote(target: Target): String = when (target) {
        Target.BOT -> SPAWNED
        Target.FORK -> SPAWNED_FORK
        Target.BOTH -> SPAWNED_BOTH
    }

    companion object {
        /** The target words — taps arrive as `dep:<word>`, texts as the bare rest. */
        const val TARGET_WORD_BOT = "bot"
        const val TARGET_WORD_FORK = "fork"
        const val TARGET_WORD_BOTH = "both"

        fun targetOf(action: String): Target? = when (action) {
            TARGET_WORD_BOT -> Target.BOT
            TARGET_WORD_FORK -> Target.FORK
            TARGET_WORD_BOTH -> Target.BOTH
            else -> null
        }

        /**
         * The ONE-TAP card — WHAT can be deployed, one honest line per
         * pipeline: the tap IS the confirmation, so these lines carry the
         * old confirm cards' content collapsed (the real cost of each).
         */
        const val TARGETS_CARD = (
            "🚀 <b>Deploy</b>\n¿Qué deployo? Tocar ES lanzar — una única vez, la botonera muere al toque:\n" +
                "🤖 <b>Bot Kotlin</b> — build (tests incluidos) → install → el LaunchAgent reinicia el daemon\n" +
                "🍴 <b>ZCode Fork</b> — build AHORA (~15 min, avisos por fases, la app sigue viva) → " +
                "cierra ZCode solo → swap → reabre (unos segundos de canal 📡 al final)\n" +
                "🚀 <b>Ambos</b> — fork PRIMERO, después el daemon (jobs hermanos de launchd)"
            )

        /** The card's flown rows — the edit's text: keyboard stripped, the choice marked. */
        const val FLOWN_BOT = "🤖 Bot Kotlin — ▶️ en vuelo"
        const val FLOWN_FORK = "🍴 ZCode Fork — ▶️ en vuelo"
        const val FLOWN_BOTH = "🚀 Ambos — ▶️ en vuelo (fork primero)"

        /** The in-flight refusal — one deploy at a time, until this process dies. */
        const val IN_FLIGHT = (
            "🚀 Ya hay un deploy en vuelo — no lanzo otro. " +
                "Si el job sigue corriendo, el script mismo se niega; esperá a que termine."
            )

        /** The door's exact sentences — the phone hears them BEFORE any script can kill anything. */
        const val THE_ANNOUNCE = "🚀 Deployando el daemon — vuelvo en un momento"

        const val THE_ANNOUNCE_FORK = (
            "🍴 Deploy del fork armado — construyo el fork (~15 min, te aviso por fases, " +
                "la app sigue viva); al final CIERRA ZCode solo, hace el swap y la reabre"
            )

        const val THE_ANNOUNCE_BOTH = (
            "🚀 Deployando AMBOS — primero el fork, después el daemon; vuelvo en un momento"
            )

        const val SPAWNED = (
            "🛠 Script lanzado detached — el build tarda minutos y el daemon " +
                "NUEVO saluda al boot (el script verifica heartbeat + armed + log tail). " +
                "Log: <code>~/.zcode/bot-kotlin/logs/deploy-daemon.log</code> — /file lo trae."
            )

        const val SPAWNED_FORK = (
            "🛠 Script del fork lanzado detached (el MISMO de <code>zcode-deploy</code>) — " +
                "el build tarda ~15 min y te habla por fases con la app viva; al final " +
                "CIERRA ZCode solo, hace el swap y la reabre (avisos por Telegram). " +
                "Log: <code>~/.zcode/bot-kotlin/logs/deploy-fork.log</code> — /file lo trae."
            )

        const val SPAWNED_BOTH = (
            "🛠 Ambos scripts lanzados detached — fork y daemon compilan en paralelo " +
                "y cada verificación saluda al volver. " +
                "Logs: <code>~/.zcode/bot-kotlin/logs/deploy-fork.log</code> y " +
                "<code>deploy-daemon.log</code> — /file los trae."
            )

        const val ANNOUNCE_FAILED = (
            "⚠️ No pude anunciar el deploy (el mensaje no salió) — NO deployo a ciegas: " +
                "el daemon moriría y el teléfono quedaría en silencio. Probá de nuevo cuando el send vuelva."
            )

        const val SPAWN_FAILED = "❌ deploy: no pude lanzar el script — el daemon NO se reinició: "

        const val NOT_OWNER = "🚫 Solo el dueño lanza el deploy."
    }
}
