package dev.lattenero.bot.channel

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val logger = KotlinLogging.logger {}

/**
 * One menu's FRESH READ, already rendered: the text and the keyboard the
 * open message would show now. Null from a read = the live state is not
 * answerable (channel down, old fork, foreign shape) — the honest nothing.
 */
data class MenuRender(val text: String, val markup: JsonObject?)

/**
 * THE MIRROR'S EFFECTFUL HALF: on a `config_option_update` stream event
 * for a task whose menu is still open, re-read the state LIVE (the same
 * reads the commands use — never the event payload, which is only the
 * trigger) and edit the open message ONCE when the text actually changed.
 *
 * The publisher's edit laws, obeyed: ONE attempt per event (never a
 * loop), a throttled edit skips itself ([LiveTelegram.edit]'s cooldown)
 * and a failed edit does NOT advance the baseline — the next event
 * re-attempts once, and the message never shows a text it does not have.
 * Every failure is one log line; nothing here ever throws at a caller.
 *
 * The reads and the edit are INJECTED (the wiring owns the client and the
 * Telegram throat) — this class is the fold + the decision, testable with
 * plain lambdas.
 */
class MenuMirrorRefresher(
    /** The conversation's mode in force (the snapshot's meta.mode, projected) — null when unreadable. */
    private val readMode: suspend (taskId: String, ws: String?) -> String?,
    /** The plan/model/effort selection + catalog (the modelSelection query's parse) — null when unreadable. */
    private val readPlan: suspend (ws: String?) -> PlanMenu.Views?,
    /** The commands' OPEN PLAN DRAFT (the 🟡 lines' source) — bound by the wiring ([bindDraft]). */
    private val draftOf: () -> PlanMenu.Draft? = { null },
    /** The title the fresh render speaks — the history's resolution, the commands' own source. */
    private val titleOf: suspend (taskId: String) -> String?,
    /** The edit: true when it LANDED. Throttled → false (the cooldown's own skip). */
    private val edit: suspend (chatId: JsonElement, messageId: Long, text: String, markup: JsonObject?) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** The registry — one volatile cell, the pure state folded at the edge. */
    @Volatile
    private var state = MenuMirror.State()

    /** The PLAN draft's source — unbound until the wiring introduces the commands. */
    @Volatile
    private var draftSource: () -> PlanMenu.Draft? = draftOf

    /** The wiring's late bind: the commands own the draft (their TTL law), the refresher only reads it. */
    fun bindDraft(source: () -> PlanMenu.Draft?) {
        draftSource = source
    }

    /** A menu message was BORN (the wiring calls this right after the send flew with its id). */
    fun noteMenu(taskId: String, mark: MenuMirror.Mark, messageId: Long, chatId: JsonElement, text: String) {
        if (messageId <= 0) return // nothing flew (shadow/parked/failed) — there is no message to mirror
        state = state.register(
            taskId,
            MenuMirror.Entry(
                kind = mark.kind,
                messageId = messageId,
                chatId = chatId,
                sentAtMs = clock(),
                lastRenderedText = text,
                ws = mark.ws,
            ),
        )
        logger.info {
            "menu mirror: ${mark.kind} registered for task ${taskId.takeLast(BotCommands.SID_TAIL)} " +
                "(message $messageId, ${text.length} chars)"
        }
    }

    /** The turn's seal: that task's menu dies NOW (the design's second expiry). */
    fun seal(taskId: String) {
        val had = state.open(taskId, clock()) != null
        state = state.seal(taskId)
        if (had) logger.info { "menu mirror: sealed with the turn ${taskId.takeLast(BotCommands.SID_TAIL)}" }
    }

    /**
     * THE EVENT: the TTL sweep first, then the open menu's refresh. The
     * payload's [options] are the trigger (and the log's paper trail) —
     * the fresh state is RE-READ, never trusted from the wire.
     */
    suspend fun onConfigUpdate(taskId: String, options: List<ConfigOptionsUpdate.Option>) {
        val now = clock()
        state = state.expire(now)
        val entry = state.open(taskId, now)
        if (entry != null) refreshOpenMenu(entry, taskId, options)
    }

    /** The open menu's refresh — the gate, the ONE edit, the honest baseline law. */
    private suspend fun refreshOpenMenu(
        entry: MenuMirror.Entry,
        taskId: String,
        options: List<ConfigOptionsUpdate.Option>,
    ) {
        val fresh = when (entry.kind) {
            MenuMirror.Kind.MODE -> freshModeRender(entry, taskId)
            MenuMirror.Kind.PLAN -> freshPlanRender(entry, taskId)
        } ?: return // the live read answered nothing — the message keeps what it shows (honest)
        if (!MenuMirror.shouldEdit(entry.lastRenderedText, fresh.text)) return // unchanged: never re-edited
        val landed = runCatching {
            edit(entry.chatId, entry.messageId, fresh.text, fresh.markup)
        }.getOrElse {
            logger.warn(it) { "menu mirror: edit crashed — one line, never a turn's death" }
            false
        }
        if (landed) {
            state = state.register(taskId, entry.copy(lastRenderedText = fresh.text))
            logger.info {
                "menu mirror: ${entry.kind} menu ${entry.messageId} refreshed " +
                    "(${options.joinToString(",") { it.id }})"
            }
        } else {
            // the baseline does NOT advance: the next event re-attempts ONCE (one attempt per event)
            logger.info { "menu mirror: edit SKIPPED or failed for ${entry.messageId} — the menu keeps its text" }
        }
    }

    /** The MODE menu's fresh render — the same renders `/mode` bare speaks. */
    private suspend fun freshModeRender(entry: MenuMirror.Entry, taskId: String): MenuRender? {
        val current = readMode(taskId, entry.ws) ?: return null
        val title = titleOf(taskId) ?: taskId.takeLast(BotCommands.SID_TAIL)
        return MenuRender(ModeMenu.statusLine(title, current), ModeMenu.keyboard(current))
    }

    /** The PLAN menu's fresh render — the same renders `/plan_model_effort` speaks, draft included. */
    private suspend fun freshPlanRender(entry: MenuMirror.Entry, taskId: String): MenuRender? {
        val v = readPlan(entry.ws) ?: return null
        return planRender(v, titleOf(taskId))
    }

    private fun planRender(v: PlanMenu.Views, title: String?): MenuRender {
        val draft = draftSource()?.takeIf(v::draftValid)
        return MenuRender(PlanMenu.statusLine(v, title, draft), PlanMenu.keyboard(v, draft))
    }
}
