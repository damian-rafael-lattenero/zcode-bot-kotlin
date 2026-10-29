package dev.lattenero.bot.transport

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The sendMessage request as a PURE composition (utils/http.py send's
 * params block): one function builds the exact wire params, so the live
 * Sender and the daemon's dry-run CANNOT diverge — dry-run logs what
 * [compose] answers, live posts what [compose] answers, byte for byte
 * ([TgApi] sends `params.toString()` as the body).
 */
object SendRequest {

    /**
     * loud=null → the config's loud_all decides (Python: `loud is None ->
     * loud = bool(cfg.get("loud_all"))`); markup → reply_markup serialized
     * as a JSON STRING (Python's `json.dumps(markup)` inside the params,
     * ", "/": " separators). chat_id passes through with whatever type the
     * config file carries, like Python's `cfg["chat_id"]`.
     */
    fun compose(
        cfg: TgConfig,
        text: String,
        loud: Boolean? = null,
        markup: JsonObject? = null,
    ): JsonObject {
        val notify = loud ?: cfg.loudAll
        val base = buildJsonObject {
            put("chat_id", cfg.chatId)
            put("text", htmlTruncate(text, CH_REPLY))
            put("parse_mode", "HTML")
            put("disable_notification", !notify)
        }
        return if (markup != null) {
            JsonObject(base + ("reply_markup" to JsonPrimitive(PyText.jsonDumps(markup))))
        } else {
            base
        }
    }
}
