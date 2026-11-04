package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.DeployCommand
import dev.lattenero.bot.channel.DeployCommand.Target
import dev.lattenero.bot.channel.DeployCommand.Verdict
import dev.lattenero.bot.channel.Keyboards
import io.github.oshai.kotlinlogging.KotlinLogging
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
 * branch is silent (the honest-indicators law): the card, the
 * cancellation, the expiry, the refusals, the loud failures.
 *
 * [say]'s contract is the send leg's: never throws, answers the born
 * message's id (null = shadow/dry-run/parked/failed — nothing flew).
 * [spawn]'s contract is the edge's: fork-and-forget for the target's OWN
 * sibling launchd job(s), throws only when a child could not be born
 * (missing script, unwritable log) — a throw means THIS daemon stays up
 * and the phone hears it LOUD.
 *
 * 2026-11-04 — three targets: bare /deploy offers 🤖 bot / 🍴 fork /
 * 🚀 both; the tap arms THAT target and the go fires exactly it. The
 * fork deploy does NOT touch this daemon (the canal 📡 dies with the
 * app and comes back with it); the bot deploy boots out THIS daemon —
 * and BOTH is fork FIRST, because a sibling job survives the bootout
 * (the spawn order is the whole point, not a style choice).
 */
class DeployFlow(
    private val machine: DeployCommand,
    private val say: suspend (String, Boolean, JsonObject?) -> Long?,
    private val spawn: suspend (Target) -> Unit,
    /** The config's owner, read PER USE (the config cadence); null = nobody confirms. */
    private val ownerUserId: () -> Long?,
) {

    /**
     * The message path — Python's rest ladder, widened: "go" runs the
     * armed target, "no" cancels, a TARGET WORD arms that target, and
     * ANYTHING ELSE (bare included) shows the target card.
     */
    suspend fun onText(rest: String, fromId: Long?) {
        handle(wordToAction(rest.trim().lowercase()), fromId)
    }

    /** The tap path — the callback_data without its `dep:` prefix. */
    suspend fun onTap(action: String, fromId: Long?) = handle(action, fromId)

    private fun wordToAction(word: String): String = when (word) {
        DeployCommand.ACTION_GO -> DeployCommand.ACTION_GO
        DeployCommand.ACTION_NO -> DeployCommand.ACTION_NO
        TARGET_WORD_BOT, TARGET_WORD_FORK, TARGET_WORD_BOTH -> word
        else -> ACTION_CARD
    }

    private suspend fun handle(action: String, fromId: Long?) {
        // a target word ARMS that target and shows ITS confirm card — the
        // newest selection is the only trusted one (the re-arm law)
        targetOf(action)?.let { target ->
            machine.arm(target)
            say(confirmCard(target), false, Keyboards.deployConfirm())
            return
        }
        if (action == ACTION_CARD) {
            say(TARGETS_CARD, false, Keyboards.deployTargets())
            return
        }
        when (val verdict = machine.confirm(action, fromId, ownerUserId())) {
            is Verdict.Confirmed -> launch(verdict.target)
            Verdict.Cancelled -> say(CANCELLED, false, null)
            Verdict.Expired -> say(EXPIRED, true, null)
            Verdict.NotArmed -> say(NOT_ARMED, false, null)
            Verdict.NotOwner -> say(NOT_OWNER, true, null)
        }
    }

    /** THE DOOR: announce → receipt check → spawn. The order is the whole point. */
    @Suppress("TooGenericExceptionCaught") // the spawn edge: any failure speaks LOUD, never crashes the poller
    private suspend fun launch(target: Target) {
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
            logger.warn {
                "deploy($target): script(s) spawned detached (announce message_id=$receipt) — " +
                    "this daemon may die mid-build"
            }
            say(spawnedNote(target), false, null)
        } catch (e: Exception) {
            logger.error(e) { "deploy($target): the spawn FAILED — this daemon stays up" }
            say(SPAWN_FAILED + (e.message ?: e::class.simpleName.orEmpty()), true, null)
        }
    }

    private fun confirmCard(target: Target): String = when (target) {
        Target.BOT -> CONFIRM_BOT
        Target.FORK -> CONFIRM_FORK
        Target.BOTH -> CONFIRM_BOTH
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
        /** The wiring's action word for the bare (or unrecognized) /deploy. */
        const val ACTION_CARD = "card"

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

        /** The selection card — WHAT can be deployed, not yet a confirmation. */
        const val TARGETS_CARD = "🚀 <b>Deploy</b>\n¿Qué deployo? Elegí el objetivo — después te pido la confirmación."

        /**
         * The confirm cards — each names the REAL pipeline it will run and
         * the honest cost (what dies, what survives).
         */
        val CONFIRM_BOT = (
            "🤖 <b>Deploy del daemon (Bot Kotlin)</b>\n" +
                "build (Gradle, tests incluidos) → install → relanza el LaunchAgent → " +
                "verifica el heartbeat armado.\n" +
                "La confirmación vence en ${DeployCommand.WINDOW_MS / 60_000} min. ¿Confirmás?"
            )

        val CONFIRM_FORK = (
            "🍴 <b>Deploy del fork de ZCode</b>\n" +
                "build del fork AHORA (~15 min, te aviso por fases) → deja el swap armado → " +
                "VOS hacés ⌘Q en la Mac y el swap + reopen salen solos.\n" +
                "Durante el build no se toca nada: el canal sigue vivo; solo cae en tu ⌘Q (el swap tarda segundos).\n" +
                "La confirmación vence en ${DeployCommand.WINDOW_MS / 60_000} min. ¿Confirmás?"
            )

        val CONFIRM_BOTH = (
            "🚀 <b>Deploy de AMBOS</b>\n" +
                "Primero el fork (el canal 📡 se cae), después el daemon (el bot se reinicia) — " +
                "ambos como jobs de launchd hermanos: sobreviven al bootout del otro.\n" +
                "La confirmación vence en ${DeployCommand.WINDOW_MS / 60_000} min. ¿Confirmás?"
            )

        /** The door's exact sentences — the phone hears them BEFORE any script can kill anything. */
        const val THE_ANNOUNCE = "🚀 Deployando el daemon — vuelvo en un momento"

        const val THE_ANNOUNCE_FORK = (
            "🍴 Deploy del fork armado — construyo el fork (~15 min, te aviso por fases); " +
                "al terminar: ⌘Q en la Mac para el swap"
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
                "el build tarda ~15 min y te habla por fases; al terminar, ⌘Q en la Mac " +
                "dispara el swap + reopen (avisos por Telegram). " +
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

        const val EXPIRED = (
            "⌛ La confirmación venció (${DeployCommand.WINDOW_MS / 60_000} s) — " +
                "mandá /deploy de nuevo."
            )

        const val NOT_ARMED = (
            "🤷 No hay confirmación armada (ya se usó o nunca hubo) — " +
                "mandá /deploy y elegí el objetivo."
            )

        const val NOT_OWNER = "🚫 Solo el dueño confirma el deploy."

        const val CANCELLED = "🙅 Cancelado — no se tocó nada."
    }
}
