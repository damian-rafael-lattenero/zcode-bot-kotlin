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
    val apiBase: String = System.getenv("ZT_API_BASE") ?: DEFAULT_API,
) {
    /** config.configured(): enabled AND token AND chat_id (Python truthiness). */
    val configured: Boolean
        get() = enabled && token.isNotEmpty() && PyText.truthy(chatId)

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
            )
        }
    }
}
