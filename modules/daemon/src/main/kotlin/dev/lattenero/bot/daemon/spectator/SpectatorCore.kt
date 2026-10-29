package dev.lattenero.bot.daemon.spectator
import dev.lattenero.bot.contracts.PyText

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** The knobs the spectator reads (defaults are config.py's DEFAULTS). */
internal data class SpectatorCfg(
    val toolsStream: Boolean = true,
    val toolsBoxFilter: String = "action",
    val onlyActiveTask: Boolean = true,
    val macSpectator: Boolean = true,
)

internal data class SpectatorMessage(val text: String, val eventTs: Double?)

internal data class SpectatorState(
    val turnId: String? = null,
    val t0Ms: Double,
    val step: Int = 0,
    val noticed: Boolean = false,
    val title: String,
)

/**
 * The spectator's pure record reduction — a port of spectator._process
 * (F33/F20/F63): pin THE turn born after startup, narrate intermediates
 * (finishReason tool-calls only — the FINAL answer is F32's), number the
 * tool boxes, and never say a word for a turn without steps.
 */
internal object SpectatorCore {

    val AGENT_TOOLS = setOf("Task", "Agent")

    private const val PIN_TOLERANCE_MS = 1000.0
    private const val NARRATION_CAP = 2500

    /** startedAt → epoch ms: ISO string (the real format) or a number, just in case. */
    fun tsMs(v: JsonElement?): Double? = when (v) {
        is JsonPrimitive -> when {
            v.isString && v.content.isNotEmpty() ->
                runCatching { Instant.parse(v.content).toEpochMilli().toDouble() }.getOrNull()

            else -> v.content.toDoubleOrNull()
        }

        else -> null
    }

    // The mirror of spectator._process keeps one guard per line (BusEvents precedent);
    // the branching IS the Python function's shape.
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    fun processRecord(
        record: JsonObject,
        st: SpectatorState,
        cfg: SpectatorCfg,
    ): Pair<SpectatorState, List<SpectatorMessage>> {
        if (PyText.pyStr(record["querySource"]) != "main_turn") return st to emptyList()
        val ts0 = tsMs(record["startedAt"])
        val pinned: SpectatorState = when {
            st.turnId == null ->
                if (ts0 != null && ts0 >= st.t0Ms - PIN_TOLERANCE_MS) {
                    st.copy(turnId = PyText.pyStr(record["turnId"]))
                } else {
                    return st to emptyList() // old record from the file (a previous turn)
                }

            else -> st
        }
        if (PyText.pyStr(record["turnId"]) != pinned.turnId) return pinned to emptyList()
        val resp = record["response"] as? JsonObject ?: return pinned to emptyList()
        val eventTs = ts0
        val out = mutableListOf<SpectatorMessage>()
        var state = pinned
        val text = PyText.pyStr(resp["text"])
        val finish = PyText.pyStr(resp["finishReason"])
        if (text.isNotEmpty() && finish == "tool-calls") {
            // intermediate narration; the FINAL one (stop) is F32's, zero duplication by construction
            state = notify(state, out, "🖥️ <b>«${PyText.esc(state.title)}»</b>\n" +
                PyText.esc(PyText.take(text, NARRATION_CAP)), eventTs)
        }
        val toolCalls = resp["toolCalls"] as? JsonArray ?: emptyList()
        // the loop mirrors Python's filter chain: one guard per iteration, in order
        @Suppress("LoopWithTooManyJumpStatements")
        for (tc in toolCalls) {
            if (tc !is JsonObject) continue
            if (!cfg.toolsStream) break // knob off: no boxes (narration continues)
            val name = PyText.pyStr(tc["name"]).ifEmpty { "tool" }
            val inp = tc["input"] as? JsonObject ?: EMPTY
            // agent spawns: the agent watcher owns that box. The shadow cannot
            // feed Python's watcher (additive contract) but MIRRORS the skip.
            if (name in AGENT_TOOLS) continue
            if (!ToolBox.boxOn(name, cfg.toolsBoxFilter)) continue
            state = state.copy(step = state.step + 1)
            state = notify(state, out, "${ToolBox.box(name, inp)} · <b>${state.step}</b>", eventTs)
        }
        return state to out
    }

    /** The turn's first message carries the spectator label; silent turns stay silent. */
    private fun notify(
        st: SpectatorState,
        out: MutableList<SpectatorMessage>,
        text: String,
        eventTs: Double?,
    ): SpectatorState {
        if (!st.noticed) {
            out.add(SpectatorMessage("🖥️ <b>«${PyText.esc(st.title)}»</b> — Mac turn live (spectator)", eventTs))
            out.add(SpectatorMessage(text, eventTs))
            return st.copy(noticed = true)
        }
        out.add(SpectatorMessage(text, eventTs))
        return st
    }

    private val EMPTY = JsonObject(emptyMap())
}
