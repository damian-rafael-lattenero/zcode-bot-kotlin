package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.DeployCommand
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
 * [spawn]'s contract is the edge's: fork-and-forget, throws only when the
 * child could not be born (missing script, unwritable log) — a throw
 * means THIS daemon stays up and the phone hears it LOUD.
 */
class DeployFlow(
    private val machine: DeployCommand,
    private val say: suspend (String, Boolean, JsonObject?) -> Long?,
    private val spawn: () -> Unit,
    /** The config's owner, read PER USE (the config cadence); null = nobody confirms. */
    private val ownerUserId: () -> Long?,
) {

    /**
     * The message path — Python's rest ladder: "go" runs, "no" cancels,
     * ANYTHING ELSE (bare included) arms a fresh window and shows the card.
     */
    suspend fun onText(rest: String, fromId: Long?) {
        val word = rest.trim().lowercase()
        handle(
            when (word) {
                DeployCommand.ACTION_GO -> DeployCommand.ACTION_GO
                DeployCommand.ACTION_NO -> DeployCommand.ACTION_NO
                else -> ACTION_CARD
            },
            fromId,
        )
    }

    /** The tap path — the callback_data without its `dep:` prefix. */
    suspend fun onTap(action: String, fromId: Long?) = handle(action, fromId)

    private suspend fun handle(action: String, fromId: Long?) {
        if (action == ACTION_CARD) {
            machine.arm()
            say(CARD, false, Keyboards.deployConfirm())
            return
        }
        when (machine.confirm(action, fromId, ownerUserId())) {
            Verdict.Confirmed -> launch()
            Verdict.Cancelled -> say(CANCELLED, false, null)
            Verdict.Expired -> say(EXPIRED, true, null)
            Verdict.NotArmed -> say(NOT_ARMED, false, null)
            Verdict.NotOwner -> say(NOT_OWNER, true, null)
        }
    }

    /** THE DOOR: announce → receipt check → spawn. The order is the whole point. */
    @Suppress("TooGenericExceptionCaught") // the spawn edge: any failure speaks LOUD, never crashes the poller
    private suspend fun launch() {
        val receipt = say(THE_ANNOUNCE, true, null)
        if (receipt == null) {
            logger.warn { "deploy: the announce did NOT land — spawn REFUSED (no blind restarts)" }
            say(ANNOUNCE_FAILED, true, null)
            return
        }
        try {
            spawn()
            logger.warn {
                "deploy: script spawned detached (announce message_id=$receipt) — this daemon may die mid-build"
            }
            say(SPAWNED, false, null)
        } catch (e: Exception) {
            logger.error(e) { "deploy: the spawn FAILED — this daemon stays up" }
            say(SPAWN_FAILED + (e.message ?: e::class.simpleName.orEmpty()), true, null)
        }
    }

    companion object {
        /** The wiring's action word for the bare (or unrecognized) /deploy. */
        const val ACTION_CARD = "card"

        /**
         * The card — the REAL pipeline (build with tests → install →
         * LaunchAgent restart → armed-heartbeat verification) and the
         * window's honest length.
         */
        val CARD = (
            "🚀 <b>Deploy del daemon</b>\n" +
                "build (Gradle, tests incluidos) → install → relanza el LaunchAgent → " +
                "verifica el heartbeat armado.\n" +
                "La confirmación vence en ${DeployCommand.WINDOW_MS / 60_000} min. ¿Confirmás?"
            )

        /** The door's exact sentence — the phone hears it BEFORE the script can kill this daemon. */
        const val THE_ANNOUNCE = "🚀 Deployando el daemon — vuelvo en un momento"

        const val SPAWNED = (
            "🛠 Script lanzado detached — el build tarda minutos y el daemon " +
                "NUEVO saluda al boot (el script verifica heartbeat + armed + log tail). " +
                "Log: <code>~/.zcode/bot-kotlin/logs/deploy-daemon.log</code> — /file lo trae."
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

        const val NOT_ARMED = "🤷 No hay confirmación armada (ya se usó o nunca hubo) — mandá /deploy."

        const val NOT_OWNER = "🚫 Solo el dueño confirma el deploy."

        const val CANCELLED = "🙅 Cancelado — no se tocó nada."
    }
}
