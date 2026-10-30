package dev.lattenero.bot.transport

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * The transport's slice of telegram-remote.json (zcode_bot.config): the
 * token, the chat, the loudness default. `chat_id` stays a JsonElement
 * because the wire param must keep whatever type the file carries
 * (Python passes cfg["chat_id"] through as-is).
 */
data class TgConfig(
    val enabled: Boolean = true,
    val token: String = "",
    val chatId: JsonElement = JsonNull,
    val loudAll: Boolean = true,
    /** from_owner's fail-closed gate (F48): without an owner NOBODY gets through. */
    val ownerUserId: Long? = null,
    /**
     * The firehose lane's own token (post-mortem 29/10: Telegram's rate
     * budget and flood-penalty history are PER BOT — one shared token let
     * the spectator's render volume mute /stop for 11h). Absent/empty →
     * there is no separate lane and [firehose] answers `this` (the
     * pre-lane behavior, byte for byte).
     */
    val firehoseToken: String = "",
    val apiBase: String = System.getenv("ZT_API_BASE") ?: DEFAULT_API,
) {
    /** config.configured(): enabled AND token AND chat_id (Python truthiness). */
    val configured: Boolean
        get() = enabled && token.isNotEmpty() && PyText.truthy(chatId)

    /**
     * The high-volume lane's view: same chat, the firehose bot's throat —
     * its budget and its penalties are its own; the commands lane can
     * never be muted by render traffic again.
     */
    val firehose: TgConfig
        get() = if (firehoseToken.isEmpty()) this else copy(token = firehoseToken)

    /** The firehose lane is live only when its token says so (for wiring). */
    val firehoseSplit: Boolean get() = firehoseToken.isNotEmpty()

    /**
     * Ktor exception messages embed the request URL and the URL carries
     * the token — every message that can reach a log passes through here.
     */
    fun redact(message: String): String = if (token.isEmpty()) message else message.replace(token, MASK)

    companion object {
        const val DEFAULT_API = "https://api.telegram.org"
        private const val MASK = "***"

        /** load_config's fallback shape: unreadable or broken → the defaults. */
        fun load(file: File): TgConfig {
            val root = try {
                Json.parseToJsonElement(file.readText()) as? JsonObject
            } catch (_: Exception) {
                null
            }
            return TgConfig(
                enabled = root?.get("enabled")?.let(PyText::truthy) ?: true,
                token = (root?.get("token") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
                chatId = root?.get("chat_id") ?: JsonNull,
                loudAll = root?.get("loud_all")?.let(PyText::truthy) ?: true,
                ownerUserId = (root?.get("owner_user_id") as? JsonPrimitive)?.content?.toLongOrNull(),
                firehoseToken = (root?.get("firehose_token") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
            )
        }
    }
}
