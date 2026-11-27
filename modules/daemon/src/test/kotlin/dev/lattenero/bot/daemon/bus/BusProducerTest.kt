package dev.lattenero.bot.daemon.bus

import dev.lattenero.bot.channel.ServerFrame
import dev.lattenero.bot.contracts.BusWire
import dev.lattenero.bot.channel.TurnProse
import dev.lattenero.bot.channel.TurnVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * U1b's producer: a simulated turn emits the contract's six kinds, one turn
 * id for every event of one turn, deterministic ids and timestamps, and no
 * producer failure ever reaches the caller.
 */
class BusProducerTest {

    private var nowMs = 1_700_000_000_000L
    private var nextId = 0L

    private fun harness(
        file: File = File(Files.createTempDirectory("bus-producer").toFile(), "bus-events.jsonl"),
        reply: String? = "done ✅",
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ): Pair<BusProducer, File> {
        val sink = BusSink(file)
        val producer = BusProducer(
            sink = sink,
            scope = scope,
            clockMs = { nowMs },
            newId = { "%032x".format(++nextId) },
            finalTurn = { reply?.let { TurnVoice(listOf(TurnProse(it))) } },
            log = {},
        )
        return producer to file
    }

    private fun events(file: File): List<JsonObject> =
        if (!file.exists()) {
            emptyList()
        } else {
            file.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        }

    private fun frame(taskId: String, seq: Long, at: Long, event: JsonObject): ServerFrame.StreamEvent =
        ServerFrame.StreamEvent(taskId, seq, at, event)

    private fun started(taskId: String, at: Long): JsonObject = buildJsonObject {
        put("type", "task_run_started")
        put("startedAt", at)
        put("taskId", taskId)
    }

    private fun toolCall(at: Long, id: String, name: String, input: JsonObject): JsonObject = buildJsonObject {
        put("type", "tool_call")
        put("at", at)
        put("toolCallId", id)
        put("toolName", name)
        put("input", input)
    }

    private fun toolResult(id: String, status: String, content: String): JsonObject = buildJsonObject {
        put("type", "tool_call_update")
        put("toolCallId", id)
        put("status", status)
        put("content", content)
    }

    @Test
    fun aTurnEmitsTheContractKindsWithOneTurnId() = runBlocking {
        val (producer, file) = harness()
        try {
            val token = producer.beginPrompt("fix the bug", sidHint = "t1", ws = "/tmp/ws")!!
            nowMs = 1_700_000_000_500L
            producer.promptFired(token)

            nowMs = 1_700_000_001_000L
            producer.onFrame(frame("t1", 1, nowMs, started("t1", nowMs)))

            nowMs = 1_700_000_002_000L
            producer.onFrame(
                frame("t1", 2, nowMs, toolCall(nowMs, "tc_1", "Read", buildJsonObject { put("file_path", "/x") })),
            )

            nowMs = 1_700_000_003_000L
            producer.onFrame(frame("t1", 3, nowMs, toolResult("tc_1", "completed", "hello")))

            nowMs = 1_700_000_004_000L
            producer.onFrame(
                frame("t1", 4, nowMs, buildJsonObject { put("type", "agent_thought_chunk"); put("content", "think") }),
            )
            nowMs = 1_700_000_005_000L
            producer.onFrame(
                frame("t1", 5, nowMs, buildJsonObject { put("type", "agent_thought_chunk"); put("content", " más") }),
            )

            nowMs = 1_700_000_042_500L
            producer.onFrame(
                frame("t1", 6, nowMs, buildJsonObject { put("type", "task_complete"); put("stopReason", "end_turn") }),
            )

            val lines = events(file)
            assertEquals(
                listOf("user.prompt", "turn.started", "tool.call", "tool.result", "turn.completed"),
                lines.map { it["kind"]!!.jsonObjectPrimitive() },
            )
            // ONE turn id for every event of the turn (the prompt tap's own mint)
            val turns = lines.map { it["turn"]!!.jsonObjectPrimitive() }.toSet()
            assertEquals(setOf("1700000000000"), turns)
            assertEquals(setOf("t1"), lines.map { it["sid"]!!.jsonObjectPrimitive() }.toSet())
            assertEquals(setOf("bot"), lines.map { it["source"]!!.jsonObjectPrimitive() }.toSet())

            val prompt = lines[0]["payload"]!!.jsonObject
            assertEquals("fix the bug", prompt["prompt"]!!.jsonObjectPrimitive())
            assertEquals("11", prompt["prompt_chars"]!!.jsonObjectPrimitive())
            assertEquals("/tmp/ws", prompt["cwd"]!!.jsonObjectPrimitive())

            val startedPayload = lines[1]["payload"]!!.jsonObject
            assertEquals("/tmp/ws", startedPayload["ws"]!!.jsonObjectPrimitive())
            assertEquals("11", startedPayload["prompt_chars"]!!.jsonObjectPrimitive())
            assertEquals("fix the bug", startedPayload["prompt"]!!.jsonObjectPrimitive())

            val call = lines[2]["payload"]!!.jsonObject
            assertEquals("tc_1", call["toolCallId"]!!.jsonObjectPrimitive())
            assertEquals("Read", call["tool"]!!.jsonObjectPrimitive())
            assertEquals("""{"file_path":"/x"}""", call["input"]!!.jsonObjectPrimitive())

            val result = lines[3]["payload"]!!.jsonObject
            assertEquals("tc_1", result["toolCallId"]!!.jsonObjectPrimitive())
            assertEquals("true", result["success"]!!.jsonObjectPrimitive())
            assertEquals("5", result["chars"]!!.jsonObjectPrimitive())

            val completed = lines[4]["payload"]!!.jsonObject
            assertEquals("41.5", completed["duration_s"]!!.jsonObjectPrimitive())
            assertEquals("6", completed["reply_chars"]!!.jsonObjectPrimitive()) // "done ✅" = 6 code points
            assertEquals("done ✅", completed["reply"]!!.jsonObjectPrimitive())
            assertEquals("9", completed["cot_chars"]!!.jsonObjectPrimitive()) // "think" + " más"

            // deterministic id generation: 32 hex, injected
            lines.forEach { line ->
                val id = line["id"]!!.jsonObjectPrimitive()
                assertEquals(32, id.length)
                assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
            }
        } finally {
            producer.close()
        }
    }

    @Test
    fun aFailedTurnSealsTurnFailedWithItsDuration() = runBlocking {
        val (producer, file) = harness()
        try {
            nowMs = 1_700_000_100_000L
            producer.onFrame(frame("t2", 1, nowMs, started("t2", nowMs)))
            nowMs = 1_700_000_103_000L
            producer.onFrame(
                frame(
                    "t2",
                    2,
                    nowMs,
                    buildJsonObject { put("type", "task_error"); put("error", "interrupted") },
                ),
            )
            val lines = events(file)
            assertEquals(listOf("turn.started", "turn.failed"), lines.map { it["kind"]!!.jsonObjectPrimitive() })
            val payload = lines[1]["payload"]!!.jsonObject
            assertEquals("interrupted", payload["error"]!!.jsonObjectPrimitive())
            assertEquals("3.0", payload["duration_s"]!!.jsonObjectPrimitive())
            // an app-started turn has no prompt at hand: no prompt fields, never invented
            assertTrue(lines[0]["payload"]!!.jsonObject.isEmpty())
        } finally {
            producer.close()
        }
    }

    @Test
    fun aReplayedTerminalOrRunStartedNeverPublishesTwice() = runBlocking {
        val (producer, file) = harness()
        try {
            nowMs = 1_700_000_200_000L
            producer.onFrame(frame("t3", 1, nowMs, started("t3", nowMs)))
            producer.onFrame(frame("t3", 2, nowMs, started("t3", nowMs))) // resubscribe burst
            nowMs = 1_700_000_201_000L
            producer.onFrame(
                frame("t3", 3, nowMs, buildJsonObject { put("type", "task_complete"); put("stopReason", "end_turn") }),
            )
            producer.onFrame(
                frame("t3", 4, nowMs, buildJsonObject { put("type", "task_complete"); put("stopReason", "end_turn") }),
            )
            assertEquals(
                listOf("turn.started", "turn.completed"),
                events(file).map { it["kind"]!!.jsonObjectPrimitive() },
            )
        } finally {
            producer.close()
        }
    }

    @Test
    fun aDroppedPromptIsNeverPublishedNorAttachedToTheNextTurn() = runBlocking {
        val (producer, file) = harness()
        try {
            val token = producer.beginPrompt("lost text")!!
            producer.promptDropped(token)
            nowMs = 1_700_000_300_000L
            producer.onFrame(frame("t4", 1, nowMs, started("t4", nowMs)))
            val lines = events(file)
            assertEquals(listOf("turn.started"), lines.map { it["kind"]!!.jsonObjectPrimitive() })
            assertTrue(lines[0]["payload"]!!.jsonObject.isEmpty())
        } finally {
            producer.close()
        }
    }

    @Test
    fun aStalePromptNeverMatchesALaterTurn() = runBlocking {
        val (producer, file) = harness()
        try {
            producer.beginPrompt("old text")
            nowMs += 11 * 60_000L // past PENDING_TTL_MS
            producer.onFrame(frame("t5", 1, nowMs, started("t5", nowMs)))
            val lines = events(file)
            assertEquals(listOf("turn.started"), lines.map { it["kind"]!!.jsonObjectPrimitive() })
            assertTrue(lines[0]["payload"]!!.jsonObject.isEmpty())
        } finally {
            producer.close()
        }
    }

    @Test
    fun aBlankPromptRegistersNothing() = runBlocking {
        val (producer, file) = harness()
        try {
            assertNull(producer.beginPrompt("   "))
            assertEquals(0, events(file).size)
        } finally {
            producer.close()
        }
    }

    @Test
    fun aWriteFailureNeverPropagatesIntoTheTurn(): Unit = runBlocking {
        // the EXPLICIT Unit: the last statement below returns Unit? and a
        // Unit?-typed @Test method is not void on the JVM — JUnit silently
        // skips it (measured: this guard law never ran before the fix)
        val dir = Files.createTempDirectory("bus-producer-dir").toFile() // not appendable
        val producer = BusProducer(
            sink = BusSink(dir),
            scope = CoroutineScope(Dispatchers.Unconfined),
            clockMs = { nowMs },
            newId = { "%032x".format(++nextId) },
            finalTurn = { TurnVoice(listOf(TurnProse("reply"))) },
            log = {},
        )
        try {
            val token = producer.beginPrompt("still fine")!!
            producer.promptFired(token)
            producer.onFrame(frame("t6", 1, nowMs, started("t6", nowMs)))
            producer.onFrame(frame("t6", 2, nowMs, toolCall(nowMs, "tc", "Bash", buildJsonObject { put("command", "ls") })))
            producer.onFrame(frame("t6", 3, nowMs, toolResult("tc", "failed", "boom")))
            producer.onFrame(
                frame("t6", 4, nowMs, buildJsonObject { put("type", "task_complete"); put("stopReason", "end_turn") }),
            )
            producer.onFrame(
                frame("t7", 1, nowMs, buildJsonObject { put("type", "task_error"); put("error", "boom") }),
            )
            producer.correction("t6", "steer", "still fine") // U8: a throwing sink never reaches the caller
        } finally {
            producer.close()
        }
    }

    @Test
    fun anEmptySnapshotSaysAnEmptyReplyRatherThanInventingText() = runBlocking {
        val (producer, file) = harness(reply = null)
        try {
            nowMs = 1_700_000_400_000L
            producer.onFrame(frame("t8", 1, nowMs, started("t8", nowMs)))
            nowMs = 1_700_000_401_000L
            producer.onFrame(
                frame("t8", 2, nowMs, buildJsonObject { put("type", "task_complete"); put("stopReason", "end_turn") }),
            )
            val completed = events(file).last()
            assertEquals("turn.completed", completed["kind"]!!.jsonObjectPrimitive())
            assertEquals("", completed["payload"]!!.jsonObject["reply"]!!.jsonObjectPrimitive())
            assertEquals("0", completed["payload"]!!.jsonObject["reply_chars"]!!.jsonObjectPrimitive())
        } finally {
            producer.close()
        }
    }

    // ── U8's CORRECTIONS: the owner correcting a running or just-finished turn ──

    /**
     * The turn attribution is live > sealed > "": a correction mid-flight
     * names the LIVE turn, one after the seal names the last sealed turn,
     * and one for a sid this producer never saw says "" — never invented.
     */
    @Test
    fun aCorrectionNamesTheLiveTurnThenTheSealedThenNothing() = runBlocking {
        val (producer, file) = harness()
        try {
            nowMs = 1_700_000_500_000L
            producer.onFrame(frame("t9", 1, nowMs, started("t9", nowMs)))
            nowMs = 1_700_000_501_000L
            producer.correction("t9", "steer", "use bun instead") // mid-flight: the LIVE turn
            nowMs = 1_700_000_502_000L
            producer.onFrame(
                frame("t9", 2, nowMs, buildJsonObject { put("type", "task_complete"); put("stopReason", "end_turn") }),
            )
            producer.correction("t9", "stop", "") // sealed: the last seal for the sid
            producer.correction("t-unknown", "cancel", "") // neither: the honest ""
            val lines = events(file)
            val corrections = lines.filter { it["kind"]!!.jsonObjectPrimitive() == "correction" }
            assertEquals(3, corrections.size)
            assertEquals(listOf("t9", "t9", "t-unknown"), corrections.map { it["sid"]!!.jsonObjectPrimitive() })
            assertEquals(
                listOf("1700000500000", "1700000500000", ""),
                corrections.map { it["turn"]!!.jsonObjectPrimitive() },
            )
            assertEquals("steer", corrections[0]["payload"]!!.jsonObject["kind"]!!.jsonObjectPrimitive())
            assertEquals("use bun instead", corrections[0]["payload"]!!.jsonObject["text"]!!.jsonObjectPrimitive())
            assertEquals("stop", corrections[1]["payload"]!!.jsonObject["kind"]!!.jsonObjectPrimitive())
            assertEquals("", corrections[1]["payload"]!!.jsonObject["text"]!!.jsonObjectPrimitive())
            assertEquals("cancel", corrections[2]["payload"]!!.jsonObject["kind"]!!.jsonObjectPrimitive())
            // ids stay unique per event (the consumer deduplicates by id)
            assertEquals(lines.size, lines.map { it["id"]!!.jsonObjectPrimitive() }.toSet().size)
        } finally {
            producer.close()
        }
    }

    /** The guard: a blank sid or an off-vocabulary kind publishes nothing. */
    @Test
    fun anOffVocabularyOrBlankSidCorrectionEmitsNothing() = runBlocking {
        val (producer, file) = harness()
        try {
            producer.correction("", "steer", "x") // blank sid: no event
            producer.correction("t9", "pause", "x") // off vocabulary: no event
            producer.correction("t9", "", "x")
            assertEquals(0, events(file).size)
        } finally {
            producer.close()
        }
    }

    /** The text rides clipped to the wire cap (code points, plus the ellipsis). */
    @Test
    fun aCorrectionTextIsClippedToTheWireCap() = runBlocking {
        val (producer, file) = harness()
        try {
            producer.correction("t9", "explicit", "x".repeat(BusWire.TEXT_CAP + 50))
            val payload = events(file).single()["payload"]!!.jsonObject
            assertEquals("x".repeat(BusWire.TEXT_CAP) + "…", payload["text"]!!.jsonObjectPrimitive())
        } finally {
            producer.close()
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectPrimitive(): String =
        (this as JsonPrimitive).content
}
