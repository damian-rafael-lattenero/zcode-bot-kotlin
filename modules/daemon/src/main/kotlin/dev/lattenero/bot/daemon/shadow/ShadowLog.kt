package dev.lattenero.bot.daemon.shadow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One shadow decision: what the Kotlin watcher WOULD have sent to Telegram,
 * with the exact text the Python side's `_notify` renders. The comparator
 * (B1's exit gate) diffs these against what the Python bot actually sent.
 */
@Serializable
data class ShadowDecision(
    /** Decision time (epoch seconds) — when the watcher spoke. */
    val ts: Double,
    @SerialName("ts_iso") val tsIso: String,
    val watcher: String,
    val decision: String,
    val text: String,
    /** The log event's timestamp the decision came from. */
    @SerialName("event_ts") val eventTs: Double? = null,
    /** The spectator's may_push evaluation at send time (null for zconn). */
    val gated: Boolean? = null,
    /** The session the decision belongs to (spectator turns). */
    val sid: String? = null,
)

/** UTC ISO with milliseconds — the bus `_now_iso` shape. */
object TimeIso {
    private val format =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun format(epochSeconds: Double): String =
        format.format(Instant.ofEpochMilli((epochSeconds * MILLIS_PER_SECOND).toLong()))

    private const val MILLIS_PER_SECOND = 1000.0
}

/**
 * The shadow-mode output: append-only JSONL, one file per watcher under the
 * shadow dir. The bus's rules apply (ADR-019): a failed write is counted,
 * never thrown — the shadow may lag, it may never break a cycle.
 */
class ShadowLog(private val dir: File) {

    private val json = Json

    // in-process counter, never reset — the bus.py rule, verbatim
    var failures: Int = 0
        private set

    fun append(decision: ShadowDecision): Boolean = try {
        dir.mkdirs()
        File(dir, "${decision.watcher}.jsonl")
            .appendText(json.encodeToString(ShadowDecision.serializer(), decision) + "\n")
        true
    } catch (_: IOException) {
        failures += 1
        false
    }
}
