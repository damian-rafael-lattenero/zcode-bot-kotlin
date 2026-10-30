package dev.lattenero.bot.channel

import dev.lattenero.bot.transport.TgApi
import dev.lattenero.bot.transport.TgConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure: F23's setMessageReaction params (updates.py react) — feedback on
 * the ORIGINAL message without sending a single new one: 👀 on receipt,
 * ✅ on handled. One composition, like EditRequest, so the wire is
 * pinnable without a network.
 */
object ReactRequest {
    fun compose(cfg: TgConfig, messageId: Long, emoji: String): JsonObject = buildJsonObject {
        put("chat_id", cfg.chatId)
        put("message_id", messageId)
        put(
            "reaction",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "emoji")
                        put("emoji", emoji)
                    },
                )
            },
        )
    }
}

/**
 * F23 — the receipt/handled cycle: 👀 the moment the owner's words are
 * accepted (never radio silence, from second zero), ✅ once they were
 * handled. Fully best-effort: if the server rejects it, silence — a
 * reaction must never fail a turn (Python's deliberate degradation).
 */
object Ack {
    const val RECEIVED = "👀"
    const val HANDLED = "✅"

    @Suppress("TooGenericExceptionCaught") // Python's bare except: silent-by-design
    suspend fun react(api: TgApi, cfg: TgConfig, messageId: Long, emoji: String) {
        if (messageId <= 0) return // no mid, no reaction (Python's guard)
        try {
            api.call("setMessageReaction", ReactRequest.compose(cfg, messageId, emoji))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // deliberate degradation: the reaction is a courtesy, not a duty
        }
    }
}
