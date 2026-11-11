package dev.lattenero.bot.channel

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * THE OPEN MENU MIRROR (pure core): the registry of the /mode, /plan,
 * /model and /effort menus STILL ON SCREEN, so a config write that
 * happens OUTSIDE the bot — from the Mac's app, from another tap — can
 * re-edit the open message with the fresh state (the fork's
 * `config_option_update` stream event is the trigger; see
 * [ConfigOptionsUpdate]).
 *
 * PURE: a value state ([State]), one decision ([shouldEdit]) — the
 * effects (the live re-read, the Telegram edit) live in
 * [MenuMirrorRefresher], which holds the state in one mutex-backed kap
 * `Ref` and folds it atomically, exactly like the render spine folds
 * [RenderState].
 *
 * The degrade is honest by construction: the fork's event does not exist
 * on the wire today (it ships with a future fork deploy), so until then
 * the registry stays empty and NOTHING edits — the commands' own live
 * re-read keeps working as always. An entry that never hears a matching
 * event dies at [TTL_MS].
 */
object MenuMirror {

    /** Which menu the message is: the FOUR this mirror serves. */
    enum class Kind { MODE, PLAN, MODEL, EFFORT }

    /** ~10 minutes: a menu older than this is off screen — the edit would be a ghost's. */
    const val TTL_MS = 10L * 60L * 1000L

    /**
     * One registered menu message: WHERE it lives (chat + message id),
     * WHAT it is (kind, the task's key, the workspace the original read
     * rode), WHEN it was born and the TEXT the message shows right now —
     * the [shouldEdit] baseline (never re-edit an unchanged menu). The
     * chat rides the config's own `chat_id` element (the wire passes it
     * through verbatim — the transport's type, not a bot-parsed number).
     */
    data class Entry(
        val kind: Kind,
        val messageId: Long,
        val chatId: JsonElement,
        val sentAtMs: Long,
        val lastRenderedText: String,
        /** The workspace the menu's original read used (the fresh read rides the same scope). */
        val ws: String? = null,
    )

    /**
     * WHAT the reply marks: the wiring speaks the reply, learns the born
     * message's id from the send leg, and registers THAT message here.
     * Null task (a plan menu with no active conversation) never marks —
     * no key, no mirror, the honest nothing.
     */
    data class Mark(val taskId: String, val ws: String?, val kind: Kind)

    /** The registry as a VALUE — every change answers a new state, no mutation. */
    data class State(private val entries: Map<String, Entry> = emptyMap()) {

        /** The open menu of one task, ALIVE at [nowMs] (an expired one reads as absent). */
        fun open(taskId: String, nowMs: Long, ttlMs: Long = TTL_MS): Entry? =
            entries[taskId]?.takeIf { nowMs - it.sentAtMs <= ttlMs }

        /** One menu born (or re-born: the task's latest menu replaces the previous). */
        fun register(taskId: String, entry: Entry): State =
            State(entries + (taskId to entry))

        /** The sweep: every menu older than the TTL dies (age is measured from ITS OWN birth). */
        fun expire(nowMs: Long, ttlMs: Long = TTL_MS): State =
            State(entries.filterValues { nowMs - it.sentAtMs <= ttlMs })

        /** The turn's seal: that task's menu dies NOW (the turn it belonged to is over). */
        fun seal(taskId: String): State = State(entries - taskId)
    }

    /**
     * The edit's ONE gate: only when the fresh text actually DIFFERS from
     * what the message shows. Same text → no edit (an unchanged menu is
     * never re-edited — one attempt per event, and most events are the
     * same state re-announced).
     */
    fun shouldEdit(prevText: String?, nextText: String?): Boolean =
        nextText != null && nextText != prevText
}

/**
 * The fork's `config_option_update` stream event (additive, mirrored from
 * upstream `ZCodeStreamEvent`): emitted after every config write, carrying
 * the changed options' ids and current values (`id:"model"` with
 * `currentValue` = provider/model+effort, `id:"mode"`, `id:"thought_level"`,
 * …). The bot uses it as the OPEN MENU's refresh TRIGGER only — the fresh
 * state is always re-READ live, never trusted from the payload.
 *
 * `{type:"config_option_update", configOptions:[{id, currentValue, …}]}`
 *
 * The additive rule: a foreign shape (wrong type, no array, non-object
 * rows) parses as null — the caller IGNORES it, one log line at most,
 * never a crash.
 */
object ConfigOptionsUpdate {

    const val TYPE = "config_option_update"

    /** One changed option: its id and the value it carries now (null = the field was absent). */
    data class Option(val id: String, val currentValue: String?)

    /**
     * The event's options, or null when the shape is foreign. The `type`
     * field is NOT re-checked here (the caller dispatched on it already) —
     * the payload's own shape is what this guards.
     */
    fun parse(event: JsonObject): List<Option>? = runCatching {
        val rows = event["configOptions"] as? kotlinx.serialization.json.JsonArray ?: return null
        rows.mapNotNull { row ->
            val o = row as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            Option(
                id = id,
                currentValue = (o["currentValue"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            )
        }
    }.getOrNull()
}
