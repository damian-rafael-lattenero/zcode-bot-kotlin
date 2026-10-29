package dev.lattenero.bot.recorder

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One line of the golden replay trace. The trace is the contract the later
 * phases (B1/B2/B3) replay against: every field nullable except the type
 * discriminator `t` and the wall timestamp.
 */
// The line IS the on-disk trace format; a config object would only hide it.
@Suppress("LongParameterList")
@Serializable
data class TraceLine(
    val t: String,
    val ts: String,
    val name: String? = null,
    val size: Long? = null,
    val mtimeMs: Long? = null,
    val sha256: String? = null,
    val content: String? = null,
    val contentB64: String? = null,
    val truncated: Boolean = false,
    val total: Int? = null,
    val changed: Int? = null,
    val gone: Int? = null,
) {
    fun toJsonLine(): String = RecorderJson.json.encodeToString(this) + "\n"
}

object RecorderJson {
    val json = Json {
        encodeDefaults = false
        explicitNulls = false
    }
}

/** The scanned identity of one file: name + size + mtime (the house's shared clock) + content hash. */
data class FileFact(
    val name: String,
    val size: Long,
    val mtimeMs: Long,
    val sha256: String,
)

/** A scanned file plus its capture decision: text inlined, base64 for binaries, or hash-only over the cap. */
data class FileSample(
    val fact: FileFact,
    val text: String?,
    val b64: String?,
    val overCap: Boolean,
)

/**
 * The PURE half of the golden replay recorder (B0 of the KAP-first
 * migration): given the previous and current facts of a state directory,
 * decide exactly what this pass's trace lines must be. No I/O — the shell
 * (Recorder.kt) feeds it scanned samples and appends the lines it returns.
 *
 * Trace contract (`replay-YYYY-MM-DD.jsonl`, UTC days):
 *  - one `pass` line per pass ALWAYS (with total/changed/gone counts): a
 *    silent state dir is distinguishable from a dead recorder;
 *  - one `file` line per new or changed file (sha256 differs): content
 *    inlined up to the cap (text, or base64 for binary), hash-only above;
 *  - one `gone` line per file that disappeared.
 *
 * Deterministic: files are emitted sorted by name.
 */
object SnapshotEngine {

    fun pass(
        nowIso: String,
        previous: Map<String, FileFact>,
        current: Map<String, FileSample>,
    ): List<TraceLine> {
        val changed = current.filterKeys { name ->
            previous[name]?.sha256 != current[name]?.fact?.sha256
        }
        val gone = previous.keys.filter { it !in current }

        val lines = mutableListOf(
            TraceLine(
                t = "pass", ts = nowIso,
                total = current.size, changed = changed.size, gone = gone.size,
            ),
        )
        changed.toSortedMap().forEach { (name, sample) ->
            lines += sample.fact.toLine(nowIso, sample)
        }
        gone.sorted().forEach { name ->
            lines += TraceLine(t = "gone", ts = nowIso, name = name)
        }
        return lines
    }

    private fun FileFact.toLine(ts: String, s: FileSample) = TraceLine(
        t = "file", ts = ts, name = name, size = size, mtimeMs = mtimeMs,
        sha256 = sha256, content = s.text, contentB64 = s.b64, truncated = s.overCap,
    )
}
