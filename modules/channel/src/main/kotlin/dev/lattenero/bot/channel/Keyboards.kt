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
     * button_of: stamp + n + title[:34], callback `t:<sid>`), then the
     * blue 🔵 subagent rows — ALWAYS visible (the owner's 2/11 law: the
     * list is tasks AND subagents, tap-selectable): the live dispatch
     * lanes first, then the store's running sessions (stream-independent,
     * each with the honest ⏸️ ghost mark). Each row is its OWN array —
     * Telegram's inline_keyboard is an array of rows.
     */
    fun tasksKeyboard(
        rows: List<TaskRow>,
        running: Set<String>,
        activeSid: String?,
        subagents: List<ToolLive>,
        dbRunning: List<RunningSubagent> = emptyList(),
        nowMs: Long = 0,
    ): JsonObject? {
        if (rows.isEmpty() && subagents.isEmpty() && dbRunning.isEmpty()) return null
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
                }
                subagents.forEach { lane ->
                    add(
                        buildJsonArray {
                            add(button(subagentLabel(lane), "sn:${lane.toolId.takeLast(LANE_TAIL)}"))
                            // the ⏹️ stops the child's generation (sst:) —
                            // only an OPEN lane earns it
                            if (lane.status == null) add(button(STOP_BUTTON, "sst:${lane.toolId.takeLast(LANE_TAIL)}"))
                        },
                    )
                }
                // the store's running sessions — visible even when the bus
                // died before their lane streamed (the /subagents law, now on
                // every list; the frozen lastMs is the ghost marker)
                dbRunning.forEach { r ->
                    add(
                        buildJsonArray {
                            val label = r.title.takeIf { it.isNotBlank() }?.take(TITLE_CAP) ?: r.sid.takeLast(LANE_TAIL)
                            val saData = "sn:${r.sid.takeLast(LANE_TAIL)}"
                            add(button("$SUBAGENT_MARK $label ${dbRunningMark(r, nowMs)}", saData))
                            // the ⏹️ rides ONLY a fresh row — a ghost's stop is decoration
                            if (dbRunningFresh(r, nowMs)) {
                                add(button(STOP_BUTTON, "sst:${r.sid.takeLast(LANE_TAIL)}"))
                            }
                        },
                    )
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

    /**
     * A store row's ALIVE verdict — the ONE freshness law shared by the
     * ▶️/⏸️ mark, the ⏹️ button's presence and the stop resolution's own
     * gate (SubagentStop): a row untouched this long IS the ghost it
     * renders as, and nothing alive ever stops it.
     */
    internal fun dbRunningFresh(r: RunningSubagent, nowMs: Long): Boolean =
        r.lastMs <= 0 || (nowMs - r.lastMs) / MINUTE_MS < SUB_STALE_MIN

    /** The same law as the mark: ▶️ fresh, ⏸️ hace Xm the ghost. */
    internal fun dbRunningMark(r: RunningSubagent, nowMs: Long): String =
        if (dbRunningFresh(r, nowMs)) "▶️" else "⏸️ hace ${(nowMs - r.lastMs) / MINUTE_MS}m"

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
     * The FULL name — the detail card's whole point (the 2/11 complaint:
     * the spawn descriptions are longísimos and the row label caps at
     * TITLE_CAP). The same fallback ladder as subagentName, uncapped.
     */
    internal fun subagentFullName(lane: ToolLive): String =
        (lane.input?.get("description") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() }
            ?: lane.title?.takeIf { it.isNotBlank() && it != lane.name }
            ?: lane.name

    /**
     * The detail card's body (the sn: tap): the row's own marks, the
     * UNCAPPED name, one honest status line. Raw text — the caller escapes
     * it for the message body.
     */
    internal fun subagentDetailText(lane: ToolLive, nowMs: Long): String =
        "$SUBAGENT_MARK «${subagentFullName(lane)}»\n" + if (lane.status == null) {
            "⏳ vivo hace ${(nowMs - lane.openedAtMs) / MINUTE_MS}m"
        } else {
            "💀 terminó"
        }

    /** The store row's detail body — the full title + the frozen-ghost honesty. */
    internal fun dbRowDetailText(r: RunningSubagent, nowMs: Long): String =
        "$SUBAGENT_MARK «${r.title.ifBlank { r.sid.takeLast(LANE_TAIL) }}»\n" + if (dbRunningFresh(r, nowMs)) {
            "▶️ corriendo"
        } else {
            "⏸️ hace ${(nowMs - r.lastMs) / MINUTE_MS}m — probablemente terminó"
        }

    /**
     * The detail card's action row: attach · order · stop, built FRESH at
     * tap time — a dead agent earns no buttons and its card stays a pure
     * memory (reading the full name survives the agent; ordering into its
     * dead inbox never resurrects it — the honest law).
     */
    fun subagentDetailKeyboard(alive: Boolean, tail: String): JsonObject? =
        if (!alive) {
            null
        } else {
            buildJsonObject {
                putJsonArray("inline_keyboard") {
                    add(
                        buildJsonArray {
                            add(button("👁 attach", "sa:$tail"))
                            add(button("✍️ orden", "so:$tail"))
                            add(button(STOP_BUTTON, "sst:$tail"))
                        },
                    )
                }
            }
        }

    /**
     * /subagents' ACTIVE view keyboard: one row per open dispatch lane —
     * the name tap (`sn:`) opens the DETAIL CARD (the full uncapped name
     * + the attach/order/stop row, rendered fresh at tap time; the row
     * label caps at TITLE_CAP and the spawn descriptions are longísimos),
     * the ✍️ `so:` tap ARMS the next free text as an ORDER direct to it,
     * and the ⏹️ `sst:` tap stops the child's generation live — then the
     * view-switch row. The «inactivos» view stays text-only: ended
     * sessions have no stream to attach to, no ear to hear an order,
     * nothing left to stop.
     */
    fun subagentsKeyboard(
        lanes: List<ToolLive>,
        dbRunning: List<RunningSubagent> = emptyList(),
        inactiveView: Boolean,
        nowMs: Long = 0,
    ): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            lanes.forEach { lane ->
                add(
                    buildJsonArray {
                        add(button(subagentLabel(lane), "sn:${lane.toolId.takeLast(LANE_TAIL)}"))
                        // the ✍️ arms the next free text as an ORDER — only
                        // an OPEN lane earns it (the pulse gate would refuse
                        // a dead one; a dead-end button is decoration)
                        if (lane.status == null) add(button("✍️", "so:${lane.toolId.takeLast(LANE_TAIL)}"))
                        // the ⏹️ stops the child's generation live (sst:) —
                        // only an OPEN lane earns it
                        if (lane.status == null) add(button(STOP_BUTTON, "sst:${lane.toolId.takeLast(LANE_TAIL)}"))
                    },
                )
            }
            // the store's running sessions — visible even when the bus died
            // before their lane streamed (each sid tail IS the attach target)
            dbRunning.forEach { r ->
                add(
                    buildJsonArray {
                        val label = r.title.takeIf { it.isNotBlank() }?.take(TITLE_CAP) ?: r.sid.takeLast(LANE_TAIL)
                        val saData = "sn:${r.sid.takeLast(LANE_TAIL)}"
                        // the store never closes a KILLED agent's row: its
                        // frozen lastMs is the ghost marker — rendered honest
                        add(button("$SUBAGENT_MARK $label ${dbRunningMark(r, nowMs)}", saData))
                        // the ✍️ rides ONLY a fresh row — same law as the ⏹️
                        if (dbRunningFresh(r, nowMs)) {
                            add(button("✍️", "so:${r.sid.takeLast(LANE_TAIL)}"))
                        }
                        // the ⏹️ rides ONLY a fresh row — a ghost's stop is decoration
                        if (dbRunningFresh(r, nowMs)) {
                            add(button(STOP_BUTTON, "sst:${r.sid.takeLast(LANE_TAIL)}"))
                        }
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
     * /verbosity's PICKER (the owner's knob, 30/10): one row per level —
     * the current one wears ✅ — tap = set. `vb:<level>` worst case ≈ 12
     * bytes — Telegram's 64-byte law never even breathes.
     */
    fun verbosityPicker(current: FeedVerbosity): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            FeedVerbosity.entries.forEach { level ->
                add(
                    buildJsonArray {
                        add(button(verbosityLabel(level, level == current), "vb:${level.name.lowercase()}"))
                    },
                )
            }
        }
    }

    /** The picker's one label: the level's Spanish word + its meaning, the current one marked. */
    fun verbosityLabel(level: FeedVerbosity, current: Boolean): String {
        val word = when (level) {
            FeedVerbosity.SUMMARY -> "🔈 summary — solo el cartel y la respuesta"
            FeedVerbosity.NORMAL -> "🔉 normal — bloques CoT + cierres de tools"
            FeedVerbosity.VERBOSE -> "🔊 verbose — todo, en vivo"
        }
        return if (current) "✅ $word" else word
    }

    /**
     * A collapsed CoT block's ONE button (the tap-expand-in-place, 2/11):
     * the registry id rides the data (`cb:<id>` — a few bytes, the 64-byte
     * law never even breathes); the label names the ACTION (open it / fold
     * it back).
     */
    fun cotToggle(registryId: Int, expanded: Boolean): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            add(
                buildJsonArray {
                    add(
                        if (expanded) {
                            button("🙈 plegar", "cb:$registryId")
                        } else {
                            button("📖 leer", "cb:$registryId")
                        },
                    )
                },
            )
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

    /** The stop tap's own button — the ⏹️ beside the ▶️/✍️, the action it names. */
    private const val STOP_BUTTON = "⏹️"
    private const val TITLE_CAP = 34

    /** A store row untouched this long renders ⏸️ (a killed agent's ghost), not ▶️. */
    internal const val SUB_STALE_MIN = 15L
    private const val MINUTE_MS = 60_000L
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
