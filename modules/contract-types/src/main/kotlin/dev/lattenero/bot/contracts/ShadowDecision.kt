package dev.lattenero.bot.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One shadow decision: what the Kotlin watcher WOULD have sent to
 * Telegram, with the exact text the Python side's `_notify`/spectator
 * renderers produce. The daemon WRITES these (ADR-023/024); the
 * comparator READS them and diffs against what the Python bot actually
 * sent (B1c) — one schema, both sides, like the bus event.
 */
@Serializable
data class ShadowDecision(
    /** Decision time (epoch seconds) — when the watcher spoke. */
    val ts: Double,
    @SerialName("ts_iso") val tsIso: String,
    val watcher: String,
    val decision: String,
    val text: String,
    /** The source event's timestamp (the log event, the rollout record). */
    @SerialName("event_ts") val eventTs: Double? = null,
    /** The spectator's may_push evaluation at send time (null for zconn). */
    val gated: Boolean? = null,
    /** The session the decision belongs to (spectator turns). */
    val sid: String? = null,
)
