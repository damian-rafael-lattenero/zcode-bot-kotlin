package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.QuotaAlertState
import dev.lattenero.bot.channel.QuotaAlerts
import dev.lattenero.bot.channel.QuotaSnapshot
import kap.Kap
import kap.Process
import kap.RestartPolicy
import kap.Schedule
import kap.evalGraph
import kap.process
import kap.repeat
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * THE QUOTA WATCHER (audit item 5): a periodic read-only pass over every
 * plan's quota, the selection in force and the provider's own last refusal —
 * the phone learns about a drying window (or a provider that cut service)
 * BEFORE the owner discovers it mid-turn.
 *
 * The decision is pure ([QuotaAlerts.decide]); this class owns the edges: the
 * snapshot read, the hysteresis state file (atomic tmp+fsync+rename), the
 * loud delivery and the supervised process. Every failure is ONE log line —
 * a quota read never touches a turn.
 */
class QuotaWatch(
    private val snapshot: suspend () -> QuotaSnapshot?,
    private val providerLimit: () -> String?,
    private val deliver: suspend (String) -> Unit,
    private val stateFile: File,
    private val thresholdPct: Int,
    private val every: Duration,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** One pass: read, decide, persist, answer the alerts to speak. Never throws. */
    // any failure is ONE log line — the watcher is a courtesy, not a lifeline
    @Suppress("TooGenericExceptionCaught")
    suspend fun pass(): List<String> = try {
        val snap = snapshot() ?: return emptyList()
        val prev = load()
        val (alerts, next) = QuotaAlerts.decide(snap, providerLimit(), prev, thresholdPct)
        if (next != prev) save(next)
        alerts
    } catch (e: CancellationException) {
        throw e // cancellation is not a quota failure
    } catch (e: Exception) {
        log.warn("quota watch pass failed ({}) — next tick retries", e.message)
        emptyList()
    }

    /** The alerts, LOUD on the commands lane (the owner's «bien fuertes»). */
    suspend fun speak(alerts: List<String>) {
        alerts.forEach { alert ->
            deliver(alert)
            log.info("quota alert: {}", alert.take(LOG_PREVIEW))
        }
    }

    /** One pass per tick, restarts under the convergence law (the GlobalNotices shape). */
    fun asProcess(onHeartbeat: () -> Unit = {}): Process<Unit> =
        Kap { speak(pass()) }
            .repeat(Schedule.spaced<Unit>(every))
            .process(
                name = PROCESS_NAME,
                restart = RestartPolicy.exponential(base = 1.seconds),
                heartbeatEvery = HEARTBEAT_EVERY,
                onHeartbeat = onHeartbeat,
                onRestart = { attempt, error, wait ->
                    log.warn("quota watch failed — restart {} in {}: {}", attempt, wait, error.message)
                },
            )

    /** The persisted hysteresis state — a corrupt file starts fresh, loudly. */
    @Suppress("TooGenericExceptionCaught") // any read/parse failure IS «no state»: starting fresh is the contract
    internal fun load(): QuotaAlertState = try {
        if (!stateFile.exists()) return QuotaAlertState()
        json.decodeFromString(QuotaAlertState.serializer(), stateFile.readText())
    } catch (e: Exception) {
        log.warn("quota-alerts state unreadable ({}) — starting fresh", e.message)
        QuotaAlertState()
    }

    /** Atomic write (tmp + fsync + rename): a crash never leaves half a JSON. */
    @Suppress("TooGenericExceptionCaught") // a failed persist is ONE log line: the alert still goes out
    internal fun save(state: QuotaAlertState) {
        try {
            stateFile.parentFile?.mkdirs()
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            FileOutputStream(tmp).use {
                it.write(json.encodeToString(QuotaAlertState.serializer(), state).toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            if (!tmp.renameTo(stateFile)) throw java.io.IOException("atomic rename failed: $stateFile")
        } catch (e: Exception) {
            log.warn("quota-alerts state not saved ({}) — the next pass may repeat one alert", e.message)
        }
    }

    companion object {
        const val PROCESS_NAME = "quota-watch"
        val HEARTBEAT_EVERY = 5.seconds
        private const val LOG_PREVIEW = 120
        private val log = LoggerFactory.getLogger("zcode-bot.quota-watch")
    }
}
