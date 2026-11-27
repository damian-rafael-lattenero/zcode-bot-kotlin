package dev.lattenero.bot.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The stopAll answer's parsers (extracted from WiringFragments to keep that
 * file under detekt's per-file function cap): the host names the tasks it
 * actually stopped, and both readers ride the same shape — a `"stopped"`
 * string array, read total (a malformed answer degrades, never throws).
 */
internal fun stoppedCountOf(resultJson: String): Int = runCatching {
    ((Json.parseToJsonElement(resultJson) as? JsonObject)?.get("stopped") as? JsonArray)?.size ?: 0
}.getOrDefault(0)

/**
 * U8's cancel attribution: stopAll's answer → the sids the HOST says it
 * stopped (a string array, the same shape [stoppedCountOf] counts). Only
 * these names ride the bus — a hard stop that never landed records nothing.
 */
internal fun stoppedSidsOf(resultJson: String): List<String> = runCatching {
    ((Json.parseToJsonElement(resultJson) as? JsonObject)?.get("stopped") as? JsonArray)
        ?.mapNotNull { element -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content }
        .orEmpty()
}.getOrDefault(emptyList())
