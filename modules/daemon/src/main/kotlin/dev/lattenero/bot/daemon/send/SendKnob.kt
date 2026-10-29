package dev.lattenero.bot.daemon.send

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * The cutover knob's three positions (ADR-023: "cutover = a knob silences
 * Python's zconn and the daemon starts sending through B2's transport;
 * rollback = the knob back" — bot ADR-025's week gate waived by the
 * system owner 30/10, the comparator continues as post-cutover audit):
 *
 * - SHADOW  — today's contract (ADR-023/024): compute, log the decision,
 *   never send. Python speaks.
 * - DRY_RUN — compose the EXACT request (method + params, the same pure
 *   composer live posts through) and log it with the token redacted,
 *   WITHOUT posting and WITHOUT touching the shared files.
 * - LIVE    — the daemon owns the send: Sender posts it, reading and
 *   writing the SAME tg-cooldown / outbox / tg-last-ph files Python uses.
 */
enum class SendMode {
    SHADOW,
    DRY_RUN,
    LIVE,
}

/** The accepted spellings of the knob's value (case/underscore tolerant). */
fun parseSendMode(value: String): SendMode? = when (value.trim().lowercase()) {
    "shadow" -> SendMode.SHADOW
    "dry-run", "dry_run", "dryrun" -> SendMode.DRY_RUN
    "live" -> SendMode.LIVE
    else -> null
}

/**
 * One reading of the knob file: the mode plus the complaint when the file
 * tried to say something we don't understand. A bad reading is always
 * SHADOW — a typo must degrade to "never sends" (the pre-cutover
 * behavior), never to a silent wrong mode; the warning makes it loud in
 * daemon.jsonl so the operator sees the flip did NOT apply.
 */
data class KnobReading(val mode: SendMode, val warning: String? = null)

/**
 * The knob file — `<home>/daemon.json`, read ONCE at boot (a mode change
 * is a restart, `launchctl kickstart gui/$(id -u)/com.zcode.bot-daemon`;
 * the file is the deploy, the restart is the apply). The missing file is
 * SHADOW with no complaint: that is the honest state of a daemon deployed
 * before the cutover existed.
 */
object SendKnob {

    const val FILE_NAME = "daemon.json"
    private const val KEY = "send"

    // any reading failure is the same answer: SHADOW, loudly (load_config's degrade)
    @Suppress("TooGenericExceptionCaught")
    fun load(file: File): KnobReading = when {
        !file.exists() -> KnobReading(SendMode.SHADOW)

        else -> try {
            val root = Json.parseToJsonElement(file.readText()) as? JsonObject
            val value = root?.get(KEY) as? JsonPrimitive
            when {
                root == null || value == null ->
                    KnobReading(SendMode.SHADOW, "no '$KEY' key — staying SHADOW")

                else -> parseSendMode(value.content)?.let { KnobReading(it) }
                    ?: KnobReading(
                        SendMode.SHADOW,
                        "unknown '$KEY' value \"${value.content}\" — staying SHADOW " +
                            "(expected shadow | dry-run | live)",
                    )
            }
        } catch (e: Exception) {
            // unreadable is a bad reading — same rule: SHADOW, loudly
            KnobReading(SendMode.SHADOW, "unreadable (${e.message}) — staying SHADOW")
        }
    }
}
