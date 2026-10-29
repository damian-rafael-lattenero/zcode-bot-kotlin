package dev.lattenero.bot.comparator

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What the PYTHON side actually did — the comparator's truth. */
data class ZconnTruth(val ts: Double, val kind: String)

data class SpectatorTurnTruth(
    val startTs: Double,
    val turnPrefix: String,
    val endTs: Double?,
    val steps: Int?,
)

/**
 * kb.log — zconn's sends. The record is TWO lines: the leveled header
 * with an explicit offset, then the bare message. Real shape (pinned
 * from production):
 *
 * `[2026-10-28 13:00:38.086 -03] INFO zcode_bot.kb component=transport`
 * `zconn down`
 */
object KbLog {

    private const val GROUP_DATE = 1
    private const val GROUP_TIME = 2
    private const val GROUP_OFFSET = 3
    private const val MILLIS_PER_SECOND = 1000.0
    private val KINDS = mapOf("zconn down" to "down", "zconn up" to "up")
    private val HEADER = Regex(
        """^\[(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}\.\d{3}) ([-+]\d{2})\] \S+ zcode_bot\.kb component=\S+$""",
    )

    fun parse(lines: List<String>): List<ZconnTruth> {
        val out = mutableListOf<ZconnTruth>()
        var pending: Double? = null
        for (line in lines) {
            val m = HEADER.find(line)
            if (m != null) {
                pending = epochOf(m.groupValues[GROUP_DATE], m.groupValues[GROUP_TIME], m.groupValues[GROUP_OFFSET])
            } else {
                val kind = KINDS[line]
                val ts = pending
                if (kind != null && ts != null) out.add(ZconnTruth(ts, kind))
                if (kind != null) pending = null
            }
        }
        return out
    }

    private fun epochOf(date: String, time: String, offset: String): Double? = runCatching {
        LocalDateTime.parse("${date}T$time", DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .atOffset(ZoneOffset.of(offset))
            .toInstant()
            .toEpochMilli() / MILLIS_PER_SECOND
    }.getOrNull()
}

/**
 * prompt-worker.log — the spectator's forensic steps (pwlog, the format
 * frozen over three incidents). Real shape: `[10-29 11:10:55] spectator:
 * turn pinned turn_5056bb83-2137` and `[10-29 11:30:54] spectator end:
 * steps=37`. No year, no zone: the same machine that wrote the shadow's
 * absolute epochs, so the local zone and current year apply.
 */
object PwLog {

    private const val GROUP_MSG = 3
    private val LINE = Regex("""^\[(\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})\] (.+)$""")
    private val PINNED = Regex("""^spectator: turn pinned (\S+)$""")
    private val END = Regex("""^spectator end: steps=(\d+)$""")

    fun parse(
        lines: List<String>,
        year: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SpectatorTurnTruth> {
        val out = mutableListOf<SpectatorTurnTruth>()
        var open: SpectatorTurnTruth? = null
        // one continue per non-matching line, like the scanner it mirrors
        @Suppress("LoopWithTooManyJumpStatements")
        for (line in lines) {
            val m = LINE.find(line) ?: continue
            val ts = epochOf(m.groupValues[1], m.groupValues[2], year, zone) ?: continue
            val msg = m.groupValues[GROUP_MSG]
            val pinned = PINNED.find(msg)
            if (pinned != null) {
                // a pinned without its end (a crashed spectator): kept open-ended
                open?.let(out::add)
                open = SpectatorTurnTruth(ts, pinned.groupValues[1], null, null)
                continue
            }
            val end = END.find(msg)
            if (end != null) {
                val steps = end.groupValues[1].toIntOrNull() ?: continue
                val current = open
                if (current != null) {
                    out.add(current.copy(endTs = ts, steps = steps))
                    open = null
                } else {
                    out.add(SpectatorTurnTruth(ts, "", ts, steps))
                }
            }
        }
        open?.let(out::add)
        return out
    }

    private fun epochOf(date: String, time: String, year: Int, zone: ZoneId): Double? = runCatching {
        LocalDateTime.parse("$year-${date}T$time", TS_FORMAT)
            .atZone(zone)
            .toInstant()
            .toEpochMilli() / MILLIS_PER_SECOND
    }.getOrNull()

    private val TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private const val MILLIS_PER_SECOND = 1000.0
}
