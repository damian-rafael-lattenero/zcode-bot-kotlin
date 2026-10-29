package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import dev.lattenero.bot.transport.TgApi
import dev.lattenero.bot.transport.TgConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val logger = KotlinLogging.logger {}

/**
 * Python's register_bot_menu (handlers_info): Telegram's «/» menu,
 * re-registered at every boot so it can never drift from what the
 * dispatcher actually speaks — the owner, 29/10: «la lista de commands
 * no corresponde con la lista real». The list is DERIVED from the eight
 * (BotCommands.MENU — the same source /help renders). Scope: the owner's
 * chat only (Python's D1) when the config carries a chat_id.
 */
object BotMenu {

    /** One best-effort registration at boot — a Telegram hiccup never stops the daemon. */
    @Suppress("TooGenericExceptionCaught") // Python's bare except: logged, never fatal
    suspend fun register(api: TgApi, cfg: TgConfig): Boolean = try {
        api.call("setMyCommands", body(cfg))
        true
    } catch (e: Exception) {
        logger.warn { "setMyCommands failed: ${e.message}" }
        false
    }

    /** {commands:[{command,description}...], scope?} — the exact body Python posted. */
    fun body(cfg: TgConfig): JsonObject = buildJsonObject {
        putJsonArray("commands") {
            BotCommands.MENU.forEach { (cmd, desc) ->
                add(
                    buildJsonObject {
                        put("command", cmd)
                        put("description", desc)
                    },
                )
            }
        }
        if (cfg.chatId != JsonNull && PyText.truthy(cfg.chatId)) {
            putJsonObject("scope") {
                put("type", "chat")
                put("chat_id", cfg.chatId)
            }
        }
    }
}
