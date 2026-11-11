package dev.lattenero.bot.channel

import io.github.oshai.kotlinlogging.KotlinLogging
import kap.Ref
import kap.evalGraph
import kotlinx.coroutines.runBlocking
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
 * THE REGISTRY IS A kap [Ref] (the audit's M finding): the poller's
 * speakReply path ([noteMenu]) and the channel's pump ([seal],
 * [onConfigUpdate]) are two DIFFERENT coroutines folding the same state.
 * The old `@Volatile var` + `state = state.register(...)` answered
 * visibility without atomicity — a concurrent seal could be clobbered by
 * a stale re-write (a lost update). Every mutation is one atomic
 * read-modify-write under the Ref's mutex ([update]/[modify]); the pure
 * [MenuMirror.State] folds exactly like the render spine folds
 * [RenderState], only the CELL changed.
 *
 * THE FOUR RENDERERS (the owner's 11/11 split): /mode keeps its own read
 * (a taskSnapshot), the three plan-picker menus render through the
 * commands' ONE path ([BotCommands.planMenuRender] etc., late-bound by
 * the wiring — the refresher is born before the commands), so a mirrored
 * refresh is byte-identical to the command's own reply and the
 * [MenuMirror.shouldEdit] baseline never fights itself.
 */
class MenuMirrorRefresher private constructor(
    /** The conversation's mode in force (the snapshot's meta.mode, projected) — null when unreadable. */
    private val readMode: suspend (taskId: String, ws: String?) -> String?,
    /** The title the fresh render speaks — the history's resolution, the commands' own source. */
    private val titleOf: suspend (taskId: String) -> String?,
    /** The edit: true when it LANDED. Throttled → false (the cooldown's own skip). */
    private val edit: suspend (chatId: JsonElement, messageId: Long, text: String, markup: JsonObject?) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The registry — the mutex-backed cell (kap Ref), one atomic fold per mutation. */
    private val state: Ref<MenuMirror.State>,
) {

    companion object {
        /**
         * The ONE construction path. kap's law: state is born from a
         * computation ([Ref.of] answers a Kap), and the wiring builds the
         * stack in plain boot code — this factory runs that computation to
         * completion exactly ONCE, at birth, on the caller's thread. The
         * bridge is creation only, never a call path; every later touch of
         * the Ref is a suspend operation inside the callers' coroutines.
         */
        fun create(
            readMode: suspend (taskId: String, ws: String?) -> String?,
            titleOf: suspend (taskId: String) -> String?,
            edit: suspend (chatId: JsonElement, messageId: Long, text: String, markup: JsonObject?) -> Boolean,
            clock: () -> Long = System::currentTimeMillis,
        ): MenuMirrorRefresher = runBlocking {
            MenuMirrorRefresher(
                readMode = readMode,
                titleOf = titleOf,
                edit = edit,
                clock = clock,
                state = Ref.of(MenuMirror.State()).evalGraph(),
            )
        }
    }

    /**
     * The three plan-picker renderers (PLAN/MODEL/EFFORT) — unbound until
     * the wiring introduces the commands (the same late bind the draft
     * source had): each answers the menu's FRESH render, byte-identical to
     * the command's own reply for the same data. Until bound, a mirrored
     * plan-family menu keeps its text (the honest nothing).
     */
    @Volatile
    private var renderPlan: suspend (ws: String?, title: String?) -> MenuRender? = { _, _ -> null }

    @Volatile
    private var renderModel: suspend (ws: String?, title: String?) -> MenuRender? = { _, _ -> null }

    @Volatile
    private var renderEffort: suspend (ws: String?, title: String?) -> MenuRender? = { _, _ -> null }

    /** The wiring's late bind: the commands own the ONE render path per menu. */
    fun bindRenderers(
        plan: suspend (ws: String?, title: String?) -> MenuRender?,
        model: suspend (ws: String?, title: String?) -> MenuRender?,
        effort: suspend (ws: String?, title: String?) -> MenuRender?,
    ) {
        renderPlan = plan
        renderModel = model
        renderEffort = effort
    }

    /**
     * A menu message was BORN (the wiring calls this right after the send
     * flew with its id). Suspend: the caller ([ChannelWiring.speakReply]'s
     * poller path) already is — the registration is one atomic fold.
     */
    suspend fun noteMenu(taskId: String, mark: MenuMirror.Mark, messageId: Long, chatId: JsonElement, text: String) {
        if (messageId <= 0) return // nothing flew (shadow/parked/failed) — there is no message to mirror
        state.update {
            it.register(
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
        }.evalGraph()
        logger.info {
            "menu mirror: ${mark.kind} registered for task ${taskId.takeLast(BotCommands.SID_TAIL)} " +
                "(message $messageId, ${text.length} chars)"
        }
    }

    /**
     * The turn's seal: that task's menu dies NOW (the design's second
     * expiry). Suspend: the caller (the pump's `onStreamEvent`) already is.
     * The had-seal and the removal are ONE atomic fold — a concurrent
     * register can no longer slip between the read and the write.
     */
    suspend fun seal(taskId: String) {
        val had = state.modify { s ->
            s.seal(taskId) to (s.open(taskId, clock()) != null)
        }.evalGraph()
        if (had) logger.info { "menu mirror: sealed with the turn ${taskId.takeLast(BotCommands.SID_TAIL)}" }
    }

    /**
     * THE EVENT: the TTL sweep and the open menu's lookup are ONE atomic
     * fold, then the refresh. The payload's [options] are the trigger (and
     * the log's paper trail) — the fresh state is RE-READ, never trusted
     * from the wire.
     */
    suspend fun onConfigUpdate(taskId: String, options: List<ConfigOptionsUpdate.Option>) {
        val now = clock()
        val entry = state.modify { s ->
            val swept = s.expire(now)
            swept to swept.open(taskId, now)
        }.evalGraph()
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
            MenuMirror.Kind.PLAN -> renderPlan(entry.ws, titleOf(taskId))
            MenuMirror.Kind.MODEL -> renderModel(entry.ws, titleOf(taskId))
            MenuMirror.Kind.EFFORT -> renderEffort(entry.ws, titleOf(taskId))
        } ?: return // the live read answered nothing — the message keeps what it shows (honest)
        if (!MenuMirror.shouldEdit(entry.lastRenderedText, fresh.text)) return // unchanged: never re-edited
        val landed = runCatching {
            edit(entry.chatId, entry.messageId, fresh.text, fresh.markup)
        }.getOrElse {
            logger.warn(it) { "menu mirror: edit crashed — one line, never a turn's death" }
            false
        }
        if (landed) {
            // The baseline advances ONLY while THIS message is still the
            // task's open one: a concurrent seal (the turn died mid-refresh)
            // or a re-born menu (a newer message registered) is never
            // resurrected or clobbered by this fold — the atomic RMW answers
            // the CURRENT state, not the snapshot the read flew with.
            state.modify { s ->
                if (s.open(taskId, clock())?.messageId == entry.messageId) {
                    s.register(taskId, entry.copy(lastRenderedText = fresh.text)) to Unit
                } else {
                    s to Unit
                }
            }.evalGraph()
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
}
