package dev.lattenero.bot.daemon.palette

import dev.lattenero.bot.channel.MenuCommand
import dev.lattenero.bot.channel.PaletteOrder
import io.github.oshai.kotlinlogging.KotlinLogging
import kap.Kap
import kap.Process
import kap.RestartPolicy
import kap.Schedule
import kap.process
import kap.repeat
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * M1's edge: re-register Telegram's «/» menu when — and ONLY when — the
 * usage ranking really moved. One pass reads the store, compares the
 * ranked [PaletteOrder] with the order Telegram already holds, and calls
 * setMyCommands only on a real change (a failed registration leaves the
 * store untouched, so the next pass retries honestly).
 */
class PaletteRefresher(
    private val store: PaletteStore,
    private val register: suspend (List<MenuCommand>) -> Boolean,
) {

    /** One pass: true = a setMyCommands flew and the store now remembers the order. */
    suspend fun pass(): Boolean {
        val ranked = store.ranked()
        val order = PaletteOrder(ranked.map { it.name })
        val moved = order != store.registeredOrder()
        val registered = moved && register(ranked)
        if (registered) {
            store.markRegistered(order)
            logger.info { "palette re-registered by usage: ${order.names.joinToString(" ")}" }
        }
        return registered
    }

    /** The refresher's own Process — the same cadence shape the other watchers ride. */
    fun asProcess(every: Duration, onHeartbeat: () -> Unit = {}): Process<Unit> =
        Kap {
            pass()
            Unit
        }
            .repeat(Schedule.spaced(every))
            .process(
                name = PROCESS_NAME,
                restart = RestartPolicy.exponential(base = 1.seconds),
                heartbeatEvery = HEARTBEAT_EVERY,
                onHeartbeat = onHeartbeat,
                onRestart = { attempt, error, delay ->
                    logger.warn(error) { "palette refresh failed — restart $attempt in $delay" }
                },
            )

    private companion object {
        const val PROCESS_NAME = "palette-refresher"
        val HEARTBEAT_EVERY = 60.seconds
    }
}
