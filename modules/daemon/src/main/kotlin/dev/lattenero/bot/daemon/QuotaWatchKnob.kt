package dev.lattenero.bot.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * THE QUOTA WATCHER'S KNOBS (audit item 5), read from the same `daemon.json`
 * the other knobs live in — once at boot, like all of them:
 *
 *  - `quota_watch` (bool, default ON): the periodic quota pass;
 *  - `quota_alert_pct` (int, default 10): the remaining-percent line;
 *  - `quota_watch_min` (int, default 30): the pass cadence in minutes.
 *
 * A bad value never throws: it degrades to the default LOUDLY (the house
 * rule the other knobs follow).
 */
data class QuotaWatchConfig(
    val on: Boolean = true,
    val thresholdPct: Int = DEFAULT_PCT,
    val everyMinutes: Int = DEFAULT_MINUTES,
    val warning: String? = null,
) {
    companion object {
        const val KEY_ON = "quota_watch"
        const val KEY_PCT = "quota_alert_pct"
        const val KEY_MIN = "quota_watch_min"
        const val DEFAULT_PCT = 10
        const val DEFAULT_MINUTES = 30
        const val MAX_MINUTES = 24 * 60
    }
}

object QuotaWatchKnob {
    private val json = Json { ignoreUnknownKeys = true }

    @Suppress("TooGenericExceptionCaught", "ReturnCount") // a bad file IS the defaults; one guard per shape
    fun load(file: File): QuotaWatchConfig {
        val root: JsonObject = try {
            if (!file.exists()) return QuotaWatchConfig()
            json.parseToJsonElement(file.readText()) as? JsonObject ?: return QuotaWatchConfig()
        } catch (e: Exception) {
            return QuotaWatchConfig(warning = "unreadable ($e) — defaults in use")
        }
        val warnings = mutableListOf<String>()
        val on = when (val value = root[QuotaWatchConfig.KEY_ON]) {
            null -> true
            is JsonPrimitive -> when (value.content.trim().lowercase()) {
                "true", "on", "yes", "1" -> true
                "false", "off", "no", "0" -> false
                else -> {
                    warnings += "'${QuotaWatchConfig.KEY_ON}' no es booleano — queda ON"
                    true
                }
            }

            else -> true
        }
        val pct = readInt(root, QuotaWatchConfig.KEY_PCT, QuotaWatchConfig.DEFAULT_PCT, 1, 99, warnings)
        val minutes = readInt(
            root,
            QuotaWatchConfig.KEY_MIN,
            QuotaWatchConfig.DEFAULT_MINUTES,
            1,
            QuotaWatchConfig.MAX_MINUTES,
            warnings,
        )
        return QuotaWatchConfig(on, pct, minutes, warnings.joinToString("; ").takeIf { it.isNotEmpty() })
    }

    @Suppress("ReturnCount") // one guard per unusable value (missing, not a number, out of range)
    private fun readInt(
        root: JsonObject,
        key: String,
        default: Int,
        min: Int,
        max: Int,
        warnings: MutableList<String>,
    ): Int {
        val value = (root[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.trim() ?: return default
        val parsed = value.toIntOrNull()
        if (parsed == null || parsed < min || parsed > max) {
            warnings += "'$key' fuera de [$min..$max] ('$value') — queda $default"
            return default
        }
        return parsed
    }
}
