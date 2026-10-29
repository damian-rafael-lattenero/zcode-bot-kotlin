package dev.lattenero.bot.daemon.shadow

import dev.lattenero.bot.contracts.ShadowDecision
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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

    private val json = kotlinx.serialization.json.Json

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
