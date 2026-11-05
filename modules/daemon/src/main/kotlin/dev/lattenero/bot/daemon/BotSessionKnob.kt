package dev.lattenero.bot.daemon

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * 2026-11-05 — the DEDICATED CONVERSATION pin (the owner's «conversación
 * predefinida»): the free-text router follows the app's OPEN conversation
 * (Routing's activeRoute re-binds whenever the open one moves), so the bot's
 * turns land in whatever Damian has in front of him — measured 4-5/11: the
 * bot drove the coordinator's own desktop session and its commands bounced
 * while that session generated.
 *
 * `bind_task_id` in daemon.json names THE bot conversation: free text goes
 * THERE, always. Layering (each outranks the next): a pending question's
 * answer, an armed ✍️ order, an explicit /tasks new intent, THE PIN, the
 * follow-the-open-conversation router. Unreadable/garbage config degrades
 * to null (the SendKnob law) — the daemon never dies over a knob.
 */
object BotSessionKnob {

    private const val KEY = "bind_task_id"

    // any reading failure is the same answer: no pin (SendKnob's degrade law)
    @Suppress("TooGenericExceptionCaught")
    fun load(daemonJson: File): String? = try {
        val root = Json.parseToJsonElement(daemonJson.readText()) as? JsonObject
        (root?.get(KEY) as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        logger.warn { "bind_task_id knob unreadable (${e.message}) — no pin" }
        null
    }
}
