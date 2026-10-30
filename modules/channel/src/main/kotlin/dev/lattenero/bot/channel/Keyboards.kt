package dev.lattenero.bot.channel

import kotlinx.serialization.json.JsonObject
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
        return "$SUBAGENT_MARK ${lane.name} $mark"
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
     * The CoT cajita's open/fold buttons (Python's ct:/cr: vocabulary):
     * collapsed shows «🧠 cot 1.2K», expanded shows the fold ⤴️.
     */
    fun cotButtons(expanded: Boolean): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            add(
                buildJsonArray {
                    if (expanded) {
                        add(button("⤴️ plegar", "cotr:x"))
                    } else {
                        add(button("🧠 cot", "cot:x"))
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
}

/** A command's answer: the words plus, when it carries choices, the inline keyboard. */
data class BotReply(
    val text: String,
    val markup: JsonObject? = null,
)
