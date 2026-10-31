package dev.lattenero.bot.channel

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The BUTTONS — Python's inline keyboards, pure JSON builders (R5's
 * producers) and the callback table (R5's `callbacks.py`, the subset the
 * eight commands own). callback_data ≤ 64 BYTES (Telegram's hard limit,
 * Python's `callback_data_valid`): `t:<sid>` is the worst case at 43.
 *
 * The design system (the owner's words, 29/10): task rows green 🟢,
 * subagent rows blue 🔵 with ▶️ running / ⏹️ stopped. A tap re-binds via
 * the SAME /task bind machinery (callback → command text → [BotCommands.handle]).
 */
@Suppress("TooManyFunctions") // one builder per command's keyboard — the vocabulary IS the object
object Keyboards {

    /** One button row — Python's {"text": …, "callback_data": …}. */
    fun button(text: String, callbackData: String): JsonObject = buildJsonObject {
        put("text", text)
        put("callback_data", callbackData)
    }

    /**
     * /tasks's keyboard: one green row per conversation (Python's
     * button_of: stamp + n + title[:34], callback `t:<sid>`), the blue
     * subagent rows nested under the WATCHED task only (the live lanes
     * we actually have — honest data, no frozen index here). Each row is
     * its OWN array — Telegram's inline_keyboard is an array of rows.
     */
    fun tasksKeyboard(
        rows: List<TaskRow>,
        running: Set<String>,
        activeSid: String?,
        watchedTaskId: String?,
        subagents: List<ToolLive>,
    ): JsonObject? {
        if (rows.isEmpty()) return null
        return buildJsonObject {
            putJsonArray("inline_keyboard") {
                rows.forEachIndexed { i, t ->
                    add(
                        buildJsonArray {
                            add(
                                button(
                                    text = taskButtonLabel(t, i + 1, t.sid in running, t.sid == activeSid),
                                    callbackData = "t:${t.sid}",
                                ),
                            )
                            // the ✍️ arms the next free text as an ORDER direct
                            // to that task — the bind is workspace-scoped (the
                            // fork's project boundary), the order is not
                            add(button("✍️", "so:${t.sid}"))
                        },
                    )
                    if (t.sid == watchedTaskId) {
                        subagents.forEach { lane ->
                            add(
                                buildJsonArray {
                                    add(button(subagentLabel(lane), "sa:${lane.toolId.takeLast(LANE_TAIL)}"))
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    /** Python's stamp vocabulary on the green row — «quiero los dos emojis» when they co-occur. */
    internal fun taskButtonLabel(t: TaskRow, n: Int, running: Boolean, active: Boolean): String {
        val marks = buildString {
            append(TASK_MARK)
            if (running) append(" $MARK_RUNNING")
            if (active) append(" $MARK_ACTIVE")
        }
        val title = t.title.takeIf { it.isNotEmpty() } ?: t.sid.takeLast(BotCommands.SID_TAIL)
        return "$marks $n · ${title.take(TITLE_CAP)}"
    }

    /** The blue lane: ▶️ while open, ⏹️ once closed (the owner's exact marks). */
    internal fun subagentLabel(lane: ToolLive): String {
        val mark = if (lane.status == null) MARK_RUNNING_LANE else MARK_STOPPED_LANE
        return "$SUBAGENT_MARK ${subagentName(lane)} $mark"
    }

    /**
     * The subagent's REAL name — what the owner named it at spawn: the
     * dispatch input's `description`, falling back to the event's own title
     * (only when it says more than the bare tool name), then the tool name.
     * The 30/10 complaint: the buttons said «bash, bash, read, read».
     */
    internal fun subagentName(lane: ToolLive): String =
        (lane.input?.get("description") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.take(TITLE_CAP)?.takeIf { it.isNotBlank() }
            ?: lane.title?.takeIf { it.isNotBlank() && it != lane.name }
            ?: lane.name

    /**
     * /subagents' ACTIVE view keyboard: one row per open dispatch lane —
     * the 🔵 `sa:` tap attaches that subagent's stream (the session attach
     * when the fork names it, lane-scope otherwise) and the ✍️ `so:` tap
     * ARMS the next free text as an ORDER direct to it — then the
     * view-switch row. The «inactivos» view stays text-only: ended
     * sessions have no stream to attach to, no ear to hear an order.
     */
    fun subagentsKeyboard(lanes: List<ToolLive>, inactiveView: Boolean): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            lanes.forEach { lane ->
                add(
                    buildJsonArray {
                        add(button(subagentLabel(lane), "sa:${lane.toolId.takeLast(LANE_TAIL)}"))
                        // the ✍️ arms the next free text as an ORDER to that subagent
                        add(button("✍️", "so:${lane.toolId.takeLast(LANE_TAIL)}"))
                    },
                )
            }
            add(
                buildJsonArray {
                    if (inactiveView) add(button("▶️ activos", "saa:")) else add(button("⏹️ inactivos", "sao:"))
                },
            )
        }
    }

    /** Python's exact ☢️ confirmation row (handlers_system._cmd_nuclear) — ONE row, both buttons. */
    fun nuclearConfirm(): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            add(
                buildJsonArray {
                    add(button("☢️ Confirm", "nuc:go"))
                    add(button("🙅 Cancel", "nuc:no"))
                },
            )
        }
    }

    /** /stop's hard half: one row, the 💀 vocabulary of the go line. */
    fun stopConfirm(): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            add(
                buildJsonArray {
                    add(button("💀 Stop EVERYTHING", "stop:go"))
                },
            )
        }
    }

    /**
     * The F17 question (the owner's words, 30/10: «que me pregunte
     * interrumpir o encolar») — the parked text is ALREADY in the queue:
     * ⚡ stops the turn (the drain then fires the text by itself), ⏳
     * keeps the natural wait. The question is an OFFER, never a gate.
     */
    fun interruptQuestion(sid: String): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            add(
                buildJsonArray {
                    add(button("⚡ Empujar e interrumpir", "intr:$sid"))
                    add(button("⏳ Dejar encolado", "qgo:$sid"))
                },
            )
        }
    }

    /**
     * The CoT BLOCKS' keyboard (Python's box_buttons — reasoning.py — the
     * ct: vocabulary reborn as cb:): one «📖 CoT N» per sealed block, the
     * most recent [COT_BUTTONS_CAP]; the EXPANDED one wears ⤴️ (Python's
     * collapse mark) and the SAME tap folds it — one data, the toggle.
     * The owner's 30/10 refinement: the block's text opens IN the card,
     * right above this row. cb:<n> worst case ≈ 9 bytes — the 64-byte law
     * never even breathes. Null while no block has sealed (the live tail
     * needs no button).
     */
    fun cotBlocksButtons(count: Int, expanded: Int?): JsonObject? {
        if (count <= 0) return null
        val first = maxOf(1, count - COT_BUTTONS_CAP + 1)
        val rows = mutableListOf<MutableList<JsonObject>>()
        var row = mutableListOf<JsonObject>()
        for (n in first..count) {
            row.add(button(if (n == expanded) "⤴️ CoT $n" else "📖 CoT $n", "cb:$n"))
            if (row.size == COT_BUTTONS_ROW) {
                rows.add(row)
                row = mutableListOf()
            }
        }
        if (row.isNotEmpty()) rows.add(row)
        return buildJsonObject {
            putJsonArray("inline_keyboard") {
                rows.forEach { r ->
                    add(buildJsonArray { r.forEach { add(it) } })
                }
            }
        }
    }

    /**
     * /subagents's ONE view-switch button: the active view offers
     * «inactivos» (`sao:` — today's ended subagents), the inactive view
     * offers the way back, «activos» (`saa:`). Both datas are fixed 4-byte
     * strings — Telegram's 64-byte law never even breathes.
     */
    fun subagentsViewButtons(inactiveView: Boolean): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            add(
                buildJsonArray {
                    if (inactiveView) {
                        add(button("▶️ activos", "saa:"))
                    } else {
                        add(button("⏹️ inactivos", "sao:"))
                    }
                },
            )
        }
    }

    /**
     * /queue's rows: one per parked text, 🚀 launch + 🗑 discard on the
     * same row (the owner decides per text, not per queue). The preview
     * is one line, capped; `ql:tg-<updateId>` worst case ≈ 20 bytes —
     * far under Telegram's 64.
     */
    fun queueKeyboard(entries: List<QueuedText>): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            entries.take(QUEUE_ROWS_CAP).forEach { item ->
                val preview = item.text.take(PREVIEW_CAP).replace('\n', ' ')
                add(
                    buildJsonArray {
                        add(button("🚀 «$preview»", "ql:${item.messageId}"))
                        add(button("🗑", "qd:${item.messageId}"))
                    },
                )
            }
        }
    }

    /**
     * Python's callback_text (R5's table, our subset): callback_data →
     * the text the owner «typed». Default: the data itself as free text
     * (the historical behavior — unknown buttons stay owner words).
     * Python guarded `t:` with a «ses…» suffix check; we own BOTH the
     * producer and the reader of these datas, so any `t:` rest rebinds.
     */
    fun callbackText(data: String): String = when (data) {
        "nuc:go" -> "/nuclear go"
        "nuc:no" -> "/nuclear no"
        "stop:go" -> "/stop go"
        else -> when {
            data.startsWith("t:") -> "/task @${data.substring(2)}"
            data.startsWith("sa:") -> "/subagents"
            data.length > CALLBACK_TEXT_CAP -> data.take(CALLBACK_TEXT_CAP)
            else -> data
        }
    }

    /** Telegram's 64-BYTE limit on callback_data (Python's callback_data_valid). */
    fun callbackDataValid(data: String): Boolean =
        data.isNotEmpty() && data.toByteArray().size <= CALLBACK_BYTES && !data.any { it.isWhitespace() }

    private const val TASK_MARK = "🟢"
    private const val SUBAGENT_MARK = "🔵"
    private const val MARK_RUNNING = "🔄"
    private const val MARK_ACTIVE = "🎯"
    private const val MARK_RUNNING_LANE = "▶️"
    private const val MARK_STOPPED_LANE = "⏹️"
    private const val TITLE_CAP = 34
    internal const val LANE_TAIL = 16
    private const val CALLBACK_BYTES = 64
    private const val CALLBACK_TEXT_CAP = 64
    private const val PREVIEW_CAP = 24
    private const val QUEUE_ROWS_CAP = 8

    /**
     * The CoT block buttons' cap (the owner's «~8»): the keyboard shows the
     * most recent ones; the older blocks travel with the close's shower
     * (the render's honest note names the promise). [COT_BUTTONS_ROW] per
     * row — 4 keeps «📖 CoT 12» legible on the phone.
     */
    internal const val COT_BUTTONS_CAP = 8
    private const val COT_BUTTONS_ROW = 4
}

/** A command's answer: the words plus, when it carries choices, the inline keyboard. */
data class BotReply(
    val text: String,
    val markup: JsonObject? = null,
)
