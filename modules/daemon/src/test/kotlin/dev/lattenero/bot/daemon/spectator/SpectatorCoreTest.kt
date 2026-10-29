package dev.lattenero.bot.daemon.spectator

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure record reduction — spectator._process's cases (F33/F20/F63). */
class SpectatorCoreTest {

    private val cfg = SpectatorCfg()

    // A REAL recent epoch: the pin window is "born after startup", and no
    // 2026 timestamp is younger than a fake 1970 one.
    private val t0 = java.time.Instant.parse("2026-10-29T12:00:00.000Z").toEpochMilli().toDouble()

    private fun state() = SpectatorState(t0Ms = t0, title = "mi tarea")

    private fun record(
        turnId: String = "turn_1",
        tsIso: String = "2026-10-29T12:00:01.000Z",
        querySource: String = "main_turn",
        finish: String = "tool-calls",
        text: String = "",
        toolCalls: List<Pair<String, String>> = emptyList(),
    ) = buildJsonObject {
        put("querySource", querySource)
        put("startedAt", tsIso)
        put("turnId", turnId)
        putJsonObject("response") {
            put("finishReason", finish)
            if (text.isNotEmpty()) put("text", text)
            if (toolCalls.isNotEmpty()) {
                put(
                    "toolCalls",
                    kotlinx.serialization.json.buildJsonArray {
                        toolCalls.forEach { (name, cmd) ->
                            add(
                                buildJsonObject {
                                    put("name", name)
                                    putJsonObject("input") { put("command", cmd) }
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    @Test
    fun anOldRecordFromTheFileIsNotTheTurn() {
        val old = record(tsIso = "2026-10-29T11:00:00.000Z")
        val (st, msgs) = SpectatorCore.processRecord(old, state(), cfg)
        assertEquals(emptyList(), msgs)
        assertEquals(null, st.turnId)
    }

    @Test
    fun theFirstFreshRecordPinsTheTurnAndOthersDoNotExist() {
        val fresh = record()
        val pinned = SpectatorCore.processRecord(fresh, state(), cfg).first
        assertEquals("turn_1", pinned.turnId)
        val other = SpectatorCore.processRecord(record(turnId = "turn_2"), pinned, cfg)
        assertEquals(emptyList(), other.second)
        assertEquals("turn_1", other.first.turnId)
    }

    @Test
    fun nonMainTurnsAreInvisible() {
        val compact = record(querySource = "compact")
        assertEquals(emptyList(), SpectatorCore.processRecord(compact, state(), cfg).second)
    }

    @Test
    fun narrationCarriesTheHeaderAndTheFinalIsNotOurs() {
        val (st, msgs) = SpectatorCore.processRecord(record(text = "pensando en voz alta"), state(), cfg)
        assertEquals(
            listOf(
                "🖥️ <b>«mi tarea»</b> — Mac turn live (spectator)",
                "🖥️ <b>«mi tarea»</b>\npensando en voz alta",
            ),
            msgs.map { it.text },
        )
        assertTrue(st.noticed)
        // finishReason stop: the FINAL answer is F32's, zero duplication
        val fin = record(finish = "stop", text = "respuesta final")
        assertEquals(emptyList(), SpectatorCore.processRecord(fin, st, cfg).second)
    }

    @Test
    fun stepsAreNumberedAndFiltered() {
        val rec = record(
            toolCalls = listOf(
                "Bash" to "pytest -q",
                "Read" to "src/x.kt", // 'action' preset: index tool, no box
                "Task" to "", // agent spawn: the agent watcher owns it
                "Bash" to "ls",
            ),
        )
        val (_, msgs) = SpectatorCore.processRecord(rec, state(), cfg)
        assertEquals(
            listOf(
                "🖥️ <b>«mi tarea»</b> — Mac turn live (spectator)",
                "🔧 <b>Bash</b> — <code>pytest -q</code>\n<pre>pytest -q</pre> · <b>1</b>",
                "🔧 <b>Bash</b> — <code>ls</code>\n<pre>ls</pre> · <b>2</b>",
            ),
            msgs.map { it.text },
        )
    }

    @Test
    fun minimalFilterKeepsOnlyBashAndEdits() {
        val rec = record(toolCalls = listOf("Bash" to "ls", "WebSearch" to "kap"))
        val minimal = SpectatorCore.processRecord(rec, state(), cfg.copy(toolsBoxFilter = "minimal"))
        assertEquals(2, minimal.second.size) // header + the bash box only
        assertTrue(minimal.second[1].text.startsWith("🔧 <b>Bash</b>"))
    }

    @Test
    fun toolsStreamOffBreaksTheBoxesButNotTheNarration() {
        val rec = record(text = "voy", toolCalls = listOf("Bash" to "ls", "Bash" to "ls -la"))
        val (_, msgs) = SpectatorCore.processRecord(rec, state(), cfg.copy(toolsStream = false))
        assertEquals(
            listOf("🖥️ <b>«mi tarea»</b> — Mac turn live (spectator)", "🖥️ <b>«mi tarea»</b>\nvoy"),
            msgs.map { it.text },
        )
    }

    @Test
    fun narrationIsCappedAt2500CodePoints() {
        val long = "x".repeat(2600)
        val (_, msgs) = SpectatorCore.processRecord(record(text = long), state(), cfg)
        assertEquals(2500, msgs[1].text.removePrefix("🖥️ <b>«mi tarea»</b>\n").length)
    }

    @Test
    fun aTurnWithoutStepsMakesNoNoise() {
        val rec = record(finish = "stop")
        assertEquals(emptyList(), SpectatorCore.processRecord(rec, state(), cfg).second)
    }

    @Test
    fun tsMsAcceptsIsoAndNumbers() {
        assertEquals(0.0, SpectatorCore.tsMs(JsonPrimitive("1970-01-01T00:00:00.000Z")))
        assertEquals(1000.0, SpectatorCore.tsMs(JsonPrimitive(1000)))
        assertEquals(null, SpectatorCore.tsMs(JsonPrimitive("not a date")))
    }
}
