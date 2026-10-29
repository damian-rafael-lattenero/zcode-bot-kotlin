package dev.lattenero.bot.transport

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * B2a's boot proof: ONE read-only getMe — it validates the token, the
 * Ktor client, and the API path with zero side effects (getMe doesn't
 * touch the chat and can't conflict with the Python poller's
 * getUpdates). The token NEVER reaches a log: TgApi redacts every
 * exception message before it can.
 */
class Selftest(private val api: TgApi) {

    data class Result(
        val ok: Boolean,
        val botId: Long? = null,
        val username: String? = null,
        val error: String? = null,
    )

    @Suppress("TooGenericExceptionCaught") // the boot proof answers for ANY failure — it must never break the start
    suspend fun run(): Result = try {
        val result = (api.call("getMe") as? JsonObject)?.get("result") as? JsonObject
        val id = result?.get("id")?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() }
        if (id != null) {
            Result(ok = true, botId = id, username = (result["username"] as? JsonPrimitive)?.content)
        } else {
            Result(ok = false, error = "getMe answered without a bot id")
        }
    } catch (e: Exception) {
        Result(ok = false, error = e.message)
    }
}
